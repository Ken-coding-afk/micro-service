<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, RouterLink } from 'vue-router'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
import { IdentityError, read, write } from '../../api/identity'
import type { IssuedAccount, Page } from '../../api/platform'
import type { EffectivePermissions, TenantUser } from '../../api/tenant'
import { permissionReason } from '../../api/tenant'
import UserRolesDialog from '../../components/UserRolesDialog.vue'

const context = inject(accountContext)
const owner = computed(() => context?.() ?? null)
const readable = computed(
  () => owner.value?.realm === 'tenant' && can(owner.value.me, 'identity:user:read'),
)
const manageable = computed(
  () => readable.value && owner.value && can(owner.value.me, 'identity:user:manage'),
)
const explainable = computed(
  () => readable.value && owner.value && can(owner.value.me, 'identity:permission:read'),
)
const rows = ref<TenantUser[]>([])
const assigning = ref<TenantUser | null>(null)
const assignable = computed(
  () => readable.value && owner.value && can(owner.value.me, 'identity:role:assign'),
)
const query = ref('')
const page = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const pending = ref(false)
const error = ref('')
const success = ref('')
const dialogError = ref('')
const dialog = ref<HTMLDialogElement | null>(null)
const mode = ref<'create' | 'profile' | 'status' | 'permissions' | 'credential'>('create')
const target = ref<TenantUser | null>(null)
const conflict = ref<TenantUser | null>(null)
const username = ref('')
const displayName = ref('')
const status = ref<'ACTIVE' | 'DISABLED'>('ACTIVE')
const permissions = ref<EffectivePermissions | null>(null)
const receipt = ref<IssuedAccount | null>(null)
const revealed = ref(false)
const uncertain = ref(false)
const commandId = ref('')
let generation = 0
let sequence = 0

