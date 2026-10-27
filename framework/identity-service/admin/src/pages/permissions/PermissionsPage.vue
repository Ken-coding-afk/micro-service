<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
import { IdentityError, read, write } from '../../api/identity'
import type { Page } from '../../api/platform'
import type { Catalog, DataScope, Permission, Role, RolePermissions } from '../../api/tenant'
import { grantChanges, grantMap, scopeName } from './grants'
import { managementRestriction } from '../../api/tenant'

const context = inject(accountContext)
const route = useRoute()
const owner = computed(() => context?.() ?? null)
const readable = computed(
  () => owner.value?.realm === 'tenant' && can(owner.value.me, 'identity:permission:read'),
)
const roleReadable = computed(
  () => readable.value && owner.value && can(owner.value.me, 'identity:role:read'),
)
const manageable = computed(
  () => roleReadable.value && owner.value && can(owner.value.me, 'identity:role:manage'),
)
const tab = ref<'catalog' | 'role'>('catalog')
const catalog = ref<Catalog | null>(null)
const assignable = ref<Catalog | null>(null)
const roles = ref<Role[]>([])
const rolePage = ref(0)
const hasMore = ref(false)
const selected = ref('')
const baseline = ref<RolePermissions | null>(null)
const draft = ref<Record<string, DataScope>>({})
const reviewed = ref(false)
const stale = ref(false)
const loading = ref(false)
const pending = ref(false)
const error = ref('')
const success = ref('')
const query = ref('')
const service = ref('')
const selectedOnly = ref(false)
let generation = 0
let readSequence = 0

