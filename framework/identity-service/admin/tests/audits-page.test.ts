import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { accountContext, type AccountContext } from '../src/app/context'
import AuditsPage from '../src/pages/audit/AuditsPage.vue'
import { IdentityError, read, write } from '../src/api/identity'

vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
  write: vi.fn(),
}))
const mockedRead = vi.mocked(read),
  mockedWrite = vi.mocked(write)
let owner: AccountContext
const entry = {
  id: 'entry-one',
  actor_id: 'a20abb78-b557-40fe-98ae-7084576ed841',
  actor_type: 'TENANT',
  tenant_id: 'tenant',
  action: 'identity.role.updated',
  target_id: 'role-one',
  result: 'SUCCESS',
  request_id: 'request-one',
  details: {
    before: { name: '旧名称' },
    after: { name: '新名称', client_secret: '[REDACTED]' },
    note: '<img src=x onerror="throw 1">',
  },
  details_truncated: false,
  created_at: '2026-10-27T00:00:00Z',
}
const originals = ['showModal', 'close'].map((name) =>
  Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name),
)
beforeEach(() => {
  vi.clearAllMocks()
  owner = reactive({
    realm: 'tenant',
    me: {
      view: 'APPLICATION_EFFECTIVE',
      sub: 'manager',
      principal_type: 'user',
      tenant_id: 'tenant',
      client_id: 'identity-admin-tenant',
      profile: {},
      audiences: {
        'identity-service': {
          permissions: ['identity:audit:read'],
          scopes: ['identity.audit'],
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
    items: [structuredClone(entry)],
  }))
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
  ;['showModal', 'close'].forEach((name, index) => {
    const old = originals[index]
    if (old) Object.defineProperty(HTMLDialogElement.prototype, name, old)
    else delete HTMLDialogElement.prototype[name as 'close' | 'showModal']
  })
  document.body.innerHTML = ''
})
async function render() {
  const wrapper = mount(AuditsPage, {
    attachTo: document.body,
    global: { provide: { [accountContext as symbol]: () => owner } },
  })
  await flushPromises()
  return wrapper
}
type Wrapper = Awaited<ReturnType<typeof render>>
function button(wrapper: Wrapper, name: string) {
  const value = wrapper.findAll('button').find((button) => button.text() === name)
  if (!value) throw new Error('Missing button: ' + name)
  return value
}
async function search(wrapper: Wrapper) {
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}
describe('只读管理审计', () => {
  it('租户与平台分别使用固定命名空间和API，页面没有写入动作', async () => {
    let wrapper = await render()
    expect(mockedRead).toHaveBeenCalledWith('tenant', '/audits?page=0&size=20')
    expect(wrapper.text()).toContain('当前组织')
    wrapper.unmount()
    mockedRead.mockClear()
    owner.realm = 'platform'
    owner.me.principal_type = 'platform'
    owner.me.tenant_id = null
    owner.me.audiences['identity-service']!.permissions = ['platform:audit:read']
    wrapper = await render()
    expect(mockedRead).toHaveBeenCalledWith('platform', '/platform/audits?page=0&size=20')
    expect(wrapper.text()).toContain('仅显示平台控制操作')
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('权限与物理身份不匹配时不查询，不能用租户权限进入平台', async () => {
    owner.realm = 'platform'
    owner.me.principal_type = 'platform'
    const wrapper = await render()
    expect(mockedRead).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('无权读取')
    wrapper.unmount()
  })
  it('时间、操作者、动作、目标与结果精确编码查询，只提交允许的筛选字段', async () => {
    const wrapper = await render(),
      fields = wrapper.findAll('input')
    await fields[0]!.setValue('2026-10-27T09:00:00')
    await fields[1]!.setValue('2026-10-27T10:00:00')
    await fields[2]!.setValue(entry.actor_id)
    await fields[3]!.setValue('identity.role.updated')
    await fields[4]!.setValue('role/one:version-2')
    await wrapper.get('select').setValue('FAILURE')
    await search(wrapper)
    const url = new URL('http://local' + mockedRead.mock.calls.at(-1)![1])
    expect(Object.fromEntries(url.searchParams)).toEqual({
      page: '0',
      size: '20',
      from: new Date('2026-10-27T09:00:00').toISOString(),
      to: new Date('2026-10-27T10:00:00').toISOString(),
      actor_id: entry.actor_id,
      action: 'identity.role.updated',
      target_id: 'role/one:version-2',
      result: 'FAILURE',
    })
    wrapper.unmount()
  })
  it('翻页继续使用已应用筛选，草稿变化不会在翻页时偷偷应用；清除恢复第一页', async () => {
    mockedRead.mockImplementation(async (_realm, path) => ({
      page: Number(new URL('http://local' + path).searchParams.get('page')),
      size: 20,
      has_more: true,
      items: [structuredClone(entry)],
    }))
    const wrapper = await render()
    await wrapper.findAll('input')[3]!.setValue('identity.role.updated')
    await search(wrapper)
    await wrapper.findAll('input')[3]!.setValue('identity.user.created')
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(mockedRead.mock.calls.at(-1)![1]).toBe(
      '/audits?page=1&size=20&action=identity.role.updated',
    )
    await button(wrapper, '清除筛选').trigger('click')
    await flushPromises()
    expect(mockedRead.mock.calls.at(-1)![1]).toBe('/audits?page=0&size=20')
    wrapper.unmount()
  })
  it.each(['time', 'actor', 'action', 'target'])('非法筛选不发送新请求：%s', async (field) => {
    const wrapper = await render(),
      fields = wrapper.findAll('input')
    if (field === 'time') {
      await fields[0]!.setValue('2026-10-27T10:00:00')
      await fields[1]!.setValue('2026-10-27T09:00:00')
    }
    if (field === 'actor') await fields[2]!.setValue('not-a-uuid')
    if (field === 'action') await fields[3]!.setValue('identity.role.updated OR true')
    if (field === 'target') await fields[4]!.setValue('role?tenant_id=other')
    await search(wrapper)
    expect(mockedRead).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('请核对时间区间')
    wrapper.unmount()
  })
  it('存储错误保持错误状态，不伪装为空列表成功；401交给账号上下文处理', async () => {
    mockedRead.mockRejectedValueOnce(new IdentityError(503, 'STORAGE_UNAVAILABLE'))
    const wrapper = await render()
    expect(wrapper.text()).not.toContain('没有符合条件')
    expect(wrapper.get('[role="alert"]').text()).toContain('服务暂时不可用')
    mockedRead.mockRejectedValueOnce(new IdentityError(401, 'INVALID_TOKEN'))
    await search(wrapper)
    expect(owner.failed).toHaveBeenCalledTimes(2)
    expect(mockedWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('详情按普通文本展示脱敏差异，截断有明确提示，HTML不执行', async () => {
    mockedRead.mockResolvedValueOnce({
      page: 0,
      size: 20,
      has_more: false,
      items: [{ ...structuredClone(entry), details_truncated: true }],
    })
    const wrapper = await render()
    await button(wrapper, '查看明细').trigger('click')
    await flushPromises()
    expect(wrapper.get('dialog').attributes('open')).toBeDefined()
    expect(wrapper.get('pre').text()).toContain('[REDACTED]')
    expect(wrapper.get('pre').text()).toContain('<img src=x')
    expect(wrapper.get('pre').find('img').exists()).toBe(false)
    expect(wrapper.get('dialog').text()).toContain('不代表原始记录的全部字段')
    await button(wrapper, '关闭明细').trigger('click')
    expect(wrapper.get('dialog').attributes('open')).toBeUndefined()
    wrapper.unmount()
  })
  it('身份切换后忽略旧请求的迟到响应并清空原筛选与详情', async () => {
    let finish!: (value: object) => void
    mockedRead.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const wrapper = await render()
    mockedRead.mockResolvedValueOnce({
      page: 0,
      size: 20,
      has_more: false,
      items: [
        {
          ...structuredClone(entry),
          action: 'platform.only',
          actor_type: 'PLATFORM',
          tenant_id: null,
        },
      ],
    })
    owner.realm = 'platform'
    owner.me.principal_type = 'platform'
    owner.me.tenant_id = null
    owner.me.audiences['identity-service']!.permissions = ['platform:audit:read']
    await flushPromises()
    finish({ page: 0, size: 20, has_more: false, items: [structuredClone(entry)] })
    await flushPromises()
    expect(wrapper.text()).toContain('platform.only')
    expect(wrapper.text()).not.toContain('identity.role.updated')
    wrapper.unmount()
  })
})