function statusName(value: string) {
  return value === 'ACTIVE' ? '启用' : value === 'DISABLED' ? '停用' : '待激活'
}
function report(problem: unknown, modal = false) {
  const message = problem instanceof IdentityError ? problem.message : '暂时无法完成操作。'
  if (modal) dialogError.value = message
  else error.value = message
  owner.value?.failed(problem)
}
async function load(targetPage = page.value) {
  if (!readable.value) return
  const epoch = generation,
    request = ++sequence
  loading.value = true
  error.value = ''
  try {
    const result = await read<Page<TenantUser>>(
      'tenant',
      `/users?page=${targetPage}&size=20&q=${encodeURIComponent(query.value.trim())}`,
    )
    if (epoch !== generation || request !== sequence) return
    rows.value = result.items
    page.value = result.page
    hasMore.value = result.has_more
  } catch (problem) {
    if (epoch === generation && request === sequence) report(problem)
  } finally {
    if (epoch === generation && request === sequence) loading.value = false
  }
}
function clearDialog() {
  target.value = null
  conflict.value = null
  permissions.value = null
  receipt.value = null
  revealed.value = false
  uncertain.value = false
  commandId.value = ''
  username.value = ''
  displayName.value = ''
  dialogError.value = ''
}
function close() {
  if (pending.value) return
  dialog.value?.close()
  clearDialog()
}
function cancel(event: Event) {
  event.preventDefault()
  if (
    receipt.value?.initial_password &&
    !window.confirm('关闭后无法再次查看初始密码，确认已妥善交付？')
  )
    return
  close()
}
async function create() {
  if (!manageable.value || pending.value) return
  clearDialog()
  mode.value = 'create'
  commandId.value = crypto.randomUUID()
  await nextTick()
  dialog.value?.showModal()
}
async function open(row: TenantUser, action: 'profile' | 'status' | 'permissions') {
  if (pending.value || (action === 'permissions' ? !explainable.value : !manageable.value)) return
  clearDialog()
  mode.value = action
  const epoch = generation
  pending.value = true
  await nextTick()
  dialog.value?.showModal()
  try {
    const current = await read<TenantUser>('tenant', `/users/${encodeURIComponent(row.id)}`)
    if (epoch !== generation) return
    target.value = current
    displayName.value = current.display_name
    status.value = current.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE'
    if (action === 'permissions') {
      const result = await read<EffectivePermissions>(
        'tenant',
        `/users/${encodeURIComponent(row.id)}/effective-permissions`,
      )
      if (epoch !== generation) return
      if (result.view !== 'ROLE_GRANTS' || result.user_id !== current.id)
        throw new IdentityError(503, 'INVALID_RESPONSE')
      permissions.value = result
    }
  } catch (problem) {
    if (epoch === generation) report(problem, true)
  } finally {
    if (epoch === generation) pending.value = false
  }
}
function resolve(keep: boolean) {
  if (!conflict.value) return
  target.value = conflict.value
  if (!keep) {
    displayName.value = conflict.value.display_name
    status.value = conflict.value.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE'
  }
  conflict.value = null
  dialogError.value = ''
}
async function submit() {
  if (
    !manageable.value ||
    pending.value ||
    conflict.value ||
    mode.value === 'credential' ||
    mode.value === 'permissions'
  )
    return
  if (mode.value !== 'create' && !target.value) return
  if (mode.value === 'status' && target.value?.status === 'PENDING_ACTIVATION') return
  if (
    mode.value === 'status' &&
    !window.confirm(`确认将此账号设为${statusName(status.value)}？状态变化会使已有授权失效。`)
  )
    return
  const epoch = generation
  pending.value = true
  dialogError.value = ''
  success.value = ''
  try {
    if (mode.value === 'create') {
      const account = username.value.trim().toLowerCase()
      const result = await write<IssuedAccount>(
        'tenant',
        '/users',
        { username: account, display_name: displayName.value },
        'POST',
        commandId.value,
      )
      if (epoch !== generation) return
      if (
        typeof result.id !== 'string' ||
        typeof result.replayed !== 'boolean' ||
        (result.initial_password !== null && typeof result.initial_password !== 'string')
      )
        throw new IdentityError(503, 'INVALID_RESPONSE', '', true)
      receipt.value = result
      username.value = account
      mode.value = 'credential'
      uncertain.value = false
      query.value = account
      await load(0)
    } else {
      const row = target.value
      if (!row) return
      const profile = mode.value === 'profile'
      const body = profile
        ? { version: row.version, display_name: displayName.value }
        : { version: row.version, status: status.value }
      await write(
        'tenant',
        `/users/${encodeURIComponent(row.id)}${profile ? '/profile' : ''}`,
        body,
        'PUT',
      )
      if (epoch !== generation) return
      dialog.value?.close()
      clearDialog()
      success.value = profile ? '用户资料已更新。' : '用户状态已更新。'
      await load()
    }
  } catch (problem) {
    if (epoch !== generation) return
    if (mode.value === 'create' && problem instanceof IdentityError && problem.uncertain)
      uncertain.value = true
    if (
      problem instanceof IdentityError &&
      problem.code === 'USER_VERSION_CONFLICT' &&
      target.value
    ) {
      try {
        const current = await read<TenantUser>(
          'tenant',
          `/users/${encodeURIComponent(target.value.id)}`,
        )
        if (epoch !== generation) return
        conflict.value = current
      } catch (unavailable) {
        if (epoch === generation) report(unavailable, true)
        return
      }
    }
    report(problem, true)
  } finally {
    if (epoch === generation) pending.value = false
  }
}
onBeforeRouteLeave(() => {
  if (pending.value) return false
  if (receipt.value?.initial_password && !window.confirm('离开将清除初始凭据，确认已妥善交付？'))
    return false
  close()
  return true
})
onBeforeUnmount(() => {
  generation++
  clearDialog()
})
watch(readable, (allowed) => {
  if (!allowed) {
    generation++
    dialog.value?.close()
    clearDialog()
    rows.value = []
    pending.value = false
    loading.value = false
  }
})
onMounted(() => load(0))
</script>

