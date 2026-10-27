import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import PlatformAccountsPage from '../src/pages/platform/PlatformAccountsPage.vue'
import { accountContext, type AccountContext } from '../src/app/context'
import { IdentityError, read, write } from '../src/api/identity'
import type { PlatformAdministrator, PlatformTenant } from '../src/api/platform'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const mockedRead = vi.mocked(read)
const mockedWrite = vi.mocked(write)
let tenant: PlatformTenant
let administrator: PlatformAdministrator
let owner: AccountContext
const dialogMethods = ['showModal', 'close'] as const
const originalDialogMethods = dialogMethods.map((name) =>
  Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name),
)

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  tenant = {
    id: 'tenant-one',
    code: 'sample-tenant',
    name: '原租户名称',
    status: 'ACTIVE',
    expires_at: '2028-01-01T00:00:59.123456Z',
    version: 0,
  }
  administrator = {
    id: 'admin-one',
    username: 'owner',
    display_name: '平台管理员',
    status: 'ACTIVE',
    must_change_password: false,
    version: 0,
  }
  owner = {
    realm: 'platform',
    me: {
      view: 'APPLICATION_EFFECTIVE',
      sub: 'admin-one',
      principal_type: 'platform',
      tenant_id: null,
      client_id: 'identity-admin-platform',
      profile: {},
      audiences: {
        'identity-service': {
          permissions: [
            'platform:tenant:read',
            'platform:tenant:manage',
            'platform:admin:read',
            'platform:admin:manage',
          ],
          scopes: ['profile', 'platform.tenants', 'platform.admins'],
          data_scopes: {},
        },
      },
    },
    changed: vi.fn(async () => {}),
    signedOut: vi.fn(),
    failed: vi.fn(),
  }
  mockedRead.mockImplementation(async (_realm, path) =>
    path.includes('?')
      ? {
          page: 0,
          size: 20,
          has_more: false,
          items: [path.startsWith('/platform/tenants') ? { ...tenant } : { ...administrator }],
        }
      : path.startsWith('/platform/tenants')
        ? { ...tenant }
        : { ...administrator },
  )
  mockedWrite.mockResolvedValue({})
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  // jsdom 不实现原生模态窗口；这里只适配 open 状态，真实焦点和背景隔离另由浏览器验证。
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
    const original = originalDialogMethods[index]
    if (original) Object.defineProperty(HTMLDialogElement.prototype, name, original)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
  })
  document.body.innerHTML = ''
})

async function screen(kind: 'tenants' | 'admins' = 'tenants') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: PlatformAccountsPage, props: { kind } }],
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

async function newTenant(wrapper: Awaited<ReturnType<typeof screen>>) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '新建租户')
    ?.trigger('click')
  await wrapper.get('#tenant-code-create').setValue('created-tenant')
  await wrapper.get('#tenant-name').setValue('新租户')
  await wrapper.get('#initial-admin-name').setValue('NewAdmin')
  await wrapper.get('#initial-admin-display').setValue('租户管理员')
}

