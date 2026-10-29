import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'
import GroupsPage from '../src/pages/groups/GroupsPage.vue'
import PermissionCatalogPage from '../src/pages/permissions/PermissionCatalogPage.vue'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const owner: AccountContext = {
  realm: 'platform',
  targetTenant: 'tenant-one',
  me: {
    view: 'APPLICATION_EFFECTIVE',
    sub: 'admin',
    principal_type: 'platform',
    tenant_id: null,
    client_id: 'console',
    profile: {},
    audiences: {
      'identity-service': {
        scopes: [],
        permissions: [
          'platform:identity:read',
          'platform:identity:manage',
          'platform:permission:read',
          'platform:permission:manage',
        ],
        data_scopes: {},
      },
    },
  },
  changed: vi.fn(async () => {}),
  signedOut: vi.fn(),
  failed: vi.fn(),
}
beforeEach(() => {
  vi.resetAllMocks()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = true
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = false
    },
  })
})
afterEach(() => {
  vi.restoreAllMocks()
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal')
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'close')
})
async function render(component: typeof GroupsPage | typeof PermissionCatalogPage) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component }],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(component, {
    global: { plugins: [router], provide: { [accountContext as symbol]: () => owner } },
  })
  await flushPromises()
  return wrapper
}
it('新建组织携带树版本，冲突保留输入并阻止重复提交', async () => {
  vi.mocked(read).mockResolvedValue({ items: [], has_more: false, organization_version: 7 })
  const page = await render(GroupsPage)
  await page
    .findAll('button')
    .find((b) => b.text() === '＋ 顶层')!
    .trigger('click')
  await page.find('dialog input[pattern]').setValue('engineering')
  await page.find('dialog input[maxlength="128"]').setValue('研发部')
  vi.mocked(write).mockRejectedValue(new IdentityError(409, 'ORGANIZATION_VERSION_CONFLICT'))
  await page.find('dialog form').trigger('submit')
  await flushPromises()
  expect(write).toHaveBeenCalledWith(
    'platform',
    '/platform/tenants/tenant-one/identity/groups',
    {
      code: 'engineering',
      name: '研发部',
      description: '',
      sort: 0,
      parent_id: null,
      organization_version: 7,
    },
    'POST',
    expect.any(String),
  )
  expect(page.find('dialog input[pattern]').element).toHaveProperty('value', 'engineering')
  expect(page.find('dialog fieldset').attributes('disabled')).toBeDefined()
  await page.find('dialog form').trigger('submit')
  expect(write).toHaveBeenCalledTimes(1)
  page.unmount()
})
it('系统权限只读并展示来源，不能提交目录修改', async () => {
  const permission = {
    code: 'identity:user:read',
    name: '读取用户',
    owner_service: 'identity-service',
    principal_type: 'TENANT',
    group_code: 'identity',
    group_name: '身份管理',
    resource_code: 'user',
    resource_name: '用户',
    action_code: 'read',
    description: '',
    sort: 0,
    supported_data_scopes: [],
    oauth_scope: 'identity.users',
    status: 'ACTIVE',
    source: 'BUILTIN',
    can_edit: false,
  }
  vi.mocked(read).mockImplementation(async (_realm, path) => {
    if (path.endsWith('/service-audiences')) return []
    if (path.includes('/roles?')) return { items: [], has_more: false }
    if (path.includes('/permissions?'))
      return { items: [permission], has_more: false, catalog_version: 3 }
    return { permission, catalog_version: 3, role_count: 1, user_count: 2 }
  })
  const page = await render(PermissionCatalogPage)
  await page.find('tbody button')!.trigger('click')
  await flushPromises()
  expect(page.find('dialog').text()).toContain('系统内置权限由框架维护')
  expect(page.find('dialog fieldset').attributes('disabled')).toBeDefined()
  await page.find('dialog form').trigger('submit')
  expect(write).not.toHaveBeenCalled()
  page.unmount()
})