const original = computed(() => grantMap(baseline.value?.grants ?? []))
const changes = computed(() => grantChanges(original.value, draft.value))
const dirty = computed(() => changes.value.length > 0)
const editable = computed(() => manageable.value && baseline.value?.role.can_edit === true)
const options = computed(() =>
  tab.value === 'role' ? (assignable.value?.items ?? []) : (catalog.value?.items ?? []),
)
const services = computed(() =>
  [...new Set(options.value.map((item) => item.owner_service))].sort(),
)
const visible = computed(() =>
  options.value.filter(
    (item) =>
      (!service.value || item.owner_service === service.value) &&
      (!selectedOnly.value || Object.hasOwn(draft.value, item.code)) &&
      (!query.value.trim() ||
        `${item.code} ${item.name} ${item.group_name} ${item.resource_name} ${item.description}`
          .toLowerCase()
          .includes(query.value.trim().toLowerCase())),
  ),
)
const groups = computed(() => {
  const result = new Map<string, { title: string; items: Permission[] }>()
  for (const item of visible.value) {
    const key = `${item.owner_service}/${item.group_code}`
    if (!result.has(key))
      result.set(key, { title: `${item.owner_service} · ${item.group_name}`, items: [] })
    result.get(key)!.items.push(item)
  }
  return [...result.values()]
})
const invalid = computed(() =>
  Object.keys(draft.value).filter((code) => {
    const item = assignable.value?.items.find((value) => value.code === code)
    if (!item) return true
    const scope = draft.value[code]
    if (Object.hasOwn(original.value, code) && original.value[code] === scope) return false
    return (
      !item.can_assign ||
      (scope === null
        ? item.supported_data_scopes.length !== 0
        : !item.assignable_data_scopes.includes(scope))
    )
  }),
)
function resetReview() {
  reviewed.value = false
  success.value = ''
}
function report(problem: unknown) {
  error.value = problem instanceof IdentityError ? problem.message : '暂时无法读取权限。'
  owner.value?.failed(problem)
}
function discardAllowed() {
  return !dirty.value || window.confirm('当前授权草稿尚未保存，确认放弃修改？')
}
function clearRole() {
  baseline.value = null
  assignable.value = null
  draft.value = {}
  selected.value = ''
  stale.value = false
  resetReview()
}
async function loadCatalog() {
  if (!readable.value) return
  const epoch = generation,
    request = ++readSequence
  loading.value = true
  error.value = ''
  try {
    const value = await read<Catalog>('tenant', '/permissions')
    if (epoch === generation && request === readSequence) catalog.value = value
  } catch (problem) {
    if (epoch === generation && request === readSequence) report(problem)
  } finally {
    if (epoch === generation && request === readSequence) loading.value = false
  }
}
async function loadRoles(page = 0) {
  if (!roleReadable.value || pending.value || !discardAllowed()) return
  const epoch = generation,
    request = ++readSequence
  loading.value = true
  error.value = ''
  clearRole()
  try {
    const value = await read<Page<Role>>('tenant', `/roles?page=${page}&size=20`)
    if (epoch !== generation || request !== readSequence) return
    roles.value = value.items
    rolePage.value = value.page
    hasMore.value = value.has_more
  } catch (problem) {
    if (epoch === generation && request === readSequence) report(problem)
  } finally {
    if (epoch === generation && request === readSequence) loading.value = false
  }
}
async function switchTab(value: 'catalog' | 'role') {
  if (pending.value || loading.value || value === tab.value || !discardAllowed()) return
  generation++
  clearRole()
  tab.value = value
  query.value = ''
  service.value = ''
  selectedOnly.value = false
  if (value === 'role') await loadRoles()
  else await loadCatalog()
}
async function choose(event: Event) {
  const select = event.target as HTMLSelectElement
  if (pending.value || loading.value || !discardAllowed()) {
    select.value = selected.value
    return
  }
  selected.value = select.value
  baseline.value = null
  assignable.value = null
  draft.value = {}
  stale.value = false
  resetReview()
  await reload(false)
}
async function reload(keepDraft: boolean) {
  if (!selected.value || pending.value || loading.value) return
  const epoch = generation,
    request = ++readSequence
  loading.value = true
  error.value = ''
  reviewed.value = false
  try {
    const id = encodeURIComponent(selected.value)
    const snapshot = await read<RolePermissions>('tenant', `/role-permissions/${id}`)
    const directory =
      snapshot.role.can_edit && manageable.value
        ? await read<Catalog>('tenant', `/permissions?assignable=true&role_id=${id}`)
        : await read<Catalog>('tenant', '/permissions')
    if (epoch !== generation || request !== readSequence) return
    if (
      snapshot.role.id !== selected.value ||
      snapshot.catalog_version !== directory.catalog_version
    )
      throw new IdentityError(409, 'ROLE_OR_CATALOG_VERSION_CONFLICT')
    baseline.value = snapshot
    assignable.value = directory
    if (!keepDraft) draft.value = grantMap(snapshot.grants)
    stale.value = false
    service.value = ''
  } catch (problem) {
    if (epoch === generation && request === readSequence) {
      stale.value = true
      report(problem)
    }
  } finally {
    if (epoch === generation && request === readSequence) loading.value = false
  }
}
function toggle(item: Permission, checked: boolean) {
  if (!editable.value || pending.value || stale.value) return
  if (!checked) delete draft.value[item.code]
  else if (
    item.can_assign &&
    (!item.supported_data_scopes.length || item.assignable_data_scopes.length)
  )
    draft.value[item.code] = item.supported_data_scopes.length
      ? item.assignable_data_scopes.includes('SELF')
        ? 'SELF'
        : (item.assignable_data_scopes[0] ?? null)
      : null
  resetReview()
}
function allVisible() {
  if (!editable.value || pending.value || stale.value) return
  for (const item of visible.value) if (!Object.hasOwn(draft.value, item.code)) toggle(item, true)
}
function removeVisible() {
  if (!editable.value || pending.value || stale.value) return
  for (const item of visible.value) delete draft.value[item.code]
  resetReview()
}
function removeInvalid(code: string) {
  if (!pending.value) {
    delete draft.value[code]
    resetReview()
  }
}
async function save() {
  if (
    !editable.value ||
    !baseline.value ||
    pending.value ||
    loading.value ||
    stale.value ||
    !reviewed.value ||
    !dirty.value ||
    invalid.value.length
  )
    return
  const epoch = generation
  pending.value = true
  error.value = ''
  success.value = ''
  const id = baseline.value.role.id
  try {
    await write(
      'tenant',
      `/role-permissions/${encodeURIComponent(id)}`,
      {
        role_version: baseline.value.role.version,
        catalog_version: baseline.value.catalog_version,
        grants: Object.entries(draft.value)
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([permission_code, data_scope]) => ({ permission_code, data_scope })),
      },
      'PUT',
    )
    if (epoch !== generation) return
    // 成功响应后仍重新读取；读取失败时保留草稿并要求核对，不能再次使用旧版本写入。
    stale.value = true
    pending.value = false
    await reload(false)
    if (!stale.value) success.value = '角色授权已保存并重新读取。'
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
onBeforeRouteLeave(() => !pending.value && discardAllowed())
onBeforeUnmount(() => {
  generation++
  clearRole()
})
watch(readable, (allowed) => {
  if (!allowed) {
    generation++
    clearRole()
    catalog.value = null
    roles.value = []
    loading.value = false
    pending.value = false
  }
})
onMounted(async () => {
  const requested = route.query.role
  const epoch = generation
  if (
    typeof requested === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requested) &&
    roleReadable.value
  ) {
    tab.value = 'role'
    await loadRoles()
    if (epoch !== generation) return
    selected.value = requested
    await reload(false)
  } else await loadCatalog()
})
</script>

