<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
import { IdentityError, read } from '../../api/identity'

interface Entry {
  id: string
  actor_id: string | null
  actor_type: string
  tenant_id: string | null
  action: string
  target_id: string | null
  result: 'SUCCESS' | 'FAILURE'
  request_id: string
  details: Record<string, unknown>
  details_truncated: boolean
  created_at: string
}
interface Page {
  page: number
  size: number
  has_more: boolean
  items: Entry[]
}
const context = inject(accountContext)
const owner = computed(() => context?.() ?? null)
const platform = computed(
  () => owner.value?.realm === 'platform' && owner.value.me.principal_type === 'platform',
)
const allowed = computed(
  () =>
    !!owner.value &&
    (platform.value
      ? can(owner.value.me, 'platform:audit:read')
      : owner.value.realm === 'tenant' &&
        owner.value.me.principal_type === 'user' &&
        can(owner.value.me, 'identity:audit:read')),
)
const identity = computed(
  () => `${owner.value?.realm}/${owner.value?.me.tenant_id}/${owner.value?.me.sub}`,
)
const rows = ref<Entry[]>([]),
  page = ref(0),
  more = ref(false),
  loading = ref(false),
  error = ref('')
const from = ref(''),
  to = ref(''),
  actor = ref(''),
  action = ref(''),
  target = ref(''),
  result = ref('')
const filter = ref('')
const selected = ref<Entry | null>(null),
  dialog = ref<HTMLDialogElement | null>(null)
let generation = 0,
  sequence = 0
const localTime = (value: string) =>
  Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString('zh-CN') : '时间不可用'
const details = computed(() =>
  selected.value ? JSON.stringify(selected.value.details, null, 2) : '',
)
function close() {
  dialog.value?.close()
  selected.value = null
}
async function open(row: Entry) {
  selected.value = row
  await nextTick()
  if (selected.value?.id === row.id) dialog.value?.showModal()
}
async function load(targetPage = 0, query = filter.value) {
  if (!allowed.value) return
  const epoch = generation,
    request = ++sequence
  loading.value = true
  error.value = ''
  close()
  try {
    const value = await read<Page>(
      platform.value ? 'platform' : 'tenant',
      `${platform.value ? '/platform' : ''}/audits?page=${targetPage}&size=20${query}`,
    )
    if (epoch !== generation || request !== sequence) return
    rows.value = value.items
    page.value = value.page
    more.value = value.has_more
    filter.value = query
  } catch (problem) {
    if (epoch !== generation || request !== sequence) return
    rows.value = []
    more.value = false
    error.value = problem instanceof IdentityError ? problem.message : '暂时无法读取审计记录。'
    owner.value?.failed(problem)
  } finally {
    if (epoch === generation && request === sequence) loading.value = false
  }
}
async function search() {
  const fields = new URLSearchParams()
  const actorValue = actor.value.trim(),
    actionValue = action.value.trim(),
    targetValue = target.value.trim()
  const start = from.value ? Date.parse(from.value) : null,
    end = to.value ? Date.parse(to.value) : null
  if (
    (start !== null && !Number.isFinite(start)) ||
    (end !== null && !Number.isFinite(end)) ||
    (start !== null && end !== null && start >= end) ||
    (actorValue &&
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(actorValue)) ||
    (actionValue && !/^[a-z][a-z0-9._:-]{0,159}$/.test(actionValue)) ||
    (targetValue && !/^[A-Za-z0-9._:/-]{1,160}$/.test(targetValue))
  ) {
    error.value = '请核对时间区间、操作者 UUID、动作编码或目标标识。'
    return
  }
  if (start !== null) fields.set('from', new Date(start).toISOString())
  if (end !== null) fields.set('to', new Date(end).toISOString())
  if (actorValue) fields.set('actor_id', actorValue)
  if (actionValue) fields.set('action', actionValue)
  if (targetValue) fields.set('target_id', targetValue)
  if (result.value) fields.set('result', result.value)
  await load(0, fields.size ? '&' + fields.toString() : '')
}
async function reset() {
  from.value = ''
  to.value = ''
  actor.value = ''
  action.value = ''
  target.value = ''
  result.value = ''
  await load(0, '')
}
watch([identity, allowed], () => {
  generation++
  sequence++
  loading.value = false
  rows.value = []
  more.value = false
  close()
  filter.value = ''
  from.value = ''
  to.value = ''
  actor.value = ''
  action.value = ''
  target.value = ''
  result.value = ''
  error.value = ''
  if (allowed.value) void load(0, '')
})
onBeforeUnmount(() => {
  generation++
  sequence++
  close()
})
onMounted(() => load(0, ''))
</script>

