<script setup lang="ts">
import { computed, onMounted, provide, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import {
  brand,
  current,
  IdentityError,
  login,
  logout,
  type Brand,
  type Me,
  type Realm,
} from '../api/identity'
import { accountContext, type AccountContext } from './context'
import { can } from './permissions'

const route = useRoute()
const title = computed(() => (typeof route.meta.title === 'string' ? route.meta.title : '个人账号'))
const section = computed(() =>
  typeof route.meta.section === 'string' ? route.meta.section : '账号中心',
)
const description = computed(() =>
  typeof route.meta.description === 'string'
    ? route.meta.description
    : '查看当前身份，管理资料、登录会话和应用授权。',
)

const branding = ref<Brand | null>(null)
const busy = ref(true)
const error = ref('')
const notice = ref('')
const realm = ref<Realm | null>(null)
const me = ref<Me | null>(null)
const monitoring = ref(false)
watch(me, async (value, _old, cleanup) => {
  monitoring.value = false
  if (value?.principal_type !== 'platform' || !can(value, 'platform:monitoring:read')) return
  const controller = new AbortController()
  cleanup(() => controller.abort())
  try {
    const response = await fetch('/bff/identity/platform/monitoring-availability', {
      credentials: 'same-origin',
      signal: controller.signal,
    })
    if (response.ok) monitoring.value = (await response.json()).enabled === true
  } catch {
    /* 不让可选监控影响账号页面。 */
  }
})
const available = ref<Partial<Record<Realm, Me>>>({})
const loginRealm = ref<Realm>('tenant')
const tenantCode = ref('')
const entering = ref(false)
const leaving = ref(false)
const realms: Realm[] = ['tenant', 'platform']

function failure(problem: unknown) {
  if (problem instanceof IdentityError && problem.status === 401) {
    if (realm.value) delete available.value[realm.value]
    realm.value = null
    me.value = null
    error.value = problem.message
  }
}

function signedOut() {
  if (realm.value) delete available.value[realm.value]
  realm.value = null
  me.value = null
  notice.value = '此账号已退出，请重新登录。'
}

async function refreshActive() {
  if (!realm.value) return
  const value = await current(realm.value)
  me.value = value
  available.value[realm.value] = value
}

const context = computed<AccountContext | null>(() =>
  realm.value && me.value
    ? {
        realm: realm.value,
        me: me.value,
        changed: refreshActive,
        signedOut,
        failed: failure,
      }
    : null,
)
provide(accountContext, () => context.value)

async function restore() {
  if (entering.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    branding.value = await brand()
    document.title = `${branding.value.project_display_name} · ${branding.value.admin_title}`
    const results = await Promise.allSettled(realms.map((value) => current(value)))
    available.value = {}
    results.forEach((result, index) => {
      const key = realms[index]
      if (result.status === 'fulfilled') available.value[key] = result.value
      else if (!(result.reason instanceof IdentityError && result.reason.status === 401)) {
        error.value =
          result.reason instanceof IdentityError ? result.reason.message : '暂时无法检查登录状态。'
      }
    })
    let preferred: Realm = 'tenant'
    let explicitPreference = false
    try {
      const stored = sessionStorage.getItem('identity.admin.realm')
      explicitPreference = stored === 'platform' || stored === 'tenant'
      if (stored === 'platform') preferred = 'platform'
    } catch {
      /* 仅界面偏好。 */
    }
    const selected = available.value[preferred]
      ? preferred
      : explicitPreference
        ? undefined
        : realms.find((value) => available.value[value])
    realm.value = selected ?? null
    me.value = selected ? (available.value[selected] ?? null) : null
    loginRealm.value = preferred
  } catch (problem) {
    error.value = problem instanceof IdentityError ? problem.message : '管理服务暂时不可用。'
  } finally {
    busy.value = false
  }
}

async function select(value: Realm) {
  if (busy.value || leaving.value) return
  error.value = ''
  notice.value = ''
  busy.value = true
  try {
    const identity = await current(value)
    realm.value = value
    me.value = identity
    available.value[value] = identity
    try {
      sessionStorage.setItem('identity.admin.realm', value)
    } catch {
      /* 仅界面偏好。 */
    }
  } catch (problem) {
    delete available.value[value]
    error.value = problem instanceof IdentityError ? problem.message : '暂时无法切换登录入口。'
  } finally {
    busy.value = false
  }
}

async function enter() {
  if (entering.value) return
  entering.value = true
  error.value = ''
  notice.value = ''
  try {
    await login(loginRealm.value, tenantCode.value.trim())
  } catch (problem) {
    entering.value = false
    error.value = problem instanceof IdentityError ? problem.message : '暂时无法开始登录。'
  }
}

async function leave() {
  if (!realm.value || leaving.value) return
  leaving.value = true
  error.value = ''
  notice.value = ''
  try {
    await logout(realm.value)
    signedOut()
    notice.value = '已退出当前应用。'
  } catch (problem) {
    error.value =
      problem instanceof IdentityError ? problem.message : '退出结果尚未确认，请重新检查状态。'
  } finally {
    leaving.value = false
  }
}

function changeLogin(value: Realm) {
  loginRealm.value = value
  realm.value = null
  me.value = null
  error.value = ''
  notice.value = ''
}

onMounted(restore)
</script>

<template>
  <div class="admin-shell">
    <aside class="sidebar">
      <div class="brand-lockup">
        <img
          v-if="branding?.logo_url"
          :src="branding.logo_url"
          alt="项目标志"
          class="brand-image"
        />
        <span v-else class="brand-symbol" aria-hidden="true">K</span>
        <div>
          <strong>{{ branding?.project_display_name ?? '身份管理' }}</strong
          ><span>身份与访问管理</span>
        </div>
      </div>
      <div class="nav-label">身份管理</div>
      <nav aria-label="管理导航">
        <RouterLink
          v-if="monitoring && me?.principal_type === 'platform'"
          to="/platform/monitoring"
          class="nav-link"
          >服务监控<span>运行指标与故障定位</span></RouterLink
        >
        <RouterLink
          v-if="me?.principal_type === 'platform' && can(me, 'platform:client:read')"
          to="/platform/clients"
          class="nav-link"
          >应用与机器身份<span>客户端、回调与服务 audience</span></RouterLink
        >
        <RouterLink
          v-if="me?.principal_type === 'platform' && can(me, 'platform:security:read')"
          to="/platform/security"
          class="nav-link"
          >项目安全策略<span>密码、登录限制与会话期限</span></RouterLink
        >
        <RouterLink to="/account" class="nav-link"
          >个人账号<span>资料、会话与授权</span></RouterLink
        >
        <RouterLink
          v-if="me?.principal_type === 'platform' && can(me, 'platform:tenant:read')"
          to="/platform/tenants"
          class="nav-link"
          >租户管理<span>组织生命周期与初始管理员</span></RouterLink
        >
        <RouterLink
          v-if="me?.principal_type === 'platform' && can(me, 'platform:admin:read')"
          to="/platform/admins"
          class="nav-link"
          >平台管理员<span>本项目的平台管理账号</span></RouterLink
        >
        <RouterLink
          v-if="me?.principal_type === 'user' && can(me, 'identity:user:read')"
          to="/users"
          class="nav-link"
          >用户管理<span>组织账号、资料与有效权限</span></RouterLink
        >
        <RouterLink
          v-if="me?.principal_type === 'user' && can(me, 'identity:permission:read')"
          to="/permissions"
          class="nav-link"
          >权限与授权<span>资源目录、范围与角色授权</span></RouterLink
        >
        <RouterLink
          v-if="me?.principal_type === 'user' && can(me, 'identity:role:read')"
          to="/roles"
          class="nav-link"
          >角色管理<span>角色资料、复制与管理边界</span></RouterLink
        >
        <RouterLink
          v-if="
            me &&
            ((me.principal_type === 'platform' && can(me, 'platform:audit:read')) ||
              (me.principal_type === 'user' && can(me, 'identity:audit:read')))
          "
          to="/audits"
          class="nav-link"
          >管理审计<span>操作记录、请求与脱敏明细</span></RouterLink
        >
        <RouterLink
          v-if="me?.principal_type === 'user' && can(me, 'identity:session:read')"
          to="/sessions"
          class="nav-link"
          >会话管理<span>登录记录与受限撤销</span></RouterLink
        >
      </nav>
      <div class="sidebar-bottom"><span class="status-dot" /> 独立项目 · 独立身份</div>
    </aside>

    <div class="workspace">
      <header class="topbar">
        <span class="breadcrumb">{{ section }} <span>/</span> {{ title }}</span>
        <div v-if="context" class="current-user">
          <span class="realm-badge">{{ realm === 'platform' ? '平台管理员' : '租户账号' }}</span>
          <span>{{ me?.profile.name ?? me?.profile.preferred_username }}</span>
          <button class="text-button" :disabled="leaving" @click="leave">
            {{ leaving ? '正在退出…' : '退出本应用' }}
          </button>
        </div>
      </header>

      <main class="main-content">
        <div v-if="error" class="message error" role="alert">
          <span>{{ error }}</span
          ><button class="text-button" :disabled="busy" @click="restore">重新检查状态</button>
        </div>
        <div v-if="notice" class="message success" role="status">{{ notice }}</div>
        <div v-if="busy" class="loading" role="status">正在检查项目与登录状态…</div>

        <template v-else-if="context">
          <div class="page-heading">
            <div>
              <p class="eyebrow">{{ section }}</p>
              <h1>{{ title }}</h1>
              <p>{{ description }}</p>
            </div>
            <button class="secondary-button" @click="changeLogin(realm ?? 'tenant')">
              重新登录 / 更换账号
            </button>
          </div>
          <div class="identity-strip">
            <div>
              <span>当前入口</span
              ><strong>{{ realm === 'platform' ? '平台管理' : '租户管理' }}</strong>
            </div>
            <div>
              <span>登录账号</span><strong>{{ me?.profile.preferred_username }}</strong>
            </div>
            <div v-if="me?.tenant_id">
              <span>组织 ID</span><code>{{ me.tenant_id }}</code>
            </div>
            <div
              v-if="available[realm === 'tenant' ? 'platform' : 'tenant']"
              class="identity-action"
            >
              <button
                class="text-button"
                @click="select(realm === 'tenant' ? 'platform' : 'tenant')"
              >
                切换到另一管理入口
              </button>
            </div>
          </div>
          <RouterView :key="`${realm}-${me?.sub}`" />
        </template>

        <section v-else class="login-layout">
          <div class="login-intro">
            <p class="eyebrow">{{ branding?.project_display_name ?? '项目账号中心' }}</p>
            <h1>安全地管理<br />每一次访问</h1>
            <p>从对应入口登录，查看自己的账号资料和授权。不同项目、不同组织的账号分别管理。</p>
            <div class="intro-rule" />
            <span>资料 · 登录会话 · 应用授权</span>
          </div>
          <form class="card login-card" @submit.prevent="enter">
            <div class="card-heading">
              <h2>{{ branding?.login_title ?? '账号登录' }}</h2>
              <p>请选择本次登录入口。</p>
            </div>
            <div class="realm-picker" aria-label="登录入口">
              <button
                type="button"
                :class="{ selected: loginRealm === 'tenant' }"
                :aria-pressed="loginRealm === 'tenant'"
                @click="loginRealm = 'tenant'"
              >
                租户账号
              </button>
              <button
                type="button"
                :class="{ selected: loginRealm === 'platform' }"
                :aria-pressed="loginRealm === 'platform'"
                @click="loginRealm = 'platform'"
              >
                平台管理员
              </button>
            </div>
            <div v-if="loginRealm === 'tenant'" class="field">
              <label for="tenant-code">组织代码</label
              ><input
                id="tenant-code"
                v-model="tenantCode"
                name="tenant_code"
                maxlength="64"
                pattern="[a-z][a-z0-9-]{0,63}"
                required
                autocomplete="organization"
                placeholder="输入管理员提供的组织代码"
              /><small>账号在所属组织内独立，请确认组织代码。</small>
            </div>
            <p v-else class="muted login-hint">平台管理员管理本项目的租户与平台配置。</p>
            <button type="submit" class="primary-button wide" :disabled="entering || !branding">
              {{ entering ? '正在进入登录…' : '继续登录' }}
            </button>
            <div v-if="available.tenant || available.platform" class="existing-sessions">
              <span>已有登录</span
              ><button
                v-for="value in realms.filter((value) => available[value])"
                :key="value"
                type="button"
                class="text-button"
                @click="select(value)"
              >
                {{ value === 'platform' ? '进入平台管理' : '进入租户管理' }}
              </button>
            </div>
          </form>
        </section>
      </main>
    </div>
  </div>
</template>
