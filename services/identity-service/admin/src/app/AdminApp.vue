<script setup lang="ts">
import { computed, onMounted, provide, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  brand,
  current,
  IdentityError,
  login,
  logout,
  read,
  type Brand,
  type Me,
} from '../api/identity'
import { accountContext, type AccountContext } from './context'
import { can } from './permissions'
import type { Page, PlatformTenant } from '../api/platform'
import TenantPicker from '../components/TenantPicker.vue'

const route = useRoute()
const router = useRouter()
const initialEntry = window.location.pathname.replace(/\/$/, '') === '/identity/admin'
const targetTenant = computed(() =>
  typeof route.params.managementTenant === 'string' ? route.params.managementTenant : undefined,
)
const title = computed(() => (typeof route.meta.title === 'string' ? route.meta.title : '个人账号'))
const section = computed(() =>
  typeof route.meta.section === 'string' ? route.meta.section : '账号中心',
)
const branding = ref<Brand | null>(null)
const busy = ref(true)
const error = ref('')
const notice = ref('')
const me = ref<Me | null>(null)
const selectedTenant = ref('')
watch(
  targetTenant,
  (value) => {
    if (value) selectedTenant.value = value
  },
  { immediate: true },
)
const tenantLinks = [
  { label: '用户管理', path: 'users' },
  { label: '用户组', path: 'groups' },
  { label: '角色管理', path: 'roles' },
]
const tenantPath = (path: string) =>
  selectedTenant.value
    ? `/platform/tenants/${selectedTenant.value}/identity/${path}`
    : '/platform/tenants'
const entering = ref(false)
const leaving = ref(false)

function failure(problem: unknown) {
  if (problem instanceof IdentityError && problem.status === 401) {
    me.value = null
    void enter()
  }
}

function signedOut() {
  me.value = null
  void enter()
}

async function refreshActive() {
  me.value = await current()
}

const context = computed<AccountContext | null>(() =>
  me.value
    ? {
        realm: 'platform',
        targetTenant: targetTenant.value,
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
    me.value = await current()
    if (can(me.value, 'platform:tenant:read')) {
      const tenants = await read<Page<PlatformTenant>>(
        'platform',
        '/platform/tenants?page=0&size=100',
      )
      const active = tenants.items.filter(
        (item) =>
          item.status === 'ACTIVE' &&
          (!item.expires_at || Date.parse(item.expires_at) > Date.now()),
      )
      if (!selectedTenant.value)
        selectedTenant.value = !tenants.has_more && active.length === 1 ? active[0].id : ''
    }
    if (initialEntry && route.path === '/account')
      await router.replace(selectedTenant.value ? tenantPath('users') : '/platform/tenants')
  } catch (problem) {
    me.value = null
    if (problem instanceof IdentityError && problem.status === 401) await enter()
    else error.value = problem instanceof IdentityError ? problem.message : '管理服务暂时不可用。'
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
    await login()
  } catch (problem) {
    entering.value = false
    error.value = problem instanceof IdentityError ? problem.message : '暂时无法开始登录。'
  }
}

async function leave() {
  if (!me.value || leaving.value) return
  leaving.value = true
  error.value = ''
  notice.value = ''
  try {
    await logout('platform')
    signedOut()
    notice.value = '已退出当前应用。'
  } catch (problem) {
    error.value =
      problem instanceof IdentityError ? problem.message : '退出结果尚未确认，请重新检查状态。'
  } finally {
    leaving.value = false
  }
}

function changeLogin() {
  me.value = null
  error.value = ''
  notice.value = ''
  void enter()
}

onMounted(restore)
</script>

