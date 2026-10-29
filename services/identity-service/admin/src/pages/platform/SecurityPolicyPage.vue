<script setup lang="ts">
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
import { IdentityError, read, write } from '../../api/identity'
import type { SecurityPolicy } from '../../api/clients'
const context = inject(accountContext)
const owner = computed(() => context?.() ?? null)
const readable = computed(
  () => owner.value?.realm === 'platform' && can(owner.value.me, 'platform:security:read'),
)
const manageable = computed(
  () => readable.value && owner.value && can(owner.value.me, 'platform:security:manage'),
)
const fields = [
  ['password_min_chars', '密码最少字符数'],
  ['login_failure_threshold', '账号登录失败阈值'],
  ['login_failure_window_seconds', '账号失败统计窗口（秒）'],
  ['login_lock_seconds', '账号锁定时长（秒）'],
  ['login_ip_attempt_limit', 'IP 尝试次数上限'],
  ['login_ip_window_seconds', 'IP 统计窗口（秒）'],
  ['session_absolute_seconds', '会话最长有效期（秒）'],
  ['session_idle_seconds', '会话闲置有效期（秒）'],
  ['recent_auth_seconds', '近期认证有效期（秒）'],
] as const
const groups = [
  {
    title: '密码要求',
    description: '所有新设密码和密码重置均使用此规则。',
    fields: fields.slice(0, 1),
  },
  { title: '登录保护', description: '按账号和来源 IP 限制失败尝试。', fields: fields.slice(1, 6) },
  {
    title: '会话与敏感操作',
    description: '调整统一的会话期限与近期认证窗口。',
    fields: fields.slice(6),
  },
]
const state = ref<SecurityPolicy | null>(null)
const values = reactive<Record<string, number>>({})
const pending = ref(false),
  blocked = ref(false),
  confirmed = ref(false),
  message = ref('')
let epoch = 0
function fail(error: unknown) {
  message.value = error instanceof Error ? error.message : '读取或保存失败，请重新核对。'
  if (error instanceof IdentityError && error.status === 401) owner.value?.failed(error)
}
async function reload() {
  if (!readable.value || pending.value) return
  const turn = ++epoch
  pending.value = true
  message.value = ''
  try {
    const result = await read<SecurityPolicy>('platform', '/platform/security-policy')
    if (turn !== epoch) return
    state.value = result
    Object.assign(values, result.values)
    blocked.value = false
    confirmed.value = false
  } catch (error) {
    if (turn === epoch) fail(error)
  } finally {
    if (turn === epoch) pending.value = false
  }
}
async function save() {
  if (!manageable.value || pending.value || blocked.value || !confirmed.value || !state.value)
    return
  const turn = epoch
  pending.value = true
  message.value = ''
  try {
    const result = await write<SecurityPolicy>(
      'platform',
      '/platform/security-policy',
      { version: state.value.version, values: { ...values } },
      'PUT',
    )
    if (turn !== epoch) return
    state.value = result
    Object.assign(values, result.values)
    confirmed.value = false
    message.value = '项目统一安全策略已保存。'
  } catch (error) {
    if (turn !== epoch) return
    fail(error)
    if (error instanceof IdentityError && (error.status === 409 || error.uncertain))
      blocked.value = true
  } finally {
    if (turn === epoch) pending.value = false
  }
}
watch(
  () => `${owner.value?.realm}:${owner.value?.me.sub}:${readable.value}`,
  () => {
    epoch++
    state.value = null
    pending.value = false
    message.value = ''
    confirmed.value = false
    for (const key of Object.keys(values)) delete values[key]
    void reload()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  epoch++
})
</script>
<template>
  <section class="panel">
    <p class="muted">以下设置作用于整个项目。</p>
    <p v-if="!readable" role="alert">仅有安全策略读取权限的平台管理员可访问。</p>
    <template v-else
      ><p v-if="message" role="status">{{ message }}</p>
      <button :disabled="pending" @click="reload">重新读取并替换草稿</button>
      <form v-if="state" @submit.prevent="save">
        <p>版本 {{ state.version }}</p>
        <fieldset :disabled="pending || blocked || !manageable">
          <section v-for="group in groups" :key="group.title" class="policy-group">
            <h2>{{ group.title }}</h2>
            <p class="muted">{{ group.description }}</p>
            <label v-for="[key, label] in group.fields" :key="key"
              >{{ label }}
              <input v-model.number="values[key]" type="number" min="1" step="1" required
            /></label>
          </section>
          <label
            ><input v-model="confirmed" type="checkbox" />已核对密码、登录限制及现有会话影响</label
          ><button :disabled="!confirmed">保存策略</button>
        </fieldset>
        <p v-if="blocked">版本冲突或结果未知，草稿已保留。请先核对当前策略，再决定修改。</p>
      </form>
    </template>
  </section>
</template>

<style scoped>
form {
  margin-top: 24px;
}
fieldset {
  display: grid;
  gap: 20px;
  grid-template-columns: 1fr;
  border: 0;
  padding: 0;
}
label {
  display: grid;
  gap: 8px;
  color: #42516a;
  font-size: 13px;
}
label:has(input[type='checkbox']) {
  display: flex;
  align-items: center;
  grid-column: 1 / -1;
}
input[type='number'] {
  box-sizing: border-box;
  width: 100%;
  padding: 6px 10px;
  border: 1px solid #d5deea;
  border-radius: 4px;
}
button {
  width: fit-content;
  padding: 9px 16px;
  border: 1px solid #d5deea;
  border-radius: 4px;
  background: #fff;
  color: #254b84;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.policy-group {
  display: grid;
  grid-template-columns: minmax(240px, 420px);
  gap: 12px;
  border-bottom: 1px solid #e1e7ef;
  padding-bottom: 24px;
}
.policy-group label {
  display: grid;
  grid-template-columns: 1fr 120px;
  align-items: center;
}
</style>
