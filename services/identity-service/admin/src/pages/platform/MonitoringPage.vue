<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
const context = inject(accountContext)
const allowed = computed(() => !!context?.() && can(context()!.me, 'platform:monitoring:read'))
type Service = {
  service: string
  instance: string
  healthy: boolean
  requests_per_second: number | null
  error_ratio: number | null
  p95_seconds: number | null
}
const rows = ref<Service[]>([]),
  enabled = ref(false),
  loading = ref(false),
  error = ref(''),
  sampled = ref(''),
  dashboard = ref(false)
let controller: AbortController | null = null
async function load() {
  if (!allowed.value || loading.value) return
  controller?.abort()
  controller = new AbortController()
  const signal = controller.signal
  loading.value = true
  error.value = ''
  try {
    const availability = await fetch('/bff/identity/platform/monitoring-summary/availability', {
      signal,
      credentials: 'same-origin',
    })
    if (!availability.ok) throw new Error('无法确认监控状态，请核对登录权限或稍后重试。')
    enabled.value = (await availability.json()).data.enabled
    if (!enabled.value) {
      rows.value = []
      return
    }
    const response = await fetch('/bff/identity/platform/monitoring-summary/services', {
      signal,
      credentials: 'same-origin',
    })
    if (!response.ok) throw new Error('监控采集暂不可用，请检查采集组件。')
    const data = (await response.json()).data
    rows.value = data.items
    sampled.value = data.sampled_at
  } catch (problem) {
    if (!signal.aborted) {
      error.value = problem instanceof Error ? problem.message : '读取失败'
      rows.value = []
    }
  } finally {
    if (!signal.aborted) loading.value = false
  }
}
function number(value: number | null, multiplier = 1, suffix = '') {
  return value === null ? '暂无数据' : (value * multiplier).toFixed(2) + suffix
}
onBeforeUnmount(() => controller?.abort())
onMounted(load)
</script>
<template>
  <section class="card management-card">
    <div class="section-heading">
      <div>
        <h2>服务运行情况</h2>
        <p class="muted">最近 5 分钟的请求量、错误比例与响应时间。</p>
      </div>
      <button class="secondary-button" :disabled="loading || !allowed" @click="load">
        {{ loading ? '正在读取…' : '刷新' }}
      </button>
    </div>
    <p v-if="!allowed" class="empty">当前账号没有服务监控权限。</p>
    <p v-else-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-else-if="!enabled && !loading" class="empty">
      本环境未启用监控。启用项目的 monitoring 组件后，这里将显示真实采集指标。
    </p>
    <template v-else-if="enabled"
      ><p class="muted">
        采样时间：{{
          sampled ? new Date(sampled).toLocaleString('zh-CN') : '读取中'
        }}。无数据不代表健康。
      </p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>服务 / 实例</th>
              <th>采集状态</th>
              <th>请求 / 秒</th>
              <th>5xx 比例</th>
              <th>P95 响应</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rows" :key="item.service + item.instance">
              <td>
                {{ item.service }}<small class="muted">{{ item.instance }}</small>
              </td>
              <td>{{ item.healthy ? '在线' : '异常' }}</td>
              <td>{{ number(item.requests_per_second) }}</td>
              <td>{{ number(item.error_ratio, 100, '%') }}</td>
              <td>{{ number(item.p95_seconds, 1000, ' ms') }}</td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="5" class="empty">尚未发现采集目标，请检查服务注册与监控配置。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <button class="text-button" @click="dashboard = !dashboard">
        {{ dashboard ? '收起' : '展开' }}详细图表</button
      ><iframe
        v-if="dashboard"
        title="服务监控详细图表"
        src="/bff/identity/platform/monitoring/d/framework-services/services?kiosk"
        class="monitoring-frame"
        referrerpolicy="same-origin"
    /></template>
  </section>
</template>
