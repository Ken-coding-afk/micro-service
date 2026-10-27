<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, RouterLink } from 'vue-router'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
import { IdentityError, read, write } from '../../api/identity'
import type { Role, RolePage, RolePermissions } from '../../api/tenant'
import { managementRestriction } from '../../api/tenant'
import RoleMembersDialog from '../../components/RoleMembersDialog.vue'

const context = inject(accountContext)
const owner = computed(() => context?.() ?? null)
const readable = computed(
  () => owner.value?.realm === 'tenant' && can(owner.value.me, 'identity:role:read'),
)
const manageable = computed(
  () => readable.value && owner.value && can(owner.value.me, 'identity:role:manage'),
)
const rows = ref<Role[]>([])
const members = ref<Role | null>(null)
const canCreate = ref(false)
const page = ref(0),
  hasMore = ref(false),
  query = ref('')
const loading = ref(false),
  pending = ref(false)
const error = ref(''),
  modalError = ref(''),
  success = ref('')
const dialog = ref<HTMLDialogElement | null>(null)
const mode = ref<'create' | 'edit' | 'copy' | 'delete'>('create')
const source = ref<RolePermissions | null>(null)
const conflict = ref<RolePermissions | null>(null)
const code = ref(''),
  name = ref(''),
  description = ref(''),
  status = ref<'ACTIVE' | 'DISABLED'>('ACTIVE')
const descriptionTooLong = computed(() => Array.from(description.value).length > 1024)
const uncertain = ref(false)
let generation = 0,
  readSequence = 0

