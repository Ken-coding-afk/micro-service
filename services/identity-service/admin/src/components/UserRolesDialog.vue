<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { accountContext } from '../app/context'
import { can } from '../app/permissions'
import { IdentityError, read, write } from '../api/identity'
import {
  managementRestriction,
  type Role,
  type RolePage,
  type TenantUser,
  type UserRoles,
} from '../api/tenant'
const props = defineProps<{ user: TenantUser | null }>()
const emit = defineEmits<{ closed: []; changed: [] }>()
const context = inject(accountContext)
const owner = computed(() => context?.() ?? null)
const allowed = computed(
  () =>
    owner.value?.realm === 'tenant' &&
    can(owner.value.me, 'identity:role:assign') &&
    can(owner.value.me, 'identity:user:read'),
)
const dialog = ref<HTMLDialogElement | null>(null)
const baseline = ref<UserRoles | null>(null)
const wanted = ref<Set<string>>(new Set())
const snapshots = ref<Record<string, Role>>({})
const verified = ref<Set<string>>(new Set())
const available = ref<Role[]>([])
const page = ref(0),
  hasMore = ref(false),
  query = ref('')
const loading = ref(false),
  pending = ref(false),
  stale = ref(false),
  reviewed = ref(false)
const error = ref(''),
  success = ref('')
