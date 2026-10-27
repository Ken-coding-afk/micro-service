<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
const context = inject(accountContext)
const owner = computed(() => context?.() ?? null)
const available = ref(false)
const message = ref('正在检查监控入口…')
const allowed = computed(
  () => owner.value?.realm === 'platform' && can(owner.value.me, 'platform:monitoring:read'),
)
watch(
  owner,
  async (_value, _old, cleanup) => {
    available.value = false
    if (!allowed.value) {
      message.value = '当前账号没有服务监控权限。'
      return
    }
    const controller = new AbortController()
    cleanup(() => controller.abort())
    try {
      const response = await fetch('/bff/identity/platform/monitoring/api/health', {
        credentials: 'same-origin',
        signal: controller.signal,
      })
      if (controller.signal.aborted) return
      available.value = response.ok
      message.value =
        response.status === 404
          ? '本环境未启用监控。'
          : '监控暂不可用，请检查采集组件；身份服务不受影响。'
    } catch {
      if (!controller.signal.aborted) message.value = '监控暂不可用，请稍后重试。'
    }
  },
  { immediate: true },
)
</script>

<template>
  <section>
    <p v-if="!available" role="status">{{ message }}</p>
    <template v-else>
      <p>指标仅供运行诊断。空面板表示暂无采集数据，不代表服务正常。</p>
      <iframe
        title="服务监控"
        src="/bff/identity/platform/monitoring/d/framework-services/services?kiosk"
        class="monitoring-frame"
        referrerpolicy="same-origin"
      />
    </template>
  </section>
</template>
