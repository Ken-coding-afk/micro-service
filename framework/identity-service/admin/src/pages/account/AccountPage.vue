<script setup lang="ts">
import { computed, inject, onMounted, ref } from 'vue'
import { accountContext } from '../../app/context'
import {
  IdentityError,
  read,
  write,
  type Consent,
  type OwnSession,
  type Profile,
} from '../../api/identity'

const account = inject(accountContext)
const context = computed(() => account?.() ?? null)
const profile = ref<Profile | null>(null)
const displayName = ref('')
const sessions = ref<OwnSession[]>([])
const consents = ref<Consent[]>([])
const loading = ref(true)
const pending = ref('')
const error = ref('')
const requestId = ref('')
const success = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const conflict = ref<Profile | null>(null)
const sessionPage = ref(0)
const consentPage = ref(0)
const pageSize = 20
const activeTab = ref<'profile' | 'security' | 'sessions' | 'consents'>('profile')
const tabs = [
  { key: 'profile', label: '基本资料' },
  { key: 'security', label: '密码安全' },
  { key: 'sessions', label: '登录会话' },
  { key: 'consents', label: '应用授权' },
] as const

function report(problem: unknown) {
  error.value = problem instanceof IdentityError ? problem.message : '暂时无法完成操作。'
  requestId.value = problem instanceof IdentityError ? problem.requestId : ''
  context.value?.failed(problem)
}

function clearMessage() {
  error.value = ''
  success.value = ''
  requestId.value = ''
}

async function load() {
  const owner = context.value
  if (!owner) return
  loading.value = true
  clearMessage()
  try {
    const [details, logins, applications] = await Promise.all([
      read<Profile>(owner.realm, '/me/profile'),
      read<OwnSession[]>(owner.realm, `/me/sessions?size=${pageSize}&page=${sessionPage.value}`),
      read<Consent[]>(owner.realm, `/me/consents?size=${pageSize}&page=${consentPage.value}`),
    ])
    profile.value = details
    displayName.value = details.display_name
    conflict.value = null
    sessions.value = logins
    consents.value = applications
  } catch (problem) {
    report(problem)
  } finally {
    loading.value = false
  }
}

async function mutate(key: string, action: () => Promise<void>) {
  if (pending.value) return
  pending.value = key
  clearMessage()
  try {
    await action()
  } catch (problem) {
    report(problem)
  } finally {
    pending.value = ''
  }
}

async function saveProfile() {
  const owner = context.value
  if (!owner || !profile.value) return
  await mutate('profile', async () => {
    try {
      const updated = await write<Profile>(
        owner.realm,
        '/me/profile',
        {
          version: profile.value?.version,
          display_name: displayName.value,
        },
        'PUT',
      )
      profile.value = updated
      displayName.value = updated.display_name
      await owner.changed()
      success.value = '显示名已更新。'
    } catch (problem) {
      if (problem instanceof IdentityError && problem.status === 409)
        conflict.value = await read<Profile>(owner.realm, '/me/profile')
      throw problem
    }
  })
}

async function savePassword() {
  const owner = context.value
  if (!owner) return
  if (newPassword.value !== confirmation.value) {
    clearMessage()
    error.value = '两次输入的新密码不一致。'
    return
  }
  if (!window.confirm('修改密码后将退出此账号的全部登录，是否继续？')) return
  await mutate('password', async () => {
    try {
      await write(owner.realm, '/me/password', {
        current_password: currentPassword.value,
        new_password: newPassword.value,
      })
      owner.signedOut()
    } finally {
      currentPassword.value = ''
      newPassword.value = ''
      confirmation.value = ''
    }
  })
}

async function revokeSession(session: OwnSession) {
  const owner = context.value
  if (
    !owner ||
    !window.confirm(
      session.current ? '将退出当前登录，是否继续？' : '撤销这条登录会话及其应用授权，是否继续？',
    )
  )
    return
  await mutate(session.id, async () => {
    await write(owner.realm, `/me/sessions/${encodeURIComponent(session.id)}:revoke`)
    if (session.current) owner.signedOut()
    else {
      sessions.value = await read(
        owner.realm,
        `/me/sessions?size=${pageSize}&page=${sessionPage.value}`,
      )
      success.value = '会话已撤销。'
    }
  })
}

async function signOutAll() {
  const owner = context.value
  if (!owner || !window.confirm('退出此账号的全部登录和应用授权，是否继续？')) return
  await mutate('all-sessions', async () => {
    await write(owner.realm, '/me/sessions:sign-out-all')
    owner.signedOut()
  })
}

