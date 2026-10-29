// 专用候选环境验收；密码与 token 不写入日志或截图。
import { chromium, expect } from '@playwright/test'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { randomUUID, createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import path from 'node:path'

const bindingPath = process.env.PLATFORM_TEST_ADMIN_E2E_BINDING
const binding = JSON.parse(await readFile(bindingPath, 'utf8'))
assert.equal(binding.test_only, true)
assert.equal(binding.project, 'qa-admin-20261028')
assert.equal(binding.origin, 'http://localhost:28080')
const origin = binding.origin
await mkdir(binding.artifacts, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: 'msedge' })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
page.setDefaultTimeout(20000)
page.on('dialog', (dialog) => dialog.accept())
let stage = 'login',
  pageErrors = 0
const passed = []
page.on('pageerror', () => pageErrors++)
function next(name) {
  passed.push(stage)
  stage = name
  console.log(name)
}
async function api(tail, method = 'GET', data) {
  const headers = { Origin: origin, 'X-Request-ID': randomUUID() }
  if (method !== 'GET') {
    const csrf = await context.request.get(origin + '/bff/identity/platform/csrf')
    assert.equal(csrf.status(), 200, 'csrf')
    const value = (await csrf.json()).data
    headers[value.header_name] = value.token
  }
  return context.request.fetch(origin + '/bff/identity/platform/api' + tail, {
    method,
    headers,
    ...(data ? { data } : {}),
  })
}
async function data(tail, method = 'GET', body) {
  const response = await api(tail, method, body)
  const json = await response.json()
  assert.equal(response.status(), 200, `${method} ${tail}: ${json.code}`)
  return json.data
}
async function shot(name) {
  for (const [width, height] of [
    [1280, 800],
    [1440, 900],
    [1920, 1080],
  ]) {
    await page.setViewportSize({ width, height })
    assert.ok(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      name + ' 横向溢出',
    )
    await page.screenshot({
      path: path.join(binding.artifacts, `${name}-${width}.png`),
      fullPage: true,
    })
  }
  await page.setViewportSize({ width: 1440, height: 900 })
}
try {
  await page.goto(origin + '/identity/admin/')
  async function login(password) {
    await page.locator('input[name=username]').fill(binding.username)
    await page.locator('input[name=password]').fill(password)
    await page.locator('button[type=submit]').click()
  }
  await login((await readFile(binding.password_file, 'utf8')).trim())
  if (binding.initial) {
    await page.waitForURL('**/identity/password/initial')
    const secret = 'Console QA ' + randomUUID()
    await page.locator('input[name=password]').fill(secret)
    await page.locator('input[name=confirmation]').fill(secret)
    await page.locator('button[type=submit]').click()
    await writeFile(binding.password_file, secret)
    binding.initial = false
    await writeFile(bindingPath, JSON.stringify(binding))
    await login(secret)
  }
  await page.locator('.sidebar').waitFor()
  const availableTenants = await data('/platform/tenants?size=100')
  if (availableTenants.items.length === 1) await page.waitForURL('**/identity/users')
  await page.goto(origin + '/identity/admin/platform/tenants')
  await page.getByRole('heading', { name: '租户管理', exact: true }).first().waitFor()
  next('tenant-creation')
  await page.getByRole('button', { name: '新建租户', exact: true }).click()
  const suffix = randomUUID().slice(0, 8),
    code = 'console-' + suffix
  await page.locator('#tenant-code-create').fill(code)
  await page.locator('#tenant-name').fill('组织验收项目')
  await page.locator('#initial-admin-name').fill('admin')
  await page.locator('#initial-admin-display').fill('项目管理员')
  await page.locator('dialog[open]').getByRole('button', { name: '创建', exact: true }).click()
  await page.getByLabel('初始密码', { exact: true }).waitFor()
  await page.getByRole('button', { name: '已记录，关闭', exact: true }).click()
  const tenant = (await data('/platform/tenants?q=' + code)).items[0]
  assert.ok(tenant)
  const target = '/platform/tenants/' + tenant.id + '/identity'
  await shot('tenants')
  next('organization-ui')
  await page.goto(origin + '/identity/admin' + target + '/groups')
  await page.getByRole('button', { name: '＋ 顶层', exact: true }).click()
  await page.getByLabel('组织编码', { exact: true }).fill('company')
  await page.getByLabel('组织名称', { exact: true }).fill('示例公司')
  await page.locator('dialog[open]').getByRole('button', { name: '保存', exact: true }).click()
  await page.getByText('已保存。', { exact: true }).waitFor()
  const company = (await data(target + '/groups')).items[0]
  const department = await data(target + '/groups', 'POST', {
    code: 'engineering',
    name: '研发部',
    parent_id: company.id,
    organization_version: company.organization_version,
  })
  const team = await data(target + '/groups', 'POST', {
    code: 'backend',
    name: '后端组',
    parent_id: department.id,
    organization_version: department.organization_version,
  })
  await page.reload()
  await page.getByRole('button', { name: '展开 示例公司', exact: true }).click()
  await page.getByRole('button', { name: '展开 研发部', exact: true }).click()
  await page.getByRole('button', { name: '后端组', exact: true }).click()
  await expect(page.getByRole('heading', { name: '后端组', exact: true })).toBeVisible()
  await shot('organization')
  next('full-user-ui')
  await page.goto(origin + '/identity/admin' + target + '/users')
  await page.getByRole('button', { name: '新建用户', exact: true }).click()
  const username = 'developer-' + suffix
  await page.getByLabel('登录账号', { exact: true }).fill(username)
  await page.getByLabel('显示名', { exact: true }).fill('研发成员')
  await page.getByLabel('真实姓名', { exact: true }).fill('测试工程师')
  await page.getByLabel('工号', { exact: true }).fill('E-' + suffix)
  await page.getByLabel('联系邮箱', { exact: true }).fill('engineer@example.test')
  await page.getByLabel('联系电话', { exact: true }).fill('13800000000')
  await page.getByLabel('备注', { exact: true }).fill('组织与授权验收')
  await page.locator('dialog[open]').getByRole('button', { name: '创建', exact: true }).click()
  await page.getByLabel('初始密码', { exact: true }).waitFor()
  await page.getByRole('button', { name: '已记录，关闭', exact: true }).click()
  let user = (await data(target + '/users?q=' + username)).items[0]
  assert.equal(user.real_name, '测试工程师')
  assert.equal(user.contact_email, 'engineer@example.test')
  await data(target + '/users/' + user.id + '/groups', 'PUT', {
    user_version: user.version,
    groups: [{ id: team.id, version: team.version }],
  })
  await page.reload()
  await expect(page.getByText('后端组', { exact: true }).first()).toBeVisible()
  await shot('users')
  next('manual-permission-ui')
  await page.goto(origin + '/identity/admin/platform/permissions')
  await page.getByRole('button', { name: '新建权限', exact: true }).click()
  await page.locator('dialog[open]').getByLabel('所属服务').selectOption('equipment-service')
  for (const [label, value] of Object.entries({
    权限编码: 'console-' + suffix + ':read',
    权限名称: '读取项目资料',
    分组编码: 'console',
    分组名称: '项目资料',
    资源编码: 'console-' + suffix,
    资源名称: '项目资料',
    动作编码: 'read',
    'OAuth scope': 'equipment-service.items.read',
  }))
    await page.getByLabel(label, { exact: true }).fill(value)
  await page.getByLabel('本人 SELF', { exact: true }).check()
  await page.getByLabel('全租户 TENANT', { exact: true }).check()
  await page.locator('dialog[open]').getByRole('button', { name: '保存权限', exact: true }).click()
  await expect(page.locator('dialog')).not.toBeVisible()
  assert.equal(
    (await data('/platform/permissions/console-' + suffix + ':read')).permission.source,
    'MANUAL',
  )
  await page.getByPlaceholder('搜索编码或名称').fill('console-' + suffix + ':read')
  await page.locator('.filter-bar').getByRole('button', { name: '查询', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: 'console-' + suffix + ':read' })
    .getByRole('button', { name: '编辑 / 引用', exact: true })
    .click()
  await expect(
    page.locator('dialog[open]').getByLabel('权限编码', { exact: true }),
  ).toHaveAttribute('readonly', '')
  await page
    .locator('dialog[open]')
    .getByLabel('权限名称', { exact: true })
    .fill('读取项目资料（已核对）')
  await page.locator('dialog[open]').getByRole('button', { name: '保存权限', exact: true }).click()
  await expect(page.locator('dialog')).not.toBeVisible()
  assert.equal(
    (await data('/platform/permissions/console-' + suffix + ':read')).permission.name,
    '读取项目资料（已核对）',
  )
  await shot('permissions')
  next('roles-and-group-authorization')
  const role = await data(target + '/roles', 'POST', {
    code: 'engineer-' + suffix,
    name: '工程师',
    description: '验收角色',
  })
  const catalog = await data(target + '/permissions?size=100')
  const grants = catalog.items
    .filter((entry) => entry.code.endsWith('-item:read'))
    .map((entry) => ({ permission_code: entry.code, data_scope: 'SELF' }))
  assert.equal(grants.length, 2)
  let roleState = await data(target + '/role-permissions/' + role.id)
  await data(target + '/role-permissions/' + role.id, 'PUT', {
    role_version: roleState.role.version,
    catalog_version: roleState.catalog_version,
    grants,
  })
  roleState = await data(target + '/role-permissions/' + role.id)
  let teamState = await data(target + '/groups/' + team.id)
  await data(target + '/groups/' + team.id + '/roles', 'PUT', {
    group_version: teamState.version,
    roles: [{ id: role.id, version: roleState.role.version }],
  })
  await page.goto(origin + '/identity/admin' + target + '/roles/' + role.id)
  await page.getByLabel('角色名称', { exact: true }).waitFor()
  await shot('roles')
  await page.getByRole('tab', { name: '权限授权', exact: true }).click()
  await page
    .getByRole('checkbox', { name: '授权 console-' + suffix + ':read', exact: true })
    .check()
  await page.getByRole('button', { name: '预览授权差异', exact: true }).click()
  await page.getByRole('button', { name: '确认保存授权', exact: true }).click()
  await expect(page.getByText('角色授权已保存并重新读取。', { exact: true })).toBeVisible()
  assert.ok(
    (await data(target + '/role-permissions/' + role.id)).grants.some(
      (grant) => grant.permission_code === 'console-' + suffix + ':read',
    ),
  )
  await page.getByLabel('搜索资源、操作或说明').fill('console-' + suffix)
  await shot('role-authorization')
  next('independent-platform-oauth')
  const clientId = 'console-external-' + suffix,
    redirect = origin + '/console-qa/callback'
  const scopes = [
    'openid',
    'platform.tenants',
    'platform.identity',
    'platform.permissions',
    'platform.audit',
    'platform.monitoring',
  ]
  const externalClient = await data('/platform/oauth-clients', 'POST', {
    client_id: clientId,
    name: '外部管理验收应用',
    principal_type: 'platform',
    owner_tenant_id: null,
    allowed_tenant_ids: [],
    audiences: ['identity-service'],
    scopes,
    redirect_uris: [redirect],
    logout_uris: [],
    public_client: false,
    first_party: true,
    status: 'ACTIVE',
  })
  const oauth = await context.newPage(),
    verifier = randomUUID() + randomUUID(),
    state = randomUUID()
  const parameters = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: redirect,
    scope: scopes.join(' '),
    nonce: randomUUID(),
    state,
    code_challenge: createHash('sha256').update(verifier).digest('base64url'),
    code_challenge_method: 'S256',
  })
  await oauth.goto(origin + '/oauth2/authorize?' + parameters)
  if (await oauth.locator('input[name=username]').isVisible()) {
    await oauth.locator('input[name=username]').fill(binding.username)
    if (await oauth.locator('#realm').isVisible())
      await oauth.locator('#realm').selectOption('PLATFORM')
    await oauth
      .locator('input[name=password]')
      .fill((await readFile(binding.password_file, 'utf8')).trim())
    await oauth.getByRole('button', { name: '登录', exact: true }).click()
  }
  await oauth.waitForURL((url) => url.origin + url.pathname === redirect)
  const callback = new URL(oauth.url())
  assert.equal(callback.searchParams.get('state'), state)
  assert.equal(callback.searchParams.get('error'), null, 'OAuth callback error')
  const tokens = await context.request.post(origin + '/oauth2/token', {
    headers: {
      Authorization:
        'Basic ' + Buffer.from(clientId + ':' + externalClient.client_secret).toString('base64'),
    },
    form: {
      grant_type: 'authorization_code',
      client_id: clientId,
      redirect_uri: redirect,
      code: callback.searchParams.get('code'),
      code_verifier: verifier,
    },
  })
  assert.equal(tokens.status(), 200, 'external code exchange')
  const access = (await tokens.json()).access_token
  const external = await browser.newContext()
  for (const tail of [
    '/platform/tenants',
    target + '/users',
    target + '/groups',
    target + '/roles',
    '/platform/permissions',
    '/platform/audits',
  ]) {
    const response = await external.request.get(origin + '/api/v1/identity' + tail, {
      headers: { Authorization: 'Bearer ' + access },
    })
    assert.equal(response.status(), 200, 'external ' + tail)
  }
  assert.equal(
    (await external.request.get(origin + '/api/v1/identity/platform/tenants')).status(),
    401,
  )
  const monitor = await external.request.get(origin + '/api/v1/platform/monitoring/services', {
    headers: { Authorization: 'Bearer ' + access },
  })
  assert.equal(monitor.status(), 200, 'external monitor')
  const observed = (await monitor.json()).data.items
  assert.ok(observed.some((item) => item.service === 'identity-service' && item.healthy))
  await external.close()
  await oauth.close()
  next('java-python-next-request')
  user = await data(target + '/users/' + user.id)
  const reset = await data(target + '/users/' + user.id + '/password-reset', 'POST', {
    version: user.version,
  })
  const business = await browser.newContext(),
    screen = await business.newPage()
  const password = 'Business QA ' + randomUUID()
  await screen.goto(origin + '/identity/password/reset')
  await screen.locator('input[name=token]').fill(reset.reset_token)
  await screen.locator('input[name=password]').fill(password)
  await screen.locator('input[name=confirmation]').fill(password)
  await screen.locator('button[type=submit]').click()
  await expect(screen.getByText(/密码已更新/)).toBeVisible()
  const effective = await data(target + '/users/' + user.id + '/effective-permissions')
  assert.ok(JSON.stringify(effective).includes('GROUP'))
  const web = (await data('/platform/oauth-clients?q=web-bff')).items.find(
    (item) => item.client_id === 'web-bff',
  )
  const { id: webId, ...definition } = web
  await data('/platform/oauth-clients/' + webId, 'PUT', {
    ...definition,
    allowed_tenant_ids: [...definition.allowed_tenant_ids, tenant.id],
  })
  await screen.goto(origin + '/')
  await screen.locator('[name=tenant_code]').fill(code)
  await screen.locator('#login button').click()
  await screen.locator('#username').fill(username)
  if (await screen.locator('#realm').isVisible())
    await screen.locator('#realm').selectOption('TENANT')
  if (await screen.locator('#tenant').isVisible()) await screen.locator('#tenant').fill(code)
  await screen.locator('#password').fill(password)
  await screen.getByRole('button', { name: '登录', exact: true }).click()
  await screen.waitForURL(origin + '/')
  async function businessStatus(expected) {
    for (const service of ['equipment-service', 'inspection-service']) {
      const response = await business.request.get(origin + '/bff/web/api/v1/' + service + '/items')
      assert.equal(response.status(), expected, service + ' status')
    }
  }
  await businessStatus(200)
  let parent = await data(target + '/groups/' + department.id)
  await data(target + '/groups/' + department.id, 'PUT', {
    version: parent.version,
    organization_version: parent.organization_version,
    name: parent.name,
    description: parent.description,
    sort: parent.sort,
    status: 'DISABLED',
  })
  await businessStatus(403)
  const userRoles = await data(target + '/users/' + user.id + '/roles')
  roleState = await data(target + '/role-permissions/' + role.id)
  await data(target + '/users/' + user.id + '/roles', 'PUT', {
    user_version: userRoles.version,
    roles: [{ id: role.id, version: roleState.role.version }],
  })
  await businessStatus(200)
  await business.close()
  next('organization-edit-and-move')
  await page.goto(origin + '/identity/admin' + target + '/groups')
  const groupSearch = page.getByPlaceholder('搜索组织名称或编码')
  await groupSearch.fill('engineering')
  await expect(page.getByRole('button', { name: '搜索', exact: true })).toBeEnabled()
  await groupSearch.press('Enter')
  await page.getByRole('button', { name: /^研发部/ }).click()
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  await page.locator('dialog[open]').getByLabel('状态').selectOption('ACTIVE')
  await page.locator('dialog[open]').getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.locator('dialog')).not.toBeVisible()
  assert.equal((await data(target + '/groups/' + department.id)).effective_status, 'ACTIVE')
  await groupSearch.fill('backend')
  await expect(page.getByRole('button', { name: '搜索', exact: true })).toBeEnabled()
  await groupSearch.press('Enter')
  await page.getByRole('button', { name: '后端组', exact: true }).click()
  await page.getByRole('button', { name: '移动', exact: true }).click()
  const move = page.locator('dialog[open]')
  await move.getByRole('button', { name: /^移除 .*研发部$/ }).click()
  await move.getByRole('button', { name: '选择', exact: true }).click()
  await move
    .locator('.reference-option')
    .filter({ hasText: '示例公司' })
    .getByRole('button', { name: '加入', exact: true })
    .click()
  await move.getByRole('button', { name: '保存', exact: true }).click()
  await expect(move).not.toBeVisible()
  assert.equal((await data(target + '/groups/' + team.id)).parent_id, company.id)
  await expect(page.getByRole('button', { name: '搜索', exact: true })).toBeEnabled()
  await expect(page.locator('tbody').getByText('示例公司 / 后端组', { exact: true })).toBeVisible()
  await shot('organization-moved')
  next('audit-monitoring-and-openapi')
  await page.goto(origin + '/identity/admin/platform/audits')
  await page.locator('tbody tr').first().waitFor()
  await shot('audits')
  await page.goto(origin + '/identity/admin/platform/monitoring')
  await expect(page.getByText('identity-service', { exact: false }).first()).toBeVisible()
  await shot('monitoring')
  assert.equal((await context.request.get(origin + '/identity/admin/users')).status(), 404)
  assert.equal((await context.request.get(origin + '/bff/identity/tenant/me')).status(), 404)
  const spec = await context.request.get('http://localhost:28081/v3/api-docs/identity-management')
  assert.equal(spec.status(), 200, 'OpenAPI')
  const paths = (await spec.json()).paths
  assert.ok(paths['/api/v1/identity/platform/tenants/{managementTenant}/identity/groups'])
  assert.ok(paths['/api/v1/identity/platform/permissions'])
  assert.deepEqual(paths['/api/v1/identity/users/{id}/groups'].get.security[0].identityOAuth, [
    'identity.groups',
    'identity.users',
  ])
  assert.deepEqual(
    paths['/api/v1/identity/platform/service-audiences'].post.security[0].identityOAuth,
    ['platform.clients'],
  )
  assert.deepEqual(paths['/api/v1/identity/users/{id}/roles'].get.security[0].identityOAuth, [
    'identity.users',
  ])
  assert.deepEqual(paths['/api/v1/identity/users/{id}/roles'].put.security[0].identityOAuth, [
    'identity.roles',
  ])
  assert.equal(pageErrors, 0, 'browser errors')
  passed.push(stage)
  await writeFile(
    path.join(binding.artifacts, 'result.json'),
    JSON.stringify(
      { passed, tenant: tenant.id, user: user.id, group: team.id, role: role.id, pageErrors },
      null,
      2,
    ),
  )
  console.log('PASS ' + passed.length + ' phases')
} catch (error) {
  if (stage !== 'login')
    await page.screenshot({ path: path.join(binding.artifacts, 'failure.png') })
  console.error('FAILED phase: ' + stage + '; ' + error.message)
  await writeFile(
    path.join(binding.artifacts, 'failure.json'),
    JSON.stringify({ stage, passed, message: error.message }),
  )
  process.exitCode = 1
} finally {
  await browser.close()
}
