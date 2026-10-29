<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { accountContext } from '../../app/context'
import { canManage, useManagementApi } from '../../app/management'
import { IdentityError } from '../../api/identity'
import type { Page } from '../../api/platform'
import { references, type Group, type Reference } from '../../api/groups'
import GroupTreeNode from '../../components/GroupTreeNode.vue'
import ReferencePicker from '../../components/ReferencePicker.vue'
const context = inject(accountContext),
  { read, write } = useManagementApi()
const owner = computed(() => context?.() ?? null)
const readable = computed(() => canManage(owner.value, 'identity:group:read'))
const manageable = computed(() => canManage(owner.value, 'identity:group:manage'))
const assignable = computed(() => canManage(owner.value, 'identity:group:assign'))
const roots = ref<Group[]>([]),
  current = ref<Group | null>(null),
  treeVersion = ref(0),
  revision = ref(0)
const query = ref(''),
  treePage = ref(0),
  treeMore = ref(false),
  error = ref(''),
  message = ref('')
const loading = ref(false),
  pending = ref(false),
  blocked = ref(false),
  dialog = ref<HTMLDialogElement>()
const mode = ref<'create' | 'edit' | 'move' | 'members' | 'roles'>('create')
const draft = reactive({ code: '', name: '', description: '', sort: 0, status: 'ACTIVE' })
const parent = ref<Reference[]>([]),
  selected = ref<Reference[]>([]),
  requestId = ref('')
const members = ref<(Reference & { direct: boolean; group_paths: string[] })[]>([]),
  roles = ref<Reference[]>([])
const tab = ref<'members' | 'roles'>('members'),
  descendants = ref(false),
  memberQuery = ref(''),
  page = ref(0),
  more = ref(false)
let generation = 0,
  selection = 0