async function revokeConsent(consent: Consent) {
  const owner = context.value
  if (!owner || !window.confirm(`撤销“${consent.name}”的全部授权，是否继续？`)) return
  await mutate(consent.client_id, async () => {
    await write(owner.realm, `/me/consents/${encodeURIComponent(consent.client_id)}:revoke`)
    consents.value = await read(
      owner.realm,
      `/me/consents?size=${pageSize}&page=${consentPage.value}`,
    )
    success.value = '应用授权已撤销。'
  })
}

function resolveConflict(keepDraft: boolean) {
  if (!conflict.value) return
  profile.value = conflict.value
  if (!keepDraft) displayName.value = conflict.value.display_name
  conflict.value = null
  clearMessage()
  success.value = keepDraft ? '已按当前资料保留草稿，请核对后再保存。' : '已采用当前资料。'
}

async function reload() {
  if (
    profile.value &&
    displayName.value !== profile.value.display_name &&
    !window.confirm('重新加载将放弃未保存的显示名，是否继续？')
  )
    return
  await load()
}

async function changePage(kind: 'sessions' | 'consents', delta: number) {
  const owner = context.value
  if (!owner || pending.value) return
  const page = kind === 'sessions' ? sessionPage : consentPage
  const next = page.value + delta
  if (next < 0) return
  await mutate('pagination', async () => {
    if (kind === 'sessions')
      sessions.value = await read(owner.realm, `/me/sessions?size=${pageSize}&page=${next}`)
    else consents.value = await read(owner.realm, `/me/consents?size=${pageSize}&page=${next}`)
    page.value = next
  })
}

