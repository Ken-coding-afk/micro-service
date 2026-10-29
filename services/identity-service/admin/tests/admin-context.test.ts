import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AdminApp from '../src/app/AdminApp.vue'
import { brand, current, login, read, IdentityError, type Me } from '../src/api/identity'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  brand: vi.fn(),
  current: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  read: vi.fn(),
}))
const platform = (): Me => ({
  view: 'APPLICATION_EFFECTIVE',
  sub: 'platform-owner',
  principal_type: 'platform',
  tenant_id: null,
  client_id: 'identity-admin-platform',
  profile: { name: '平台账号' },
  audiences: {
    'identity-service': {
      permissions: ['platform:tenant:read'],
      scopes: ['profile', 'platform.tenants'],
      data_scopes: {},
    },
  },
})
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  vi.mocked(read).mockResolvedValue({ page: 0, size: 100, has_more: false, items: [] })
  vi.mocked(login).mockReset().mockResolvedValue()
  vi.mocked(brand).mockResolvedValue({
    project_display_name: '示例项目',
    login_title: '账号登录',
    admin_title: '身份管理',
    logo_url: '',
    api_major: 1,
  })
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState({}, '', '/')
})

async function screen(path = '/account') {
  const placeholder = { render: () => h('div', '页面内容') }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/account', component: placeholder },
      { path: '/platform/audits', component: placeholder },
      { path: '/platform/tenants/:managementTenant/identity/users', component: placeholder },
      {
        path: '/platform/tenants',
        component: placeholder,
        meta: { title: '租户管理', section: '平台管理' },
      },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AdminApp, { global: { plugins: [router], stubs: { TenantPicker: true } } })
  await flushPromises()
  return { wrapper, router }
}

describe('平台管理员唯一入口', () => {
  it.each([
    [[], '/platform/tenants'],
    [['only'], '/platform/tenants/only/identity/users'],
    [['default', 'other'], '/platform/tenants'],
  ])('首页按可用租户数量进入工作区 %j', async (ids, expected) => {
    window.history.replaceState({}, '', '/identity/admin/')
    vi.mocked(current).mockResolvedValue(platform())
    vi.mocked(read).mockResolvedValue({
      items: (ids as string[]).map((id) => ({ id, code: id, status: 'ACTIVE', expires_at: null })),
      has_more: false,
    })
    const { wrapper, router } = await screen()
    expect(router.currentRoute.value.path).toBe(expected)
    wrapper.unmount()
  })
  it('两个标签页分别保留 URL 目标，刷新不自动切到默认租户', async () => {
    vi.mocked(current).mockResolvedValue(platform())
    vi.mocked(read).mockResolvedValue({
      items: ['default', 'other'].map((id) => ({ id, status: 'ACTIVE' })),
      has_more: false,
    })
    const first = await screen('/platform/tenants/default/identity/users')
    const second = await screen('/platform/tenants/other/identity/users')
    expect(first.router.currentRoute.value.params.managementTenant).toBe('default')
    expect(second.router.currentRoute.value.params.managementTenant).toBe('other')
    first.wrapper.unmount()
    second.wrapper.unmount()
  })
  it('匿名打开后台自动进入平台账号密码页，不需要二次点击', async () => {
    vi.mocked(current).mockRejectedValue(new IdentityError(401, 'INVALID_CREDENTIALS'))
    const { wrapper } = await screen()
    expect(current).toHaveBeenCalledExactlyOnceWith()
    expect(wrapper.text()).toContain('平台管理员登录')
    expect(wrapper.text()).not.toContain('租户账号')
    expect(wrapper.find('[name=tenant_code]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('页面内容')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.find('.sidebar').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('继续登录')
    expect(login).toHaveBeenCalledExactlyOnceWith()
    wrapper.unmount()
  })
  it('登录准备失败时显示错误并允许手动重试，不自动循环', async () => {
    vi.mocked(current).mockRejectedValue(new IdentityError(401, 'INVALID_CREDENTIALS'))
    vi.mocked(login).mockRejectedValueOnce(new IdentityError(503, 'SERVICE_UNAVAILABLE'))
    const { wrapper } = await screen()
    expect(login).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[role=alert]').text()).toContain('服务暂时不可用')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(login).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('更换账号直接打开登录表单', async () => {
    vi.mocked(current).mockResolvedValue(platform())
    const { wrapper } = await screen()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '更换账号')!
      .trigger('click')
    expect(login).toHaveBeenCalledExactlyOnceWith()
    expect(wrapper.find('form').exists()).toBe(false)
    wrapper.unmount()
  })
  it('旧租户偏好不影响平台会话恢复，不读取租户会话', async () => {
    sessionStorage.setItem('identity.admin.realm', 'tenant')
    vi.mocked(current).mockResolvedValue(platform())
    const { wrapper } = await screen()
    expect(current).toHaveBeenCalledExactlyOnceWith()
    expect(wrapper.text()).toContain('页面内容')
    expect(wrapper.text()).not.toContain('进入租户管理')
    expect(wrapper.get('.realm-badge').text()).toBe('平台管理员')
    expect(login).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('会话失效不显示管理内容，错误身份也不挂载页面', async () => {
    vi.mocked(current).mockRejectedValue(new IdentityError(503, 'INVALID_IDENTITY_RESPONSE'))
    const { wrapper } = await screen()
    expect(wrapper.text()).not.toContain('页面内容')
    expect(wrapper.get('[role=alert]').text()).toContain('服务暂时不可用')
    wrapper.unmount()
  })
  it('平台权限决定项目导航，租户权限不能代替平台权限', async () => {
    const active = platform()
    active.audiences['identity-service'].permissions = [
      'platform:tenant:read',
      'identity:audit:read',
    ]
    vi.mocked(current).mockResolvedValue(active)
    const { wrapper } = await screen()
    expect(wrapper.findAll('nav a').map((a) => a.text())).toEqual(['租户管理', '个人账号'])
    wrapper.unmount()
  })
  it('未选择租户不显示租户内管理，平台审计始终使用平台路径', async () => {
    const active = platform()
    active.audiences['identity-service'].permissions = [
      'platform:identity:read',
      'platform:audit:read',
    ]
    vi.mocked(current).mockResolvedValue(active)
    const { wrapper } = await screen()
    expect(wrapper.findAll('nav a').map((a) => a.text())).toEqual([
      '用户管理',
      '用户组',
      '角色管理',
      '审计日志',
      '个人账号',
    ])
    expect(wrapper.find('a[href="/platform/audits"]').exists()).toBe(true)
    wrapper.unmount()
  })
  it.each([
    [[], []],
    [['platform:identity:manage'], []],
    [['platform:identity:read'], ['用户管理', '用户组', '角色管理']],
    [
      ['platform:identity:read', 'platform:audit:read'],
      ['用户管理', '用户组', '角色管理'],
    ],
  ])('选定租户后按平台权限显示目标管理入口 %j', async (permissions, expected) => {
    const active = platform()
    active.audiences['identity-service'].permissions = permissions
    vi.mocked(current).mockResolvedValue(active)
    const { wrapper } = await screen('/platform/tenants/alpha/identity/users')
    const links = wrapper
      .findAll('nav a')
      .filter((a) => (a.attributes('href') ?? '').includes('/tenants/alpha/'))
    expect(links.map((a) => a.text())).toEqual(expected)
    expect(
      links.every((a) =>
        (a.attributes('href') ?? '').includes('/platform/tenants/alpha/identity/'),
      ),
    ).toBe(true)
    expect(wrapper.get('.realm-badge').text()).toBe('平台管理员')
    wrapper.unmount()
  })
})
