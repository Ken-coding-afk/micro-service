import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import RoleDetailsPage from '../src/pages/roles/RoleDetailsPage.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'
vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const source = {
  role: {
    id: 'reader',
    code: 'reader',
    name: '阅读者',
    status: 'ACTIVE',
    version: 4,
    description: '原说明',
    protected: false,
    can_manage: true,
  },
  grants: [],
  catalog_version: 7,
}
const owner: AccountContext = {
  realm: 'platform',
  targetTenant: 'a',
  me: {
    view: 'APPLICATION_EFFECTIVE',
    sub: 'admin',
    principal_type: 'platform',
    tenant_id: null,
    client_id: 'identity-admin-platform',
    profile: {},
    audiences: {
      'identity-service': {
        permissions: ['platform:identity:read', 'platform:identity:manage'],
        scopes: ['platform.identity'],
        data_scopes: {},
      },
    },
  },
  changed: async () => {},
  signedOut: () => {},
  failed: () => {},
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(read).mockResolvedValue(structuredClone(source))
  vi.mocked(write).mockResolvedValue({})
  vi.spyOn(window, 'confirm').mockReturnValue(false)
})
afterEach(() => vi.restoreAllMocks())
async function screen() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/platform/tenants/a/identity/roles', component: { render: () => h('p') } },
      { path: '/roles/:roleId', component: RoleDetailsPage },
      { path: '/elsewhere', component: { render: () => h('p', 'elsewhere') } },
    ],
  })
  await router.push('/roles/reader')
  await router.isReady()
  const view = mount(
    { render: () => h(RouterView) },
    { global: { plugins: [router], provide: { [accountContext as symbol]: () => owner } } },
  )
  await flushPromises()
  return { view, router }
}
it('角色资料携带版本保存到平台目标，409 保留草稿并阻止盲重试', async () => {
  const { view } = await screen()
  vi.mocked(write).mockRejectedValue(new IdentityError(409, 'ROLE_VERSION_CONFLICT'))
  await view.find('#role-detail-name').setValue('编辑中的名称')
  await view.find('form').trigger('submit')
  await flushPromises()
  expect(write).toHaveBeenCalledWith(
    'platform',
    '/platform/tenants/a/identity/roles/reader',
    {
      version: 4,
      name: '编辑中的名称',
      description: '原说明',
      status: 'ACTIVE',
    },
    'PUT',
  )
  expect(view.find<HTMLInputElement>('#role-detail-name').element.value).toBe('编辑中的名称')
  expect(view.text()).toContain('草稿已保留')
  await view.find('form').trigger('submit')
  expect(write).toHaveBeenCalledTimes(1)
  view.unmount()
})
it('取消离开保留角色草稿，成员使用同一目标租户分页查询', async () => {
  const { view, router } = await screen()
  await view.find('#role-detail-name').setValue('未保存')
  await router.push('/elsewhere')
  expect(router.currentRoute.value.path).toBe('/roles/reader')
  vi.mocked(window.confirm).mockReturnValue(true)
  vi.mocked(read).mockResolvedValue({ items: [], page: 0, has_more: false })
  await view
    .findAll('[role=tab]')
    .find((x) => x.text() === '直接用户')!
    .trigger('click')
  await flushPromises()
  expect(read).toHaveBeenLastCalledWith(
    'platform',
    '/platform/tenants/a/identity/roles/reader/members?page=0&size=20',
  )
  expect(view.text()).toContain('该角色暂无成员')
  view.unmount()
})

it('返回基本资料读取最新角色版本，避免授权或成员操作后使用旧版本保存', async () => {
  const { view } = await screen()
  vi.mocked(read).mockResolvedValue({ items: [], page: 0, has_more: false })
  await view
    .findAll('[role=tab]')
    .find((x) => x.text() === '直接用户')!
    .trigger('click')
  await flushPromises()
  vi.mocked(read).mockResolvedValue({ ...source, role: { ...source.role, version: 5 } })
  await view
    .findAll('[role=tab]')
    .find((x) => x.text() === '基本资料')!
    .trigger('click')
  await flushPromises()
  await view.find('#role-detail-name').setValue('新名称')
  await view.find('form').trigger('submit')
  await flushPromises()
  expect(write).toHaveBeenCalledWith(
    'platform',
    '/platform/tenants/a/identity/roles/reader',
    { version: 5, name: '新名称', description: '原说明', status: 'ACTIVE' },
    'PUT',
  )
  view.unmount()
})
