<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, RouterLink, useRoute } from 'vue-router'
import { accountContext } from '../../app/context'
import { canManage, managementPath, useManagementApi } from '../../app/management'
import type { Group } from '../../api/groups'
import type { RolePermissions, TenantUser } from '../../api/tenant'
import type { Page } from '../../api/platform'
import { IdentityError } from '../../api/identity'
import RolePermissionEditor from './RolePermissionEditor.vue'
import UserRolesDialog from '../../components/UserRolesDialog.vue'
const context = inject(accountContext),
  route = useRoute(),
  { read, write } = useManagementApi()
const owner = computed(() => context?.() ?? null)
const roleId = String(route.params.roleId)
const state = ref<RolePermissions | null>(null),
  name = ref(''),
  description = ref(''),
  error = ref(''),
  pending = ref(false),
  blocked = ref(false)
const tab = ref<'profile' | 'permissions' | 'members' | 'groups'>('profile')
const editor = ref<InstanceType<typeof RolePermissionEditor> | null>(null)
const members = ref<TenantUser[]>([]),
  page = ref(0),
  more = ref(false),
  assigning = ref<TenantUser | null>(null)
const groups = ref<Group[]>([]),
  groupPage = ref(0),
  groupMore = ref(false)
let generation = 0
const dirty = computed(
  () =>
    !!state.value &&
    (name.value !== state.value.role.name ||
      description.value !== (state.value.role.description ?? '')),
)
const editable = computed(
  () => canManage(owner.value, 'identity:role:manage') && state.value?.role.can_manage,
)
function report(problem: unknown) {
  error.value = problem instanceof Error ? problem.message : '读取或保存失败'
  owner.value?.failed(problem)
}
async function load() {
  if (!canManage(owner.value, 'identity:role:read') || pending.value) return
  const turn = generation
  pending.value = true
  error.value = ''
  try {
    const value = await read<RolePermissions>(
      'tenant',
      `/role-permissions/${encodeURIComponent(roleId)}`,
    )
    if (turn !== generation) return
    state.value = value
    name.value = value.role.name
    description.value = value.role.description ?? ''
    blocked.value = false
  } catch (problem) {
    if (turn === generation) report(problem)
  } finally {
    if (turn === generation) pending.value = false
  }
}
function beforeNavigation() {
  return !pending.value && (!dirty.value || window.confirm('放弃未保存的角色资料？'))
}
async function switchTab(value: typeof tab.value) {
  if (
    value === tab.value ||
    !beforeNavigation() ||
    (editor.value && !editor.value.beforeNavigation())
  )
    return
  name.value = state.value?.role.name ?? ''
  description.value = state.value?.role.description ?? ''
  tab.value = value
  error.value = ''
  if (value === 'profile') await load()
  if (value === 'members') await loadMembers(0)
  if (value === 'groups') await loadGroups(0)
}
async function save() {
  if (!editable.value || !state.value || pending.value || blocked.value) return
  const turn = generation
  pending.value = true
  error.value = ''
  try {
    await write(
      'tenant',
      `/roles/${encodeURIComponent(roleId)}`,
      {
        version: state.value.role.version,
        name: name.value,
        description: description.value,
        status: state.value.role.status,
      },
      'PUT',
    )
    if (turn !== generation) return
    pending.value = false
    await load()
  } catch (problem) {
    if (turn !== generation) return
    report(problem)
    blocked.value =
      problem instanceof IdentityError && (problem.status === 409 || problem.uncertain)
  } finally {
    if (turn === generation) pending.value = false
  }
}
async function loadMembers(target: number) {
  if (!canManage(owner.value, 'identity:user:read') || pending.value) return
  const turn = generation
  pending.value = true
  error.value = ''
  try {
    const value = await read<Page<TenantUser>>(
      'tenant',
      `/roles/${encodeURIComponent(roleId)}/members?page=${target}&size=20`,
    )
    if (turn !== generation) return
    members.value = value.items
    page.value = value.page
    more.value = value.has_more
  } catch (problem) {
    if (turn === generation) report(problem)
  } finally {
    if (turn === generation) pending.value = false
  }
}
async function loadGroups(next: number) {
  const turn = generation
  pending.value = true
  error.value = ''
  try {
    const result = await read<Page<Group>>('tenant', `/roles/${roleId}/groups?page=${next}&size=20`)
    if (turn !== generation) return
    groups.value = result.items
    groupPage.value = next
    groupMore.value = result.has_more
  } catch (problem) {
    if (turn === generation) report(problem)
  } finally {
    if (turn === generation) pending.value = false
  }
}
function closeAssignment() {
  assigning.value = null
  void loadMembers(page.value)
}
onBeforeRouteLeave(beforeNavigation)
onBeforeRouteUpdate(beforeNavigation)
onBeforeUnmount(() => {
  generation++
})
onMounted(load)
</script>
<template>
  <RouterLink class="text-button" :to="managementPath(owner, '/roles')">← 返回角色列表</RouterLink>
  <section class="card management-card">
    <div v-if="error" class="message error" role="alert">{{ error }}</div>
    <template v-if="state">
      <div class="section-heading">
        <h2>{{ state.role.name }}</h2>
        <span class="muted"
          >{{ state.role.code }} ·
          {{
            state.role.protected ? '受保护角色' : state.role.status === 'ACTIVE' ? '启用' : '停用'
          }}</span
        >
      </div>
      <div class="permission-tabs" role="tablist" aria-label="角色详情">
        <button
          type="button"
          class="secondary-button"
          role="tab"
          :aria-selected="tab === 'profile'"
          @click="switchTab('profile')"
        >
          基本资料
        </button>
        <button
          v-if="canManage(owner, 'identity:permission:read')"
          type="button"
          class="secondary-button"
          role="tab"
          :aria-selected="tab === 'permissions'"
          @click="switchTab('permissions')"
        >
          权限授权
        </button>
        <button
          v-if="canManage(owner, 'identity:user:read')"
          type="button"
          class="secondary-button"
          role="tab"
          :aria-selected="tab === 'members'"
          @click="switchTab('members')"
        >
          直接用户
        </button>
        <button
          class="secondary-button"
          role="tab"
          :aria-selected="tab === 'groups'"
          @click="switchTab('groups')"
        >
          授权用户组
        </button>
      </div>
      <form v-if="tab === 'profile'" class="profile-form" @submit.prevent="save">
        <div class="field">
          <label for="role-detail-name">角色名称</label
          ><input
            id="role-detail-name"
            v-model="name"
            :readonly="!editable"
            :disabled="pending"
            required
            maxlength="128"
          />
        </div>
        <div class="field">
          <label for="role-detail-description">角色说明</label
          ><textarea
            id="role-detail-description"
            v-model="description"
            :readonly="!editable"
            :disabled="pending"
            maxlength="1024"
          />
        </div>
        <p v-if="blocked" role="status">草稿已保留，请重新读取当前资料后核对。</p>
        <div class="form-actions">
          <button v-if="editable" class="primary-button" :disabled="pending || blocked || !dirty">
            保存资料</button
          ><button
            type="button"
            class="secondary-button"
            :disabled="pending"
            @click="beforeNavigation() && load()"
          >
            重新读取
          </button>
        </div>
      </form>
      <RolePermissionEditor v-else-if="tab === 'permissions'" ref="editor" :role-id="roleId" />
      <template v-else-if="tab === 'groups'">
        <p class="muted">用户组的直接成员会取得本角色；组织停用时该来源不生效。</p>
        <RouterLink class="text-button" :to="managementPath(owner, '/groups')"
          >前往用户组调整授权</RouterLink
        >
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>组织路径</th>
                <th>状态</th>
                <th>直接成员</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="group in groups" :key="group.id">
                <td>{{ group.group_path }}</td>
                <td>{{ group.effective_status === 'ACTIVE' ? '生效中' : '未生效' }}</td>
                <td>{{ group.member_count }}</td>
              </tr>
              <tr v-if="!groups.length">
                <td colspan="3" class="empty">没有用户组被授予本角色</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <button
            class="text-button"
            :disabled="pending || !groupPage"
            @click="loadGroups(groupPage - 1)"
          >
            上一页</button
          ><button
            class="text-button"
            :disabled="pending || !groupMore"
            @click="loadGroups(groupPage + 1)"
          >
            下一页
          </button>
        </div>
      </template>
      <template v-else>
        <p class="muted">在用户的角色分配中添加或移除成员。</p>
        <RouterLink class="text-button" :to="managementPath(owner, '/users')"
          >前往用户管理添加成员</RouterLink
        >
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>账号</th>
                <th>显示名</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="user in members" :key="user.id">
                <td>{{ user.username }}</td>
                <td>{{ user.display_name }}</td>
                <td>{{ user.status === 'ACTIVE' ? '启用' : '非启用' }}</td>
                <td>
                  <button
                    v-if="canManage(owner, 'identity:role:assign')"
                    class="text-button"
                    @click="assigning = user"
                  >
                    调整角色
                  </button>
                </td>
              </tr>
              <tr v-if="!pending && !members.length">
                <td colspan="4" class="empty">该角色暂无成员</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <button
            class="secondary-button"
            :disabled="pending || page === 0"
            @click="loadMembers(page - 1)"
          >
            上一页</button
          ><span>第 {{ page + 1 }} 页</span
          ><button
            class="secondary-button"
            :disabled="pending || !more || page >= 10000"
            @click="loadMembers(page + 1)"
          >
            下一页
          </button>
        </div>
        <UserRolesDialog :user="assigning" @closed="closeAssignment" />
      </template>
    </template>
    <p v-else-if="pending" role="status">正在读取角色…</p>
    <p v-else>当前角色不可读取。</p>
  </section>
</template>