<template>
  <section class="card management-card">
    <div v-if="!readable" class="empty" role="alert">当前账号无权读取租户用户。</div>
    <template v-else>
      <div class="section-heading">
        <div>
          <h2>用户列表</h2>
          <p class="muted">
            仅显示当前组织内可管理的账号。角色授权和状态由服务端在提交时重新核验。
          </p>
        </div>
        <button
          v-if="manageable"
          class="primary-button"
          :disabled="loading || pending"
          @click="create"
        >
          新建用户
        </button>
      </div>
      <div v-if="error" class="message error" role="alert">{{ error }}</div>
      <div v-if="success" class="message success" role="status">{{ success }}</div>
      <form class="search-form" @submit.prevent="load(0)">
        <label class="sr-only" for="user-query">搜索账号或显示名</label
        ><input
          id="user-query"
          v-model="query"
          maxlength="100"
          placeholder="搜索账号或显示名"
        /><button class="secondary-button" :disabled="loading || pending">查询</button>
      </form>
      <div v-if="loading" class="loading" role="status">正在读取用户…</div>
      <div class="table-wrap">
        <table>
          <caption class="sr-only">
            当前租户用户
          </caption>
          <thead>
            <tr>
              <th scope="col">登录名</th>
              <th scope="col">显示名</th>
              <th scope="col">状态</th>
              <th scope="col">凭证状态</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.username }}</td>
              <td>{{ row.display_name }}</td>
              <td>{{ statusName(row.status) }}</td>
              <td>{{ row.must_change_password ? '待首次改密' : '已设置' }}</td>
              <td class="row-actions">
                <template v-if="manageable"
                  ><button class="text-button" :disabled="pending" @click="open(row, 'profile')">
                    修改资料</button
                  ><button
                    class="text-button"
                    :disabled="pending || row.status === 'PENDING_ACTIVATION'"
                    @click="open(row, 'status')"
                  >
                    启停
                  </button></template
                ><button
                  v-if="explainable"
                  class="text-button"
                  :disabled="pending"
                  @click="open(row, 'permissions')"
                >
                  有效权限</button
                ><button
                  v-if="assignable"
                  class="text-button"
                  :disabled="pending"
                  @click="assigning = row"
                >
                  分配角色</button
                ><RouterLink
                  v-if="owner && can(owner.me, 'identity:session:read')"
                  :to="{ path: '/sessions', query: { user_id: row.id } }"
                  class="text-button"
                  >查看会话</RouterLink
                ><span v-if="!manageable && !explainable && !assignable" class="muted">只读</span>
              </td>
            </tr>
            <tr v-if="!loading && !rows.length">
              <td colspan="5" class="empty">暂无符合条件的用户。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>第 {{ page + 1 }} 页</span
        ><button
          class="secondary-button"
          :disabled="loading || pending || page === 0"
          @click="load(page - 1)"
        >
          上一页</button
        ><button
          class="secondary-button"
          :disabled="loading || pending || !hasMore || page >= 10000"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </div>
    </template>
    <dialog
      ref="dialog"
      class="management-dialog"
      aria-labelledby="user-dialog-title"
      @cancel="cancel"
    >
      <h2 id="user-dialog-title">
        {{
          mode === 'create'
            ? '新建用户'
            : mode === 'profile'
              ? '修改用户资料'
              : mode === 'status'
                ? '用户启停'
                : mode === 'credential'
                  ? '初始凭据交付'
                  : '用户有效权限'
        }}
      </h2>
      <div v-if="dialogError" class="message error" role="alert">{{ dialogError }}</div>
      <div v-if="pending && !target && mode !== 'create'" class="loading">正在处理…</div>
      <template v-if="mode === 'credential' && receipt">
        <p>账号 {{ username }} 已创建，首次登录必须修改密码。</p>
        <template v-if="receipt.initial_password"
          ><div class="field">
            <label for="user-initial-password">初始密码</label
            ><input
              id="user-initial-password"
              :value="receipt.initial_password"
              :type="revealed ? 'text' : 'password'"
              readonly
              autocomplete="off"
            />
          </div>
          <button class="text-button" @click="revealed = !revealed">
            {{ revealed ? '隐藏密码' : '显示密码' }}
          </button>
          <p class="credential-warning">关闭后无法再次查看，请确认已受控交付。</p></template
        >
        <p v-else class="credential-warning">
          此请求已处理，不会再次交付原密码。请核对账号，凭据问题使用受保护的重置流程处理。
        </p>
        <div class="form-actions">
          <button class="primary-button" :disabled="pending" @click="close">已记录，关闭</button>
        </div>
      </template>
      <template v-else-if="mode === 'permissions'">
        <template v-if="permissions"
          ><p class="muted">
            角色授权快照 · {{ permissions.as_of }} · 目录版本 {{ permissions.catalog_version }}
          </p>
          <p class="muted">
            应用实际权限还受客户端和 token scopes 限制；业务对象归属仍由业务服务判断。
          </p>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">权限</th>
                  <th scope="col">结果 / 范围</th>
                  <th scope="col">来源角色 / 授权版本</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(decision, code) in permissions.permissions" :key="code">
                  <td>
                    <code>{{ code }}</code>
                  </td>
                  <td>
                    {{ decision.allowed ? '允许' : '拒绝' }} / {{ decision.data_scope ?? '无'
                    }}<small v-if="decision.reasons.length" class="muted">{{
                      decision.reasons.map(permissionReason).join('、')
                    }}</small>
                  </td>
                  <td>
                    <div v-for="source in decision.sources" :key="source.role_id">
                      <code>{{ source.role_id }}</code> / {{ source.authorization_version }} /
                      {{ source.role_active ? '启用' : '停用' }} / {{ source.data_scope }}
                    </div>
                  </td>
                </tr>
                <tr v-if="!Object.keys(permissions.permissions).length">
                  <td colspan="3" class="empty">当前没有角色授权。</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
        <div class="form-actions">
          <button class="secondary-button" :disabled="pending" @click="close">关闭</button>
        </div>
      </template>
      <form v-else @submit.prevent="submit">
        <p v-if="target" class="muted">{{ target.username }} · {{ statusName(target.status) }}</p>
        <p v-if="uncertain" class="credential-warning">
          创建结果尚未确认，保留原内容与请求编号。请明确核对，不要另建重复账号。
        </p>
        <fieldset class="plain-fields" :disabled="pending || uncertain">
          <div v-if="mode === 'create'" class="field">
            <label for="new-user-name">登录账号</label
            ><input
              id="new-user-name"
              v-model="username"
              required
              maxlength="128"
              autocomplete="off"
            />
          </div>
          <div v-if="mode !== 'status'" class="field">
            <label for="user-display-name">显示名</label
            ><input id="user-display-name" v-model="displayName" required maxlength="128" />
          </div>
          <div v-else class="field">
            <label for="user-status">状态</label
            ><select
              id="user-status"
              v-model="status"
              :disabled="target?.status === 'PENDING_ACTIVATION'"
            >
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
            <p v-if="target?.status === 'PENDING_ACTIVATION'" class="muted">
              待激活账号必须完成首次改密，不能在此绕过激活。
            </p>
          </div>
        </fieldset>
        <div v-if="conflict" class="conflict-panel" role="status">
          <strong>当前记录已变化</strong>
          <p>服务器当前显示名：{{ conflict.display_name }} · {{ statusName(conflict.status) }}</p>
          <div class="form-actions">
            <button type="button" class="secondary-button" @click="resolve(false)">
              采用当前记录</button
            ><button type="button" class="secondary-button" @click="resolve(true)">
              保留我的修改
            </button>
          </div>
        </div>
        <div class="form-actions">
          <button
            class="primary-button"
            :disabled="
              pending ||
              !!conflict ||
              !manageable ||
              (mode !== 'create' && !target) ||
              (mode === 'status' && target?.status === 'PENDING_ACTIVATION')
            "
          >
            {{
              pending
                ? '正在提交…'
                : mode === 'create'
                  ? uncertain
                    ? '核对本次请求'
                    : '创建'
                  : '保存修改'
            }}</button
          ><button type="button" class="secondary-button" :disabled="pending" @click="close">
            取消
          </button>
        </div>
      </form>
    </dialog>
    <UserRolesDialog :user="assigning" @closed="assigning = null" @changed="load()" />
  </section>
</template>