<template>
  <div class="admin-shell" :class="{ 'admin-auth': !context }">
    <aside v-if="context" class="sidebar">
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
          ><span>项目管理控制台</span>
        </div>
      </div>
      <nav aria-label="管理导航">
        <div class="nav-label">项目管理</div>
        <RouterLink v-if="can(me!, 'platform:tenant:read')" to="/platform/tenants" class="nav-link"
          >租户管理</RouterLink
        >
        <template v-if="can(me!, 'platform:identity:read')">
          <RouterLink
            v-for="item in tenantLinks"
            :key="item.path"
            :to="tenantPath(item.path)"
            active-class=""
            exact-active-class=""
            class="nav-link"
            :class="{
              'router-link-active': !!targetTenant && route.path.includes(`/identity/${item.path}`),
            }"
            >{{ item.label }}</RouterLink
          >
        </template>
        <RouterLink
          v-if="can(me!, 'platform:permission:read')"
          to="/platform/permissions"
          class="nav-link"
          >权限管理</RouterLink
        >
        <RouterLink v-if="can(me!, 'platform:audit:read')" to="/platform/audits" class="nav-link"
          >审计日志</RouterLink
        >
        <RouterLink
          v-if="can(me!, 'platform:monitoring:read')"
          to="/platform/monitoring"
          class="nav-link"
          >服务监控</RouterLink
        >
        <details class="settings-nav">
          <summary class="nav-label">系统设置</summary>
          <RouterLink
            v-if="can(me!, 'platform:client:read')"
            to="/platform/clients"
            class="nav-link"
            >接入应用</RouterLink
          >
          <RouterLink
            v-if="can(me!, 'platform:security:read')"
            to="/platform/security"
            class="nav-link"
            >安全策略</RouterLink
          >
          <RouterLink v-if="can(me!, 'platform:admin:read')" to="/platform/admins" class="nav-link"
            >平台管理员</RouterLink
          >
        </details>
        <RouterLink to="/account" class="nav-link account-nav">个人账号</RouterLink>
      </nav>
    </aside>

    <div class="workspace">
      <header v-if="context" class="topbar">
        <span class="breadcrumb">{{ section }} <span>/</span> {{ title }}</span>
        <div v-if="context" class="current-user">
          <span class="realm-badge">平台管理员</span>
          <span>{{ me?.profile.name ?? me?.profile.preferred_username }}</span>
          <button class="text-button" @click="changeLogin()">更换账号</button>
          <button class="text-button" :disabled="leaving" @click="leave">
            {{ leaving ? '正在退出…' : '退出本应用' }}
          </button>
        </div>
      </header>

      <main class="main-content" :class="{ 'auth-main': !context }">
        <div v-if="error" class="message error" role="alert">
          <span>{{ error }}</span
          ><button class="text-button" :disabled="busy" @click="restore">重新检查状态</button>
        </div>
        <div v-if="notice" class="message success" role="status">{{ notice }}</div>
        <div v-if="busy" class="loading" role="status">正在检查项目与登录状态…</div>

        <template v-else-if="context">
          <TenantPicker
            v-if="targetTenant"
            :model-value="[targetTenant]"
            single
            @update:model-value="
              (ids) =>
                router.push(
                  `/platform/tenants/${encodeURIComponent(ids[0])}/identity/${route.path.includes('/roles/') ? 'roles' : String(route.path.split('/').pop())}`,
                )
            "
          />
          <div class="page-heading">
            <h1>{{ title }}</h1>
          </div>
          <RouterView :key="`${me?.sub}-${targetTenant ?? ''}-${route.params.roleId ?? ''}`" />
        </template>

        <section v-else aria-labelledby="admin-login-title">
          <div class="auth-brand">
            <img
              v-if="branding?.logo_url"
              :src="branding.logo_url"
              class="auth-logo"
              alt="项目标志"
            />
            <span v-else class="auth-mark" aria-hidden="true">K</span>
            <div>
              <strong>{{ branding?.project_display_name ?? '项目管理' }}</strong
              ><small>身份管理控制台</small>
            </div>
          </div>
          <h1 id="admin-login-title">平台管理员登录</h1>
          <p v-if="entering" class="auth-description" role="status">正在打开登录页…</p>
          <form v-if="!entering" @submit.prevent="enter">
            <button type="submit" class="auth-submit" :disabled="entering || !branding">
              重新打开登录页
            </button>
          </form>
          <p class="auth-footer">仅限本项目平台管理员使用</p>
        </section>
      </main>
    </div>
  </div>
</template>
