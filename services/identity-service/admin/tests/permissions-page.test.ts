import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import RolePermissionEditor from '../src/pages/roles/RolePermissionEditor.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'
import type { Catalog, Permission, RolePermissions } from '../src/api/tenant'
import { grantChanges, grantMap } from '../src/pages/permissions/grants'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const mockedRead = vi.mocked(read),
  mockedWrite = vi.mocked(write)
let owner: AccountContext, catalog: Catalog, snapshot: RolePermissions
function permission(code: string, scoped = true): Permission {
  return {
    code,
    name: code.endsWith('read') ? '读取文档' : '运行作业',
    owner_service: 'document-service',
    group_code: 'documents',
    group_name: '文档',
    resource_code: 'document',
    resource_name: '文档',
    action_code: 'read',
    description: '真实目录说明',
    sort: 0,
    status: 'ACTIVE',
    oauth_scope: 'documents.read',
    supported_data_scopes: scoped ? ['SELF', 'TENANT'] : [],
    assignable_data_scopes: scoped ? ['SELF', 'TENANT'] : [],
    can_assign: true,
  }
}
beforeEach(() => {
  vi.clearAllMocks()
  owner = {
    realm: 'platform',
    targetTenant: 'tenant-one',
    me: {
      view: 'APPLICATION_EFFECTIVE',
      sub: 'manager',
      principal_type: 'platform',
      tenant_id: null,
      client_id: 'identity-admin-platform',
      profile: {},
      audiences: {
        'identity-service': {
          scopes: ['identity.permissions', 'identity.roles'],
          permissions: [
            'platform:identity:read',
            'platform:identity:read',
            'platform:identity:manage',
          ],
          data_scopes: {},
        },
      },
    },
    changed: vi.fn(async () => {}),
    signedOut: vi.fn(),
    failed: vi.fn(),
  }
  catalog = {
    catalog_version: 7,
    items: [permission('document:read'), permission('job:run', false)],
  }
  snapshot = {
    role: {
      id: 'reader-role',
      code: 'reader',
      name: '阅读者',
      status: 'ACTIVE',
      protected: false,
      version: 4,
      authorization_version: 2,
      can_assign: true,
      can_edit: true,
      can_unassign: true,
      restrictions: [],
    },
    catalog_version: 7,
    grants: [],
  }
  mockedRead.mockImplementation(async (_realm, path) => {
    if (path.startsWith('/platform/tenants/tenant-one/identity/roles?'))
      return { page: 0, size: 20, has_more: false, items: [structuredClone(snapshot.role)] }
    if (path.startsWith('/platform/tenants/tenant-one/identity/role-permissions/'))
      return structuredClone(snapshot)
    return structuredClone(catalog)
  })
  mockedWrite.mockImplementation(async (_realm, _path, body) => {
    snapshot.grants = (body as RolePermissions).grants
    snapshot.role.version++
    return {}
  })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => {
  vi.restoreAllMocks()
})
async function screen() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: RolePermissionEditor, props: { roleId: snapshot.role.id } }],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(
    { render: () => h(RouterView) },
    { global: { plugins: [router], provide: { [accountContext as symbol]: () => owner } } },
  )
  await flushPromises()
  return wrapper
}
async function click(wrapper: Awaited<ReturnType<typeof screen>>, text: string) {
  const button = wrapper.findAll('button').find((value) => value.text() === text)
  expect(button, text).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
const checkbox = (code: string) => `input[aria-label="授权 ${code}"]`
describe('角色权限编辑器', () => {
  it('角色详情链接直接读取确定角色，即使该角色不在列表当前页', async () => {
    snapshot.role.id = '12345678-1234-1234-1234-123456789abc'
    mockedRead.mockImplementation(async (_realm, path) =>
      path.startsWith('/platform/tenants/tenant-one/identity/roles?')
        ? { page: 0, size: 20, has_more: true, items: [] }
        : path.startsWith('/platform/tenants/tenant-one/identity/role-permissions/')
          ? structuredClone(snapshot)
          : structuredClone(catalog),
    )
    const wrapper = await screen()
    expect(mockedRead).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/role-permissions/' + snapshot.role.id,
    )
    expect(mockedRead.mock.calls.some(([, path]) => path.includes('/roles?'))).toBe(false)
    expect(wrapper.text()).toContain('阅读者')
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('未选租户不能读取；只读权限不提供写入入口', async () => {
    owner.targetTenant = undefined
    let wrapper = await screen()
    expect(mockedRead).not.toHaveBeenCalled()
    wrapper.unmount()
    owner.targetTenant = 'tenant-one'
    owner.me.audiences['identity-service'].permissions = ['platform:identity:read']
    wrapper = await screen()
    expect(wrapper.text()).toContain('真实目录说明')
    expect(wrapper.findAll('button').some((value) => value.text() === '提交授权')).toBe(false)
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('新增项默认未授权，SELF 上限不能选 TENANT，预览确认才提交三个版本', async () => {
    catalog.items[0].assignable_data_scopes = ['SELF']
    const wrapper = await screen()
    expect((wrapper.get(checkbox('document:read')).element as HTMLInputElement).checked).toBe(false)
    await wrapper.get(checkbox('document:read')).setValue(true)
    expect(
      wrapper
        .findAll('select[aria-label="数据范围 document:read"] option')
        .map((value) => value.attributes('value')),
    ).toEqual(['SELF'])
    expect(wrapper.findAll('button').some((value) => value.text() === '确认保存授权')).toBe(false)
    await click(wrapper, '预览授权差异')
    expect(wrapper.text()).toContain('新增 · document:read')
    await click(wrapper, '确认保存授权')
    expect(mockedWrite).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/role-permissions/reader-role',
      {
        role_version: 4,
        catalog_version: 7,
        grants: [{ permission_code: 'document:read', data_scope: 'SELF' }],
      },
      'PUT',
    )
    expect(wrapper.text()).toContain('角色授权已保存并重新读取')
    expect(snapshot.role.version).toBe(5)
    wrapper.unmount()
  })
  it('范围扩大和不适用范围显式预览，草稿变化使原确认失效', async () => {
    snapshot.grants = [{ permission_code: 'document:read', data_scope: 'SELF' }]
    const wrapper = await screen()
    await wrapper.get('select[aria-label="数据范围 document:read"]').setValue('TENANT')
    await click(wrapper, '预览授权差异')
    expect(wrapper.text()).toContain('范围扩大 · document:read')
    await wrapper.get(checkbox('job:run')).setValue(true)
    expect(wrapper.find('[aria-label="授权差异预览"]').exists()).toBe(false)
    await click(wrapper, '预览授权差异')
    await click(wrapper, '确认保存授权')
    expect(mockedWrite.mock.calls[0][2]).toMatchObject({
      grants: [
        { permission_code: 'document:read', data_scope: 'TENANT' },
        { permission_code: 'job:run', data_scope: null },
      ],
    })
    wrapper.unmount()
  })
  it('409 保留草稿，不自动读取覆盖或重放，人工核对后重新预览当前版本', async () => {
    const wrapper = await screen()
    await wrapper.get(checkbox('document:read')).setValue(true)
    await click(wrapper, '预览授权差异')
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'ROLE_OR_CATALOG_VERSION_CONFLICT'))
    const reads = mockedRead.mock.calls.length
    await click(wrapper, '确认保存授权')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    expect(mockedRead).toHaveBeenCalledTimes(reads)
    expect((wrapper.get(checkbox('document:read')).element as HTMLInputElement).checked).toBe(true)
    snapshot.role.version = 12
    await click(wrapper, '保留草稿并重新核对')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[aria-label="授权差异预览"]').exists()).toBe(false)
    await click(wrapper, '预览授权差异')
    await click(wrapper, '确认保存授权')
    expect(mockedWrite.mock.calls[1][2]).toMatchObject({
      role_version: 12,
    })
    wrapper.unmount()
  })
  it('受保护角色只读，仍显示已有 SELF 范围，不请求可授目录或发出写入', async () => {
    snapshot.role.protected = true
    snapshot.role.can_edit = false
    snapshot.grants = [{ permission_code: 'document:read', data_scope: 'SELF' }]
    const wrapper = await screen()
    expect(wrapper.text()).toContain('受保护管理员角色禁止普通编辑')
    expect(wrapper.text()).toContain('本人数据')
    expect(wrapper.get(checkbox('document:read')).attributes('disabled')).toBeDefined()
    expect(mockedRead.mock.calls.some((call) => call[1].includes('assignable=true'))).toBe(false)
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('未知写入结果冻结保存并保留草稿，拒绝扩大时不显示成功', async () => {
    const wrapper = await screen()
    await wrapper.get(checkbox('document:read')).setValue(true)
    await click(wrapper, '预览授权差异')
    mockedWrite.mockRejectedValueOnce(new IdentityError(0, 'TRANSPORT_UNAVAILABLE', '', true))
    await click(wrapper, '确认保存授权')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('提交前必须重新核对当前版本')
    expect(wrapper.text()).not.toContain('角色授权已保存')
    expect((wrapper.get(checkbox('document:read')).element as HTMLInputElement).checked).toBe(true)
    wrapper.unmount()
  })
  it('角色和目录快照版本不一致时不开放保存', async () => {
    catalog.catalog_version = 8
    const wrapper = await screen()
    expect(wrapper.text()).toContain('角色或目录已变化')
    expect(wrapper.findAll('button').some((value) => value.text() === '预览授权差异')).toBe(false)
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('弃用授权可移除，批量选择不会重新授出弃用项或越过数据范围', async () => {
    catalog.items[0].status = 'DEPRECATED'
    catalog.items[0].can_assign = false
    snapshot.grants = [{ permission_code: 'document:read', data_scope: 'SELF' }]
    const wrapper = await screen()
    await wrapper.get(checkbox('document:read')).setValue(false)
    expect(wrapper.get(checkbox('document:read')).attributes('disabled')).toBeDefined()
    await click(wrapper, '选择当前可授项')
    expect((wrapper.get(checkbox('document:read')).element as HTMLInputElement).checked).toBe(false)
    expect((wrapper.get(checkbox('job:run')).element as HTMLInputElement).checked).toBe(true)
    await click(wrapper, '预览授权差异')
    expect(wrapper.text()).toContain('移除 · document:read')
    wrapper.unmount()
  })
})
describe('授权差异的业务含义', () => {
  it('区分 null 不适用授权与未授权，并保持确定排序', () => {
    expect(grantChanges({}, grantMap([{ permission_code: 'job:run', data_scope: null }]))).toEqual([
      { code: 'job:run', kind: '新增', before: undefined, after: null },
    ])
    expect(grantChanges({ 'job:run': null }, {})).toEqual([
      { code: 'job:run', kind: '移除', before: null, after: undefined },
    ])
  })
  it('SELF/TENANT 的扩大和收紧不被当成新增，未变内容没有差异', () => {
    expect(
      grantChanges({ b: 'TENANT', a: 'SELF', c: null }, { b: 'SELF', a: 'TENANT', c: null }).map(
        (value) => [value.code, value.kind],
      ),
    ).toEqual([
      ['a', '范围扩大'],
      ['b', '范围收紧'],
    ])
  })
})
