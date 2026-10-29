<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, RouterLink } from 'vue-router'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
import { IdentityError, read, write } from '../../api/identity'
import type { Page } from '../../api/platform'
import type { Permission } from '../../api/tenant'
type Entry = Permission & { source: 'BUILTIN' | 'PUBLISHED' | 'MANUAL'; can_edit: boolean }
type Details = {
  permission: Entry
  catalog_version: number
  role_count: number
  user_count: number
}
type RoleReference = {
  id: string
  name: string
  tenant_id: string
  tenant_name: string
  status: string
  data_scope: string
}
const context = inject(accountContext),
  owner = computed(() => context?.())
const readable = computed(() => !!owner.value && can(owner.value.me, 'platform:permission:read'))
const manageable = computed(
  () => !!owner.value && can(owner.value.me, 'platform:permission:manage'),
)
const rows = ref<Entry[]>([]),
  services = ref<{ service: string; audience: string }[]>([])
const query = ref(''),
  service = ref(''),
  source = ref(''),
  status = ref(''),
  version = ref(0),
  page = ref(0),
  more = ref(false)
const pending = ref(false),
  loading = ref(false),
  error = ref(''),
  success = ref(''),
  blocked = ref(false)
const dialog = ref<HTMLDialogElement>(),
  details = ref<Details | null>(null),
  roleRows = ref<RoleReference[]>([]),
  rolePage = ref(0),
  roleMore = ref(false)
const draft = reactive({
  code: '',
  name: '',
  owner_service: '',
  group_code: '',
  group_name: '',
  resource_code: '',
  resource_name: '',
  action_code: '',
  oauth_scope: '',
  description: '',
  sort: 0,
  status: 'ACTIVE',
  supported_data_scopes: [] as string[],
})
const fields = [
  { key: 'code', label: '权限编码', hint: '例如 order:read', semantic: true, max: 160 },
  { key: 'name', label: '权限名称', hint: '例如 查看订单', semantic: false, max: 128 },
  { key: 'group_code', label: '分组编码', hint: '例如 order', semantic: false, max: 128 },
  { key: 'group_name', label: '分组名称', hint: '例如 订单管理', semantic: false, max: 128 },
  { key: 'resource_code', label: '资源编码', hint: '例如 order', semantic: true, max: 128 },
  { key: 'resource_name', label: '资源名称', hint: '例如 订单', semantic: false, max: 128 },
  { key: 'action_code', label: '动作编码', hint: '例如 read', semantic: true, max: 128 },
  { key: 'oauth_scope', label: 'OAuth scope', hint: '例如 orders.read', semantic: true, max: 160 },
] as const
const editable = computed(
  () => manageable.value && (!details.value || details.value.permission.can_edit),
)
let generation = 0,
  sequence = 0