function time(value: string) {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '—' : parsed.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <section class="card account-card">
    <div class="tabs" role="tablist" aria-label="个人账号">
      <button
        v-for="tab in tabs"
        :id="`tab-${tab.key}`"
        :key="tab.key"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.key"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>
    <div class="account-content">
      <div v-if="error" class="message error" role="alert">
        <span
          >{{ error }}<small v-if="requestId">问题编号：{{ requestId }}</small></span
        ><button v-if="!conflict" class="text-button" :disabled="!!pending" @click="reload">
          重新加载
        </button>
      </div>
      <div v-if="success" class="message success" role="status">{{ success }}</div>
      <div v-if="loading" role="status" class="loading">正在读取账号信息…</div>

      <form
        v-else-if="activeTab === 'profile' && profile"
        role="tabpanel"
        aria-labelledby="tab-profile"
        class="profile-form"
        @submit.prevent="saveProfile"
      >
        <h2>基本资料</h2>
        <p class="muted">显示名用于辨认账号。登录名由管理员维护。</p>
        <div class="field">
          <label for="profile-username">登录名</label
          ><input
            id="profile-username"
            :value="profile.username"
            readonly
            autocomplete="username"
          />
        </div>
        <div class="field">
          <label for="display-name">显示名</label
          ><input
            id="display-name"
            v-model="displayName"
            :disabled="!!pending"
            maxlength="128"
            required
            autocomplete="name"
          />
        </div>
        <div v-if="conflict" class="conflict-panel" role="status">
          <strong>资料版本发生变化</strong>
          <p>当前保存的显示名：{{ conflict.display_name }}</p>
          <p>你的草稿：{{ displayName }}</p>
          <div class="form-actions">
            <button type="button" class="secondary-button" @click="resolveConflict(false)">
              采用当前资料</button
            ><button type="button" class="secondary-button" @click="resolveConflict(true)">
              保留我的修改
            </button>
          </div>
        </div>
        <div class="form-actions">
          <button type="submit" class="primary-button" :disabled="!!pending || !!conflict">
            {{ pending === 'profile' ? '正在保存…' : '保存资料' }}</button
          ><button type="button" class="secondary-button" :disabled="!!pending" @click="reload">
            重新加载
          </button>
        </div>
      </form>

      <form
        v-else-if="activeTab === 'security'"
        role="tabpanel"
        aria-labelledby="tab-security"
        class="profile-form"
        @submit.prevent="savePassword"
      >
        <h2>修改密码</h2>
        <p class="muted">修改后全部登录失效。敏感操作可能要求重新登录。</p>
        <div class="field">
          <label for="current-password">原密码</label
          ><input
            id="current-password"
            v-model="currentPassword"
            :disabled="!!pending"
            type="password"
            autocomplete="current-password"
            required
            maxlength="128"
          />
        </div>
        <div class="field">
          <label for="new-password">新密码</label
          ><input
            id="new-password"
            v-model="newPassword"
            :disabled="!!pending"
            type="password"
            autocomplete="new-password"
            required
            maxlength="128"
          />
        </div>
        <div class="field">
          <label for="confirm-password">确认新密码</label
          ><input
            id="confirm-password"
            v-model="confirmation"
            :disabled="!!pending"
            type="password"
            autocomplete="new-password"
            required
            maxlength="128"
          />
        </div>
        <button type="submit" class="primary-button" :disabled="!!pending">
          {{ pending === 'password' ? '正在提交…' : '更新密码并退出' }}
        </button>
      </form>

      <div v-else-if="activeTab === 'sessions'" role="tabpanel" aria-labelledby="tab-sessions">
        <div class="section-heading">
          <div>
            <h2>登录会话</h2>
            <p class="muted">撤销会话会同时撤销该次登录关联的应用授权。</p>
          </div>
          <button class="danger-button" :disabled="!!pending" @click="signOutAll">
            退出全部登录
          </button>
        </div>
        <div class="table-wrap">
          <table>
            <caption class="sr-only">
              本人登录会话
            </caption>
            <thead>
              <tr>
                <th scope="col">登录时间</th>
                <th scope="col">最近活动</th>
                <th scope="col">关联应用</th>
                <th scope="col">状态</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="session in sessions" :key="session.id">
                <td>
                  {{ time(session.created_at)
                  }}<span v-if="session.current" class="small-badge">当前登录</span>
                </td>
                <td>{{ time(session.last_activity_at) }}</td>
                <td>
                  <span v-if="!session.applications.length" class="muted">暂无</span
                  ><span
                    v-for="application in session.applications"
                    :key="application"
                    class="scope-badge"
                    >{{ application }}</span
                  >
                </td>
                <td>
                  <span :class="session.active ? 'status-active' : 'muted'">{{
                    session.active ? '有效' : '已失效'
                  }}</span>
                </td>
                <td>
                  <button
                    class="text-button danger-text"
                    :disabled="!!pending || !session.active"
                    @click="revokeSession(session)"
                  >
                    {{ pending === session.id ? '正在撤销…' : '撤销' }}
                  </button>
                </td>
              </tr>
              <tr v-if="!sessions.length">
                <td colspan="5" class="empty">暂无登录会话。</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <span>第 {{ sessionPage + 1 }} 页</span
          ><button
            class="secondary-button"
            :disabled="!!pending || sessionPage === 0"
            @click="changePage('sessions', -1)"
          >
            上一页</button
          ><button
            class="secondary-button"
            :disabled="!!pending || sessions.length < pageSize"
            @click="changePage('sessions', 1)"
          >
            下一页
          </button>
        </div>
      </div>

      <div v-else-if="activeTab === 'consents'" role="tabpanel" aria-labelledby="tab-consents">
        <h2>应用授权</h2>
        <p class="muted">撤销后，该应用需要重新申请授权，其他应用保持独立。</p>
        <div class="table-wrap">
          <table>
            <caption class="sr-only">
              本人已同意的应用授权
            </caption>
            <thead>
              <tr>
                <th scope="col">应用</th>
                <th scope="col">已同意范围</th>
                <th scope="col">状态</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="consent in consents" :key="consent.client_id">
                <td>
                  <strong>{{ consent.name }}</strong
                  ><small class="muted">{{ consent.client_id }}</small>
                </td>
                <td>
                  <span v-for="scope in consent.scopes" :key="scope" class="scope-badge">{{
                    scope
                  }}</span>
                </td>
                <td>{{ consent.status === 'ACTIVE' ? '启用' : '已停用' }}</td>
                <td>
                  <button
                    class="text-button danger-text"
                    :disabled="!!pending"
                    @click="revokeConsent(consent)"
                  >
                    {{ pending === consent.client_id ? '正在撤销…' : '撤销授权' }}
                  </button>
                </td>
              </tr>
              <tr v-if="!consents.length">
                <td colspan="4" class="empty">暂无需要管理的应用同意记录。</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <span>第 {{ consentPage + 1 }} 页</span
          ><button
            class="secondary-button"
            :disabled="!!pending || consentPage === 0"
            @click="changePage('consents', -1)"
          >
            上一页</button
          ><button
            class="secondary-button"
            :disabled="!!pending || consents.length < pageSize"
            @click="changePage('consents', 1)"
          >
            下一页
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
