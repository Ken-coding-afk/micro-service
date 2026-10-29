<script setup lang="ts">
import { inject, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { accountContext } from '../app/context'
import { canManage, useManagementApi } from '../app/management'
import type { TenantUser } from '../api/tenant'
import { IdentityError } from '../api/identity'
const props = defineProps<{ user: TenantUser | null }>()
const emit = defineEmits<{ closed: [] }>()
const context = inject(accountContext)
const { read, write } = useManagementApi()
const dialog = ref<HTMLDialogElement | null>(null),
  pending = ref(false),
  error = ref(''),
  token = ref(''),
  expires = ref(''),
  revealed = ref(false),
  uncertain = ref(false)
let generation = 0,
  requestId = ''
watch(
  () => props.user,
  async (user) => {
    generation++
    token.value = ''
    expires.value = ''
    error.value = ''
    revealed.value = false
    uncertain.value = false
    if (user) {
      requestId = crypto.randomUUID()
      await nextTick()
      if (props.user) dialog.value?.showModal()
    } else dialog.value?.close()
  },
  { immediate: true },
)
async function issue() {
  if (
    !props.user ||
    pending.value ||
    token.value ||
    uncertain.value ||
    !canManage(context?.(), 'identity:user:manage')
  )
    return
  const turn = generation,
    user = props.user
  pending.value = true
  error.value = ''
  try {
    const current = await read<TenantUser>('tenant', `/users/${encodeURIComponent(user.id)}`)
    if (turn !== generation) return
    const result = await write<{ reset_token: string; expires_at: string }>(
      'tenant',
      `/users/${encodeURIComponent(user.id)}/password-reset`,
      { version: current.version },
      'POST',
      requestId,
    )
    if (turn !== generation) return
    token.value = result.reset_token
    expires.value = result.expires_at
  } catch (problem) {
    if (turn !== generation) return
    error.value = problem instanceof IdentityError ? problem.message : '无法签发凭证。'
    uncertain.value =
      problem instanceof IdentityError &&
      (problem.uncertain || problem.code === 'PASSWORD_RESET_ALREADY_ISSUED')
    context?.()?.failed(problem)
  } finally {
    if (turn === generation) pending.value = false
  }
}
function mayLeave() {
  return (
    !pending.value &&
    (!token.value || window.confirm('关闭后无法再次查看重置凭证，确认已妥善交付？'))
  )
}
function close() {
  if (!mayLeave()) return
  token.value = ''
  dialog.value?.close()
  emit('closed')
}
onBeforeRouteLeave(mayLeave)
onBeforeRouteUpdate(mayLeave)
onBeforeUnmount(() => {
  generation++
  token.value = ''
})
</script>
<template>
  <dialog
    ref="dialog"
    class="management-dialog"
    aria-labelledby="password-reset-title"
    @cancel.prevent="close"
  >
    <h2 id="password-reset-title">重置用户密码</h2>
    <p v-if="user">{{ user.display_name }} · {{ user.username }}</p>
    <p class="muted">
      签发后由你将凭证受控交付给本人。有效期 15
      分钟，只能使用一次；用户完成重置后，旧登录与应用授权将被撤销。
    </p>
    <div v-if="error" class="message error" role="alert">{{ error }}</div>
    <p v-if="uncertain" class="credential-warning">
      签发结果尚未确认。请先核对交付情况；关闭后重新发起会使先前未使用的凭证失效。
    </p>
    <template v-if="token">
      <div class="field">
        <label for="reset-credential">一次性重置凭证</label
        ><input
          id="reset-credential"
          :type="revealed ? 'text' : 'password'"
          :value="token"
          readonly
          autocomplete="off"
        />
      </div>
      <button type="button" class="text-button" @click="revealed = !revealed">
        {{ revealed ? '隐藏凭证' : '显示凭证' }}
      </button>
      <p>有效期至 {{ new Date(expires).toLocaleString('zh-CN') }}</p>
      <p>
        请用户打开
        <a class="text-button" href="/identity/password/reset" target="_blank" rel="noopener"
          >密码重置页</a
        >，输入凭证并设置新密码。
      </p>
    </template>
    <div class="form-actions">
      <button v-if="!token" class="primary-button" :disabled="pending || uncertain" @click="issue">
        {{ pending ? '正在签发…' : '签发重置凭证' }}</button
      ><button class="secondary-button" :disabled="pending" @click="close">
        {{ token ? '已交付，关闭' : '取消' }}
      </button>
    </div>
  </dialog>
</template>
