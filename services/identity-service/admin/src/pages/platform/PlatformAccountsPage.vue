<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
import { IdentityError, read, write } from '../../api/identity'
import type {
  IssuedAccount,
  IssuedTenant,
  Page,
  PlatformAdministrator,
  PlatformTenant,
} from '../../api/platform'

const props = defineProps<{ kind: 'tenants' | 'admins' }>()
type Row = PlatformTenant | PlatformAdministrator
const account = inject(accountContext)
const owner = computed(() => account?.() ?? null)
const tenantMode = computed(() => props.kind === 'tenants')
const noun = computed(() => (tenantMode.value ? '租户' : '平台管理员'))
const endpoint = computed(() => `/platform/${props.kind}`)
const permission = computed(() => (tenantMode.value ? 'platform:tenant' : 'platform:admin'))
const readable = computed(
  () => owner.value?.realm === 'platform' && can(owner.value.me, `${permission.value}:read`),
)
const manageable = computed(
  () => readable.value && owner.value && can(owner.value.me, `${permission.value}:manage`),
)
const rows = ref<Row[]>([])
const page = ref(0)
const hasMore = ref(false)
const query = ref('')
const loading = ref(false)
const pending = ref(false)
const error = ref('')
const success = ref('')
const modalError = ref('')
const dialog = ref<HTMLDialogElement | null>(null)
const mode = ref<'create' | 'edit' | 'credential'>('create')
const editing = ref<Row | null>(null)
const conflict = ref<Row | null>(null)
const issued = ref<{
  objectId: string
  accountId: string
  username: string
  code: string
  password: string | null
} | null>(null)
const revealed = ref(false)
const uncertainCreate = ref(false)
const commandId = ref('')
let generation = 0
let readSequence = 0
const draft = reactive({
  code: '',
  name: '',
  expiresAt: '',
  adminUsername: '',
  adminDisplayName: '',
  username: '',
  displayName: '',
  status: 'ACTIVE' as 'ACTIVE' | 'DISABLED',
})

