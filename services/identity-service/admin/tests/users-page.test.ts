import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import UsersPage from '../src/pages/users/UsersPage.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'
import type { TenantUser } from '../src/api/tenant'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const mockedRead = vi.mocked(read),
  mockedWrite = vi.mocked(write)
let owner: AccountContext
let user: TenantUser
const dialogMethods = ['showModal', 'close'] as const
const originals = dialogMethods.map((name) =>
  Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name),
)
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  user = {
    id: 'user-1',
    username: 'learner',
    display_name: '原姓名',
    status: 'ACTIVE',
    must_change_password: false,
    version: 2,
  }
  owner = {
    realm: 'tenant',
    me: {
      view: 'APPLICATION_EFFECTIVE',
      sub: 'manager',
      principal_type: 'user',
      tenant_id: 'tenant-one',
      client_id: 'identity-admin-tenant',
      profile: {},
      audiences: {
        'identity-service': {
          scopes: ['identity.users'],
          permissions: ['identity:user:read', 'identity:user:manage', 'identity:permission:read'],
          data_scopes: {},
        },
      },
    },
    changed: vi.fn(async () => {}),
    signedOut: vi.fn(),
    failed: vi.fn(),
  }
  mockedRead.mockImplementation(async (_realm, path) =>
    path.includes('?') ? { page: 0, size: 20, has_more: false, items: [{ ...user }] } : { ...user },
  )
  mockedWrite.mockResolvedValue({})
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  // jsdom 仅适配 open 状态；真实原生窗口另由 Edge 验证。
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '')
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.removeAttribute('open')
    },
  })
})
afterEach(() => {
  vi.restoreAllMocks()
  dialogMethods.forEach((name, index) => {
    const descriptor = originals[index]
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
  })
  document.body.innerHTML = ''
})
async function screen() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: UsersPage },
      { path: '/sessions', component: { render: () => null } },
    ],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(
    { render: () => h(RouterView) },
    {
      attachTo: document.body,
      global: { plugins: [router], provide: { [accountContext as symbol]: () => owner } },
    },
  )
  await flushPromises()
  return wrapper
}
async function click(wrapper: Awaited<ReturnType<typeof screen>>, text: string) {
  const button = wrapper.findAll('button').find((b) => b.text() === text)
  expect(button, text).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
async function newUser(wrapper: Awaited<ReturnType<typeof screen>>) {
  await click(wrapper, '新建用户')
  await wrapper.get('#new-user-name').setValue('NewLearner')
  await wrapper.get('#user-display-name').setValue('新学员')
}
describe('租户用户管理', () => {
  it('独立会话读取权显示固定目标链接，不推导会话撤销权限', async () => {
    owner.me.audiences['identity-service'].permissions.push('identity:session:read')
    const wrapper = await screen()
    const link = wrapper.findAll('a').find((link) => link.text() === '查看会话')
    expect(link?.attributes('href')).toBe('/sessions?user_id=' + user.id)
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('无角色的新账号展示逐项拒绝及中文原因，不把目录判定误当成已授权', async () => {
    const wrapper = await screen()
    mockedRead.mockImplementation(async (_realm, path) =>
      path.endsWith('/effective-permissions')
        ? {
            view: 'ROLE_GRANTS',
            as_of: '2026-10-27T00:00:00Z',
            user_id: user.id,
            user_version: 2,
            catalog_version: 4,
            permissions: {
              'document:read': {
                allowed: false,
                data_scope: null,
                reasons: ['NO_ROLE_GRANT'],
                sources: [],
              },
            },
          }
        : { ...user },
    )
    await click(wrapper, '有效权限')
    expect(wrapper.text()).toContain('缺少角色授权')
    expect(wrapper.text()).toContain('拒绝')
    expect(wrapper.text()).not.toContain('NO_ROLE_GRANT')
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('拒绝平台入口读取；只读用户没有创建或编辑入口', async () => {
    owner.realm = 'platform'
    let wrapper = await screen()
    expect(mockedRead).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前账号无权读取租户用户')
    wrapper.unmount()
    owner.realm = 'tenant'
    owner.me.audiences['identity-service'].permissions = ['identity:user:read']
    wrapper = await screen()
    expect(wrapper.findAll('tbody button')).toHaveLength(0)
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('创建只发送本租户字段和稳定请求编号，提交中防重复，关闭清空密码', async () => {
    const wrapper = await screen()
    await newUser(wrapper)
    let finish: (value: unknown) => void = () => {}
    mockedWrite.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await wrapper.get('dialog form').trigger('submit')
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    expect(mockedWrite).toHaveBeenCalledWith(
      'tenant',
      '/users',
      { username: 'newlearner', display_name: '新学员' },
      'POST',
      expect.any(String),
    )
    finish({ id: 'new-user', initial_password: 'one-time-fixture-secret', replayed: false })
    await flushPromises()
    expect(wrapper.get('#user-initial-password').attributes('type')).toBe('password')
    expect((wrapper.get('#user-initial-password').element as HTMLInputElement).value).toBe(
      'one-time-fixture-secret',
    )
    await click(wrapper, '已记录，关闭')
    expect(wrapper.find('#user-initial-password').exists()).toBe(false)
    expect(sessionStorage.length).toBe(0)
    wrapper.unmount()
  })
  it('创建结果未知不会自动重放，显式核对沿用原编号且不恢复旧密码', async () => {
    const wrapper = await screen()
    await newUser(wrapper)
    mockedWrite.mockRejectedValueOnce(new IdentityError(0, 'TRANSPORT_UNAVAILABLE', '', true))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    const first = mockedWrite.mock.calls[0]
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    expect(wrapper.get('#new-user-name').element.closest('fieldset')?.disabled).toBe(true)
    mockedWrite.mockResolvedValueOnce({ id: 'existing', initial_password: null, replayed: true })
    await click(wrapper, '核对本次请求')
    expect(mockedWrite.mock.calls[1]).toEqual(first)
    expect(wrapper.find('#user-initial-password').exists()).toBe(false)
    expect(wrapper.text()).toContain('不会再次交付原密码')
    wrapper.unmount()
  })
  it('资料与启停分别提交，采用新读取版本且不混入其他字段', async () => {
    const wrapper = await screen()
    await click(wrapper, '修改资料')
    await wrapper.get('#user-display-name').setValue('修改姓名')
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenLastCalledWith(
      'tenant',
      '/users/user-1/profile',
      { version: 2, display_name: '修改姓名' },
      'PUT',
    )
    user = { ...user, version: 3, display_name: '修改姓名' }
    await click(wrapper, '启停')
    await wrapper.get('#user-status').setValue('DISABLED')
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenLastCalledWith(
      'tenant',
      '/users/user-1',
      { version: 3, status: 'DISABLED' },
      'PUT',
    )
    expect(mockedWrite).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('待激活账号不能从启停绕过首次改密，最后管理员拒绝不显示成功', async () => {
    user.status = 'PENDING_ACTIVATION'
    const wrapper = await screen()
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text() === '启停')
        ?.attributes('disabled'),
    ).toBeDefined()
    user.status = 'ACTIVE'
    await click(wrapper, '查询')
    await click(wrapper, '启停')
    await wrapper.get('#user-status').setValue('DISABLED')
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'LAST_TENANT_ADMIN'))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('至少一个有效租户管理员')
    expect(wrapper.text()).not.toContain('用户状态已更新')
    expect(wrapper.get('dialog').attributes('open')).toBeDefined()
    expect(wrapper.find('.conflict-panel').exists()).toBe(false)
    wrapper.unmount()
  })
  it('版本冲突保留资料草稿，显式核对后才允许用当前版本保存', async () => {
    const wrapper = await screen()
    await click(wrapper, '修改资料')
    await wrapper.get('#user-display-name').setValue('我的草稿')
    user = { ...user, version: 8, display_name: '其他人修改' }
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'USER_VERSION_CONFLICT'))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect((wrapper.get('#user-display-name').element as HTMLInputElement).value).toBe('我的草稿')
    expect(wrapper.text()).toContain('其他人修改')
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    await click(wrapper, '保留我的修改')
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenLastCalledWith(
      'tenant',
      '/users/user-1/profile',
      { version: 8, display_name: '我的草稿' },
      'PUT',
    )
    wrapper.unmount()
  })
  it('有效权限展示真实快照及角色来源，明确应用与业务对象限制，不执行写入', async () => {
    const wrapper = await screen()
    mockedRead.mockImplementation(async (_realm, path) =>
      path.endsWith('/effective-permissions')
        ? {
            view: 'ROLE_GRANTS',
            as_of: '2026-10-27T00:00:00Z',
            user_id: user.id,
            user_version: 2,
            catalog_version: 4,
            permissions: {
              'document:read': {
                allowed: true,
                data_scope: 'SELF',
                reasons: [],
                sources: [
                  {
                    role_id: 'reader-role',
                    data_scope: 'SELF',
                    role_active: true,
                    authorization_version: 9,
                  },
                ],
              },
            },
          }
        : { ...user },
    )
    await click(wrapper, '有效权限')
    expect(wrapper.text()).toContain('document:read')
    expect(wrapper.text()).toContain('reader-role')
    expect(wrapper.text()).toContain('SELF')
    expect(wrapper.text()).toContain('业务对象归属仍由业务服务判断')
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
