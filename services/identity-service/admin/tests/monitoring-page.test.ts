import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { accountContext, type AccountContext } from '../src/app/context'
import MonitoringPage from '../src/pages/platform/MonitoringPage.vue'

afterEach(() => vi.unstubAllGlobals())
function owner(allowed: boolean): AccountContext {
  return {
    realm: 'platform',
    me: {
      view: 'APPLICATION_EFFECTIVE',
      sub: 'admin',
      principal_type: 'platform',
      tenant_id: null,
      client_id: 'identity-admin-platform',
      profile: {},
      audiences: {
        'identity-service': {
          permissions: allowed ? ['platform:monitoring:read'] : [],
          scopes: ['platform.monitoring'],
          data_scopes: {},
        },
      },
    },
    changed: vi.fn(async () => {}),
    signedOut: vi.fn(),
    failed: vi.fn(),
  }
}
it('does not request a dashboard without permission', async () => {
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  const page = mount(MonitoringPage, {
    global: { provide: { [accountContext as symbol]: () => owner(false) } },
  })
  await flushPromises()
  expect(fetcher).not.toHaveBeenCalled()
  expect(page.find('iframe').exists()).toBe(false)
  expect(page.text()).toContain('没有服务监控权限')
  page.unmount()
})
it('keeps an unavailable monitor out of the iframe', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: true, json: async () => ({ data: { enabled: false } }) })),
  )
  const page = mount(MonitoringPage, {
    global: { provide: { [accountContext as symbol]: () => owner(true) } },
  })
  await flushPromises()
  expect(page.text()).toContain('未启用监控')
  expect(page.find('iframe').exists()).toBe(false)
  page.unmount()
})
it('embeds only the same-origin protected dashboard', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => ({
      ok: true,
      json: async () => ({
        data: url.endsWith('/availability')
          ? { enabled: true }
          : {
              items: [
                {
                  service: 'identity-service',
                  instance: 'one',
                  healthy: true,
                  requests_per_second: 2,
                  error_ratio: 0,
                  p95_seconds: 0.125,
                },
              ],
              sampled_at: '2026-10-28T12:00:00Z',
            },
      }),
    })),
  )
  const page = mount(MonitoringPage, {
    global: { provide: { [accountContext as symbol]: () => owner(true) } },
  })
  await flushPromises()
  expect(page.text()).toContain('identity-service')
  expect(page.text()).toContain('125.00 ms')
  expect(page.find('iframe').exists()).toBe(false)
  await page
    .findAll('button')
    .find((button) => button.text() === '展开详细图表')!
    .trigger('click')
  expect(page.find('iframe').attributes('src')).toMatch(/^\/bff\/identity\/platform\/monitoring\//)
  page.unmount()
})
