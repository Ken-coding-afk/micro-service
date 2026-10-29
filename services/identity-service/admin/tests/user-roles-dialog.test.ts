import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import UserRolesDialog from '../src/components/UserRolesDialog.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'
import type { Role, TenantUser, UserRoles } from '../src/api/tenant'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const mockedRead = vi.mocked(read),
  mockedWrite = vi.mocked(write)
let owner: AccountContext, baseline: UserRoles, target: TenantUser, roles: Role[]
const methods = ['showModal', 'close'] as const
const originals = methods.map((name) =>
  Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name),
)
function role(id: string, version = 1): Role {
  return {
    id,
    code: id,
    name: id + '名称',
    status: 'ACTIVE',
    protected: false,
    version,
    authorization_version: 0,
    can_assign: true,
    can_edit: false,
    can_unassign: true,
    can_manage: false,
    restrictions: [],
  }
}
beforeEach(() => {
  vi.clearAllMocks()
  target = {
    id: 'target',
    username: 'learner',
    real_name: null,
    employee_no: null,
    contact_email: null,
    phone: null,
    avatar_url: null,
    description: null,
    created_at: '2026-10-28T00:00:00Z',
    updated_at: '2026-10-28T00:00:00Z',
    last_login_at: null,
    group_names: null,
    display_name: '学员',
    status: 'ACTIVE',
    must_change_password: false,
    version: 4,
  }
  roles = [role('old-role', 2), role('new-role', 8), role('third-role', 5)]
  baseline = { user_id: target.id, version: 4, roles: [structuredClone(roles[0])] }
  owner = {
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
          scopes: ['identity.roles', 'identity.users'],
          permissions: ['platform:identity:manage', 'platform:identity:read'],
          data_scopes: {},
        },
      },
    },
    changed: vi.fn(async () => {}),
    signedOut: vi.fn(),
    failed: vi.fn(),
  }
  mockedRead.mockImplementation(async (_realm, path) => {
    if (path.startsWith('/platform/tenants/tenant-one/identity/users/'))
      return structuredClone(baseline)
    const params = new URLSearchParams(path.split('?')[1]),
      page = Number(params.get('page'))
    const q = params.get('q') ?? ''
    return {
      page,
      size: 20,
      has_more: !q && page === 0,
      items: structuredClone(
        q ? roles.filter((role) => role.code.includes(q)) : [roles[page === 0 ? 1 : 2]],
      ),
    }
  })
  mockedWrite.mockImplementation(async (_realm, _path, body) => {
    baseline.version++
    baseline.roles = (body as { roles: { id: string }[] }).roles.map((value) =>
      structuredClone(roles.find((role) => role.id === value.id)!),
    )
    return {}
  })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
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
  methods.forEach((name, index) => {
    const old = originals[index]
    if (old) Object.defineProperty(HTMLDialogElement.prototype, name, old)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
  })
  document.body.innerHTML = ''
})
async function screen() {
  const subject = ref<TenantUser | null>(target)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { render: () => h(UserRolesDialog, { user: subject.value }) } },
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
  return { wrapper, subject }
}
async function click(wrapper: Awaited<ReturnType<typeof screen>>['wrapper'], text: string) {
  const button = wrapper.findAll('button').find((value) => value.text() === text)
  expect(button, text).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
const checkbox = (code: string) => `input[aria-label="分配角色 ${code}"]`
describe('用户角色分配', () => {
  it('缺少分配权不能读取角色选项或提交', async () => {
    owner.me.audiences['identity-service'].permissions = ['platform:identity:read']
    const { wrapper } = await screen()
    expect(mockedRead).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前账号无权分配用户角色')
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('翻页保留未显示的原角色与已选角色，预览后提交用户/全部角色/委派版本', async () => {
    const { wrapper } = await screen()
    await wrapper.get(checkbox('new-role')).setValue(true)
    await click(wrapper, '下一页角色')
    expect(wrapper.text()).toContain('其他页面已选角色')
    expect((wrapper.get(checkbox('old-role')).element as HTMLInputElement).checked).toBe(true)
    await wrapper.get(checkbox('third-role')).setValue(true)
    await click(wrapper, '预览角色差异')
    expect(wrapper.text()).toContain('增加 · new-role名称')
    expect(wrapper.text()).not.toContain('移除 · old-role名称')
    await click(wrapper, '确认分配角色')
    expect(mockedWrite).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/users/target/roles',
      {
        user_version: 4,
        roles: [
          { id: 'new-role', version: 8 },
          { id: 'old-role', version: 2 },
          { id: 'third-role', version: 5 },
        ],
      },
      'PUT',
    )
    expect(wrapper.text()).toContain('用户角色已保存并重新读取')
    wrapper.unmount()
  })
  it('保存期间阻止重复命令，变更选择必须重新预览', async () => {
    const { wrapper } = await screen()
    await wrapper.get(checkbox('new-role')).setValue(true)
    await click(wrapper, '预览角色差异')
    await wrapper.get(checkbox('old-role')).setValue(false)
    expect(wrapper.find('[aria-label="角色分配差异预览"]').exists()).toBe(false)
    await click(wrapper, '预览角色差异')
    let finish: () => void = () => {}
    mockedWrite.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = () => resolve({})
        }),
    )
    const button = wrapper.findAll('button').find((value) => value.text() === '确认分配角色')!
    await button.trigger('click')
    await button.trigger('click')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    finish()
    await flushPromises()
    wrapper.unmount()
  })
  it('409 保留选择，人工重新核对后才使用新用户/角色/委派版本', async () => {
    const { wrapper } = await screen()
    await wrapper.get(checkbox('new-role')).setValue(true)
    await click(wrapper, '预览角色差异')
    baseline.version = 9
    roles[1].version = 12
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'ROLE_VERSION_CONFLICT'))
    const calls = mockedRead.mock.calls.length
    await click(wrapper, '确认分配角色')
    expect(mockedRead).toHaveBeenCalledTimes(calls)
    expect((wrapper.get(checkbox('new-role')).element as HTMLInputElement).checked).toBe(true)
    await click(wrapper, '保留草稿并重新核对')
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    await click(wrapper, '预览角色差异')
    await click(wrapper, '确认分配角色')
    expect(mockedWrite.mock.calls[1][2]).toMatchObject({
      user_version: 9,
      roles: [
        { id: 'new-role', version: 12 },
        { id: 'old-role', version: 2 },
      ],
    })
    wrapper.unmount()
  })
  it('未知结果不重放；跨页草稿重新核对前不能保存，也不静默丢弃', async () => {
    const { wrapper } = await screen()
    await click(wrapper, '下一页角色')
    await wrapper.get(checkbox('third-role')).setValue(true)
    await click(wrapper, '预览角色差异')
    mockedWrite.mockRejectedValueOnce(new IdentityError(0, 'TRANSPORT_UNAVAILABLE', '', true))
    await click(wrapper, '确认分配角色')
    await click(wrapper, '保留草稿并重新核对')
    expect(wrapper.text()).toContain('需重新查找核对')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '预览角色差异')
        ?.attributes('disabled'),
    ).toBeDefined()
    expect(mockedWrite).toHaveBeenCalledTimes(1)
    roles[2].version = 6
    await click(wrapper, '重新查找')
    expect((wrapper.get(checkbox('third-role')).element as HTMLInputElement).checked).toBe(true)
    await click(wrapper, '预览角色差异')
    await click(wrapper, '确认分配角色')
    expect(mockedWrite.mock.calls[1][2]).toMatchObject({
      roles: [
        { id: 'old-role', version: 2 },
        { id: 'third-role', version: 6 },
      ],
    })
    wrapper.unmount()
  })
  it('受保护/受限目标不能移除，停用的新角色不能新增；已分配停用角色可撤销并恢复草稿', async () => {
    baseline.roles[0].can_unassign = false
    baseline.roles[0].protected = true
    roles[1].can_assign = false
    roles[1].status = 'DISABLED'
    roles[1].restrictions = ['ROLE_NOT_ASSIGNABLE']
    let screenResult = await screen(),
      wrapper = screenResult.wrapper
    expect(wrapper.get(checkbox('old-role')).attributes('disabled')).toBeDefined()
    expect(wrapper.get(checkbox('new-role')).attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('停用角色不可新增分配')
    wrapper.unmount()
    baseline.roles[0] = { ...roles[0], status: 'DISABLED', can_assign: false }
    screenResult = await screen()
    wrapper = screenResult.wrapper
    await wrapper.get(checkbox('old-role')).setValue(false)
    expect(wrapper.get(checkbox('old-role')).attributes('disabled')).toBeUndefined()
    await wrapper.get(checkbox('old-role')).setValue(true)
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '预览角色差异')
        ?.attributes('disabled'),
    ).toBeDefined()
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('返回其他目标的角色快照时失败关闭，不继续请求选项', async () => {
    baseline.user_id = 'another-user'
    const { wrapper } = await screen()
    expect(mockedRead).toHaveBeenCalledTimes(1)
    expect(wrapper.find('table').exists()).toBe(false)
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('切换目标后迟到的旧读取不会继续查询或覆盖新目标', async () => {
    let finish: (value: UserRoles) => void = () => {}
    mockedRead.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const { wrapper, subject } = await screen()
    baseline = { user_id: 'new-target', version: 10, roles: [] }
    subject.value = { ...target, id: 'new-target' }
    await flushPromises()
    finish({ user_id: 'target', version: 4, roles: [roles[0]] })
    await flushPromises()
    expect(
      mockedRead.mock.calls.some((call) =>
        call[1].startsWith('/platform/tenants/tenant-one/identity/roles?assignable_to=target&'),
      ),
    ).toBe(false)
    expect(wrapper.text()).toContain('用户版本 10')
    expect(wrapper.text()).not.toContain('old-role名称')
    wrapper.unmount()
  })
})