describe('平台账号与租户管理', () => {
  it('无读取权不请求数据，只有读取权时不提供写入操作', async () => {
    owner.realm = 'tenant'
    let wrapper = await screen()
    expect(mockedRead).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前账号无权访问此管理页面')
    wrapper.unmount()
    owner.realm = 'platform'
    owner.me.audiences['identity-service'].permissions = ['platform:tenant:read']
    wrapper = await screen()
    expect(wrapper.findAll('button').some((button) => button.text() === '新建租户')).toBe(false)
    expect(wrapper.findAll('tbody button')).toHaveLength(0)
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('创建防重复并一次性交付初始密码，关闭后不保留凭据', async () => {
    const wrapper = await screen()
    await newTenant(wrapper)
    let finish: (value: unknown) => void = () => {}
    mockedWrite.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await wrapper.get('dialog form').trigger('submit')
    await wrapper.get('dialog form').trigger('submit')
    expect(mockedWrite).toHaveBeenCalledOnce()
    const call = mockedWrite.mock.calls[0]
    expect(call.slice(0, 4)).toEqual([
      'platform',
      '/platform/tenants',
      {
        code: 'created-tenant',
        name: '新租户',
        expires_at: null,
        admin_username: 'newadmin',
        admin_display_name: '租户管理员',
      },
      'POST',
    ])
    expect(call[4]).toMatch(/^[0-9a-f-]{36}$/)
    finish({
      tenant_id: 'created',
      admin_id: 'new-admin',
      initial_password: 'example-initial-only',
      replayed: false,
    })
    await flushPromises()
    expect(wrapper.get('#issued-password').attributes('type')).toBe('password')
    expect((wrapper.get('#issued-password').element as HTMLInputElement).value).toBe(
      'example-initial-only',
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '已记录，关闭')
      ?.trigger('click')
    expect(wrapper.find('#issued-password').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('example-initial-only')
    expect(sessionStorage.length).toBe(0)
    wrapper.unmount()
  })

  it('未知创建结果不自动重放，人工核对沿用同一编号且不重新展示旧密码', async () => {
    const wrapper = await screen()
    await newTenant(wrapper)
    mockedWrite.mockRejectedValueOnce(new IdentityError(503, 'UNKNOWN_RESULT', '', true))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('本次请求结果尚未确认')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    const id = mockedWrite.mock.calls[0][4]
    mockedWrite.mockResolvedValueOnce({
      tenant_id: 'existing',
      admin_id: 'existing-admin',
      initial_password: null,
      replayed: true,
    })
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledTimes(2)
    expect(mockedWrite.mock.calls[1][4]).toBe(id)
    expect(wrapper.find('#issued-password').exists()).toBe(false)
    expect(wrapper.text()).toContain('本次为重复请求')
    wrapper.unmount()
  })

  it('只改名称时保留到期时间的完整精度', async () => {
    const wrapper = await screen()
    await wrapper.get('tbody button').trigger('click')
    await flushPromises()
    await wrapper.get('#tenant-name').setValue('新的租户名称')
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite).toHaveBeenCalledWith(
      'platform',
      '/platform/tenants/tenant-one',
      {
        version: 0,
        name: '新的租户名称',
        expires_at: '2028-01-01T00:00:59.123456Z',
        status: 'ACTIVE',
      },
      'PUT',
    )
    wrapper.unmount()
  })

  it('版本冲突保留草稿，核对后才使用当前版本重新提交', async () => {
    const wrapper = await screen()
    await wrapper.get('tbody button').trigger('click')
    await flushPromises()
    await wrapper.get('#tenant-name').setValue('我的草稿')
    tenant = { ...tenant, name: '服务器新名称', version: 1 }
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'TENANT_VERSION_CONFLICT'))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect((wrapper.get('#tenant-name').element as HTMLInputElement).value).toBe('我的草稿')
    expect(wrapper.text()).toContain('服务器新名称')
    expect(mockedWrite).toHaveBeenCalledOnce()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保留我的修改')
      ?.trigger('click')
    expect(mockedWrite).toHaveBeenCalledOnce()
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(mockedWrite.mock.calls[1][2]).toMatchObject({ version: 1, name: '我的草稿' })
    wrapper.unmount()
  })

  it('最后管理员拒绝有明确提示，失败不关闭窗口或显示成功', async () => {
    const wrapper = await screen('admins')
    await wrapper.get('tbody button').trigger('click')
    await flushPromises()
    await wrapper.get('#platform-status').setValue('DISABLED')
    mockedWrite.mockRejectedValueOnce(new IdentityError(409, 'LAST_PLATFORM_ADMIN'))
    await wrapper.get('dialog form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('必须保留至少一个有效平台管理员')
    expect(wrapper.get('dialog').attributes('open')).toBeDefined()
    expect(wrapper.findAll('[role="status"]').some((item) => item.text().includes('已更新'))).toBe(
      false,
    )
    wrapper.unmount()
  })
})