<template>
  <section class="card management-card">
    <div v-if="!readable" class="empty" role="alert">当前账号无权读取权限目录。</div>
    <template v-else>
      <div class="section-heading">
        <div>
          <h2>资源操作与授权</h2>
          <p class="muted">目录由所属服务发布，新增目录不会自动授予任何角色。</p>
        </div>
      </div>
      <div class="permission-tabs" role="tablist" aria-label="权限视图">
        <button
          role="tab"
          :aria-selected="tab === 'catalog'"
          class="secondary-button"
          :disabled="pending || loading"
          @click="switchTab('catalog')"
        >
          权限目录</button
        ><button
          v-if="roleReadable"
          role="tab"
          :aria-selected="tab === 'role'"
          class="secondary-button"
          :disabled="pending || loading"
          @click="switchTab('role')"
        >
          角色授权
        </button>
      </div>
      <div v-if="error" class="message error" role="alert">{{ error }}</div>
      <div v-if="success" class="message success" role="status">{{ success }}</div>
      <template v-if="tab === 'role'">
        <div class="role-picker">
          <div class="field">
            <label for="authorization-role">选择角色</label
            ><select
              id="authorization-role"
              :value="selected"
              :disabled="pending || loading"
              @change="choose"
            >
              <option value="">请选择当前可见角色</option>
              <option
                v-if="baseline && !roles.some((item) => item.id === baseline!.role.id)"
                :value="baseline.role.id"
              >
                {{ baseline.role.name }} · {{ baseline.role.code }}
              </option>
              <option v-for="role in roles" :key="role.id" :value="role.id">
                {{ role.name }} · {{ role.code }}{{ role.protected ? ' · 受保护' : '' }}
              </option>
            </select>
          </div>
          <div class="pagination">
            <span>角色第 {{ rolePage + 1 }} 页</span
            ><button
              class="secondary-button"
              :disabled="pending || loading || rolePage === 0"
              @click="loadRoles(rolePage - 1)"
            >
              上一页角色</button
            ><button
              class="secondary-button"
              :disabled="pending || loading || !hasMore || rolePage >= 10000"
              @click="loadRoles(rolePage + 1)"
            >
              下一页角色
            </button>
          </div>
        </div>
        <div v-if="baseline" class="identity-strip">
          <div>
            <span>当前角色</span><strong>{{ baseline.role.name }}</strong>
          </div>
          <div>
            <span>版本 / 目录</span
            ><strong>{{ baseline.role.version }} / {{ baseline.catalog_version }}</strong>
          </div>
          <div>
            <span>管理边界</span><strong>{{ editable ? '允许编辑' : '只读' }}</strong
            ><small v-if="!editable" class="muted">{{
              baseline.role.protected
                ? '受保护管理员角色禁止普通编辑'
                : baseline.role.restrictions.map(managementRestriction).join('、')
            }}</small>
          </div>
        </div>
        <div v-if="stale" class="conflict-panel" role="alert">
          <strong>提交前必须重新核对当前版本</strong>
          <p>草稿仍保留，系统不会自动覆盖。重新读取后仍须预览差异并手动确认。</p>
          <button class="secondary-button" :disabled="pending || loading" @click="reload(true)">
            保留草稿并重新核对</button
          ><button
            class="secondary-button"
            :disabled="pending || loading"
            @click="discardAllowed() && reload(false)"
          >
            放弃草稿并读取当前授权
          </button>
        </div>
      </template>
      <p v-if="tab === 'catalog' && catalog" class="muted">
        目录版本 {{ catalog.catalog_version }} · 只读目录不授予当前账号业务权限。
      </p>
      <div v-if="tab === 'catalog' || baseline" class="catalog-filters">
        <div class="field">
          <label for="permission-query">搜索资源、操作或说明</label
          ><input id="permission-query" v-model="query" maxlength="100" />
        </div>
        <div class="field">
          <label for="permission-service">所属服务</label
          ><select id="permission-service" v-model="service">
            <option value="">全部服务</option>
            <option v-for="name in services" :key="name" :value="name">{{ name }}</option>
          </select>
        </div>
        <label v-if="tab === 'role'" class="check-label"
          ><input v-model="selectedOnly" type="checkbox" />只看已授权</label
        >
      </div>
      <div v-if="loading" class="loading" role="status">正在读取当前权限快照…</div>
      <template v-if="tab === 'catalog' || baseline">
        <div v-if="tab === 'role' && editable" class="form-actions">
          <button
            class="secondary-button"
            :disabled="pending || loading || stale"
            @click="allVisible"
          >
            选择当前可授项</button
          ><button
            class="secondary-button"
            :disabled="pending || loading || stale"
            @click="removeVisible"
          >
            移除当前筛选授权
          </button>
        </div>
        <section v-for="group in groups" :key="group.title" class="permission-group">
          <h3>{{ group.title }}</h3>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th v-if="tab === 'role'" scope="col">授权</th>
                  <th scope="col">资源 / 操作</th>
                  <th scope="col">编码与说明</th>
                  <th scope="col">状态 / scope</th>
                  <th scope="col">数据范围</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in group.items" :key="item.code">
                  <td v-if="tab === 'role'">
                    <input
                      type="checkbox"
                      :aria-label="`授权 ${item.code}`"
                      :checked="Object.hasOwn(draft, item.code)"
                      :disabled="
                        !editable ||
                        pending ||
                        loading ||
                        stale ||
                        (!item.can_assign && !Object.hasOwn(draft, item.code))
                      "
                      @change="toggle(item, ($event.target as HTMLInputElement).checked)"
                    />
                  </td>
                  <td>
                    {{ item.resource_name }}<br />{{ item.name }}
                    <small class="muted">{{ item.action_code }}</small>
                  </td>
                  <td>
                    <code>{{ item.code }}</code>
                    <p class="muted">{{ item.description }}</p>
                  </td>
                  <td>
                    {{
                      item.status === 'ACTIVE'
                        ? '有效'
                        : item.status === 'DEPRECATED'
                          ? '已弃用'
                          : '已停用'
                    }}<br /><code>{{ item.oauth_scope }}</code>
                  </td>
                  <td>
                    <template v-if="tab === 'role' && Object.hasOwn(draft, item.code)"
                      ><select
                        v-if="editable && item.can_assign && item.supported_data_scopes.length"
                        v-model="draft[item.code]"
                        :aria-label="`数据范围 ${item.code}`"
                        :disabled="!editable || pending || loading || stale || !item.can_assign"
                        @change="resetReview"
                      >
                        <option
                          v-for="scope in item.assignable_data_scopes"
                          :key="scope"
                          :value="scope"
                        >
                          {{ scopeName(scope) }}
                        </option></select
                      ><span v-else>{{ scopeName(draft[item.code]) }}</span></template
                    ><span v-else>{{
                      item.supported_data_scopes.map(scopeName).join(' / ') || '不适用'
                    }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <div v-if="!loading && !groups.length" class="empty">暂无符合筛选条件的权限。</div>
        <div v-if="invalid.length" class="message error" role="alert">
          部分草稿已超出当前可授范围，须先移除：
          <div v-for="code in invalid" :key="code">
            <code>{{ code }}</code
            ><button class="text-button" :disabled="pending" @click="removeInvalid(code)">
              移除此草稿项
            </button>
          </div>
        </div>
        <template v-if="tab === 'role' && editable"
          ><div class="form-actions">
            <button
              class="secondary-button"
              :disabled="pending || loading || stale || !dirty || !!invalid.length"
              @click="reviewed = true"
            >
              预览授权差异</button
            ><span class="muted">{{ changes.length }} 项变化；新增权限默认未选。</span>
          </div>
          <section v-if="reviewed" class="conflict-panel" aria-label="授权差异预览">
            <h3>请确认本次授权变化</h3>
            <p>范围扩大可能增加数据访问。最终保存仍由服务端重查角色、成员、目录及管理委派。</p>
            <ul>
              <li v-for="change in changes" :key="change.code">
                {{ change.kind }} · {{ change.code }} · {{ scopeName(change.before) }} →
                {{ scopeName(change.after) }}
              </li>
            </ul>
            <button
              class="primary-button"
              :disabled="pending || loading || stale || !!invalid.length || !dirty"
              @click="save"
            >
              {{ pending ? '正在保存…' : '确认保存授权' }}
            </button>
          </section>
        </template>
      </template>
    </template>
  </section>
</template>