function isTenant(row: Row): row is PlatformTenant {
  return 'code' in row
}
function label(row: Row) {
  return isTenant(row) ? row.name : row.display_name
}
function identifier(row: Row) {
  return isTenant(row) ? row.code : row.username
}
function date(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '长期有效'
}
function localDate(value: string | null) {
  if (!value) return ''
  const parsed = new Date(value)
  return new Date(parsed.getTime() - parsed.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}
function expiry() {
  if (!draft.expiresAt) return null
  if (
    editing.value &&
    isTenant(editing.value) &&
    draft.expiresAt === localDate(editing.value.expires_at)
  )
    return editing.value.expires_at
  const value = new Date(draft.expiresAt)
  if (!Number.isFinite(value.getTime())) throw new IdentityError(400, 'INVALID_DATETIME')
  return value.toISOString()
}
function report(problem: unknown, modal = false) {
  const message = problem instanceof IdentityError ? problem.message : '暂时无法完成操作。'
  if (modal) modalError.value = message
  else error.value = message
  owner.value?.failed(problem)
}

async function loadRows(targetPage = page.value) {
  if (!readable.value) return
  const epoch = generation
  const sequence = ++readSequence
  loading.value = true
  error.value = ''
  try {
    const result = await read<Page<Row>>(
      'platform',
      `${endpoint.value}?page=${targetPage}&size=20&q=${encodeURIComponent(query.value.trim())}`,
    )
    if (epoch !== generation || sequence !== readSequence) return
    rows.value = result.items
    page.value = result.page
    hasMore.value = result.has_more
  } catch (problem) {
    if (epoch === generation && sequence === readSequence) report(problem)
  } finally {
    if (epoch === generation && sequence === readSequence) loading.value = false
  }
}

function resetModal() {
  issued.value = null
  revealed.value = false
  editing.value = null
  conflict.value = null
  commandId.value = ''
  uncertainCreate.value = false
  modalError.value = ''
  Object.assign(draft, {
    code: '',
    name: '',
    expiresAt: '',
    adminUsername: '',
    adminDisplayName: '',
    username: '',
    displayName: '',
    status: 'ACTIVE',
  })
}

async function openCreate() {
  if (!manageable.value || pending.value) return
  resetModal()
  mode.value = 'create'
  if (tenantMode.value && !rows.value.length && !query.value && page.value === 0) {
    draft.code = 'default'
    draft.name = '默认租户'
    draft.adminUsername = 'admin'
    draft.adminDisplayName = '租户管理员'
  }
  commandId.value = crypto.randomUUID()
  await nextTick()
  dialog.value?.showModal()
}

function apply(row: Row) {
  editing.value = row
  draft.status = row.status
  if (isTenant(row)) {
    draft.code = row.code
    draft.name = row.name
    draft.expiresAt = localDate(row.expires_at)
  } else {
    draft.username = row.username
    draft.displayName = row.display_name
  }
}

async function openEdit(row: Row) {
  if (!manageable.value || pending.value) return
  resetModal()
  mode.value = 'edit'
  pending.value = true
  const epoch = generation
  await nextTick()
  dialog.value?.showModal()
  try {
    const current = await read<Row>('platform', `${endpoint.value}/${encodeURIComponent(row.id)}`)
    if (epoch === generation) apply(current)
  } catch (problem) {
    if (epoch === generation) report(problem, true)
  } finally {
    if (epoch === generation) pending.value = false
  }
}

function closeModal() {
  if (pending.value) return
  dialog.value?.close()
  resetModal()
}

function cancel(event: Event) {
  event.preventDefault()
  if (pending.value) return
  if (issued.value && !window.confirm('关闭后无法再次查看初始密码，确认已妥善交付？')) return
  closeModal()
}

function resolveConflict(keepDraft: boolean) {
  if (!conflict.value) return
  if (keepDraft) editing.value = conflict.value
  else apply(conflict.value)
  conflict.value = null
  modalError.value = ''
}

async function submit() {
  if (!manageable.value || pending.value || mode.value === 'credential' || conflict.value) return
  if (mode.value === 'edit' && !editing.value) return
  if (
    mode.value === 'edit' &&
    editing.value?.status === 'ACTIVE' &&
    draft.status === 'DISABLED' &&
    !window.confirm(
      tenantMode.value
        ? '停用后该组织的新登录和访问将失效，是否继续？'
        : '停用后该管理员的已有授权将失效，是否继续？',
    )
  )
    return
  const epoch = generation
  pending.value = true
  modalError.value = ''
  success.value = ''
  const target = endpoint.value
  try {
    if (mode.value === 'create') {
      const lookup = tenantMode.value ? draft.code.trim() : draft.username.trim().toLowerCase()
      const username = tenantMode.value ? draft.adminUsername.trim().toLowerCase() : lookup
      const body = tenantMode.value
        ? {
            code: lookup,
            name: draft.name,
            expires_at: expiry(),
            admin_username: username,
            admin_display_name: draft.adminDisplayName,
          }
        : { username, display_name: draft.displayName }
      const result = await write<IssuedAccount | IssuedTenant>(
        'platform',
        target,
        body,
        'POST',
        commandId.value,
      )
      if (epoch !== generation) return
      const objectId = 'tenant_id' in result ? result.tenant_id : result.id
      const accountId = 'admin_id' in result ? result.admin_id : result.id
      if (
        typeof objectId !== 'string' ||
        typeof accountId !== 'string' ||
        typeof result.replayed !== 'boolean' ||
        (result.initial_password !== null && typeof result.initial_password !== 'string')
      )
        throw new IdentityError(503, 'INVALID_RESPONSE', '', true)
      issued.value = {
        objectId,
        accountId,
        username,
        code: tenantMode.value ? lookup : '',
        password: result.initial_password,
      }
      mode.value = 'credential'
      uncertainCreate.value = false
      query.value = lookup
      page.value = 0
      await loadRows(0)
    } else {
      const row = editing.value
      if (!row) return
      const body = tenantMode.value
        ? { version: row.version, name: draft.name, expires_at: expiry(), status: draft.status }
        : { version: row.version, display_name: draft.displayName, status: draft.status }
      await write('platform', `${target}/${encodeURIComponent(row.id)}`, body, 'PUT')
      if (epoch !== generation) return
      dialog.value?.close()
      resetModal()
      success.value = `${noun.value}已更新。`
      await loadRows()
    }
  } catch (problem) {
    if (epoch !== generation) return
    if (mode.value === 'create' && problem instanceof IdentityError && problem.uncertain)
      uncertainCreate.value = true
    if (
      mode.value === 'edit' &&
      problem instanceof IdentityError &&
      problem.code.endsWith('_VERSION_CONFLICT') &&
      editing.value
    ) {
      try {
        conflict.value = await read<Row>(
          'platform',
          `${target}/${encodeURIComponent(editing.value.id)}`,
        )
      } catch (unavailable) {
        report(unavailable, true)
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
  if (issued.value && !window.confirm('离开将清除本次初始凭据，确认已妥善交付？')) return false
  dialog.value?.close()
  resetModal()
  return true
})
onBeforeUnmount(() => {
  generation++
  resetModal()
})
watch(
  () => props.kind,
  () => {
    generation++
    dialog.value?.close()
    resetModal()
    rows.value = []
    page.value = 0
    query.value = ''
    hasMore.value = false
    loading.value = false
    pending.value = false
    success.value = ''
    void loadRows(0)
  },
)
watch(readable, (allowed) => {
  if (!allowed) {
    generation++
    dialog.value?.close()
    resetModal()
    rows.value = []
    loading.value = false
    pending.value = false
  }
})
onMounted(() => loadRows(0))
</script>

<template>
  <section class="card management-card">
    <div v-if="!readable" class="empty" role="alert">当前账号无权访问此管理页面。</div>
    <template v-else>
      <div class="section-heading">
        <div>
          <h2>{{ noun }}列表</h2>
          <p class="muted">
            {{
              tenantMode
                ? '租户独立管理账号与授权，平台管理不授予租户业务数据访问权。'
                : '平台管理员维护本项目的平台配置，至少保留一位已激活的有效管理员。'
            }}
          </p>
        </div>
        <button
          v-if="manageable"
          class="primary-button"
          :disabled="loading || pending"
          @click="openCreate"
        >
          新建{{ noun }}
        </button>
      </div>
      <div v-if="error" class="message error" role="alert">{{ error }}</div>
      <div v-if="success" class="message success" role="status">{{ success }}</div>
      <form class="search-form" @submit.prevent="loadRows(0)">
        <label class="sr-only" for="platform-query">{{
          tenantMode ? '搜索组织代码或名称' : '搜索账号或显示名'
        }}</label
        ><input
          id="platform-query"
          v-model="query"
          maxlength="100"
          :placeholder="tenantMode ? '搜索组织代码或名称' : '搜索账号或显示名'"
        /><button class="secondary-button" :disabled="loading || pending" type="submit">
          查询
        </button>
      </form>
      <div v-if="loading" class="loading" role="status">正在读取{{ noun }}…</div>
      <div class="table-wrap">
        <table>
          <caption class="sr-only">
            {{
              noun
            }}列表
          </caption>
          <thead>
            <tr>
              <th scope="col">{{ tenantMode ? '组织代码' : '登录名' }}</th>
              <th scope="col">{{ tenantMode ? '租户名称' : '显示名' }}</th>
              <th scope="col">状态</th>
              <th scope="col">{{ tenantMode ? '有效期限' : '账号激活' }}</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ identifier(row) }}</td>
              <td>{{ label(row) }}</td>
              <td>
                <span :class="row.status === 'ACTIVE' ? 'status-active' : 'muted'">{{
                  row.status === 'ACTIVE' ? '启用' : '停用'
                }}</span>
              </td>
              <td>
                {{
                  isTenant(row)
                    ? date(row.expires_at)
                    : row.must_change_password
                      ? '待首次改密'
                      : '已激活'
                }}
              </td>
              <td>
                <div class="row-actions">
                  <template
                    v-if="isTenant(row) && owner && can(owner.me, 'platform:identity:read')"
                  >
                    <RouterLink
                      class="text-button"
                      :to="`/platform/tenants/${row.id}/identity/users`"
                      >管理用户</RouterLink
                    >
                    <RouterLink
                      class="text-button"
                      :to="`/platform/tenants/${row.id}/identity/roles`"
                      >管理角色</RouterLink
                    >
                  </template>
                  <button
                    v-if="manageable"
                    class="text-button"
                    :disabled="pending"
                    @click="openEdit(row)"
                  >
                    编辑</button
                  ><span v-else class="muted">只读</span>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && !rows.length">
              <td colspan="5" class="empty">暂无符合条件的{{ noun }}。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>第 {{ page + 1 }} 页</span
        ><button
          class="secondary-button"
          :disabled="loading || pending || page === 0"
          @click="loadRows(page - 1)"
        >
          上一页</button
        ><button
          class="secondary-button"
          :disabled="loading || pending || !hasMore || page >= 10000"
          @click="loadRows(page + 1)"
        >
          下一页
        </button>
      </div>
    </template>

    <dialog
      ref="dialog"
      class="management-dialog"
      aria-labelledby="platform-dialog-title"
      @cancel="cancel"
    >
      <template v-if="mode === 'credential' && issued">
        <h2 id="platform-dialog-title">初始凭据交付</h2>
        <p class="muted">{{ noun }}已创建。请受控交付初始凭据，账号首次登录必须修改密码。</p>
        <dl class="credential-summary">
          <dt>{{ tenantMode ? '租户 ID' : '管理员 ID' }}</dt>
          <dd>{{ issued.objectId }}</dd>
          <template v-if="issued.code"
            ><dt>组织代码</dt>
            <dd>{{ issued.code }}</dd></template
          >
          <dt>管理员账号</dt>
          <dd>{{ issued.username }}</dd>
        </dl>
        <template v-if="issued.password"
          ><div class="field">
            <label for="issued-password">初始密码</label
            ><input
              id="issued-password"
              :value="issued.password"
              :type="revealed ? 'text' : 'password'"
              readonly
              autocomplete="off"
            />
          </div>
          <button type="button" class="text-button" @click="revealed = !revealed">
            {{ revealed ? '隐藏密码' : '显示密码' }}
          </button>
          <p class="credential-warning">
            关闭窗口后无法再次查看此密码，请确认已妥善交付。
          </p></template
        >
        <p v-else class="credential-warning">
          本次为重复请求，系统不会再次交付初始密码。请核对已有记录，凭据问题通过受保护的重置流程处理。
        </p>
        <div class="form-actions">
          <button type="button" class="primary-button" :disabled="pending" @click="closeModal">
            已记录，关闭
          </button>
        </div>
      </template>
      <form v-else @submit.prevent="submit">
        <h2 id="platform-dialog-title">{{ mode === 'create' ? '新建' : '编辑' }}{{ noun }}</h2>
        <div v-if="modalError" class="message error" role="alert">{{ modalError }}</div>
        <p v-if="uncertainCreate" class="credential-warning">
          本次请求结果尚未确认，字段已保留。再次核对使用同一请求，不会自动重复创建或重新交付已有密码。
        </p>
        <div v-if="pending && mode === 'edit' && !editing" class="loading">正在读取当前记录…</div>
        <fieldset :disabled="pending || uncertainCreate" class="plain-fields">
          <template v-if="tenantMode"
            ><div class="field">
              <label for="tenant-code-create">租户编码</label
              ><input
                id="tenant-code-create"
                v-model="draft.code"
                :readonly="mode === 'edit'"
                required
                maxlength="64"
                pattern="[a-z][a-z0-9-]{0,63}"
              />
            </div>
            <div class="field">
              <label for="tenant-name">租户名称</label
              ><input id="tenant-name" v-model="draft.name" maxlength="128" required />
            </div>
            <div class="field">
              <label for="tenant-expires">有效期至</label
              ><input id="tenant-expires" v-model="draft.expiresAt" type="datetime-local" /><small
                >留空为长期有效。</small
              >
            </div>
            <template v-if="mode === 'create'"
              ><div class="field">
                <label for="initial-admin-name">管理员账号</label
                ><input
                  id="initial-admin-name"
                  v-model="draft.adminUsername"
                  maxlength="128"
                  required
                  autocomplete="off"
                />
              </div>
              <div class="field">
                <label for="initial-admin-display">管理员显示名</label
                ><input
                  id="initial-admin-display"
                  v-model="draft.adminDisplayName"
                  maxlength="128"
                  required
                /></div></template
          ></template>
          <template v-else
            ><div class="field">
              <label for="platform-admin-name">管理员账号</label
              ><input
                id="platform-admin-name"
                v-model="draft.username"
                :readonly="mode === 'edit'"
                maxlength="128"
                required
                autocomplete="off"
              />
            </div>
            <div class="field">
              <label for="platform-admin-display">管理员显示名</label
              ><input
                id="platform-admin-display"
                v-model="draft.displayName"
                maxlength="128"
                required
              /></div
          ></template>
          <div v-if="mode === 'edit'" class="field">
            <label for="platform-status">状态</label
            ><select id="platform-status" v-model="draft.status">
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
          </div>
        </fieldset>
        <div v-if="conflict" class="conflict-panel" role="status">
          <strong>当前记录已变化</strong>
          <p>服务器当前名称：{{ label(conflict) }}</p>
          <p>服务器当前状态：{{ conflict.status === 'ACTIVE' ? '启用' : '停用' }}</p>
          <div class="form-actions">
            <button type="button" class="secondary-button" @click="resolveConflict(false)">
              采用当前记录</button
            ><button type="button" class="secondary-button" @click="resolveConflict(true)">
              保留我的修改
            </button>
          </div>
        </div>
        <div class="form-actions">
          <button
            type="submit"
            class="primary-button"
            :disabled="pending || !!conflict || !manageable || (mode === 'edit' && !editing)"
          >
            {{
              pending
                ? '正在提交…'
                : mode === 'create'
                  ? uncertainCreate
                    ? '核对本次请求'
                    : '创建'
                  : '保存修改'
            }}</button
          ><button type="button" class="secondary-button" :disabled="pending" @click="closeModal">
            取消
          </button>
        </div>
      </form>
    </dialog>
  </section>
</template>