<template>
  <section class="card management-card">
    <div v-if="!allowed" class="empty" role="alert">当前账号无权读取管理审计。</div>
    <template v-else>
      <div class="section-heading">
        <div>
          <h2>审计记录</h2>
          <p class="muted">
            {{
              platform ? '仅显示平台控制操作。' : '仅显示当前组织内的身份管理与认证记录。'
            }}记录只读，详情由服务端脱敏。
          </p>
        </div>
        <span class="realm-badge">{{ platform ? '平台范围' : '当前组织' }}</span>
      </div>
      <form class="audit-filters" @submit.prevent="search">
        <label class="field"
          >开始时间<input
            v-model="from"
            type="datetime-local"
            step="1"
            :disabled="loading" /></label
        ><label class="field"
          >结束时间（不含）<input v-model="to" type="datetime-local" step="1" :disabled="loading"
        /></label>
        <label class="field"
          >操作者 ID<input
            v-model="actor"
            maxlength="36"
            placeholder="精确 UUID，可留空"
            :disabled="loading" /></label
        ><label class="field"
          >动作编码<input
            v-model="action"
            maxlength="160"
            placeholder="精确匹配，例如 identity.role.created"
            :disabled="loading"
        /></label>
        <label class="field"
          >目标标识<input
            v-model="target"
            maxlength="160"
            placeholder="精确匹配，可留空"
            :disabled="loading" /></label
        ><label class="field"
          >执行结果<select v-model="result" aria-label="执行结果" :disabled="loading">
            <option value="">全部结果</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILURE">失败</option>
          </select></label
        >
        <div class="audit-actions">
          <button class="primary-button" :disabled="loading">查询审计</button
          ><button type="button" class="secondary-button" :disabled="loading" @click="reset">
            清除筛选
          </button>
        </div>
      </form>
      <div v-if="error" class="message error" role="alert">{{ error }}</div>
      <div v-if="loading" class="loading" role="status">正在读取审计记录…</div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>操作者</th>
              <th>动作 / 目标</th>
              <th>结果</th>
              <th>请求与详情</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ localTime(row.created_at) }}</td>
              <td class="audit-id">
                <span>{{
                  row.actor_type === 'PLATFORM'
                    ? '平台账号'
                    : row.actor_type === 'TENANT' || row.actor_type === 'USER'
                      ? '租户账号'
                      : row.actor_type
                }}</span
                ><br /><code>{{ row.actor_id ?? '未识别主体' }}</code>
              </td>
              <td class="audit-id">
                <code>{{ row.action }}</code
                ><br />{{ row.target_id ?? '无指定目标' }}
              </td>
              <td>{{ row.result === 'SUCCESS' ? '成功' : '失败' }}</td>
              <td class="audit-id">
                <code>{{ row.request_id }}</code
                ><br /><button class="text-button" :disabled="loading" @click="open(row)">
                  查看明细</button
                ><small v-if="row.details_truncated" class="muted">明细已截断</small>
              </td>
            </tr>
            <tr v-if="!loading && !error && rows.length === 0">
              <td colspan="5" class="empty">没有符合条件的审计记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>第 {{ page + 1 }} 页</span
        ><button class="secondary-button" :disabled="loading || page === 0" @click="load(page - 1)">
          上一页</button
        ><button
          class="secondary-button"
          :disabled="loading || !more || page >= 10000"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </div>
    </template>
    <dialog
      ref="dialog"
      class="management-dialog audit-dialog"
      aria-labelledby="audit-dialog-title"
      @cancel="close"
    >
      <h2 id="audit-dialog-title">审计明细</h2>
      <template v-if="selected"
        ><p>
          <strong>{{ selected.action }}</strong> ·
          {{ selected.result === 'SUCCESS' ? '成功' : '失败' }}
        </p>
        <p class="muted">{{ localTime(selected.created_at) }} · 请求 {{ selected.request_id }}</p>
        <div v-if="selected.details_truncated" class="audit-warning">
          服务端已截断明细，当前内容不代表原始记录的全部字段。
        </div>
        <p class="muted">以下仅为服务端允许展示的脱敏内容。</p>
        <pre>{{ details }}</pre></template
      ><button class="secondary-button" @click="close">关闭明细</button>
    </dialog>
  </section>
</template>

<style scoped>
.audit-filters {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin: 24px 0;
}
.audit-actions {
  grid-column: 1 / -1;
  display: flex;
  gap: 12px;
}
.audit-id {
  max-width: 260px;
  overflow-wrap: anywhere;
}
.audit-dialog {
  width: min(850px, calc(100vw - 48px));
  overflow-wrap: anywhere;
}
.audit-dialog pre {
  max-height: 430px;
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #f5f8fc;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  overflow: auto;
  font-size: 12px;
}
.audit-warning {
  padding: 12px 15px;
  background: #fff8ed;
  border: 1px solid #efd7ad;
  border-radius: 8px;
}
@media (max-width: 900px) {
  .audit-filters {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 600px) {
  .audit-filters {
    grid-template-columns: 1fr;
  }
}
</style>
