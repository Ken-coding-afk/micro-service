<script setup lang="ts">
import { inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { accountContext } from '../app/context'
import { useManagementApi } from '../app/management'
import { IdentityError } from '../api/identity'
import { references, type Reference } from '../api/groups'
import type { TenantUser } from '../api/tenant'
import ReferencePicker from './ReferencePicker.vue'
const props = defineProps<{ user: TenantUser }>()
const emit = defineEmits<{ closed: []; changed: [] }>()
const context = inject(accountContext),
  { read, write } = useManagementApi()
const dialog = ref<HTMLDialogElement>(),
  groups = ref<Reference[]>([]),
  version = ref(0)
const error = ref(''),
  pending = ref(false),
  blocked = ref(true)
let alive = true
async function load() {
  pending.value = true
  error.value = ''
  try {
    const result = await read<{ version: number; groups: Reference[] }>(
      'tenant',
      `/users/${props.user.id}/groups`,
    )
    if (!alive) return
    version.value = result.version
    groups.value = result.groups
    blocked.value = false
  } catch (problem) {
    error.value = problem instanceof Error ? problem.message : '读取失败'
    context?.()?.failed(problem)
  } finally {
    if (alive) pending.value = false
  }
}
async function save() {
  if (pending.value || blocked.value) return
  pending.value = true
  error.value = ''
  try {
    await write(
      'tenant',
      `/users/${props.user.id}/groups`,
      { user_version: version.value, groups: references(groups.value) },
      'PUT',
    )
    if (alive) {
      emit('changed')
      emit('closed')
    }
  } catch (problem) {
    error.value = problem instanceof Error ? problem.message : '保存失败'
    blocked.value =
      problem instanceof IdentityError && (problem.status === 409 || problem.uncertain)
    context?.()?.failed(problem)
  } finally {
    if (alive) pending.value = false
  }
}
function leave() {
  return !pending.value && window.confirm('放弃未保存的用户组调整？')
}
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onBeforeUnmount(() => {
  alive = false
})
onMounted(() => {
  dialog.value?.showModal()
  void load()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="management-dialog"
    aria-labelledby="user-groups-title"
    @cancel.prevent="!pending && emit('closed')"
  >
    <h2 id="user-groups-title">{{ user.display_name }} · 所属用户组</h2>
    <p class="muted">用户获得直接所属有效用户组的角色；上级组织的角色不会自动继承。</p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <ReferencePicker v-model="groups" kind="groups" :disabled="pending || blocked" />
    <div class="form-actions">
      <button v-if="blocked" class="secondary-button" :disabled="pending" @click="load">
        重新读取</button
      ><button class="primary-button" :disabled="pending || blocked" @click="save">
        保存用户组</button
      ><button class="secondary-button" :disabled="pending" @click="emit('closed')">取消</button>
    </div>
  </dialog>
</template>
