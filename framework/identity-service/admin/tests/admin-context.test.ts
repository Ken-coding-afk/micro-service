import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AdminApp from '../src/app/AdminApp.vue'
import { brand, current, IdentityError, type Me } from '../src/api/identity'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  brand: vi.fn(),
  current: vi.fn(),
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
})

async function screen() {
  const placeholder = { render: () => h('div', '页面内容') }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/account', component: placeholder },
      { path: '/audits', component: placeholder },
      { path: '/sessions', component: placeholder },
      {
        path: '/platform/tenants',
        component: placeholder,
        meta: { title: '租户管理', section: '平台管理' },
      },
    ],
  })
  await router.push('/account')
  await router.isReady()
  const wrapper = mount(AdminApp, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('固定管理入口与导航', () => {
  it('会话读取权才显示租户会话入口，只有管理权不能替代读取', async () => {
    const tenant: Me = {
      ...platform(),
      principal_type: 'user',
      tenant_id: 'tenant',
      client_id: 'identity-admin-tenant',
    }
    tenant.audiences['identity-service'].permissions = ['identity:session:read']
    vi.mocked(current).mockImplementation(async (realm) => {
      if (realm === 'tenant') return tenant
      throw new IdentityError(401, 'INVALID_CREDENTIALS')
    })
    let view = await screen()
    expect(view.wrapper.findAll('nav a').some((link) => link.text().includes('会话管理'))).toBe(
      true,
    )
    view.wrapper.unmount()
    tenant.audiences['identity-service'].permissions = ['identity:session:manage']
    view = await screen()
    expect(view.wrapper.findAll('nav a').some((link) => link.text().includes('会话管理'))).toBe(
      false,
    )
    view.wrapper.unmount()
  })
  it.each(['platform', 'tenant'] as const)(
    '审计导航要求当前物理身份的独立权限：%s',
    async (realm) => {
      const active: Me =
        realm === 'platform'
          ? platform()
          : {
              ...platform(),
              principal_type: 'user',
              tenant_id: 'tenant',
              client_id: 'identity-admin-tenant',
            }
      active.audiences['identity-service'].permissions = [
        realm === 'platform' ? 'platform:audit:read' : 'identity:audit:read',
      ]
      vi.mocked(current).mockImplementation(async (requested) => {
        if (requested === realm) return active
        throw new IdentityError(401, 'INVALID_CREDENTIALS')
      })
      let view = await screen()
      expect(view.wrapper.findAll('nav a').some((link) => link.text().includes('管理审计'))).toBe(
        true,
      )
      view.wrapper.unmount()
      active.audiences['identity-service'].permissions = [
        realm === 'platform' ? 'identity:audit:read' : 'platform:audit:read',
      ]
      view = await screen()
      expect(view.wrapper.findAll('nav a').some((link) => link.text().includes('管理审计'))).toBe(
        false,
      )
      view.wrapper.unmount()
    },
  )
  it('角色读取权显示固定角色入口，不隐含目录或用户读取能力', async () => {
    const tenant: Me = {
      ...platform(),
      principal_type: 'user',
      tenant_id: 'tenant',
      client_id: 'identity-admin-tenant',
    }
    tenant.audiences['identity-service'].permissions = ['identity:role:read']
    vi.mocked(current).mockImplementation(async (realm) => {
      if (realm === 'tenant') return tenant
      throw new IdentityError(401, 'INVALID_CREDENTIALS')
    })
    const { wrapper } = await screen()
    const links = wrapper.findAll('nav a')
    expect(links.some((link) => link.text().includes('角色管理'))).toBe(true)
    expect(links.some((link) => link.text().includes('权限与授权'))).toBe(false)
    expect(links.some((link) => link.text().includes('用户管理'))).toBe(false)
    wrapper.unmount()
  })
  it('目录读取权显示固定权限入口，不隐含用户管理权', async () => {
    const tenant: Me = {
      ...platform(),
      principal_type: 'user',
      tenant_id: 'tenant',
      client_id: 'identity-admin-tenant',
    }
    tenant.audiences['identity-service'].permissions = ['identity:permission:read']
    vi.mocked(current).mockImplementation(async (realm) => {
      if (realm === 'tenant') return tenant
      throw new IdentityError(401, 'INVALID_CREDENTIALS')
    })
    const { wrapper } = await screen()
    expect(wrapper.findAll('nav a').some((link) => link.text().includes('权限与授权'))).toBe(true)
    expect(wrapper.findAll('nav a').some((link) => link.text().includes('用户管理'))).toBe(false)
    wrapper.unmount()
  })
  it('租户读取权只显示租户用户入口，不产生平台导航', async () => {
    const tenant: Me = {
      ...platform(),
      principal_type: 'user',
      tenant_id: 'tenant',
      client_id: 'identity-admin-tenant',
    }
    tenant.audiences['identity-service'].permissions = ['identity:user:read']
    vi.mocked(current).mockImplementation(async (realm) => {
      if (realm === 'tenant') return tenant
      throw new IdentityError(401, 'INVALID_CREDENTIALS')
    })
    const { wrapper } = await screen()
    expect(wrapper.findAll('nav a').some((link) => link.text().includes('用户管理'))).toBe(true)
    expect(wrapper.findAll('nav a').some((link) => link.text().includes('租户管理'))).toBe(false)
    wrapper.unmount()
  })
  it('已选择租户入口失效时不静默进入仍有效的平台入口', async () => {
    sessionStorage.setItem('identity.admin.realm', 'tenant')
    vi.mocked(current).mockImplementation(async (realm) => {
      if (realm === 'platform') return platform()
      throw new IdentityError(401, 'INVALID_CREDENTIALS')
    })
    const { wrapper } = await screen()
    expect(wrapper.findAll('h2').some((item) => item.text() === '账号登录')).toBe(true)
    expect(wrapper.findAll('h1').some((item) => item.text() === '个人账号')).toBe(false)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '进入平台管理')
      ?.trigger('click')
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('个人账号')
    expect(vi.mocked(current).mock.calls.filter((call) => call[0] === 'platform')).toHaveLength(2)
    wrapper.unmount()
  })

  it('导航只显示当前应用允许的固定平台页面', async () => {
    sessionStorage.setItem('identity.admin.realm', 'platform')
    vi.mocked(current).mockImplementation(async (realm) => {
      if (realm === 'platform') return platform()
      throw new IdentityError(401, 'INVALID_CREDENTIALS')
    })
    const { wrapper, router } = await screen()
    expect(wrapper.findAll('nav a').some((link) => link.text().includes('租户管理'))).toBe(true)
    expect(wrapper.findAll('nav a').some((link) => link.text().includes('平台管理员'))).toBe(false)
    await router.push('/platform/tenants')
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('租户管理')
    wrapper.unmount()
  })

  it('租户主体不会因同名伪造显示权限出现平台导航', async () => {
    const tenant: Me = {
      ...platform(),
      principal_type: 'user',
      tenant_id: 'tenant',
      client_id: 'identity-admin-tenant',
    }
    vi.mocked(current).mockImplementation(async (realm) => {
      if (realm === 'tenant') return tenant
      throw new IdentityError(401, 'INVALID_CREDENTIALS')
    })
    const { wrapper } = await screen()
    expect(wrapper.findAll('nav a')).toHaveLength(1)
    expect(wrapper.findAll('nav a')[0].text()).toContain('个人账号')
    wrapper.unmount()
  })
})
