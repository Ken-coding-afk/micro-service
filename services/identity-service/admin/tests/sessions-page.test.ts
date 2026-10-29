import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import SessionsPage from '../src/pages/sessions/SessionsPage.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const mockedRead = vi.mocked(read),
  mockedWrite = vi.mocked(write)
let owner: AccountContext
const target = '231fa756-13e6-4445-a74b-1160e89e21fa'
const session = {
  id: 'session-one',
  user_id: target,
  username: 'learner',
  display_name: '学习者',
  created_at: '2026-10-27T00:00:00Z',
  last_activity_at: '2026-10-27T00:10:00Z',
  expires_at: '2026-10-27T08:00:00Z',
  revoked_at: null,
  active: true,
  applications: ['client-one'],
}
beforeEach(() => {
  vi.clearAllMocks()
  owner = reactive({
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
          permissions: ['platform:identity:read', 'platform:identity:manage'],
          scopes: ['identity.sessions'],
          data_scopes: {},
        },
      },
    },
    changed: vi.fn(async () => {}),
    signedOut: vi.fn(),
    failed: vi.fn(),
  }) as AccountContext
  mockedRead.mockImplementation(async (_realm, path) => ({
    page: Number(new URL('http://local' + path).searchParams.get('page')),
    size: 20,
    has_more: false,
    items: [structuredClone(session)],
  }))
  mockedWrite.mockResolvedValue({ revoked: true })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})
async function render(query = '') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/platform/tenants/tenant-one/identity/sessions', component: SessionsPage },
      { path: '/account', component: { render: () => null } },
    ],
  })
  await router.push('/platform/tenants/tenant-one/identity/sessions' + query)
  await router.isReady()
  const wrapper = mount(
    { render: () => h(RouterView) },
    {
      attachTo: document.body,
      global: { plugins: [router], provide: { [accountContext as symbol]: () => owner } },
    },
  )
  await flushPromises()
  return { wrapper, router }
}
type Wrapper = Awaited<ReturnType<typeof render>>['wrapper']
function button(wrapper: Wrapper, name: string) {
  const found = wrapper.findAll('button').find((button) => button.text() === name)
  if (!found) throw new Error('Missing button: ' + name)
  return found
}
describe('管理员会话治理', () => {
  it('未选择租户或缺少读取权时不请求', async () => {
    owner.targetTenant = undefined
    let view = await render()
    expect(mockedRead).not.toHaveBeenCalled()
    expect(view.wrapper.text()).toContain('个人账号页')
    view.wrapper.unmount()
    owner.targetTenant = 'tenant-one'
    owner.me.audiences['identity-service']!.permissions = ['platform:identity:manage']
    view = await render()
    expect(mockedRead).not.toHaveBeenCalled()
    view.wrapper.unmount()
  })
  it('只读权可以查询账号、应用和时间摘要，不能发起撤销', async () => {
    owner.me.audiences['identity-service']!.permissions = ['platform:identity:read']
    const { wrapper } = await render()
    expect(mockedRead).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/sessions?page=0&size=20',
    )
    expect(wrapper.text()).toContain('学习者')
    expect(wrapper.text()).toContain('client-one')
    expect(wrapper.text()).toContain('当前仅有读取权限')
    expect(wrapper.findAll('button').some((button) => button.text() === '撤销会话')).toBe(false)
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('用户页传入完整UUID时按该目标筛选；非法ID不请求，翻页使用已应用条件', async () => {
    mockedRead.mockImplementation(async (_realm, path) => ({
      page: Number(new URL('http://local' + path).searchParams.get('page')),
      size: 20,
      has_more: true,
      items: [structuredClone(session)],
    }))
    const { wrapper } = await render('?user_id=' + target)
    expect(mockedRead.mock.calls[0]?.[1]).toBe(
      '/platform/tenants/tenant-one/identity/sessions?page=0&size=20&user_id=' + target,
    )
    await wrapper.get('input').setValue('not-a-uuid')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mockedRead).toHaveBeenCalledTimes(1)
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(mockedRead.mock.calls.at(-1)?.[1]).toBe(
      '/platform/tenants/tenant-one/identity/sessions?page=1&size=20&user_id=' + target,
    )
    wrapper.unmount()
  })
  it('取消确认不写，指定撤销只使用服务端会话ID和空命令；成功后重新读取', async () => {
    const { wrapper } = await render()
    vi.mocked(window.confirm).mockReturnValueOnce(false)
    await button(wrapper, '撤销会话').trigger('click')
    expect(mockedWrite).not.toHaveBeenCalled()
    await button(wrapper, '撤销会话').trigger('click')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/sessions/session-one:revoke',
    )
    expect(mockedRead).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('指定会话撤销已完成')
    wrapper.unmount()
  })
  it('全端退出仅使用目标账号ID，自管时明确提示当前入口可能退出', async () => {
    mockedRead.mockResolvedValueOnce({
      page: 0,
      size: 20,
      has_more: false,
      items: [{ ...session, user_id: 'manager' }],
    })
    const { wrapper } = await render()
    await button(wrapper, '用户全端退出').trigger('click')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/users/manager:sign-out-all',
    )
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('当前管理入口退出'))
    wrapper.unmount()
  })
  it('提交期间不能重复或切换页面/目标；不影响同一页其他目标的命令路径', async () => {
    const { wrapper, router } = await render()
    let finish!: (value: object) => void
    mockedWrite.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await button(wrapper, '撤销会话').trigger('click')
    await button(wrapper, '撤销会话').trigger('click')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    await router.push('/account')
    expect(router.currentRoute.value.path).toBe('/platform/tenants/tenant-one/identity/sessions')
    await router.push('/platform/tenants/tenant-one/identity/sessions?user_id=' + target)
    expect(router.currentRoute.value.query.user_id).toBeUndefined()
    finish({ revoked: true })
    await flushPromises()
    wrapper.unmount()
  })
  it('未知结果冻结全部撤销且不重放，人工读取成功后才解除；读取失败不能解除', async () => {
    const { wrapper } = await render()
    mockedWrite.mockRejectedValueOnce(new IdentityError(0, 'UNKNOWN', '', true))
    await button(wrapper, '撤销会话').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已暂停后续撤销')
    expect(button(wrapper, '撤销会话').attributes('disabled')).toBeDefined()
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    mockedRead.mockRejectedValueOnce(new IdentityError(503, 'UNAVAILABLE'))
    await button(wrapper, '重新读取会话').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已暂停后续撤销')
    await button(wrapper, '重新读取会话').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('已暂停后续撤销')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('已撤销/当前不可用项不能再次操作，撤销后重读失败不宣称已核对', async () => {
    mockedRead.mockResolvedValueOnce({
      page: 0,
      size: 20,
      has_more: false,
      items: [{ ...session, active: false }],
    })
    const { wrapper } = await render()
    expect(button(wrapper, '撤销会话').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('当前不可用')
    await button(wrapper, '重新读取会话').trigger('click')
    await flushPromises()
    mockedRead.mockRejectedValueOnce(new IdentityError(503, 'UNAVAILABLE'))
    await button(wrapper, '撤销会话').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('指定会话撤销已完成')
    expect(wrapper.text()).not.toContain('已重新读取当前状态')
    expect(wrapper.text()).not.toContain('没有符合条件')
    wrapper.unmount()
  })
})
