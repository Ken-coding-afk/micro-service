import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import TenantPicker from '../src/components/TenantPicker.vue'
import { read } from '../src/api/identity'
vi.mock('../src/api/identity', async (original) => ({
  ...(await original<typeof import('../src/api/identity')>()),
  read: vi.fn(),
}))
const tenant = (id: string) => ({ id, name: `租户${id}`, code: id, status: 'ACTIVE' })
beforeEach(() => vi.resetAllMocks())
it('分页搜索保留已选名称，选择仅发出目标不修改认证状态', async () => {
  vi.mocked(read).mockImplementation(async (_realm, path) =>
    path.includes('?') ? { items: [tenant('b')], page: 0, has_more: true } : tenant('a'),
  )
  const view = mount(TenantPicker, { props: { modelValue: ['a'] } })
  await flushPromises()
  await view.find('input').setValue('b')
  await view
    .findAll('button')
    .find((x) => x.text() === '查询')!
    .trigger('click')
  await flushPromises()
  expect(view.text()).toContain('租户a')
  expect(read).toHaveBeenLastCalledWith('platform', '/platform/tenants?page=0&size=20&q=b')
  await view
    .findAll('button')
    .find((x) => x.text() === '选择')!
    .trigger('click')
  expect(view.emitted('update:modelValue')).toEqual([[['a', 'b']]])
  view.unmount()
})
it('旧目标的迟到响应不覆盖新目标，读取失败可重试', async () => {
  let finish!: (value: unknown) => void
  vi.mocked(read)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValue(tenant('b'))
  const view = mount(TenantPicker, { props: { modelValue: ['a'], single: true } })
  await view.setProps({ modelValue: ['b'] })
  await flushPromises()
  finish(tenant('a'))
  await flushPromises()
  expect(view.text()).not.toContain('租户a')
  await view
    .findAll('button')
    .find((x) => x.text() === '重试读取')!
    .trigger('click')
  await flushPromises()
  expect(view.text()).toContain('租户b')
  expect(view.find('[role=alert]').exists()).toBe(false)
  view.unmount()
})
