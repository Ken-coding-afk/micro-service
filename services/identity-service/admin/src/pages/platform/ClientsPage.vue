<script setup lang="ts">
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import TenantPicker from '../../components/TenantPicker.vue'
import { accountContext } from '../../app/context'
import { can } from '../../app/permissions'
import { IdentityError, read, write } from '../../api/identity'
import type { IssuedClient, OAuthClient } from '../../api/clients'
const context = inject(accountContext)
const owner = computed(() => context?.() ?? null)
const readable = computed(
  () => owner.value?.realm === 'platform' && can(owner.value.me, 'platform:client:read'),
)
const manageable = computed(
  () => readable.value && owner.value && can(owner.value.me, 'platform:client:manage'),
)
const rows = ref<OAuthClient[]>([])
const page = ref(0),
  query = ref(''),
  more = ref(false),
  pending = ref(false)
const error = ref(''),
  success = ref(''),
  opened = ref(false),
  secret = ref(''),
  blocked = ref(false),
  confirmed = ref(false)
const editing = ref<OAuthClient | null>(null)
let epoch = 0,
  requestId = ''
const form = reactive({
  client_id: '',
  name: '',
  principal_type: 'user',
  owner_tenant_id: '',
  tenants: [] as string[],
  audiences: '',
  scopes: '',
  redirects: '',
  logouts: '',
  public_client: false,
  first_party: true,
  status: 'ACTIVE',
})
const service = reactive({ name: '', audience: '' })
const fixed = (item: OAuthClient) => item.client_id.startsWith('identity-admin-')
const lines = (value: string) => [...new Set(value.split(/[\s,]+/).filter(Boolean))]
function fail(value: unknown) {
  error.value = value instanceof Error ? value.message : '操作失败，请核对当前状态。'
  if (value instanceof IdentityError && value.status === 401) owner.value?.failed(value)
}
async function load() {
  if (!readable.value || pending.value) return
  const turn = ++epoch
  pending.value = true
  error.value = ''
  try {
    const value = await read<{ items: OAuthClient[]; has_more: boolean }>(
      'platform',
      `/platform/oauth-clients?page=${page.value}&size=20&q=${encodeURIComponent(query.value)}`,
    )
    if (turn === epoch) {
      rows.value = value.items
      more.value = value.has_more
    }
  } catch (failure) {
    if (turn === epoch) fail(failure)
  } finally {
    if (turn === epoch) pending.value = false
  }
}
function close() {
  opened.value = false
  secret.value = ''
  editing.value = null
  confirmed.value = false
}
function begin(item: OAuthClient | null) {
  if (!manageable.value || pending.value || (item && fixed(item))) return
  close()
  editing.value = item
  blocked.value = false
  error.value = ''
  success.value = ''
  requestId = crypto.randomUUID()
  Object.assign(form, {
    client_id: item?.client_id ?? '',
    name: item?.name ?? '',
    principal_type: item?.principal_type ?? 'user',
    owner_tenant_id: item?.owner_tenant_id ?? '',
    tenants: [...(item?.allowed_tenant_ids ?? [])],
    audiences: item?.audiences.join('\n') ?? '',
    scopes: item?.scopes.join('\n') ?? '',
    redirects: item?.redirect_uris.join('\n') ?? '',
    logouts: item?.logout_uris.join('\n') ?? '',
    public_client: item?.public_client ?? false,
    first_party: item?.first_party ?? true,
    status: item?.status ?? 'ACTIVE',
  })
  opened.value = true
}
async function submit() {
  if (!manageable.value || pending.value || blocked.value || !confirmed.value) return
  const turn = epoch
  pending.value = true
  error.value = ''
  secret.value = ''
  const machine = form.principal_type === 'service'
  const body = {
    client_id: form.client_id.trim(),
    name: form.name.trim(),
    principal_type: form.principal_type,
    owner_tenant_id:
      form.principal_type === 'platform' ? null : form.owner_tenant_id.trim() || null,
    allowed_tenant_ids: form.principal_type === 'platform' ? [] : form.tenants,
    audiences: lines(form.audiences),
    scopes: lines(form.scopes),
    redirect_uris: machine ? [] : lines(form.redirects),
    logout_uris: machine ? [] : lines(form.logouts),
    public_client: machine ? false : form.public_client,
    first_party: machine ? false : form.first_party,
    status: form.status,
    ...(editing.value ? { version: editing.value.version } : {}),
  }
  try {
    if (editing.value) {
      const result = await write<OAuthClient>(
        'platform',
        `/platform/oauth-clients/${editing.value.id}`,
        body,
        'PUT',
      )
      if (turn !== epoch) return
      editing.value = result
      success.value = '应用配置已保存。已签发授权按后端版本规则失效，请同步调用方。'
    } else {
      const result = await write<IssuedClient>(
        'platform',
        '/platform/oauth-clients',
        body,
        'POST',
        requestId,
      )
      if (turn !== epoch) return
      editing.value = result.client
      secret.value = result.client_secret ?? ''
      success.value = result.replayed
        ? '应用已存在，本次没有重新交付秘密。请查询当前状态。'
        : '应用已创建。请将一次性秘密保存到调用方的受控文件。'
    }
    blocked.value = true
  } catch (failure) {
    if (turn !== epoch) return
    fail(failure)
    if (failure instanceof IdentityError && (failure.uncertain || failure.status === 409))
      blocked.value = true
  } finally {
    if (turn === epoch) pending.value = false
  }
}
async function registerService() {
  if (!manageable.value || pending.value) return
  const turn = epoch
  pending.value = true
  error.value = ''
  success.value = ''
  try {
    await write('platform', '/platform/service-audiences', {
      service: service.name.trim(),
      audience: service.audience.trim(),
    })
    if (turn === epoch)
      success.value = '服务 audience 已登记；相同绑定可核对重试，业务权限仍需由服务发布。'
  } catch (failure) {
    if (turn === epoch) fail(failure)
  } finally {
    if (turn === epoch) pending.value = false
  }
}
async function rotate() {
  if (
    !manageable.value ||
    !editing.value ||
    editing.value.public_client ||
    pending.value ||
    blocked.value ||
    !confirmed.value
  )
    return
  const turn = epoch
  pending.value = true
  error.value = ''
  secret.value = ''
  try {
    const result = await write<IssuedClient>(
      'platform',
      `/platform/oauth-clients/${editing.value.id}/secret:rotate`,
      { version: editing.value.version },
    )
    if (turn !== epoch) return
    editing.value = result.client
    secret.value = result.client_secret ?? ''
    blocked.value = true
    success.value = '旧秘密和旧授权版本已失效。请保存新秘密并更新调用方，关闭后不再展示。'
  } catch (failure) {
    if (turn !== epoch) return
    fail(failure)
    if (failure instanceof IdentityError && (failure.uncertain || failure.status === 409))
      blocked.value = true
  } finally {
    if (turn === epoch) pending.value = false
  }
}
async function changePage(delta: number) {
  page.value += delta
  await load()
}
async function search() {
  page.value = 0
  await load()
}
watch(
  () => `${owner.value?.realm}:${owner.value?.me.sub}:${readable.value}`,
  () => {
    epoch++
    pending.value = false
    rows.value = []
    close()
    error.value = ''
    success.value = ''
    page.value = 0
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  epoch++
  close()
})
</script>
<template>
  <section class="panel">
    <p class="muted">
      先登记服务 audience，再登记用户应用或机器身份。应用 scope 不代替用户角色授权。
    </p>
    <p v-if="!readable" role="alert">仅有应用读取权限的平台管理员可访问。</p>
    <template v-else>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <p v-if="success" role="status">{{ success }}</p>
      <form class="toolbar" @submit.prevent="search">
        <label>应用搜索 <input v-model="query" placeholder="client_id 或名称" /></label
        ><button :disabled="pending">查询</button
        ><button v-if="manageable" type="button" :disabled="pending" @click="begin(null)">
          登记应用
        </button>
      </form>
      <details v-if="manageable">
        <summary>登记服务 audience</summary>
        <form @submit.prevent="registerService">
          <label
            >服务名
            <input v-model="service.name" required placeholder="sample-java-service" /></label
          ><label
            >Audience
            <input v-model="service.audience" required placeholder="sample-java-service" /></label
          ><button :disabled="pending">登记服务</button>
        </form>
      </details>
      <table>
        <thead>
          <tr>
            <th>应用</th>
            <th>类型</th>
            <th>状态</th>
            <th>范围</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <td>
              {{ item.name }}<br /><code>{{ item.client_id }}</code>
            </td>
            <td>{{ item.principal_type }}</td>
            <td>{{ item.status }}</td>
            <td>{{ item.audiences.join(', ') }}</td>
            <td>
              <span v-if="fixed(item)">固定管理入口</span
              ><button v-else-if="manageable" :disabled="pending" @click="begin(item)">
                查看与编辑
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="!rows.length && !pending">没有匹配应用。</p>
      <button :disabled="pending || page === 0" @click="changePage(-1)">上一页</button
      ><span> 第 {{ page + 1 }} 页 </span
      ><button :disabled="pending || !more" @click="changePage(1)">下一页</button>
      <section
        v-if="opened"
        role="dialog"
        aria-modal="false"
        aria-label="应用登记与编辑"
        class="panel"
      >
        <h3>{{ editing ? '应用配置' : '登记应用' }}</h3>
        <form @submit.prevent="submit">
          <fieldset :disabled="pending || blocked">
            <label
              >Client ID <input v-model="form.client_id" required :readonly="!!editing"
            /></label>
            <label>名称 <input v-model="form.name" required maxlength="200" /></label>
            <label
              >主体类型
              <select v-model="form.principal_type" :disabled="!!editing">
                <option value="user">用户应用</option>
                <option value="platform">平台管理应用</option>
                <option value="service">机器身份</option>
              </select></label
            >
            <label v-if="form.principal_type !== 'platform'"
              >拥有租户 ID（项目级留空）<input v-model="form.owner_tenant_id" :readonly="!!editing"
            /></label>
            <div v-if="form.principal_type !== 'platform'" class="full-width">
              <h3>允许登录的租户</h3>
              <TenantPicker v-model="form.tenants" :disabled="pending || blocked" />
            </div>
            <p v-else class="muted">
              平台管理应用使用平台账号登录，不绑定业务租户；仍需管理员权限及对应平台 scope。
            </p>
            <label>Audiences（每行一个）<textarea v-model="form.audiences" required /></label>
            <label
              >Scopes（每行一个，按应用类型登记）<textarea v-model="form.scopes" required />
            </label>
            <template v-if="form.principal_type !== 'service'"
              ><label>准确回调 URI <textarea v-model="form.redirects" required /></label
              ><label>退出回调 URI <textarea v-model="form.logouts" /></label
              ><label
                ><input
                  v-model="form.public_client"
                  type="checkbox"
                  :disabled="!!editing"
                />公开客户端（没有秘密）</label
              ><label
                ><input
                  v-model="form.first_party"
                  type="checkbox"
                />本项目第一方应用（必须是机密客户端）</label
              ></template
            >
            <label
              >状态
              <select v-model="form.status">
                <option value="ACTIVE">启用</option>
                <option value="DISABLED">停用</option>
              </select></label
            >
            <label
              ><input
                v-model="confirmed"
                type="checkbox"
              />已核对租户、scope、回调和授权失效影响</label
            ><button :disabled="!confirmed">{{ editing ? '保存配置' : '确认登记' }}</button>
            <button
              v-if="editing && !editing.public_client"
              type="button"
              :disabled="!confirmed"
              @click="rotate"
            >
              轮换秘密并使旧授权失效
            </button>
          </fieldset>
        </form>
        <p v-if="blocked">
          本次提交已结束或需核对。关闭后重新查询当前记录再编辑，不能直接重复提交。
        </p>
        <label v-if="secret"
          >一次性 client_secret（关闭后清除）<textarea
            :value="secret"
            readonly
            autocomplete="off"
            spellcheck="false"
          />
        </label>
        <button :disabled="pending" @click="close">关闭并清除秘密</button>
      </section>
    </template>
  </section>
