<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
import { IdentityError, read, write } from '../../api/identity'

interface Session {
  id: string
  user_id: string
  username: string
  display_name: string
  created_at: string
  last_activity_at: string
  expires_at: string
  revoked_at: string | null
  active: boolean
  applications: string[]
}
interface Page {
  page: number
  size: number
  has_more: boolean
  items: Session[]
}
const route = useRoute(),
  context = inject(accountContext)
const owner = computed(() => context?.() ?? null)
const readable = computed(
  () =>
    owner.value?.realm === 'tenant' &&
    owner.value.me.principal_type === 'user' &&
    can(owner.value.me, 'identity:session:read'),
)
const manageable = computed(
  () => readable.value && owner.value && can(owner.value.me, 'identity:session:manage'),
)
const identity = computed(
  () => `${owner.value?.realm}/${owner.value?.me.tenant_id}/${owner.value?.me.sub}`,
)
const rows = ref<Session[]>([]),
  page = ref(0),
  more = ref(false),
  loading = ref(false),
  pending = ref(false)
const query = ref(''),
  applied = ref(''),
  error = ref(''),
  success = ref(''),
  uncertain = ref(false)
let generation = 0,
  sequence = 0
function time(value: string | null) {
  return value && Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString('zh-CN') : '—'
}
function report(problem: unknown) {
  error.value = problem instanceof IdentityError ? problem.message : '暂时无法完成会话操作。'
  owner.value?.failed(problem)
}
async function load(target = page.value, user = applied.value) {
  if (!readable.value) return
  const epoch = generation,
    request = ++sequence
  loading.value = true
  error.value = ''
  rows.value = []
  more.value = false
  try {
    const value = await read<Page>(
      'tenant',
      `/sessions?page=${target}&size=20${user ? '&user_id=' + encodeURIComponent(user) : ''}`,
    )
    if (epoch !== generation || request !== sequence) return
    rows.value = value.items
    page.value = value.page
    more.value = value.has_more
    applied.value = user
    uncertain.value = false
  } catch (problem) {
    if (epoch === generation && request === sequence) report(problem)
  } finally {
    if (epoch === generation && request === sequence) loading.value = false
  }
}
async function search() {
  if (pending.value) return
  const user = query.value.trim()
  if (user && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(user)) {
    error.value = '用户 ID 必须是完整 UUID，也可清空后查询全部可见会话。'
    return
  }
  success.value = ''
  await load(0, user)
}
async function act(kind: 'one' | 'all', row: Session) {
  if (!manageable.value || pending.value || loading.value || uncertain.value || !row.active) return
  const self = row.user_id === owner.value?.me.sub
  const message =
    kind === 'one'
      ? `确认撤销 ${row.username} 的此登录会话及其关联应用授权？`
      : `确认让 ${row.username} 的全部登录会话与关联应用授权退出？`
  if (!window.confirm(message + (self ? '这可能使当前管理入口退出。' : '其他账号不受影响。')))
    return
  const epoch = generation
  pending.value = true
  error.value = ''
  success.value = ''
  try {
    await write(
      'tenant',
      kind === 'one'
        ? `/sessions/${encodeURIComponent(row.id)}:revoke`
        : `/users/${encodeURIComponent(row.user_id)}:sign-out-all`,
    )
    if (epoch !== generation) return
    success.value = kind === 'one' ? '指定会话撤销已完成。' : '该用户全端退出已完成。'
    await load()
  } catch (problem) {
    if (epoch !== generation) return
    if (problem instanceof IdentityError && problem.uncertain) uncertain.value = true
    report(problem)
  } finally {
    if (epoch === generation) pending.value = false
  }
}
function fromRoute() {
  query.value = typeof route.query.user_id === 'string' ? route.query.user_id : ''
  void search()
}
watch(() => route.query.user_id, fromRoute)
watch([identity, readable], () => {
  generation++
  sequence++
  rows.value = []
  more.value = false
  pending.value = false
  loading.value = false
  uncertain.value = false
  success.value = ''
  error.value = ''
  applied.value = ''
  if (readable.value) fromRoute()
})
onBeforeRouteLeave(() => !pending.value)
onBeforeRouteUpdate(() => !pending.value)
onBeforeUnmount(() => {
  generation++
  sequence++
})
onMounted(fromRoute)
</script>

<template>
  <section class="card management-card">
    <div v-if="!readable" class="empty" role="alert">
      当前账号无权读取租户会话。平台账号可在个人账号页管理本人会话。
    </div>
    <template v-else>
      <div class="section-heading">
        <div>
          <h2>登录会话</h2>
          <p class="muted">只显示当前组织与当前管理范围内的会话，撤销同时处理关联应用授权。</p>
        </div>
        <button class="secondary-button" :disabled="loading || pending" @click="load()">
          重新读取会话
        </button>
      </div>
      <form class="search-form" @submit.prevent="search">
        <label class="sr-only" for="managed-session-user">筛选用户 ID</label
        ><input
          id="managed-session-user"
          v-model="query"
          maxlength="36"
          placeholder="用户 UUID，留空查看全部可见会话"
          :disabled="loading || pending"
        /><button class="secondary-button" :disabled="loading || pending">查询会话</button>
      </form>
      <div v-if="uncertain" class="session-warning" role="alert">
        操作结果尚未确认，已暂停后续撤销。请重新读取当前会话状态核对，系统不会自动重发。
      </div>
      <div v-if="error" class="message error" role="alert">{{ error }}</div>
      <div v-if="success" class="message success" role="status">{{ success }}</div>
      <div v-if="!manageable" class="session-note">当前仅有读取权限，不能撤销会话。</div>
      <div v-if="loading" class="loading" role="status">正在读取会话…</div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>账号 / 会话</th>
              <th>登录 / 最近活动</th>
              <th>到期 / 撤销</th>
              <th>当前状态 / 应用</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td class="session-id">
                {{ row.display_name }} · {{ row.username }}<br /><code>{{ row.id }}</code>
              </td>
              <td>
                {{ time(row.created_at) }}<br /><span class="muted">{{
                  time(row.last_activity_at)
                }}</span>
              </td>
              <td>
                {{ time(row.expires_at) }}<br /><span class="muted">{{
                  time(row.revoked_at)
                }}</span>
              </td>
              <td>
                {{ row.active ? '有效' : row.revoked_at ? '已撤销' : '当前不可用'
                }}<small class="muted">{{
                  row.applications.length ? row.applications.join('、') : '无关联应用'
                }}</small>
              </td>
              <td class="row-actions">
                <template v-if="manageable"
                  ><button
                    class="text-button"
                    :disabled="pending || loading || uncertain || !row.active"
                    @click="act('one', row)"
                  >
                    撤销会话</button
                  ><button
                    class="text-button"
                    :disabled="pending || loading || uncertain || !row.active"
                    @click="act('all', row)"
                  >
                    用户全端退出
                  </button></template
                ><span v-else class="muted">只读</span>
              </td>
            </tr>
            <tr v-if="!loading && !error && rows.length === 0">
              <td colspan="5" class="empty">没有符合条件的可见会话。</td>
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
          :disabled="loading || pending || !more || page >= 10000"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.search-form {
  margin-bottom: 24px;
  max-width: 560px;
}
.session-id {
  max-width: 250px;
  overflow-wrap: anywhere;
}
.session-warning,
.session-note {
  margin: 20px 0;
  padding: 13px 16px;
  border-radius: 8px;
}
.session-warning {
  background: #fff8ed;
  border: 1px solid #efd7ad;
}
.session-note {
  background: #f0f5fd;
  border: 1px solid #d5e2f6;
}
</style>
