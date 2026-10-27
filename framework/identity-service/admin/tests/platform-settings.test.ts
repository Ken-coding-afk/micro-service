import { beforeEach, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ClientsPage from '../src/pages/platform/ClientsPage.vue'
import SecurityPolicyPage from '../src/pages/platform/SecurityPolicyPage.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'
import type { OAuthClient } from '../src/api/clients'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const client: OAuthClient = {
  id: 'client-one',
  client_id: 'web-bff',
  name: '业务应用',
  principal_type: 'user',
  owner_tenant_id: null,
  allowed_tenant_ids: ['tenant-a'],
  audiences: ['sample-java-service'],
  scopes: ['java.items.read'],
  redirect_uris: ['http://localhost/bff/web/callback'],
  logout_uris: [],
  public_client: false,
  first_party: true,
  status: 'ACTIVE',
  version: 2,
}
function account(permissions: string[]): AccountContext {
  return {
    realm: 'platform',
    me: {
      view: 'APPLICATION_EFFECTIVE',
      sub: 'admin-one',
      principal_type: 'platform',
      tenant_id: null,
      client_id: 'identity-admin-platform',
      profile: {},
      audiences: { 'identity-service': { permissions, scopes: [], data_scopes: {} } },
    },
    changed: vi.fn(async () => {}),
    signedOut: vi.fn(),
    failed: vi.fn(),
  }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(read).mockResolvedValue({ items: [client], has_more: false })
})
it('轮换携带版本，秘密仅当前窗口展示，关闭清除', async () => {
  const owner = ref(account(['platform:client:read', 'platform:client:manage']))
  const wrapper = mount(ClientsPage, {
    global: { provide: { [accountContext as symbol]: () => owner.value } },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((x) => x.text() === '查看与编辑')!
    .trigger('click')
  const checks = wrapper.findAll('input[type=checkbox]')
  await checks.at(-1)!.setValue(true)
  vi.mocked(write).mockResolvedValue({
    client: { ...client, version: 3 },
    client_secret: 'one-time-fixture',
    replayed: false,
  })
  await wrapper
    .findAll('button')
    .find((x) => x.text().startsWith('轮换秘密'))!
    .trigger('click')
  await flushPromises()
  expect(write).toHaveBeenCalledWith(
    'platform',
    '/platform/oauth-clients/client-one/secret:rotate',
    { version: 2 },
  )
  expect(wrapper.find('textarea[readonly]').element).toHaveProperty('value', 'one-time-fixture')
  await wrapper
    .findAll('button')
    .find((x) => x.text() === '关闭并清除秘密')!
    .trigger('click')
  expect(wrapper.find('textarea[readonly]').exists()).toBe(false)
  wrapper.unmount()
})
it('应用结果未知保留草稿且阻止重复写入，切换身份丢弃晚到响应', async () => {
  const owner = ref(account(['platform:client:read', 'platform:client:manage']))
  const wrapper = mount(ClientsPage, {
    global: { provide: { [accountContext as symbol]: () => owner.value } },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((x) => x.text() === '查看与编辑')!
    .trigger('click')
  await wrapper.findAll('input[type=checkbox]').at(-1)!.setValue(true)
  vi.mocked(write).mockRejectedValue(new IdentityError(503, 'UNKNOWN', '', true))
  await wrapper.find('[role=dialog] form').trigger('submit')
  await flushPromises()
  expect(wrapper.find('[role=dialog] fieldset').attributes('disabled')).toBeDefined()
  await wrapper.find('[role=dialog] form').trigger('submit')
  expect(write).toHaveBeenCalledTimes(1)
  let resolve!: (value: unknown) => void
  vi.mocked(read).mockReturnValueOnce(
    new Promise((r) => {
      resolve = r
    }),
  )
  await wrapper
    .findAll('button')
    .find((x) => x.text() === '查询')!
    .trigger('click')
  owner.value = { ...account([]), realm: 'tenant' }
  await flushPromises()
  resolve({ items: [client], has_more: false })
  await flushPromises()
  expect(wrapper.text()).not.toContain('业务应用')
  expect(wrapper.find('[role=dialog]').exists()).toBe(false)
  wrapper.unmount()
})
it('安全策略只提交版本和策略值，冲突不能覆盖，读取可恢复', async () => {
  const owner = account(['platform:security:read', 'platform:security:manage'])
  const values = {
    password_min_chars: 12,
    login_failure_threshold: 5,
    login_failure_window_seconds: 60,
    login_lock_seconds: 600,
    login_ip_attempt_limit: 50,
    login_ip_window_seconds: 60,
    session_absolute_seconds: 3600,
    session_idle_seconds: 900,
    recent_auth_seconds: 300,
  }
  vi.mocked(read).mockResolvedValue({ version: 4, values, effective: values })
  const wrapper = mount(SecurityPolicyPage, {
    global: { provide: { [accountContext as symbol]: () => owner } },
  })
  await flushPromises()
  await wrapper.find('input[type=number]').setValue(14)
  await wrapper.find('input[type=checkbox]').setValue(true)
  vi.mocked(write).mockRejectedValue(new IdentityError(409, 'SECURITY_POLICY_VERSION_CONFLICT'))
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  expect(write).toHaveBeenCalledWith(
    'platform',
    '/platform/security-policy',
    { version: 4, values: { ...values, password_min_chars: 14 } },
    'PUT',
  )
  expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined()
  expect(wrapper.find('input[type=number]').element).toHaveProperty('value', '14')
  await wrapper.find('form').trigger('submit')
  expect(write).toHaveBeenCalledTimes(1)
  await wrapper
    .findAll('button')
    .find((x) => x.text().startsWith('重新读取'))!
    .trigger('click')
  await flushPromises()
  expect(wrapper.find('input[type=number]').element).toHaveProperty('value', '12')
  wrapper.unmount()
})
it('租户不能读取平台应用或策略接口', async () => {
  const owner = {
    ...account(['platform:client:read', 'platform:security:read']),
    realm: 'tenant' as const,
  }
  for (const component of [ClientsPage, SecurityPolicyPage]) {
    const wrapper = mount(component, {
      global: { provide: { [accountContext as symbol]: () => owner } },
    })
    await flushPromises()
    expect(wrapper.find('[role=alert]').exists()).toBe(true)
    wrapper.unmount()
  }
  expect(read).not.toHaveBeenCalled()
})