</template>

<style scoped>
form,
fieldset {
  display: grid;
  gap: 16px;
}
fieldset {
  border: 0;
  padding: 0;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  align-items: start;
}
label {
  align-content: start;
  display: grid;
  gap: 7px;
  font-size: 13px;
  color: #42516a;
}
.full-width {
  grid-column: 1 / -1;
}
label:has(input[type='checkbox']) {
  display: flex;
  align-items: center;
  grid-column: 1 / -1;
}
input:not([type='checkbox']),
select,
textarea {
  box-sizing: border-box;
  width: 100%;
  min-height: 40px;
  padding: 9px 12px;
  border: 1px solid #d5deea;
  border-radius: 8px;
  font: inherit;
}
textarea {
  min-height: 90px;
  resize: vertical;
}
button {
  width: fit-content;
  padding: 9px 16px;
  border: 1px solid #d5deea;
  border-radius: 8px;
  background: #fff;
  color: #254b84;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.toolbar {
  display: flex;
  gap: 12px;
  align-items: end;
  margin: 24px 0;
}
details,
[role='dialog'] {
  margin: 24px 0;
}
summary {
  cursor: pointer;
  margin-bottom: 16px;
}
table {
  width: 100%;
  border-collapse: collapse;
  margin: 24px 0;
  text-align: left;
}
td,
th {
  padding: 12px;
  border-bottom: 1px solid #e5eaf2;
  overflow-wrap: anywhere;
}
</style>
