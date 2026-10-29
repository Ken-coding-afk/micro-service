<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useManagementApi } from '../app/management'
import type { Group } from '../api/groups'
import type { Page } from '../api/platform'
defineOptions({ name: 'GroupTreeNode' })
const props = defineProps<{ group: Group; selected?: string }>()
const emit = defineEmits<{ selected: [Group] }>()
const { read } = useManagementApi()
const children = ref<Group[]>([]),
  expanded = ref(false),
  loading = ref(false),
  error = ref(''),
  more = ref(false),
  page = ref(0)
let alive = true
async function load(next = 0) {
  loading.value = true
  error.value = ''
  try {
    const result = await read<Page<Group>>(
      'tenant',
      `/groups?parent_id=${props.group.id}&page=${next}&size=25`,
    )
    if (!alive) return
    children.value = next ? [...children.value, ...result.items] : result.items
    page.value = next
    more.value = result.has_more
    expanded.value = true
  } catch (problem) {
    if (alive) error.value = problem instanceof Error ? problem.message : '加载失败'
  } finally {
    if (alive) loading.value = false
  }
}
onBeforeUnmount(() => {
  alive = false
})
</script>
<template>
  <li
    role="treeitem"
    :aria-expanded="group.has_children ? expanded : undefined"
    :aria-selected="selected === group.id"
  >
    <div class="tree-row" :class="{ selected: selected === group.id }">
      <button
        class="tree-toggle"
        :disabled="!group.has_children || loading"
        :aria-label="`${expanded ? '折叠' : '展开'} ${group.name}`"
        @click="expanded ? (expanded = false) : load()"
      >
        {{ group.has_children ? (expanded ? '−' : '+') : '·' }}
      </button>
      <button class="tree-label" @click="emit('selected', group)">
        {{ group.name }}<small v-if="group.effective_status === 'DISABLED'">未生效</small>
      </button>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <ul v-if="expanded" role="group" class="organization-tree">
      <GroupTreeNode
        v-for="child in children"
        :key="child.id"
        :group="child"
        :selected="selected"
        @selected="emit('selected', $event)"
      />
      <li v-if="more">
        <button class="text-button" :disabled="loading" @click="load(page + 1)">更多组织</button>
      </li>
    </ul>
  </li>
</template>
