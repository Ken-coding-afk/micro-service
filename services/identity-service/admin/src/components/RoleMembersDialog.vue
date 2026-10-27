<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { accountContext } from '../app/context'
import { can } from '../app/permissions'
import { IdentityError, read } from '../api/identity'
import type { Page } from '../api/platform'
import type { Role, TenantUser } from '../api/tenant'

const props = defineProps<{ role: Role | null }>()
const emit = defineEmits<{ closed: [] }>()
const context = inject(accountContext)
const allowed = computed(() => {
  const owner = context?.()
  return (
    owner?.realm === 'tenant' &&
    can(owner.me, 'identity:role:read') &&
    can(owner.me, 'identity:user:read')
  )
})
const dialog = ref<HTMLDialogElement | null>(null)
const rows = ref<TenantUser[]>([])
const page = ref(0),
  hasMore = ref(false),
  loading = ref(false),
  error = ref('')
let generation = 0,
  readSequence = 0
function clear() {
  rows.value = []
  page.value = 0
  hasMore.value = false
  loading.value = false
  error.value = ''
}
function close() {
  generation++
  dialog.value?.close()
  clear()
  emit('closed')
}
async function load(targetPage = 0) {
  const role = props.role
  if (!role || !allowed.value) return
  const epoch = generation,
    sequence = ++readSequence
  loading.value = true
  error.value = ''
  try {
    const value = await read<Page<TenantUser>>(
      'tenant',
      `/roles/${encodeURIComponent(role.id)}/members?page=${targetPage}&size=20`,
    )
    if (epoch !== generation || sequence !== readSequence) return
    rows.value = value.items
    page.value = value.page
    hasMore.value = value.has_more
  } catch (problem) {
    if (epoch !== generation || sequence !== readSequence) return
    error.value = problem instanceof IdentityError ? problem.message : '暂时无法读取成员。'
    context?.()?.failed(problem)
  } finally {
    if (epoch === generation && sequence === readSequence) loading.value = false
  }
}
watch(
  () => props.role,
  async (role) => {
    generation++
    dialog.value?.close()
    clear()
    if (!role) return
    const epoch = generation
    await nextTick()
    if (epoch !== generation) return
    dialog.value?.showModal()
    await load(0)
  },
  { immediate: true },
)
watch(allowed, (value) => {
  if (!value) close()
})
onBeforeUnmount(() => {
  generation++
  clear()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="management-dialog role-members-dialog"
    aria-labelledby="role-members-title"
    @cancel.prevent="close"
  >
    <h2 id="role-members-title">角色成员</h2>
    <p v-if="role" class="muted">{{ role.name }} · {{ role.code }}</p>
    <p class="muted">仅展示当前管理范围内的账号摘要；列表为空不代表其他管理范围内没有成员。</p>
    <div v-if="!allowed" class="message error" role="alert">需要角色与用户读取权限。</div>
    <div v-if="error" class="message error" role="alert">{{ error }}</div>
    <div v-if="loading" class="loading" role="status">正在读取当前成员…</div>
    <div v-if="allowed" class="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">账号</th>
            <th scope="col">显示名</th>
            <th scope="col">状态</th>
            <th scope="col">凭证状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in rows" :key="user.id">
            <td>{{ user.username }}</td>
            <td>{{ user.display_name }}</td>
            <td>
              {{
                user.status === 'ACTIVE' ? '启用' : user.status === 'DISABLED' ? '停用' : '待激活'
              }}
            </td>
            <td>{{ user.must_change_password ? '待首次改密' : '已设置' }}</td>
          </tr>
          <tr v-if="!rows.length && !loading">
            <td colspan="4" class="empty">当前管理范围内没有可显示的成员。</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="allowed" class="pagination">
      <span>第 {{ page + 1 }} 页</span
      ><button class="secondary-button" :disabled="loading || page === 0" @click="load(page - 1)">
        上一页成员</button
      ><button
        class="secondary-button"
        :disabled="loading || !hasMore || page >= 10000"
        @click="load(page + 1)"
      >
        下一页成员
      </button>
    </div>
    <div class="form-actions">
      <button class="secondary-button" @click="close">关闭成员列表</button>
    </div>
  </dialog>
</template>
