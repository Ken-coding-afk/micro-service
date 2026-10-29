import { afterEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { mount } from '@vue/test-utils'
import { accountContext, type AccountContext } from '../src/app/context'
import { canManage, useManagementApi } from '../src/app/management'
const owner = (): AccountContext => ({
  realm: 'platform',
  targetTenant: 'tenant-a',
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
})
const response = (data: unknown) =>
  new Response(JSON.stringify({ code: 'OK', request_id: 'request-test', data }))
afterEach(() => {
  vi.unstubAllGlobals()
})
describe('平台管理目标与认证身份分离', () => {
  it('没有管理目标不映射租户能力，审计额外要求平台审计权限', () => {
    const context = owner()
    expect(canManage(context, 'identity:user:manage')).toBe(true)
    expect(canManage(context, 'identity:audit:read')).toBe(false)
    expect(canManage(context, 'identity:unknown:manage')).toBe(false)
    context.targetTenant = undefined
    expect(canManage(context, 'identity:user:read')).toBe(false)
    expect(context.me.tenant_id).toBeNull()
  })
  it('获取 CSRF 期间切换目标，写入仍使用发起时的 A 路径及平台会话', async () => {
    let release!: (value: Response) => void
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            release = resolve
          }),
      )
      .mockResolvedValueOnce(response({ id: 'user' }))
    vi.stubGlobal('fetch', fetcher)
    const context = owner()
    let api!: ReturnType<typeof useManagementApi>
    const wrapper = mount(
      {
        setup() {
          api = useManagementApi()
          return () => h('div')
        },
      },
      { global: { provide: { [accountContext as symbol]: () => context } } },
    )
    const pending = api.write(
      'tenant',
      '/users/user/profile',
      { version: 2, display_name: 'A 的用户' },
      'PUT',
      'write-a',
    )
    context.targetTenant = 'tenant-b'
    release(response({ header_name: 'X-CSRF-TOKEN', token: 'csrf' }))
    await pending
    expect(fetcher.mock.calls[0][0]).toBe('/bff/identity/platform/csrf')
    expect(fetcher.mock.calls[1][0]).toBe(
      '/bff/identity/platform/api/platform/tenants/tenant-a/identity/users/user/profile',
    )
    expect(new Headers(fetcher.mock.calls[1][1]?.headers).has('Authorization')).toBe(false)
    expect(context.me.principal_type).toBe('platform')
    expect(context.me.tenant_id).toBeNull()
    wrapper.unmount()
  })
  it('非平台身份不得使用后台目标接口', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ items: [] }))
    vi.stubGlobal('fetch', fetcher)
    const context = owner()
    context.realm = 'tenant'
    context.me.principal_type = 'user'
    context.me.tenant_id = 'real-tenant'
    let api!: ReturnType<typeof useManagementApi>
    const wrapper = mount(
      {
        setup() {
          api = useManagementApi()
          return () => h('div')
        },
      },
      { global: { provide: { [accountContext as symbol]: () => context } } },
    )
    expect(() => api.read('tenant', '/users')).toThrow()
    expect(fetcher).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
