<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useManagementApi } from '../app/management'
import type { Page } from '../api/platform'
import type { Reference } from '../api/groups'
const props = defineProps<{
  modelValue: Reference[]
  kind: 'users' | 'groups' | 'roles'
  disabled?: boolean
  single?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [Reference[]] }>()
const { read } = useManagementApi()
const query = ref(''),
  rows = ref<Reference[]>([]),
  error = ref(''),
  loading = ref(false)
const page = ref(0),
  more = ref(false),
  opened = ref(false)
let sequence = 0
function label(item: Reference) {
  return item.group_path || item.name || item.display_name || item.username || item.id
}
async function search(target = 0) {
  const turn = ++sequence
  loading.value = true
  opened.value = true
  error.value = ''
  try {
    const value = await read<Page<Reference>>(
      'tenant',
      `/${props.kind}?q=${encodeURIComponent(query.value.trim())}&page=${target}&size=20`,
    )
    if (turn !== sequence) return
    rows.value = value.items
    page.value = target
    more.value = value.has_more
  } catch (problem) {
    if (turn === sequence) error.value = problem instanceof Error ? problem.message : '无法读取选项'
  } finally {
    if (turn === sequence) loading.value = false
  }
}
function choose(item: Reference) {
  emit('update:modelValue', props.single ? [item] : [...props.modelValue, item])
  if (props.single) opened.value = false
}
onBeforeUnmount(() => sequence++)
</script>
<template>
  <div class="reference-picker">
    <div class="selection-chips">
      <span v-for="item in modelValue" :key="item.id" class="selection-chip">
        {{ label(item) }}
        <button
          type="button"
          class="text-button"
          :disabled="disabled"
          :aria-label="`移除 ${label(item)}`"
          @click="
            emit(
              'update:modelValue',
              modelValue.filter((value) => value.id !== item.id),
            )
          "
        >
          ×
        </button>
      </span>
      <span v-if="!modelValue.length" class="muted">未选择</span>
      <button
        type="button"
        class="text-button"
        :disabled="disabled"
        @click="opened ? (opened = false) : search()"
      >
        {{ opened ? '收起' : '选择' }}
      </button>
    </div>
    <div v-if="opened" class="reference-options">
      <div class="search-form">
        <input
          v-model="query"
          :disabled="disabled || loading"
          placeholder="输入名称或编码搜索"
          maxlength="100"
          @keydown.enter.prevent="search()"
        />
        <button
          type="button"
          class="secondary-button"
          :disabled="disabled || loading"
          @click="search()"
        >
          查询
        </button>
      </div>
      <p v-if="kind === 'groups'" class="muted">默认列出顶层组织，搜索可查找任意层级。</p>
      <p v-if="error" role="alert" class="message error">{{ error }}</p>
      <div v-for="item in rows" :key="item.id" class="reference-option">
        <span
          >{{ label(item) }}
          <small class="muted"
            >{{ item.code || item.username }}{{ item.protected ? ' · 受保护角色' : '' }}</small
          ></span
        >
        <button
          type="button"
          class="text-button"
          :disabled="
            disabled ||
            item.status === 'DISABLED' ||
            item.effective_status === 'DISABLED' ||
            item.protected ||
            modelValue.some((value) => value.id === item.id)
          "
          @click="choose(item)"
        >
          {{ modelValue.some((value) => value.id === item.id) ? '已选择' : '加入' }}
        </button>
      </div>
      <p v-if="!loading && !rows.length" class="empty">没有符合条件的记录</p>
      <div class="pagination">
        <button
          type="button"
          class="text-button"
          :disabled="loading || page === 0"
          @click="search(page - 1)"
        >
          上一页</button
        ><span>{{ page + 1 }}</span
        ><button
          type="button"
          class="text-button"
          :disabled="loading || !more"
          @click="search(page + 1)"
        >
          下一页
        </button>
      </div>
    </div>
  </div>
</template>