const requestId = ref('')
function report(problem: unknown) {
  error.value = problem instanceof Error ? problem.message : '操作失败'
  owner.value?.failed(problem)
}
async function load(next = 0) {
  if (!readable.value) return
  const turn = ++sequence,
    epoch = generation
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({
      q: query.value,
      owner_service: service.value,
      source: source.value,
      status: status.value,
      page: String(next),
      size: '25',
    })
    const result = await read<Page<Entry> & { catalog_version: number }>(
      'platform',
      `/platform/permissions?${params}`,
    )
    if (turn !== sequence || epoch !== generation) return
    rows.value = result.items
    version.value = result.catalog_version
    page.value = next
    more.value = result.has_more
  } catch (problem) {
    if (epoch === generation) report(problem)
  } finally {
    if (epoch === generation && turn === sequence) loading.value = false
  }
}
async function references(next = 0) {
  if (!details.value) return
  const result = await read<Page<RoleReference>>(
    'platform',
    `/platform/permissions/${encodeURIComponent(details.value.permission.code)}/roles?page=${next}&size=20`,
  )
  roleRows.value = result.items
  rolePage.value = next
  roleMore.value = result.has_more
}
async function loadReferences(next: number) {
  loading.value = true
  try {
    await references(next)
  } catch (problem) {
    report(problem)
  } finally {
    loading.value = false
  }
}
async function open(item?: Entry) {
  if (pending.value || loading.value) return
  details.value = null
  error.value = ''
  success.value = ''
  blocked.value = false
  roleRows.value = []
  loading.value = true
  try {
    if (item) {
      const result = await read<Details>(
        'platform',
        `/platform/permissions/${encodeURIComponent(item.code)}`,
      )
      details.value = result
      version.value = result.catalog_version
      Object.assign(draft, result.permission)
      draft.supported_data_scopes = [...result.permission.supported_data_scopes]
      await references()
    } else {
      Object.assign(draft, {
        code: '',
        name: '',
        owner_service: service.value,
        group_code: '',
        group_name: '',
        resource_code: '',
        resource_name: '',
        action_code: '',
        oauth_scope: '',
        description: '',
        sort: 0,
        status: 'ACTIVE',
        supported_data_scopes: [],
      })
    }
    requestId.value = crypto.randomUUID()
    await nextTick()
    dialog.value?.showModal()
  } catch (problem) {
    report(problem)
  } finally {
    loading.value = false
  }
}
async function save() {
  if (!editable.value || pending.value || blocked.value) return
  if (
    details.value?.permission.status === 'ACTIVE' &&
    draft.status === 'DISABLED' &&
    !window.confirm(
      `停用此权限？当前被 ${details.value.role_count} 个角色引用，影响 ${details.value.user_count} 个有效用户。`,
    )
  )
    return
  pending.value = true
  error.value = ''
  try {
    const body = {
      name: draft.name,
      group_code: draft.group_code,
      group_name: draft.group_name,
      resource_name: draft.resource_name,
      description: draft.description,
      sort: draft.sort,
      catalog_version: version.value,
    }
    if (details.value)
      await write(
        'platform',
        `/platform/permissions/${encodeURIComponent(draft.code)}`,
        { ...body, status: draft.status },
        'PUT',
      )
    else
      await write(
        'platform',
        '/platform/permissions',
        {
          ...body,
          code: draft.code,
          owner_service: draft.owner_service,
          resource_code: draft.resource_code,
          action_code: draft.action_code,
          oauth_scope: draft.oauth_scope,
          supported_data_scopes: draft.supported_data_scopes,
        },
        'POST',
        requestId.value,
      )
    dialog.value?.close()
    success.value = '权限已保存；请在角色中分配后供业务使用。'
    await load(page.value)
  } catch (problem) {
    report(problem)
    blocked.value =
      problem instanceof IdentityError && (problem.status === 409 || problem.uncertain)
  } finally {
    pending.value = false
  }
}
function leave() {
  return (
    !pending.value &&
    (!dialog.value?.open || !editable.value || window.confirm('放弃未保存的权限修改？'))
  )
}
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onBeforeUnmount(() => {
  generation++
  sequence++
})
onMounted(async () => {
  if (!readable.value) return
  await load()
  try {
    services.value = await read('platform', '/platform/service-audiences')
  } catch (problem) {
    report(problem)
  }
})
</script>
<template>
  <section class="card management-card">
    <div v-if="!readable" class="empty">没有权限目录读取权限。</div>
    <template v-else>
      <div class="section-heading">
        <div>
          <h2>权限目录</h2>
          <p class="muted">在项目中定义业务操作，分配给角色后，由业务接口按权限编码校验。</p>
        </div>
        <button
          v-if="manageable"
          class="primary-button"
          :disabled="pending || loading"
          @click="open()"
        >
          新建权限
        </button>
      </div>
      <p v-if="error && !dialog?.open" class="message error" role="alert">{{ error }}</p>
      <p v-if="success" class="message success" role="status">{{ success }}</p>
      <form class="filter-bar" @submit.prevent="load()">
        <input v-model="query" placeholder="搜索编码或名称" maxlength="160" /><select
          v-model="service"
          aria-label="所属服务"
        >
          <option value="">全部服务</option>
          <option value="identity-service">identity-service</option>
          <option v-for="item in services" :key="item.service" :value="item.service">
            {{ item.service }}
          </option></select
        ><select v-model="source" aria-label="权限来源">
          <option value="">全部来源</option>
          <option value="MANUAL">手工维护</option>
          <option value="PUBLISHED">服务发布</option>
          <option value="BUILTIN">系统内置</option></select
        ><select v-model="status" aria-label="权限状态">
          <option value="">全部状态</option>
          <option value="ACTIVE">启用</option>
          <option value="DISABLED">停用</option>
          <option value="DEPRECATED">废弃</option></select
        ><button class="secondary-button" :disabled="loading">查询</button>
      </form>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>权限</th>
              <th>所属服务</th>
              <th>分组 / 资源</th>
              <th>来源</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rows" :key="item.code">
              <td>
                <strong>{{ item.name }}</strong
                ><small class="muted">{{ item.code }}</small>
              </td>
              <td>{{ item.owner_service }}</td>
              <td>{{ item.group_name }} / {{ item.resource_name }}</td>
              <td>
                {{
                  { BUILTIN: '系统内置', PUBLISHED: '服务发布', MANUAL: '手工维护' }[item.source]
                }}
              </td>
              <td>{{ { ACTIVE: '启用', DISABLED: '停用', DEPRECATED: '废弃' }[item.status] }}</td>
              <td>
                <button class="text-button" :disabled="loading || pending" @click="open(item)">
                  {{ item.can_edit && manageable ? '编辑 / 引用' : '查看' }}
                </button>
              </td>
            </tr>
            <tr v-if="!loading && !rows.length">
              <td colspan="6" class="empty">没有符合条件的权限。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>第 {{ page + 1 }} 页</span
        ><button class="text-button" :disabled="loading || !page" @click="load(page - 1)">
          上一页</button
        ><button class="text-button" :disabled="loading || !more" @click="load(page + 1)">
          下一页
        </button>
      </div>
    </template>
    <dialog
      ref="dialog"
      class="management-dialog wide-dialog"
      aria-labelledby="catalog-dialog-title"
      @cancel.prevent="!pending && dialog?.close()"
    >
      <h2 id="catalog-dialog-title">{{ details ? '权限详情' : '新建业务权限' }}</h2>
      <p v-if="error" class="message error" role="alert">{{ error }}</p>
      <p v-if="blocked" class="muted">目录已更新或结果未确认，请关闭后重新读取。</p>
      <p v-if="details && !details.permission.can_edit" class="muted">
        {{
          details.permission.source === 'BUILTIN'
            ? '系统内置权限由框架维护。'
            : '此权限由服务发布，请修改该服务的权限目录后重新发布。'
        }}
      </p>
      <form @submit.prevent="save">
        <fieldset class="plain-fields" :disabled="pending || blocked || !editable">
          <label class="field"
            >所属服务<select v-model="draft.owner_service" required :disabled="!!details">
              <option value="" disabled>选择已注册业务服务</option>
              <option v-for="item in services" :key="item.service" :value="item.service">
                {{ item.service }}
              </option>
              <option v-if="draft.owner_service === 'identity-service'">identity-service</option>
            </select></label
          >
          <div class="form-grid">
            <label v-for="field in fields" :key="field.key" class="field"
              >{{ field.label
              }}<input
                v-model="draft[field.key]"
                :placeholder="field.hint"
                :maxlength="field.max"
                required
                :readonly="!!details && field.semantic"
            /></label>
          </div>
          <div class="field">
            <label>支持的数据范围</label>
            <div class="row-actions">
              <label
                ><input
                  v-model="draft.supported_data_scopes"
                  type="checkbox"
                  value="SELF"
                  :disabled="!!details"
                />
                本人 SELF</label
              ><label
                ><input
                  v-model="draft.supported_data_scopes"
                  type="checkbox"
                  value="TENANT"
                  :disabled="!!details"
                />
                全租户 TENANT</label
              >
            </div>
            <small class="muted"
              >不选择表示纯操作权限；具体数据过滤由业务服务实现。编码、服务、资源、动作和 scope
              创建后固定。</small
            >
          </div>
          <label class="field">说明<input v-model="draft.description" maxlength="1000" /></label>
          <div class="form-grid">
            <label class="field"
              >分组排序<input v-model.number="draft.sort" type="number" min="0" required /></label
            ><label v-if="details" class="field"
              >状态<select v-model="draft.status">
                <option value="ACTIVE">启用</option>
                <option value="DISABLED">停用</option>
                <option v-if="draft.status === 'DEPRECATED'" value="DEPRECATED">废弃</option>
              </select></label
            >
          </div>
        </fieldset>
        <template v-if="details"
          ><h3>授权引用</h3>
          <p class="muted">{{ details.role_count }} 个角色 · {{ details.user_count }} 个有效用户</p>
          <div
            v-for="role in roleRows"
            :key="`${role.tenant_id}-${role.id}`"
            class="reference-option"
          >
            <span>{{ role.tenant_name }} / {{ role.name }}</span
            ><RouterLink
              class="text-button"
              :to="`/platform/tenants/${role.tenant_id}/identity/roles/${role.id}`"
              >查看角色</RouterLink
            >
          </div>
          <div v-if="roleMore || rolePage" class="pagination">
            <button
              type="button"
              class="text-button"
              :disabled="loading || !rolePage"
              @click="loadReferences(rolePage - 1)"
            >
              上一页</button
            ><button
              type="button"
              class="text-button"
              :disabled="loading || !roleMore"
              @click="loadReferences(rolePage + 1)"
            >
              下一页
            </button>
          </div></template
        >
        <div class="form-actions">
          <button v-if="editable" class="primary-button" :disabled="pending || blocked">
            保存权限</button
          ><button
            type="button"
            class="secondary-button"
            :disabled="pending"
            @click="dialog?.close()"
          >
            关闭
          </button>
        </div>
      </form>
    </dialog>
  </section>
</template>
