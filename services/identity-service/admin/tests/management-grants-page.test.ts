import { expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Page from '../src/pages/roles/RolesPage.vue'
import { accountContext } from '../src/app/context'
import { read, write } from '../src/api/identity'
vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
it('已退役入口不再出现在管理员页面，也不会请求旧 API', async () => {
  vi.mocked(read).mockResolvedValue({
    page: 0,
    size: 20,
    has_more: false,
    can_create: true,
    items: [],
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: Page }],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(Page, {
    global: {
      plugins: [router],
      provide: {
        [accountContext as symbol]: () => ({
          realm: 'platform',
          targetTenant: 'tenant-one',
          me: {
            view: 'APPLICATION_EFFECTIVE',
            sub: 'admin',
            principal_type: 'platform',
            tenant_id: null,
            client_id: 'identity-admin-platform',
            profile: {},
            audiences: {
              'identity-service': {
                scopes: ['identity.roles', 'identity.users', 'identity.permissions'],
                permissions: [
                  'platform:identity:read',
                  'platform:identity:manage',
                  'platform:identity:read',
                  'platform:identity:read',
                ],
                data_scopes: {},
              },
            },
          },
          changed: vi.fn(),
          signedOut: vi.fn(),
          failed: vi.fn(),
        }),
      },
    },
  })
  await flushPromises()
  expect(wrapper.text()).not.toContain('管理委派')
  expect(
    vi
      .mocked(read)
      .mock.calls.some(
        ([, path]) =>
          path.includes('management-grants') || path.includes('authorization-evaluations'),
      ),
  ).toBe(false)
  expect(write).not.toHaveBeenCalled()
  wrapper.unmount()
})
