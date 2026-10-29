import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import RolesPage from '../src/pages/roles/RolesPage.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'
import type { RolePermissions } from '../src/api/tenant'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const mockedRead = vi.mocked(read),
  mockedWrite = vi.mocked(write)
let owner: AccountContext, source: RolePermissions, canCreate: boolean
const methods = ['showModal', 'close'] as const
const originals = methods.map((name) =>
  Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name),
)
beforeEach(() => {
  vi.clearAllMocks()
  canCreate = true
  source = {
    role: {
      id: 'role-one',
      code: 'reader',
      name: '阅读者',
      status: 'ACTIVE',
      protected: false,
      version: 4,
      authorization_version: 2,
      can_assign: true,
      can_edit: true,
      can_unassign: true,
      can_manage: true,
      restrictions: [],
    },
    catalog_version: 7,
    grants: [{ permission_code: 'document:read', data_scope: 'SELF' }],
  }
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
          scopes: ['identity.roles'],
          permissions: [
            'platform:identity:read',
            'platform:identity:manage',
            'platform:identity:read',
          ],
          data_scopes: {},
        },
      },
    },
    changed: vi.fn(async () => {}),
    signedOut: vi.fn(),
    failed: vi.fn(),
  }
  mockedRead.mockImplementation(async (_realm, path) =>
    path.startsWith('/platform/tenants/tenant-one/identity/roles?')
      ? {
          page: 0,
          size: 20,
          has_more: false,
          can_create: canCreate,
          items: [structuredClone(source.role)],
        }
      : structuredClone(source),
  )
  mockedWrite.mockResolvedValue({})
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '')
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.removeAttribute('open')
    },
  })
})
afterEach(() => {
  vi.restoreAllMocks()
  methods.forEach((name, i) => {
    const old = originals[i]
    if (old) Object.defineProperty(HTMLDialogElement.prototype, name, old)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
  })
  document.body.innerHTML = ''
})
async function screen() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: RolesPage },
      {
        path: '/platform/tenants/tenant-one/identity/permissions',
        component: { render: () => h('div') },
      },
    ],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(
    { render: () => h(RouterView) },
    {
      attachTo: document.body,
      global: { plugins: [router], provide: { [accountContext as symbol]: () => owner } },
    },
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
describe('角色资料管理', () => {
  it('删除读取当前版本并二次确认，提交中不重复发送DELETE', async () => {
    const wrapper = await screen()
    await click(wrapper, '删除角色')
    expect(wrapper.text()).toContain('没有成员且无管理委派引用')
    let finish: () => void = () => {}
    mockedWrite.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = () => resolve({ id: 'role-one', deleted: true })
        }),
    )
    await wrapper.get('dialog form').trigger('submit')
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    expect(mockedWrite).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/roles/role-one',
      { version: 4 },
      'DELETE',
    )
    finish()
    await flushPromises()
    expect(wrapper.text()).toContain('角色已删除')
    wrapper.unmount()
  })
  it('取消删除确认不发送命令，也不关闭审核窗口', async () => {
    const wrapper = await screen()
    await click(wrapper, '删除角色')
    vi.mocked(window.confirm).mockReturnValueOnce(false)
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).not.toHaveBeenCalled()
    expect(wrapper.get('dialog').attributes('open')).toBeDefined()
    wrapper.unmount()
  })
  it('非空角色拒绝明确提示，保留窗口且不显示删除成功', async () => {
    const wrapper = await screen()
    await click(wrapper, '删除角色')
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'ROLE_NOT_EMPTY'))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('先移除此角色的用户和用户组引用')
    expect(wrapper.text()).not.toContain('角色已删除')
    expect(wrapper.find('dialog [role="status"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('删除版本冲突必须核对后再次确认，不自动执行', async () => {
    const wrapper = await screen()
    await click(wrapper, '删除角色')
    source.role.version = 10
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'ROLE_VERSION_CONFLICT'))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    await click(wrapper, '核对后继续删除')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite.mock.calls[1]).toEqual([
      'platform',
      '/platform/tenants/tenant-one/identity/roles/role-one',
      { version: 10 },
      'DELETE',
    ])
    wrapper.unmount()
  })
  it('删除结果未知只查询核对，不自动重发或宣称成功', async () => {
    const wrapper = await screen()
    await click(wrapper, '删除角色')
    mockedWrite.mockRejectedValueOnce(new IdentityError(0, 'TRANSPORT_UNAVAILABLE', '', true))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    await click(wrapper, '核对角色列表')
    expect(mockedRead).toHaveBeenLastCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/roles?page=0&size=20&q=reader',
    )
    expect(wrapper.text()).not.toContain('角色已删除')
    wrapper.unmount()
  })
  it('复制带入源说明，支持修改或清空后明确提交', async () => {
    source.role.description = '源说明\n第二行'
    const wrapper = await screen()
    await click(wrapper, '复制角色')
    expect((wrapper.get('#role-description').element as HTMLTextAreaElement).value).toBe(
      '源说明\n第二行',
    )
    await wrapper.get('#role-code').setValue('copy-description')
    await wrapper.get('#role-description').setValue('')
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite.mock.calls[0][2]).toMatchObject({
      code: 'copy-description',
      description: '',
    })
    wrapper.unmount()
  })
  it('说明长度按Unicode码点计算，1024个emoji可提交，超长不发送', async () => {
    const wrapper = await screen()
    await click(wrapper, '新建角色')
    await wrapper.get('#role-code').setValue('unicode-description')
    await wrapper.get('#role-name').setValue('说明角色')
    await wrapper.get('#role-description').setValue('😀'.repeat(1025))
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).not.toHaveBeenCalled()
    await wrapper.get('#role-description').setValue('😀'.repeat(1024))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect((mockedWrite.mock.calls[0][2] as { description: string }).description).toBe(
      '😀'.repeat(1024),
    )
    wrapper.unmount()
  })
  it('未选租户不能读取，受限角色不能维护资料', async () => {
    owner.targetTenant = undefined
    let wrapper = await screen()
    expect(mockedRead).not.toHaveBeenCalled()
    wrapper.unmount()
    owner.targetTenant = 'tenant-one'
    canCreate = false
    source.role.can_manage = false
    wrapper = await screen()
    expect(wrapper.text()).toContain('可编辑授权')
    expect(
      wrapper
        .findAll('button')
        .some((value) => ['新建角色', '编辑资料', '复制角色'].includes(value.text())),
    ).toBe(false)
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('创建期间不重复提交，成功后按编码向服务器查询', async () => {
    const wrapper = await screen()
    await click(wrapper, '新建角色')
    await wrapper.get('#role-code').setValue('new-role')
    await wrapper.get('#role-name').setValue('新角色')
    let finish: () => void = () => {}
    mockedWrite.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = () => resolve({})
        }),
    )
    await wrapper.get('dialog form').trigger('submit')
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    expect(mockedWrite).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/roles',
      {
        code: 'new-role',
        name: '新角色',
        description: '',
      },
    )
    finish()
    await flushPromises()
    expect(mockedRead).toHaveBeenLastCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/roles?page=0&size=20&q=new-role',
    )
    expect(wrapper.text()).toContain('角色已创建')
    wrapper.unmount()
  })
  it('复制使用当前源角色和目录版本，保留源状态而不复制保护能力', async () => {
    source.role.status = 'DISABLED'
    const wrapper = await screen()
    await click(wrapper, '复制角色')
    await wrapper.get('#role-code').setValue('copy-reader')
    await wrapper.get('#role-name').setValue('复制阅读者')
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/roles/role-one:copy',
      {
        role_version: 4,
        catalog_version: 7,
        code: 'copy-reader',
        name: '复制阅读者',
        description: '',
      },
    )
    expect(wrapper.text()).toContain('授权和状态沿用已确认源角色')
    wrapper.unmount()
  })
  it('资料与状态提交当前版本，服务端拒绝时保留窗口并不显示成功', async () => {
    const wrapper = await screen()
    await click(wrapper, '编辑资料')
    await wrapper.get('#role-name').setValue('更新名称')
    await wrapper.get('#role-status').setValue('DISABLED')
    mockedWrite.mockRejectedValueOnce(new IdentityError(403, 'MANAGEMENT_NOT_ALLOWED'))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/roles/role-one',
      { version: 4, name: '更新名称', status: 'DISABLED', description: '' },
      'PUT',
    )
    expect(wrapper.get('dialog').attributes('open')).toBeDefined()
    expect(wrapper.text()).not.toContain('角色资料已更新')
    wrapper.unmount()
  })
  it('409 保留草稿，人工核对后才使用新的角色版本', async () => {
    const wrapper = await screen()
    await click(wrapper, '编辑资料')
    await wrapper.get('#role-name').setValue('我的草稿')
    await wrapper.get('#role-description').setValue('我的说明草稿')
    source.role.version = 8
    source.role.name = '服务器新名称'
    source.role.description = '服务器新说明'
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'ROLE_VERSION_CONFLICT'))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect((wrapper.get('#role-name').element as HTMLInputElement).value).toBe('我的草稿')
    expect(wrapper.text()).toContain('服务器新名称')
    expect(wrapper.text()).toContain('服务器新说明')
    expect((wrapper.get('#role-description').element as HTMLTextAreaElement).value).toBe(
      '我的说明草稿',
    )
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    await click(wrapper, '保留我的修改')
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite.mock.calls[1][2]).toMatchObject({
      version: 8,
      name: '我的草稿',
      description: '我的说明草稿',
    })
    wrapper.unmount()
  })
  it('未知创建结果只允许查询核对，不把非幂等创建直接重放', async () => {
    const wrapper = await screen()
    await click(wrapper, '新建角色')
    await wrapper.get('#role-code').setValue('unknown-role')
    await wrapper.get('#role-name').setValue('待核对角色')
    mockedWrite.mockRejectedValueOnce(new IdentityError(0, 'TRANSPORT_UNAVAILABLE', '', true))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    await click(wrapper, '核对角色列表')
    expect(mockedRead).toHaveBeenLastCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/roles?page=0&size=20&q=unknown-role',
    )
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('受保护角色不出现普通维护按钮，授权链接携带确定角色', async () => {
    source.role.protected = true
    source.role.can_manage = false
    const wrapper = await screen()
    expect(wrapper.findAll('tbody button').map((button) => button.text())).toEqual(['查看成员'])
    expect(wrapper.get('tbody a').attributes('href')).toBe(
      '/platform/tenants/tenant-one/identity/roles/role-one',
    )
    wrapper.unmount()
  })
})