function report(problem: unknown) {
  error.value = problem instanceof Error ? problem.message : '操作失败'
  owner.value?.failed(problem)
}
async function loadTree(next = 0) {
  const turn = generation
  loading.value = true
  error.value = ''
  try {
    const result = await read<Page<Group> & { organization_version: number }>(
      'tenant',
      `/groups?q=${encodeURIComponent(query.value)}&page=${next}&size=25`,
    )
    if (turn !== generation) return
    roots.value = next ? [...roots.value, ...result.items] : result.items
    treeVersion.value = result.organization_version
    treePage.value = next
    treeMore.value = result.has_more
    revision.value++
  } catch (problem) {
    if (turn === generation) report(problem)
  } finally {
    if (turn === generation) loading.value = false
  }
}
async function choose(group: Group) {
  if (pending.value) return
  const turn = ++selection
  error.value = ''
  loading.value = true
  try {
    const value = await read<Group>('tenant', `/groups/${group.id}`)
    if (turn !== selection) return
    current.value = value
    treeVersion.value = value.organization_version
    await loadDetails(0)
  } catch (problem) {
    if (turn === selection) report(problem)
  } finally {
    if (turn === selection) loading.value = false
  }
}
function selectTab(value: 'members' | 'roles') {
  tab.value = value
  void loadDetails()
}
async function loadDetails(next = 0) {
  if (!current.value) return
  const id = current.value.id,
    turn = selection
  loading.value = true
  try {
    if (tab.value === 'members') {
      const result = await read<Page<(typeof members.value)[number]>>(
        'tenant',
        `/groups/${id}/members?include_descendants=${descendants.value}&q=${encodeURIComponent(memberQuery.value)}&page=${next}&size=20`,
      )
      if (turn !== selection) return
      members.value = result.items
      page.value = result.page
      more.value = result.has_more
    } else {
      const result = await read<{ version: number; roles: Reference[] }>(
        'tenant',
        `/groups/${id}/roles`,
      )
      if (turn !== selection) return
      roles.value = result.roles
      current.value!.version = result.version
    }
  } catch (problem) {
    if (turn === selection) report(problem)
  } finally {
    if (turn === selection) loading.value = false
  }
}
async function open(action: typeof mode.value, child = false) {
  if (pending.value || loading.value) return
  error.value = ''
  message.value = ''
  blocked.value = false
  mode.value = action
  if (action !== 'create' && current.value) await choose(current.value)
  if (error.value) return
  const g = current.value
  Object.assign(
    draft,
    action === 'create' ? { code: '', name: '', description: '', sort: 0, status: 'ACTIVE' } : g,
  )
  selected.value = action === 'roles' ? [...roles.value] : []
  parent.value = child && g ? [g] : []
  if (action === 'move' && g?.parent_id) {
    try {
      parent.value = [await read<Group>('tenant', `/groups/${g.parent_id}`)]
    } catch (problem) {
      report(problem)
      return
    }
  }
  requestId.value = crypto.randomUUID()
  await nextTick()
  dialog.value?.showModal()
}
async function save() {
  if (pending.value || blocked.value) return
  pending.value = true
  error.value = ''
  try {
    const g = current.value
    if (mode.value === 'create') {
      current.value = await write<Group>(
        'tenant',
        '/groups',
        {
          code: draft.code,
          name: draft.name,
          description: draft.description,
          sort: draft.sort,
          parent_id: parent.value[0]?.id ?? null,
          organization_version: treeVersion.value,
        },
        'POST',
        requestId.value,
      )
    } else if (g) {
      const base = `/groups/${g.id}`
      if (mode.value === 'edit')
        await write(
          'tenant',
          base,
          {
            name: draft.name,
            description: draft.description,
            sort: draft.sort,
            status: draft.status,
            version: g.version,
            organization_version: g.organization_version,
          },
          'PUT',
        )
      if (mode.value === 'move')
        await write(
          'tenant',
          `${base}:move`,
          {
            parent_id: parent.value[0]?.id ?? null,
            sort: draft.sort,
            version: g.version,
            organization_version: g.organization_version,
          },
          'POST',
        )
      if (mode.value === 'members')
        await write(
          'tenant',
          `${base}/members:add`,
          { group_version: g.version, users: references(selected.value) },
          'POST',
        )
      if (mode.value === 'roles')
        await write(
          'tenant',
          `${base}/roles`,
          { group_version: g.version, roles: references(selected.value) },
          'PUT',
        )
    }
    dialog.value?.close()
    selected.value = []
    message.value = '已保存。'
    pending.value = false
    await loadTree()
    if (current.value) await choose(current.value)
  } catch (problem) {
    report(problem)
    blocked.value =
      problem instanceof IdentityError && (problem.status === 409 || problem.uncertain)
  } finally {
    pending.value = false
  }
}
async function removeMember(member: Reference) {
  if (
    !current.value ||
    pending.value ||
    !window.confirm('从当前组移除此成员？其他组和直接角色不受影响。')
  )
    return
  pending.value = true
  error.value = ''
  try {
    await write(
      'tenant',
      `/groups/${current.value.id}/members:remove`,
      { group_version: current.value.version, users: references([member]) },
      'POST',
    )
    pending.value = false
    await choose(current.value)
  } catch (problem) {
    report(problem)
  } finally {
    pending.value = false
  }
}
async function removeGroup() {
  const g = current.value
  if (!g || pending.value || !window.confirm(`删除“${g.name}”？仅空组织可以删除。`)) return
  pending.value = true
  error.value = ''
  try {
    await write(
      'tenant',
      `/groups/${g.id}`,
      { version: g.version, organization_version: g.organization_version },
      'DELETE',
    )
    current.value = null
    await loadTree()
  } catch (problem) {
    report(problem)
  } finally {
    pending.value = false
  }
}
function leave() {
  return !pending.value && (!dialog.value?.open || window.confirm('放弃未保存的用户组修改？'))
}
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onBeforeUnmount(() => {
  generation++
  selection++
})
onMounted(() => {
  if (readable.value) void loadTree()
})
</script>
<template>
  <div v-if="!readable" class="empty">没有用户组读取权限。</div>
  <section v-else class="organization-layout">
    <aside class="organization-panel">
      <div class="section-heading">
        <h2>组织结构</h2>
        <button
          v-if="manageable"
          class="text-button"
          :disabled="pending || loading"
          @click="open('create')"
        >
          ＋ 顶层
        </button>
      </div>
      <form class="search-form" @submit.prevent="loadTree()">
        <input v-model="query" placeholder="搜索组织名称或编码" maxlength="100" /><button
          class="text-button"
          :disabled="loading"
        >
          搜索
        </button>
      </form>
      <ul :key="revision" class="organization-tree" role="tree" aria-label="组织结构">
        <GroupTreeNode
          v-for="group in roots"
          :key="group.id"
          :group="group"
          :selected="current?.id"
          @selected="choose"
        />
      </ul>
      <p v-if="!roots.length && !loading" class="empty">还没有组织，先创建公司或部门。</p>
      <button
        v-if="treeMore"
        class="text-button"
        :disabled="loading"
        @click="loadTree(treePage + 1)"
      >
        加载更多
      </button>
    </aside>
    <div class="card management-card organization-content">
      <p v-if="error && !dialog?.open" class="message error" role="alert">{{ error }}</p>
      <p v-if="message" class="message success" role="status">{{ message }}</p>
      <template v-if="current">
        <p class="muted">{{ current.group_path }}</p>
        <div class="section-heading">
          <div>
            <h2>{{ current.name }}</h2>
            <p class="muted">
              {{ current.code }} ·
              {{
                current.effective_status === 'ACTIVE'
                  ? '生效中'
                  : current.status === 'DISABLED'
                    ? '已停用'
                    : '上级组织已停用'
              }}
            </p>
          </div>
          <div v-if="manageable" class="row-actions">
            <button
              class="text-button"
              :disabled="pending || loading"
              @click="open('create', true)"
            >
              新增下级</button
            ><button class="text-button" :disabled="pending || loading" @click="open('edit')">
              编辑</button
            ><button
              class="text-button"
              :disabled="pending || loading || current.effective_status !== 'ACTIVE'"
              @click="open('move')"
            >
              移动</button
            ><button
              class="text-button danger-text"
              :disabled="
                pending ||
                loading ||
                !!(current.descendant_count || current.member_count || current.role_count)
              "
              @click="removeGroup"
            >
              删除
            </button>
          </div>
        </div>
        <p>{{ current.description }}</p>
        <p class="muted">
          直接成员 {{ current.member_count }} · 含下级成员 {{ current.subtree_member_count }} ·
          下级组织 {{ current.descendant_count }} · 角色 {{ current.role_count }}
        </p>
        <div class="permission-tabs">
          <button
            class="secondary-button"
            :aria-pressed="tab === 'members'"
            @click="selectTab('members')"
          >
            成员</button
          ><button
            class="secondary-button"
            :aria-pressed="tab === 'roles'"
            @click="selectTab('roles')"
          >
            角色授权
          </button>
        </div>
        <template v-if="tab === 'members'">
          <form class="filter-bar" @submit.prevent="loadDetails()">
            <input v-model="memberQuery" placeholder="搜索组内用户" maxlength="100" /><label
              ><input v-model="descendants" type="checkbox" @change="loadDetails()" />
              包含下级组织</label
            ><button class="secondary-button" :disabled="loading">查询</button
            ><button
              v-if="assignable"
              type="button"
              class="primary-button"
              :disabled="loading || pending || current.effective_status !== 'ACTIVE'"
              @click="open('members')"
            >
              添加成员
            </button>
          </form>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>账号</th>
                  <th>姓名</th>
                  <th>所属组织</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="member in members" :key="member.id">
                  <td>{{ member.username }}</td>
                  <td>{{ member.display_name }}</td>
                  <td>{{ member.group_paths.join('、') }}</td>
                  <td>
                    <button
                      v-if="assignable && member.direct"
                      class="text-button"
                      :disabled="pending"
                      @click="removeMember(member)"
                    >
                      移出本组</button
                    ><span v-else class="muted">{{
                      member.direct ? '直接成员' : '下级组织成员'
                    }}</span>
                  </td>
                </tr>
                <tr v-if="!members.length">
                  <td colspan="4" class="empty">暂无成员</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="pagination">
            <button class="text-button" :disabled="loading || !page" @click="loadDetails(page - 1)">
              上一页</button
            ><span>{{ page + 1 }}</span
            ><button
              class="text-button"
              :disabled="loading || !more"
              @click="loadDetails(page + 1)"
            >
              下一页
            </button>
          </div>
        </template>
        <template v-else
          ><p class="muted">
            这些角色授予本组的直接成员，不向下级组织继承；本组或任一上级停用后，这一授权来源失效。
          </p>
          <button
            v-if="assignable"
            class="primary-button"
            :disabled="loading || pending"
            @click="open('roles')"
          >
            调整角色
          </button>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>角色</th>
                  <th>编码</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="role in roles" :key="role.id">
                  <td>{{ role.name }}</td>
                  <td>{{ role.code }}</td>
                  <td>{{ role.status === 'ACTIVE' ? '启用' : '停用' }}</td>
                </tr>
                <tr v-if="!roles.length">
                  <td colspan="3" class="empty">未分配角色</td>
                </tr>
              </tbody>
            </table>
          </div></template
        >
      </template>
      <div v-else class="empty organization-empty">
        <h2>选择一个组织</h2>
        <p>在左侧维护公司、部门和团队，在这里管理成员与角色。</p>
      </div>
    </div>
    <dialog
      ref="dialog"
      class="management-dialog"
      aria-labelledby="group-dialog-title"
      @cancel.prevent="!pending && dialog?.close()"
    >
      <h2 id="group-dialog-title">
        {{
          {
            create: '新建用户组',
            edit: '编辑用户组',
            move: '移动用户组',
            members: '添加成员',
            roles: '调整组角色',
          }[mode]
        }}
      </h2>
      <p v-if="error" class="message error" role="alert">{{ error }}</p>
      <p v-if="blocked" class="muted">数据已变化或结果未确认，请关闭后重新读取再操作。</p>
      <form @submit.prevent="save">
        <fieldset class="plain-fields" :disabled="pending || blocked">
          <template v-if="mode === 'create' || mode === 'edit'"
            ><label v-if="mode === 'create'" class="field"
              >组织编码<input
                v-model="draft.code"
                required
                pattern="[a-z][a-z0-9_-]{0,63}"
                maxlength="64" /></label
            ><label class="field"
              >组织名称<input v-model="draft.name" required maxlength="128" /></label
            ><label class="field"
              >说明<textarea v-model="draft.description" maxlength="1000" /></label
            ><label v-if="mode === 'edit'" class="field"
              >状态<select v-model="draft.status">
                <option value="ACTIVE">启用</option>
                <option value="DISABLED">停用</option>
              </select></label
            >
            <p v-if="mode === 'edit'" class="muted">
              停用组织会同时停止该组织及所有下级组织的组角色授权。
            </p></template
          >
          <template v-if="mode === 'create' || mode === 'move'"
            ><label class="field">上级组织（留空为顶层）</label
            ><ReferencePicker v-model="parent" kind="groups" single :disabled="pending || blocked"
          /></template>
          <label v-if="['create', 'edit', 'move'].includes(mode)" class="field"
            >排序<input v-model.number="draft.sort" type="number" min="0" required
          /></label>
          <ReferencePicker
            v-if="mode === 'members' || mode === 'roles'"
            v-model="selected"
            :kind="mode === 'members' ? 'users' : 'roles'"
            :disabled="pending || blocked"
          />
        </fieldset>
        <div class="form-actions">
          <button
            class="primary-button"
            :disabled="pending || blocked || (mode === 'members' && !selected.length)"
          >
            保存</button
          ><button
            type="button"
            class="secondary-button"
            :disabled="pending"
            @click="dialog?.close()"
          >
            取消
          </button>
        </div>
      </form>
    </dialog>
  </section>
</template>
