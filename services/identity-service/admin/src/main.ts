import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import AdminApp from './app/AdminApp.vue'
import MonitoringPage from './pages/platform/MonitoringPage.vue'
import AccountPage from './pages/account/AccountPage.vue'
import PlatformAccountsPage from './pages/platform/PlatformAccountsPage.vue'
import UsersPage from './pages/users/UsersPage.vue'
import PermissionCatalogPage from './pages/permissions/PermissionCatalogPage.vue'
import GroupsPage from './pages/groups/GroupsPage.vue'
import RoleDetailsPage from './pages/roles/RoleDetailsPage.vue'
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
      path: '/platform/tenants/:managementTenant/identity/roles/:roleId',
      component: RoleDetailsPage,
      meta: { title: '角色详情', section: '项目管理' },
    },
    {
      path: '/platform/monitoring',
      component: MonitoringPage,
      meta: { title: '服务监控', section: '平台管理' },
    },
    {
      path: '/platform/clients',
      component: ClientsPage,
      meta: { title: '接入应用', section: '平台管理' },
    },
    {
      path: '/platform/security',
      component: SecurityPolicyPage,
      meta: { title: '项目安全策略', section: '平台管理' },
    },
    {
      path: '/platform/tenants/:managementTenant/identity/users',
      component: UsersPage,
      meta: { title: '用户管理', section: '项目管理' },
    },
    {
      path: '/platform/tenants/:managementTenant/identity/roles',
      component: RolesPage,
      meta: { title: '角色管理', section: '项目管理' },
    },
    {
      path: '/platform/permissions',
      component: PermissionCatalogPage,
      meta: { title: '权限管理', section: '项目管理' },
    },
    {
      path: '/platform/tenants/:managementTenant/identity/groups',
      component: GroupsPage,
      meta: { title: '用户组', section: '项目管理' },
    },
    {
      path: '/platform/tenants/:managementTenant/identity/sessions',
      component: SessionsPage,
      meta: { title: '会话管理', section: '项目管理' },
    },
    {
      path: '/platform/tenants/:managementTenant/identity/audits',
      component: AuditsPage,
      meta: { title: '租户审计', section: '项目管理' },
    },
    { path: '/', redirect: '/account' },
    { path: '/account', component: AccountPage },
    {
      path: '/platform/audits',
      component: AuditsPage,
      meta: {
        title: '审计日志',
        section: '安全管理',
        description: '按当前身份范围查询管理与认证记录，查看脱敏差异。',
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
