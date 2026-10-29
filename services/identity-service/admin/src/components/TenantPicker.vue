<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { IdentityError, read } from '../api/identity'
import type { Page, PlatformTenant } from '../api/platform'
const props = defineProps<{ modelValue: string[]; single?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()
const query = ref(''),
  rows = ref<PlatformTenant[]>([]),
  selected = ref<Record<string, PlatformTenant>>({})
const error = ref(''),
  loading = ref(false),
  page = ref(0),
  more = ref(false),
  expanded = ref(false)
let generation = 0,
  sequence = 0
const retry = ref(0)
watch(
  () => [props.modelValue, retry.value] as const,
  async ([ids]) => {
    const turn = ++generation
    error.value = ''
    const kept: Record<string, PlatformTenant> = {}
    for (const id of ids) if (selected.value[id]) kept[id] = selected.value[id]
    selected.value = kept
    await Promise.all(
      ids
        .filter((id) => !kept[id])
        .map(async (id) => {
          try {
            const item = await read<PlatformTenant>(
              'platform',
              `/platform/tenants/${encodeURIComponent(id)}`,
            )
            if (generation === turn) selected.value[id] = item
          } catch (problem) {
            if (generation === turn)
              error.value =
                problem instanceof IdentityError ? problem.message : '无法读取已选租户，请重试。'
          }
        }),
    )
  },
  { immediate: true },
)
async function search(target = 0) {
  const turn = ++sequence
  loading.value = true
  error.value = ''
  expanded.value = true
  try {
    const result = await read<Page<PlatformTenant>>(
      'platform',
      `/platform/tenants?page=${target}&size=20&q=${encodeURIComponent(query.value.trim())}`,
    )
    if (turn !== sequence) return
    rows.value = result.items
    page.value = result.page
    more.value = result.has_more
  } catch (problem) {
    if (turn === sequence)
      error.value = problem instanceof IdentityError ? problem.message : '无法查询租户。'
  } finally {
    if (turn === sequence) loading.value = false
  }
}
async function retryRead() {
  retry.value++
  if (expanded.value || !props.single) await search(0)
}
function choose(item: PlatformTenant) {
  selected.value[item.id] = item
  emit('update:modelValue', props.single ? [item.id] : [...props.modelValue, item.id])
  if (props.single) expanded.value = false
}
onBeforeUnmount(() => {
  generation++
  sequence++
})
</script>
<template>
  <div class="tenant-picker">
    <div class="selected-tenants">
      <span v-for="id in modelValue" :key="id" class="tenant-selection">
        {{ selected[id] ? `${selected[id].name} · ${selected[id].code}` : '租户信息读取中…' }}
        <small v-if="selected[id]?.status === 'DISABLED'">已停用</small>
        <button
          v-if="!single"
          type="button"
          class="text-button"
          :disabled="disabled"
          :aria-label="`移除 ${selected[id]?.name ?? '租户'}`"
          @click="
            emit(
              'update:modelValue',
              modelValue.filter((value) => value !== id),
            )
          "
        >
          移除
        </button>
      </span>
      <span v-if="!modelValue.length" class="muted">{{
        single ? '选择管理租户' : '未允许任何租户'
      }}</span>
      <button
        v-if="single"
        type="button"
        class="text-button"
        :disabled="disabled"
        @click="expanded = !expanded"
      >
        切换租户
      </button>
    </div>
    <div v-if="!single || expanded" class="tenant-search">
      <label class="field"
        >搜索租户名称或代码<input
          v-model="query"
          :disabled="disabled"
          maxlength="100"
          @keydown.enter.prevent="search(0)"
      /></label>
      <button
        type="button"
        class="secondary-button"
        :disabled="loading || disabled"
        @click="search(0)"
      >
        查询
      </button>
      <div v-if="loading" role="status">正在查询…</div>
      <ul v-else>
        <li v-for="item in rows" :key="item.id">
          <span
            >{{ item.name }}
            <small>{{ item.code }} · {{ item.status === 'ACTIVE' ? '启用' : '停用' }}</small></span
          >
          <button
            type="button"
            class="text-button"
            :disabled="disabled || modelValue.includes(item.id)"
            @click="choose(item)"
          >
            {{ modelValue.includes(item.id) ? '已选择' : '选择' }}
          </button>
        </li>
      </ul>
      <div v-if="rows.length" class="pagination">
        <button
          type="button"
          class="text-button"
          :disabled="loading || page === 0"
          @click="search(page - 1)"
        >
          上一页</button
        ><span>第 {{ page + 1 }} 页</span
        ><button
          type="button"
          class="text-button"
          :disabled="loading || !more || page >= 10000"
          @click="search(page + 1)"
        >
          下一页
        </button>
      </div>
    </div>
    <div v-if="error" class="message error" role="alert">
      {{ error }} <button type="button" class="text-button" @click="retryRead">重试读取</button>
    </div>
  </div>
</template>