function report(problem: unknown, modal = false) {
  const text = problem instanceof IdentityError ? problem.message : '暂时无法完成角色操作。'
  if (modal) modalError.value = text
  else error.value = text
  owner.value?.failed(problem)
}
async function load(targetPage = page.value) {
  if (!readable.value) return
  const epoch = generation,
    sequence = ++readSequence
  loading.value = true
  error.value = ''
  try {
    const value = await read<RolePage>(
      'tenant',
      `/roles?page=${targetPage}&size=20&q=${encodeURIComponent(query.value.trim())}`,
    )
    if (epoch !== generation || sequence !== readSequence) return
    rows.value = value.items
    canCreate.value = value.can_create === true
    page.value = value.page
    hasMore.value = value.has_more
  } catch (problem) {
    if (epoch === generation && sequence === readSequence) report(problem)
  } finally {
    if (epoch === generation && sequence === readSequence) loading.value = false
  }
}
function reset() {
  source.value = null
  conflict.value = null
  code.value = ''
  name.value = ''
  description.value = ''
  status.value = 'ACTIVE'
  uncertain.value = false
  modalError.value = ''
}
function close() {
  if (pending.value) return
  dialog.value?.close()
  reset()
}
async function create() {
  if (!manageable.value || !canCreate.value || pending.value) return
  reset()
  mode.value = 'create'
  await nextTick()
  dialog.value?.showModal()
}
async function open(row: Role, action: 'edit' | 'copy' | 'delete') {
  if (!manageable.value || !row.can_manage || pending.value) return
  reset()
  mode.value = action
  const epoch = generation
  pending.value = true
  await nextTick()
  dialog.value?.showModal()
  try {
    const current = await read<RolePermissions>(
      'tenant',
      `/role-permissions/${encodeURIComponent(row.id)}`,
    )
    if (epoch !== generation) return
    if (!current.role.can_manage || current.role.protected)
      throw new IdentityError(403, 'MANAGEMENT_NOT_ALLOWED')
    source.value = current
    code.value = action === 'copy' ? '' : current.role.code
    name.value = action === 'copy' ? current.role.name + '副本' : current.role.name
    description.value = current.role.description ?? ''
    status.value = current.role.status
  } catch (problem) {
    if (epoch === generation) report(problem, true)
  } finally {
    if (epoch === generation) pending.value = false
  }
}
function resolve(keep: boolean) {
  if (!conflict.value) return
  source.value = conflict.value
  if (!keep) {
    if (mode.value !== 'copy') code.value = conflict.value.role.code
    name.value = conflict.value.role.name
    description.value = conflict.value.role.description ?? ''
    status.value = conflict.value.role.status
  }
  conflict.value = null
  modalError.value = ''
}
async function lookup() {
  if (pending.value) return
  const identifier = code.value
  close()
  query.value = identifier
  await load(0)
}
async function submit() {
  if (
    !manageable.value ||
    pending.value ||
    conflict.value ||
    uncertain.value ||
    descriptionTooLong.value
  )
    return
  if (mode.value === 'create' ? !canCreate.value : !source.value?.role.can_manage) return
  if (
    mode.value === 'delete' &&
    !window.confirm('确认永久删除此空角色及其权限关系？有成员或委派引用时，服务端会拒绝删除。')
  )
    return
  if (
    mode.value === 'edit' &&
    source.value?.role.status !== status.value &&
    !window.confirm('变更角色状态将改变现有成员的授权，确认继续？')
  )
    return
  const epoch = generation
  pending.value = true
  modalError.value = ''
  success.value = ''
  const action = mode.value
  try {
    if (action === 'create')
      await write('tenant', '/roles', {
        code: code.value.trim(),
        name: name.value,
        description: description.value,
      })
    else {
      const current = source.value
      if (!current) return
      if (action === 'delete')
        await write(
          'tenant',
          `/roles/${encodeURIComponent(current.role.id)}`,
          { version: current.role.version },
          'DELETE',
        )
      else if (action === 'copy')
        await write('tenant', `/roles/${encodeURIComponent(current.role.id)}:copy`, {
          role_version: current.role.version,
          catalog_version: current.catalog_version,
          code: code.value.trim(),
          name: name.value,
          description: description.value,
        })
      else
        await write(
          'tenant',
          `/roles/${encodeURIComponent(current.role.id)}`,
          {
            version: current.role.version,
            name: name.value,
            status: status.value,
            description: description.value,
          },
          'PUT',
        )
    }
    if (epoch !== generation) return
    const identifier = code.value.trim()
    dialog.value?.close()
    reset()
    success.value =
      action === 'create'
        ? '角色已创建。'
        : action === 'delete'
          ? '角色已删除。'
          : action === 'copy'
            ? '角色已复制，授权和状态沿用已确认源角色。'
            : '角色资料已更新。'
    query.value = identifier
    await load(0)
  } catch (problem) {
    if (epoch !== generation) return
    if (problem instanceof IdentityError && problem.uncertain) uncertain.value = true
    if (
      problem instanceof IdentityError &&
      problem.status === 409 &&
      source.value &&
      problem.code.includes('VERSION_CONFLICT')
    ) {
      try {
        const current = await read<RolePermissions>(
          'tenant',
          `/role-permissions/${encodeURIComponent(source.value.role.id)}`,
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
onBeforeRouteLeave(() => !pending.value)
onBeforeUnmount(() => {
  generation++
  reset()
})
watch(readable, (value) => {
  if (!value) {
    generation++
    dialog.value?.close()
    reset()
    rows.value = []
    canCreate.value = false
    pending.value = false
    loading.value = false
  }
})
onMounted(() => load(0))
</script>

<template>
  <section class="card management-card">
    <div v-if="!readable" class="empty" role="alert">当前账号无权读取角色。</div>
    <template v-else>
      <div class="section-heading">
        <div>
          <h2>角色列表</h2>
          <p class="muted">角色归属当前组织，创建和维护角色资料由受保护租户管理员执行。</p>
        </div>
        <button
          v-if="manageable && canCreate"
          class="primary-button"
          :disabled="loading || pending"
          @click="create"
        >
          新建角色
        </button>
      </div>
      <div v-if="error" class="message error" role="alert">{{ error }}</div>
      <div v-if="success" class="message success" role="status">{{ success }}</div>
      <form class="search-form" @submit.prevent="load(0)">
        <label class="sr-only" for="role-query">搜索角色编码或名称</label
        ><input
          id="role-query"
          v-model="query"
          maxlength="100"
          placeholder="搜索角色编码或名称"
        /><button class="secondary-button" :disabled="pending || loading">查询</button>
      </form>
      <div v-if="loading" class="loading" role="status">正在读取角色…</div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">角色</th>
              <th scope="col">状态</th>
              <th scope="col">版本 / 授权版本</th>
              <th scope="col">管理边界</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>
                {{ row.name }}<br /><code>{{ row.code }}</code>
                <p v-if="row.description" class="muted role-description">{{ row.description }}</p>
              </td>
              <td>{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</td>
              <td>{{ row.version }} / {{ row.authorization_version }}</td>
              <td>
                {{
                  row.protected
                    ? '受保护管理员角色'
                    : row.can_manage
                      ? '可维护资料'
                      : row.can_edit
                        ? '可编辑授权'
                        : '只读 / 受限'
                }}<small v-if="row.restrictions.length" class="muted">{{
                  row.restrictions.map(managementRestriction).join('、')
                }}</small>
              </td>
              <td class="row-actions">
                <button
                  v-if="owner && can(owner.me, 'identity:user:read')"
                  class="text-button"
                  :disabled="pending"
                  @click="members = row"
                >
                  查看成员
                </button>
                <template v-if="manageable && row.can_manage"
                  ><button class="text-button" :disabled="pending" @click="open(row, 'edit')">
                    编辑资料</button
                  ><button class="text-button" :disabled="pending" @click="open(row, 'copy')">
                    复制角色</button
                  ><button
                    class="text-button danger"
                    :disabled="pending"
                    @click="open(row, 'delete')"
                  >
                    删除角色
                  </button></template
                ><RouterLink
                  v-if="can(owner!.me, 'identity:permission:read')"
                  :to="{ path: '/permissions', query: { role: row.id } }"
                  class="text-button"
                  >查看授权</RouterLink
                >
              </td>
            </tr>
            <tr v-if="!loading && !rows.length">
              <td colspan="5" class="empty">暂无符合条件的角色。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>第 {{ page + 1 }} 页</span
        ><button
          class="secondary-button"
          :disabled="pending || loading || page === 0"
          @click="load(page - 1)"
        >
          上一页</button
        ><button
          class="secondary-button"
          :disabled="pending || loading || !hasMore || page >= 10000"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </div>
    </template>
    <dialog
      ref="dialog"
      class="management-dialog"
      aria-labelledby="role-dialog-title"
      @cancel.prevent="close"
    >
      <h2 id="role-dialog-title">
        {{
          mode === 'create'
            ? '新建角色'
            : mode === 'copy'
              ? '复制角色'
              : mode === 'delete'
                ? '删除角色'
                : '编辑角色资料'
        }}
      </h2>
      <div v-if="modalError" class="message error" role="alert">{{ modalError }}</div>
      <p v-if="mode === 'copy' && source" class="muted">
        源角色 {{ source.role.name }} · 版本 {{ source.role.version }} / 目录
        {{ source.catalog_version }}，复制权限与启停状态，不复制受保护能力。
      </p>
      <p v-if="uncertain" class="credential-warning">
        结果尚未确认，不能直接重发。请查询角色列表核对当前状态，确认前不要重复执行本次操作。
      </p>
      <form @submit.prevent="submit">
        <div v-if="mode === 'delete' && source" class="conflict-panel">
          <strong>{{ source.role.name }} · {{ source.role.code }}</strong>
          <p>
            当前版本 {{ source.role.version }}，删除将清理此角色的
            {{ source.grants.length }} 项权限关系。
          </p>
          <p>
            仅可删除没有成员且无管理委派引用的普通角色。受保护角色禁止删除；委派历史不会被清除。
          </p>
        </div>
        <fieldset v-if="mode !== 'delete'" class="plain-fields" :disabled="pending || uncertain">
          <div class="field">
            <label for="role-code">角色编码</label
            ><input
              id="role-code"
              v-model="code"
              :readonly="mode === 'edit'"
              required
              pattern="[a-z][a-z0-9-]{0,127}"
              maxlength="128"
            />
          </div>
          <div class="field">
            <label for="role-name">角色名称</label
            ><input id="role-name" v-model="name" required maxlength="128" />
          </div>
          <div class="field">
            <label for="role-description">角色说明（可选）</label
            ><textarea
              id="role-description"
              v-model="description"
              maxlength="2048"
              rows="4"
            /><small :class="descriptionTooLong ? 'credential-warning' : 'muted'"
              >{{ Array.from(description).length }} /
              1024，纯文本，可换行。说明变化不扩大授权。</small
            >
          </div>
          <div v-if="mode === 'edit'" class="field">
            <label for="role-status">状态</label
            ><select id="role-status" v-model="status">
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
          </div>
        </fieldset>
        <div v-if="conflict" class="conflict-panel" role="status">
          <strong>角色或目录已变化</strong>
          <p>
            当前：{{ conflict.role.name }} · {{ conflict.role.status }} · 版本
            {{ conflict.role.version }} / 目录 {{ conflict.catalog_version }}
          </p>
          <p class="role-description">当前说明：{{ conflict.role.description || '未填写' }}</p>
          <div class="form-actions">
            <button type="button" class="secondary-button" @click="resolve(false)">
              采用当前记录</button
            ><button type="button" class="secondary-button" @click="resolve(true)">
              {{ mode === 'delete' ? '核对后继续删除' : '保留我的修改' }}
            </button>
          </div>
        </div>
        <div class="form-actions">
          <button
            v-if="!uncertain"
            class="primary-button"
            :disabled="
              pending ||
              !!conflict ||
              !manageable ||
              descriptionTooLong ||
              (mode !== 'create' && !source?.role.can_manage)
            "
          >
            {{
              pending
                ? '正在提交…'
                : mode === 'create'
                  ? '创建'
                  : mode === 'copy'
                    ? '确认复制'
                    : mode === 'delete'
                      ? '确认删除角色'
                      : '保存修改'
            }}</button
          ><button v-else type="button" class="primary-button" :disabled="pending" @click="lookup">
            核对角色列表</button
          ><button type="button" class="secondary-button" :disabled="pending" @click="close">
            取消
          </button>
        </div>
      </form>
    </dialog>
    <RoleMembersDialog :role="members" @closed="members = null" />
  </section>
</template>
