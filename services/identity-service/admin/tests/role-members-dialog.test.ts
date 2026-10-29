import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import RoleMembersDialog from '../src/components/RoleMembersDialog.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'
import type { Role } from '../src/api/tenant'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
let owner: AccountContext, role: Role
const methods = ['showModal', 'close'] as const,
  originals = methods.map((name) =>
    Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name),
  )
beforeEach(() => {
  vi.clearAllMocks()
  role = {
    id: 'reader-role',
    code: 'reader',
    name: '阅读者',
    status: 'ACTIVE',
    protected: false,
    version: 0,
    authorization_version: 0,
    can_assign: true,
    can_edit: true,
    can_unassign: true,
    restrictions: [],
  }
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
          scopes: ['identity.users', 'identity.roles'],
          permissions: ['platform:identity:read', 'platform:identity:read'],
          data_scopes: {},
        },
      },
    },
    changed: vi.fn(async () => {}),
    signedOut: vi.fn(),
    failed: vi.fn(),
  }
  vi.mocked(read).mockImplementation(async (_realm, path) => {
    const page = Number(new URLSearchParams(path.split('?')[1]).get('page'))
    return {
      page,
      size: 20,
      has_more: page === 0,
      items: [
        {
          id: 'member-' + page,
          username: 'learner-' + page,
          display_name: '学员' + page,
          status: 'ACTIVE',
          must_change_password: false,
          version: 0,
        },
      ],
    }
  })
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
  const subject = ref<Role | null>(role)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { render: () => h(RoleMembersDialog, { role: subject.value }) } },
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
describe('只读角色成员', () => {
  it('缺少平台身份读取权时不请求或展示成员', async () => {
    owner.me.audiences['identity-service'].permissions = ['identity:role:read']
    const { wrapper } = await screen()
    expect(read).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('需要角色与用户读取权限')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(write).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('只通过确定角色分页读取摘要，失败不跳页或发出写入', async () => {
    const { wrapper } = await screen()
    expect(read).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/roles/reader-role/members?page=0&size=20',
    )
    expect(wrapper.text()).toContain('learner-0')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '下一页成员')!
      .trigger('click')
    await flushPromises()
    expect(read).toHaveBeenLastCalledWith(
      'platform',
      '/platform/tenants/tenant-one/identity/roles/reader-role/members?page=1&size=20',
    )
    expect(wrapper.text()).toContain('learner-1')
    vi.mocked(read).mockRejectedValueOnce(new IdentityError(503, 'DEPENDENCY_UNAVAILABLE'))
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '上一页成员')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('第 2 页')
    expect(wrapper.text()).toContain('服务暂时不可用')
    expect(write).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('角色切换清空原数据，迟到响应不覆盖新的目标', async () => {
    let finish: (value: unknown) => void = () => {}
    vi.mocked(read).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const { wrapper, subject } = await screen()
    subject.value = { ...role, id: 'other-role', name: '另一个角色' }
    await flushPromises()
    finish({
      page: 0,
      size: 20,
      has_more: false,
      items: [
        {
          id: 'old',
          username: 'old-member',
          display_name: '旧目标',
          status: 'ACTIVE',
          must_change_password: false,
          version: 0,
        },
      ],
    })
    await flushPromises()
    expect(wrapper.text()).toContain('另一个角色')
    expect(wrapper.text()).not.toContain('old-member')
    expect(write).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
