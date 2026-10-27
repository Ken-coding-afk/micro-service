import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AccountPage from '../src/pages/account/AccountPage.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import {
  IdentityError,
  read,
  write,
  type Consent,
  type OwnSession,
  type Profile,
} from '../src/api/identity'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const mockedRead = vi.mocked(read)
const mockedWrite = vi.mocked(write)
let details: Profile
let sessions: OwnSession[]
let consents: Consent[]
const owner: AccountContext = {
  realm: 'tenant',
  me: {
    view: 'APPLICATION_EFFECTIVE',
    sub: 'owner',
    tenant_id: 'tenant',
    principal_type: 'user',
    client_id: 'identity-admin-tenant',
    profile: {},
    audiences: {},
  },
  changed: vi.fn(async () => {}),
  signedOut: vi.fn(),
  failed: vi.fn(),
}

beforeEach(() => {
  vi.clearAllMocks()
  details = { id: 'owner', username: 'account', display_name: '原显示名', version: 0 }
  sessions = [
    {
      id: 'current',
      created_at: '2026-10-27T00:00:00Z',
      last_activity_at: '2026-10-27T00:00:00Z',
      expires_at: '2026-10-27T08:00:00Z',
      revoked_at: null,
      current: true,
      active: true,
      applications: ['identity-admin-tenant'],
    },
    {
      id: 'other',
      created_at: '2026-10-26T23:00:00Z',
      last_activity_at: '2026-10-27T00:00:00Z',
      expires_at: '2026-10-27T07:00:00Z',
      revoked_at: null,
      current: false,
      active: true,
      applications: ['example-app'],
    },
  ]
  consents = [
    { client_id: 'external:one', name: '示例应用', status: 'ACTIVE', scopes: ['profile'] },
  ]
  mockedRead.mockImplementation(async (_realm, path) =>
    path === '/me/profile'
      ? { ...details }
      : path.startsWith('/me/sessions')
        ? [...sessions]
        : [...consents],
  )
  mockedWrite.mockImplementation(async () => ({ ...details }))
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

async function screen() {
  const wrapper = mount(AccountPage, {
    global: { provide: { [accountContext as symbol]: () => owner } },
  })
  await flushPromises()
  return wrapper
}

describe('个人账号交互', () => {
  it('资料保存期间不重复提交，版本来自已读取记录', async () => {
    const wrapper = await screen()
    let finish: (value: unknown) => void = () => {}
    mockedWrite.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await wrapper.get('#display-name').setValue('本人草稿')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(mockedWrite).toHaveBeenCalledOnce()
    expect(mockedWrite).toHaveBeenCalledWith(
      'tenant',
      '/me/profile',
      { version: 0, display_name: '本人草稿' },
      'PUT',
    )
    expect(wrapper.get('#display-name').attributes('disabled')).toBeDefined()
    finish({ ...details, display_name: '本人草稿', version: 1 })
    await flushPromises()
    expect(wrapper.text()).toContain('显示名已更新')
    expect(owner.changed).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('409 保留草稿，展示当前值，必须人工核对后才再次保存', async () => {
    const wrapper = await screen()
    await wrapper.get('#display-name').setValue('我的修改')
    details = { ...details, display_name: '服务器的新姓名', version: 1 }
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'USER_VERSION_CONFLICT'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect((wrapper.get('#display-name').element as HTMLInputElement).value).toBe('我的修改')
    expect(wrapper.text()).toContain('服务器的新姓名')
    expect(mockedWrite).toHaveBeenCalledOnce()
    const preserve = wrapper.findAll('button').find((button) => button.text() === '保留我的修改')
    await preserve?.trigger('click')
    expect(mockedWrite).toHaveBeenCalledOnce()
    mockedWrite.mockResolvedValueOnce({ ...details, display_name: '我的修改', version: 2 })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenLastCalledWith(
      'tenant',
      '/me/profile',
      { version: 1, display_name: '我的修改' },
      'PUT',
    )
    wrapper.unmount()
  })

  it('重新加载未保存资料前确认，取消会保留草稿', async () => {
    const wrapper = await screen()
    await wrapper.get('#display-name').setValue('未保存草稿')
    vi.mocked(window.confirm).mockReturnValueOnce(false)
    const reload = wrapper.findAll('button').find((button) => button.text() === '重新加载')
    await reload?.trigger('click')
    await flushPromises()
    expect(mockedRead).toHaveBeenCalledTimes(3)
    expect((wrapper.get('#display-name').element as HTMLInputElement).value).toBe('未保存草稿')
    wrapper.unmount()
  })

  it('密码确认不一致不发送；成功提交后清空凭据并退出', async () => {
    const wrapper = await screen()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await wrapper.get('#current-password').setValue('example-current-password')
    await wrapper.get('#new-password').setValue('example-new-password')
    await wrapper.get('#confirm-password').setValue('different-password')
    await wrapper.get('form').trigger('submit')
    expect(mockedWrite).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('两次输入的新密码不一致')
    await wrapper.get('#confirm-password').setValue('example-new-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledWith('tenant', '/me/password', {
      current_password: 'example-current-password',
      new_password: 'example-new-password',
    })
    expect(owner.signedOut).toHaveBeenCalledOnce()
    expect((wrapper.get('#current-password').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#new-password').element as HTMLInputElement).value).toBe('')
    expect(wrapper.text()).not.toContain('example-current-password')
    wrapper.unmount()
  })

  it('撤销结果未知不自动重放，也不提前显示成功', async () => {
    const wrapper = await screen()
    await wrapper.findAll('[role="tab"]')[2].trigger('click')
    mockedWrite.mockRejectedValueOnce(new IdentityError(503, 'UNKNOWN_RESULT', '', true))
    await wrapper.findAll('tbody button')[1].trigger('click')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledOnce()
    expect(mockedWrite).toHaveBeenCalledWith('tenant', '/me/sessions/other:revoke')
    expect(wrapper.text()).toContain('操作结果尚未确认')
    expect(wrapper.text()).not.toContain('会话已撤销')
    expect(owner.signedOut).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('退出全部登录需确认，正确调用本人入口', async () => {
    const wrapper = await screen()
    await wrapper.findAll('[role="tab"]')[2].trigger('click')
    vi.mocked(window.confirm).mockReturnValueOnce(false)
    const all = wrapper.findAll('button').find((button) => button.text() === '退出全部登录')
    await all?.trigger('click')
    expect(mockedWrite).not.toHaveBeenCalled()
    await all?.trigger('click')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledWith('tenant', '/me/sessions:sign-out-all')
    expect(owner.signedOut).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('撤销应用授权使用明确客户端，成功后重新读取列表', async () => {
    const wrapper = await screen()
    await wrapper.findAll('[role="tab"]')[3].trigger('click')
    consents = []
    await wrapper.findAll('tbody button')[0].trigger('click')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledWith('tenant', '/me/consents/external%3Aone:revoke')
    expect(wrapper.text()).toContain('暂无需要管理的应用同意记录')
    wrapper.unmount()
  })
})
