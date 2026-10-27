import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import AdminApp from './app/AdminApp.vue'
import MonitoringPage from './pages/platform/MonitoringPage.vue'
import AccountPage from './pages/account/AccountPage.vue'
import PlatformAccountsPage from './pages/platform/PlatformAccountsPage.vue'
import UsersPage from './pages/users/UsersPage.vue'
import PermissionsPage from './pages/permissions/PermissionsPage.vue'
import RolesPage from './pages/roles/RolesPage.vue'
import AuditsPage from './pages/audit/AuditsPage.vue'
import SessionsPage from './pages/sessions/SessionsPage.vue'
import ClientsPage from './pages/platform/ClientsPage.vue'
import SecurityPolicyPage from './pages/platform/SecurityPolicyPage.vue'
import './app/style.css'

const router = createRouter({
  history: createWebHistory('/identity/admin/'),
  routes: [
    {
      path: '/platform/monitoring',
      component: MonitoringPage,
      meta: { title: '服务监控', section: '平台管理' },
    },
    {
      path: '/platform/clients',
      component: ClientsPage,
      meta: { title: '应用与机器身份', section: '平台管理' },
    },
    {
      path: '/platform/security',
      component: SecurityPolicyPage,
      meta: { title: '项目安全策略', section: '平台管理' },
    },
    { path: '/', redirect: '/account' },
    { path: '/account', component: AccountPage },
    {
      path: '/sessions',
      component: SessionsPage,
      meta: {
        title: '会话管理',
        section: '安全管理',
        description: '查看组织内可管理的登录会话，明确撤销指定或全部授权。',
      },
    },
    {
      path: '/audits',
      component: AuditsPage,
      meta: {
        title: '管理审计',
        section: '安全管理',
        description: '按当前身份范围查询管理与认证记录，查看脱敏差异。',
      },
    },
    {
      path: '/roles',
      component: RolesPage,
      meta: {
        title: '角色管理',
        section: '租户管理',
        description: '维护角色基本资料，按真实管理边界复制和启停角色。',
      },
    },
    {
      path: '/permissions',
      component: PermissionsPage,
      meta: {
        title: '权限与授权',
        section: '租户管理',
        description: '按服务查看资源操作，核对角色授权与数据范围。',
      },
    },
    {
      path: '/users',
      component: UsersPage,
      meta: {
        title: '用户管理',
        section: '租户管理',
        description: '管理当前组织内的账号、资料与授权状态。',
      },
    },
    {
      path: '/platform/tenants',
      component: PlatformAccountsPage,
      props: { kind: 'tenants' },
      meta: {
        title: '租户管理',
        section: '平台管理',
        description: '管理本项目的组织、有效期限与初始管理员。',
      },
    },
    {
      path: '/platform/admins',
      component: PlatformAccountsPage,
      props: { kind: 'admins' },
      meta: {
        title: '平台管理员',
        section: '平台管理',
        description: '维护本项目的平台管理账号，平台账号独立于租户账号。',
      },
    },
    { path: '/:pathMatch(.*)*', redirect: '/account' },
  ],
})
createApp(AdminApp).use(router).mount('#app')