let generation = 0
const original = computed(() => new Set(baseline.value?.roles.map((role) => role.id) ?? []))
const changes = computed(() =>
  [...new Set([...original.value, ...wanted.value])]
    .sort()
    .filter((id) => original.value.has(id) !== wanted.value.has(id))
    .map((id) => ({ id, role: snapshots.value[id], added: wanted.value.has(id) })),
)
const visible = computed(() => [
  ...(baseline.value?.roles ?? []).map((role) => snapshots.value[role.id] ?? role),
  ...available.value.filter((role) => !original.value.has(role.id)),
])
const offPage = computed(() =>
  [...wanted.value].filter((id) => !visible.value.some((role) => role.id === id)),
)
const invalid = computed(() => {
  const ids = new Set<string>()
  for (const id of wanted.value)
    if (!verified.value.has(id) || (!original.value.has(id) && !snapshots.value[id]?.can_assign))
      ids.add(id)
  for (const role of baseline.value?.roles ?? [])
    if (!wanted.value.has(role.id) && !snapshots.value[role.id]?.can_unassign) ids.add(role.id)
  return [...ids]
})
function resetReview() {
  reviewed.value = false
  success.value = ''
}
function report(problem: unknown) {
  error.value = problem instanceof IdentityError ? problem.message : '暂时无法核对角色。'
  owner.value?.failed(problem)
}
function remember(roles: Role[]) {
  for (const role of roles) {
    snapshots.value[role.id] = role
    verified.value.add(role.id)
  }
}
function clear() {
  baseline.value = null
  wanted.value = new Set()
  snapshots.value = {}
  verified.value = new Set()
  available.value = []
  page.value = 0
  query.value = ''
  hasMore.value = false
  loading.value = false
  pending.value = false
  stale.value = false
  reviewed.value = false
  error.value = ''
  success.value = ''
}
function discardAllowed() {
  return !changes.value.length || window.confirm('角色分配草稿尚未保存，确认放弃修改？')
}
function close() {
  if (pending.value || !discardAllowed()) return
  generation++
  dialog.value?.close()
  clear()
  emit('closed')
}
async function reload(keep: boolean) {
  const target = props.user
  if (!target || !allowed.value || loading.value || pending.value) return
  const epoch = generation
  loading.value = true
  error.value = ''
  resetReview()
  try {
    const current = await read<UserRoles>('tenant', `/users/${encodeURIComponent(target.id)}/roles`)
    if (epoch !== generation) return
    if (current.user_id !== target.id) throw new IdentityError(503, 'INVALID_RESPONSE')
    const options = await read<RolePage>(
      'tenant',
      `/roles?assignable_to=${encodeURIComponent(target.id)}&page=0&size=20&q=${encodeURIComponent(query.value.trim())}`,
    )
    if (epoch !== generation) return
    baseline.value = current
    verified.value = new Set()
    remember(current.roles)
    remember(options.items)
    available.value = options.items
    page.value = options.page
    hasMore.value = options.has_more
    if (!keep) wanted.value = new Set(current.roles.map((role) => role.id))
    stale.value = false
  } catch (problem) {
    if (epoch === generation) {
      stale.value = true
      report(problem)
    }
  } finally {
    if (epoch === generation) loading.value = false
  }
}
async function loadOptions(targetPage = 0) {
  if (!props.user || !allowed.value || loading.value || pending.value || stale.value) return
  const epoch = generation
  loading.value = true
  error.value = ''
  resetReview()
  try {
    const value = await read<RolePage>(
      'tenant',
      `/roles?assignable_to=${encodeURIComponent(props.user.id)}&page=${targetPage}&size=20&q=${encodeURIComponent(query.value.trim())}`,
    )
    if (epoch !== generation) return
    remember(value.items)
    available.value = value.items
    page.value = value.page
    hasMore.value = value.has_more
  } catch (problem) {
    if (epoch === generation) report(problem)
  } finally {
    if (epoch === generation) loading.value = false
  }
}
function toggle(role: Role, selected: boolean) {
  if (!allowed.value || pending.value || loading.value || stale.value) return
  if (selected ? !role.can_assign && !original.value.has(role.id) : !role.can_unassign) return
  if (selected) {
    if (wanted.value.size >= 1000 && !wanted.value.has(role.id)) return
    wanted.value.add(role.id)
    snapshots.value[role.id] = role
    verified.value.add(role.id)
  } else wanted.value.delete(role.id)
  resetReview()
}
function removeDraft(id: string) {
  if (pending.value || loading.value || stale.value || original.value.has(id)) return
  wanted.value.delete(id)
  resetReview()
}
async function find(role: Role | undefined) {
  if (!role) return
  query.value = role.code.slice(0, 100)
  await loadOptions(0)
}
async function save() {
  if (
    !allowed.value ||
    !baseline.value ||
    !props.user ||
    pending.value ||
    loading.value ||
    stale.value ||
    !reviewed.value ||
    !changes.value.length ||
    invalid.value.length ||
    wanted.value.size > 1000
  )
    return
  const epoch = generation,
    target = props.user.id
  if (
    changes.value.some((change) => change.role?.protected) &&
    !window.confirm('本次涉及受保护管理员角色，将改变管理身份。确认按已预览差异提交？')
  )
    return
  pending.value = true
  error.value = ''
  success.value = ''
  try {
    await write(
      'tenant',
      `/users/${encodeURIComponent(target)}/roles`,
      {
        user_version: baseline.value.version,
        roles: [...wanted.value].sort().map((id) => ({ id, version: snapshots.value[id].version })),
      },
      'PUT',
    )
    if (epoch !== generation) return
    pending.value = false
    stale.value = true
    await reload(false)
    if (epoch !== generation) return
    if (!stale.value) {
      success.value = '用户角色已保存并重新读取。'
      emit('changed')
    }
  } catch (problem) {
    if (epoch !== generation) return
    if (problem instanceof IdentityError && (problem.status === 409 || problem.uncertain))
      stale.value = true
    report(problem)
  } finally {
    if (epoch === generation) {
      pending.value = false
      reviewed.value = false
    }
  }
}
watch(
  () => props.user,
  async (user) => {
    generation++
    dialog.value?.close()
    clear()
    if (!user) return
    const epoch = generation
    await nextTick()
    if (epoch !== generation) return
    dialog.value?.showModal()
    await reload(false)
  },
  { immediate: true },
)
watch(allowed, (value) => {
  if (!value) {
    generation++
    dialog.value?.close()
    clear()
    emit('closed')
  }
})
onBeforeRouteLeave(() => {
  if (pending.value || (props.user && !discardAllowed())) return false
  return true
})
onBeforeUnmount(() => {
  generation++
  clear()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="management-dialog role-assignment-dialog"
    aria-labelledby="user-roles-title"
    @cancel.prevent="close"
  >
    <h2 id="user-roles-title">分配用户角色</h2>
    <p v-if="user" class="muted">
      {{ user.display_name }} · {{ user.username }} · 当前组织内的账号
    </p>
    <div v-if="!allowed" class="message error" role="alert">当前账号无权分配用户角色。</div>
    <div v-if="error" class="message error" role="alert">{{ error }}</div>
    <div v-if="success" class="message success" role="status">{{ success }}</div>
    <div v-if="loading" class="loading" role="status">正在核对用户和角色版本…</div>
    <template v-if="baseline && allowed">
      <p class="muted">
        用户版本 {{ baseline.version }} · 已分配
        {{ baseline.roles.length }} 个角色。翻页和筛选不会移除未显示的原角色。
      </p>
      <div v-if="stale" class="conflict-panel" role="alert">
        <strong>保存前必须重新核对当前版本</strong>
        <p>
          保留草稿不会自动保存；其他人新增的角色会作为差异明确列出。跨页草稿须重新查找确认，不能静默裁剪。
        </p>
        <button class="secondary-button" :disabled="pending || loading" @click="reload(true)">
          保留草稿并重新核对</button
        ><button
          class="secondary-button"
          :disabled="pending || loading"
          @click="discardAllowed() && reload(false)"
        >
          放弃草稿并读取当前角色
        </button>
      </div>
      <form class="search-form" @submit.prevent="loadOptions(0)">
        <label class="sr-only" for="assignment-query">查找可分配角色</label
        ><input
          id="assignment-query"
          v-model="query"
          maxlength="100"
          placeholder="角色编码或名称"
        /><button class="secondary-button" :disabled="pending || loading || stale">查询角色</button>
      </form>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">选择</th>
              <th scope="col">角色 / 状态</th>
              <th scope="col">角色版本</th>
              <th scope="col">管理边界</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="role in visible" :key="role.id">
              <td>
                <input
                  type="checkbox"
                  :aria-label="`分配角色 ${role.code}`"
                  :checked="wanted.has(role.id)"
                  :disabled="
                    pending ||
                    loading ||
                    stale ||
                    (wanted.has(role.id)
                      ? !role.can_unassign
                      : !role.can_assign && !original.has(role.id))
                  "
                  @change="toggle(role, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td>
                {{ role.name }}<br /><code>{{ role.code }}</code
                ><small class="muted"
                  >{{ role.status === 'ACTIVE' ? '启用' : '停用'
                  }}{{ role.protected ? ' · 受保护管理员' : ''
                  }}{{ original.has(role.id) ? ' · 当前已分配' : '' }}</small
                >
              </td>
              <td>{{ role.version }}</td>
              <td>
                {{ role.restrictions.map(managementRestriction).join('、') || '按当前快照允许' }}
              </td>
            </tr>
            <tr v-if="!visible.length && !loading">
              <td colspan="4" class="empty">没有可显示的角色。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>可选角色第 {{ page + 1 }} 页</span
        ><button
          class="secondary-button"
          :disabled="pending || loading || stale || page === 0"
          @click="loadOptions(page - 1)"
        >
          上一页角色</button
        ><button
          class="secondary-button"
          :disabled="pending || loading || stale || !hasMore || page >= 10000"
          @click="loadOptions(page + 1)"
        >
          下一页角色
        </button>
      </div>
      <div v-if="offPage.length" class="conflict-panel">
        <strong>其他页面已选角色</strong>
        <div v-for="id in offPage" :key="id">
          {{ snapshots[id]?.name ?? id }} · {{ verified.has(id) ? '已核对' : '需重新查找核对'
          }}<button
            class="text-button"
            :disabled="pending || loading || stale"
            @click="find(snapshots[id])"
          >
            重新查找</button
          ><button
            class="text-button"
            :disabled="pending || loading || stale"
            @click="removeDraft(id)"
          >
            取消该草稿项
          </button>
        </div>
      </div>
      <div v-if="invalid.length" class="message error" role="alert">
        {{ invalid.length }} 项角色尚未核对或超出当前可分配/移除边界，请查找核对或调整选择。
      </div>
      <div class="form-actions">
        <button
          class="secondary-button"
          :disabled="pending || loading || stale || !changes.length || !!invalid.length"
          @click="reviewed = true"
        >
          预览角色差异</button
        ><span class="muted"
          >{{ changes.length }} 项变动；分配角色不会赋予操作者该角色的业务权限。</span
        >
      </div>
      <section v-if="reviewed" class="conflict-panel" aria-label="角色分配差异预览">
        <h3>请确认本次角色变动</h3>
        <ul>
          <li v-for="change in changes" :key="change.id">
            {{ change.added ? '增加' : '移除' }} · {{ change.role?.name ?? change.id }} ·
            {{ change.role?.code }}{{ change.role?.protected ? ' · 将改变受保护管理身份' : '' }}
          </li>
        </ul>
        <p>最终保存重新核验目标、完整角色授权、全部角色版本与管理员身份。</p>
        <button
          class="primary-button"
          :disabled="pending || loading || stale || !!invalid.length"
          @click="save"
        >
          {{ pending ? '正在保存…' : '确认分配角色' }}
        </button>
      </section>
    </template>
    <div class="form-actions">
      <button class="secondary-button" :disabled="pending" @click="close">关闭角色分配</button>
    </div>
  </dialog>
</template>
