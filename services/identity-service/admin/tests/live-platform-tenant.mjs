// 仅独立候选环境；秘密只从受控文件读取，报告不含凭证和响应正文。
import { chromium, expect } from '@playwright/test'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import assert from 'node:assert/strict'
import path from 'node:path'
let browser,
  phase = 'binding'
const phases = []
try {
  const binding = JSON.parse(await readFile(process.env.PLATFORM_TEST_ADMIN_E2E_BINDING, 'utf8'))
  assert.equal(binding.test_only, true)
  assert.equal(binding.project, 'qa-admin-20261028')
  assert.equal(binding.origin, 'http://localhost:28080')
  const origin = binding.origin
  await mkdir(binding.artifacts, { recursive: true })
  browser = await chromium.launch({
    headless: true,
    channel: process.env.PLATFORM_TEST_BROWSER_CHANNEL ?? 'msedge',
  })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  let errors = 0
  let allowDialogs = true
  context.on('page', (page) => {
    page.setDefaultTimeout(20000)
    page.on('pageerror', () => errors++)
    page.on('dialog', (dialog) => (allowDialogs ? dialog.accept() : dialog.dismiss()))
  })
  const page = await context.newPage()
  function stage(name) {
    if (phase !== 'binding') phases.push(phase)
    phase = name
    console.log('阶段：' + name)
  }
  async function api(tail, method = 'GET', data) {
    const headers = { Origin: origin, 'X-Request-ID': randomUUID() }
    if (method !== 'GET') {
      const csrf = await page.request.get(origin + '/bff/identity/platform/csrf')
      assert.equal(csrf.status(), 200)
      const token = (await csrf.json()).data
      headers[token.header_name] = token.token
    }
    return context.request.fetch(origin + '/bff/identity/platform/api' + tail, {
      method,
      headers,
      ...(data ? { data } : {}),
    })
  }
  async function data(tail) {
    const response = await api(tail)
    assert.equal(response.status(), 200, tail)
    return (await response.json()).data
  }
  async function shot(name) {
    for (const width of [1440, 1280]) {
      await page.setViewportSize({ width, height: width === 1440 ? 900 : 800 })
      assert.equal(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        true,
        '页面横向溢出',
      )
      await page.evaluate(() => window.scrollTo(0, 0))
      await page.screenshot({
        path: path.join(binding.artifacts, `${name}-${width}.png`),
        fullPage: true,
      })
    }
  }
  stage('平台登录')
  await page.goto(origin + '/identity/admin/')
  await page.getByRole('heading', { name: '平台管理员登录', exact: true }).waitFor()
  const password = (await readFile(binding.password_file, 'utf8')).trim()
  async function credentials(value) {
    await page.locator('input[name=username]').fill(binding.username)
    await page.locator('input[name=password]').fill(value)
    await page.locator('button[type=submit]').click()
  }
  await credentials(password)
  if (binding.initial) {
    await page.waitForURL('**/identity/password/initial')
    const next = 'QA platform ' + randomUUID()
    await page.locator('input[name=password]').fill(next)
    await page.locator('input[name=confirmation]').fill(next)
    await page.locator('button[type=submit]').click()
    await writeFile(binding.password_file, next, { mode: 0o600 })
    binding.initial = false
    await writeFile(process.env.PLATFORM_TEST_ADMIN_E2E_BINDING, JSON.stringify(binding), {
      mode: 0o600,
    })
    await credentials(next)
  }
  await page.getByRole('heading', { name: '租户管理', exact: true }).first().waitFor()
  const me = await data('/me')
  assert.equal(me.principal_type, 'platform')
  assert.equal(me.tenant_id, null)
  await shot('tenants')
  const tenants = await data('/platform/tenants?size=20&page=0')
  const a = tenants.items.find((x) => x.code === 'alpha'),
    b = tenants.items.find((x) => x.code === 'beta')
  assert.ok(a && b)
  const target = '/platform/tenants/' + a.id + '/identity'
  const userName = 'qa-' + randomUUID().slice(0, 8),
    roleCode = 'qa-' + randomUUID().slice(0, 8)
  stage('平台目标租户创建用户')
  await page.goto(origin + '/identity/admin' + target + '/users')
  await page.getByRole('button', { name: '新建用户', exact: true }).click()
  await page.getByLabel('登录账号', { exact: true }).fill(userName)
  await page.getByLabel('显示名', { exact: true }).fill('验收用户 · 长名称与授权检查')
  await page.getByRole('button', { name: '创建', exact: true }).click()
  await page.getByLabel('初始密码', { exact: true }).waitFor()
  assert.equal(await page.getByLabel('初始密码', { exact: true }).getAttribute('type'), 'password')
  await page.getByRole('button', { name: '已记录，关闭', exact: true }).click()
  const user = (await data(target + '/users?q=' + userName)).items[0]
  assert.ok(user)
  await shot('users')
  await page.getByLabel('搜索账号或显示名', { exact: true }).fill('no-match-' + randomUUID())
  await page.locator('.search-form').getByRole('button', { name: '查询', exact: true }).click()
  await page.getByText('暂无符合条件的用户。', { exact: true }).waitFor()
  await shot('users-empty')

  stage('角色资料权限成员闭环')
  await page.goto(origin + '/identity/admin' + target + '/roles')
  await page.getByRole('button', { name: '新建角色', exact: true }).click()
  await page.getByLabel('角色编码', { exact: true }).fill(roleCode)
  await page.getByLabel('角色名称', { exact: true }).fill('项目验收阅读角色')
  await page.locator('dialog[open]').getByRole('button', { name: '创建', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色已创建' }).waitFor()
  const role = (await data(target + '/roles?q=' + roleCode)).items[0]
  assert.ok(role)
  await page.goto(origin + '/identity/admin' + target + '/roles/' + role.id)
  await page.getByLabel('角色名称', { exact: true }).fill('未保存的草稿')
  const concurrent = await data(target + '/role-permissions/' + role.id)
  assert.equal(
    (
      await api(target + '/roles/' + role.id, 'PUT', {
        version: concurrent.role.version,
        name: concurrent.role.name,
        description: '并发修改验收',
        status: concurrent.role.status,
      })
    ).status(),
    200,
  )
  await page.getByRole('button', { name: '保存资料', exact: true }).click()
  await page.getByText('草稿已保留，请重新读取当前资料后核对。', { exact: true }).waitFor()
  await shot('role-conflict')

  allowDialogs = false
  await page.getByRole('link', { name: '用户管理', exact: true }).click()
  assert.ok(page.url().endsWith('/roles/' + role.id), '取消离开须保留草稿')
  assert.equal(await page.getByLabel('角色名称', { exact: true }).inputValue(), '未保存的草稿')
  allowDialogs = true
  await page.getByRole('button', { name: '切换租户', exact: true }).click()
  await page.getByLabel('搜索租户名称或代码', { exact: true }).fill('beta')
  await page.locator('.tenant-picker').getByRole('button', { name: '查询', exact: true }).click()
  await page.locator('.tenant-picker').getByRole('button', { name: '选择', exact: true }).click()
  await page.waitForURL('**/platform/tenants/' + b.id + '/identity/roles')
  await page.goto(origin + '/identity/admin' + target + '/roles/' + role.id)
  await page.getByRole('tab', { name: '权限授权', exact: true }).click()
  const catalog = await data(target + '/permissions')
  const reads = catalog.items.filter((x) => x.code.endsWith('-item:read'))
  assert.equal(reads.length, 2)
  for (const permission of reads) {
    await page.getByLabel('授权 ' + permission.code, { exact: true }).check()
    await page.getByLabel('数据范围 ' + permission.code, { exact: true }).selectOption('SELF')
  }
  await page.getByRole('button', { name: '预览授权差异', exact: true }).click()
  await page.getByRole('button', { name: '确认保存授权', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色授权已保存' }).waitFor()
  await shot('role-grants')
  await page.goto(origin + '/identity/admin' + target + '/users')
  await page
    .getByRole('row')
    .filter({ hasText: userName })
    .getByRole('button', { name: '分配角色', exact: true })
    .click()
  await page.getByLabel('分配角色 ' + roleCode, { exact: true }).check()
  await page.getByRole('button', { name: '预览角色差异', exact: true }).click()
  await page.getByRole('button', { name: '确认分配角色', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '已保存' })).toBeVisible()
  await page
    .locator('dialog[open]')
    .getByRole('button', { name: '关闭角色分配', exact: true })
    .click()
  stage('密码重置一次性交付与消费')
  await page
    .getByRole('row')
    .filter({ hasText: userName })
    .getByRole('button', { name: '重置密码', exact: true })
    .click()
  await page.getByRole('button', { name: '签发重置凭证', exact: true }).click()
  const tokenBox = page.getByLabel('一次性重置凭证', { exact: true })
  await tokenBox.waitFor()
  const token = await tokenBox.inputValue()
  assert.equal(token.length, 43)
  assert.equal(await tokenBox.getAttribute('type'), 'password')
  await shot('reset-issue-masked')
  await page.getByRole('button', { name: '已交付，关闭', exact: true }).click()
  assert.equal(await tokenBox.count(), 0)
  await expect(
    page
      .getByRole('row')
      .filter({ hasText: userName })
      .getByRole('button', { name: '重置密码', exact: true }),
  ).toBeFocused()
  const resetContext = await browser.newContext()
  const reset = await resetContext.newPage()
  await reset.goto(origin + '/identity/password/reset')
  await reset.locator('input[name=token]').fill(token)
  const newPassword = 'QA user ' + randomUUID()
  await reset.locator('input[name=password]').fill(newPassword)
  await reset.locator('input[name=confirmation]').fill(newPassword)
  await reset.locator('button[type=submit]').click()
  await expect(reset.getByText(/密码已更新/)).toBeVisible()
  await resetContext.close()
  stage('Java Python 实际数据范围与撤权')
  const services = ['equipment-service', 'inspection-service']
  async function grant(scope, create = true) {
    const snapshot = await data(target + '/role-permissions/' + role.id)
    const permissions = catalog.items.filter(
      (x) => x.code.endsWith('-item:read') || (create && x.code.endsWith('-item:create')),
    )
    const response = await api(target + '/role-permissions/' + role.id, 'PUT', {
      role_version: snapshot.role.version,
      catalog_version: snapshot.catalog_version,
      grants: scope
        ? permissions.map((x) => ({
            permission_code: x.code,
            data_scope: x.supported_data_scopes.length ? scope : null,
          }))
        : [],
    })
    assert.equal(response.status(), 200)
  }
  async function businessLogin(tenant, username, secret) {
    const client = await browser.newContext()
    const screen = await client.newPage()
    await screen.goto(origin + '/')
    await screen.locator('[name=tenant_code]').fill(tenant)
    await screen.locator('#login button').click()
    await screen.locator('#username').fill(username)
    await screen.locator('#realm').selectOption('TENANT')
    await screen.locator('#tenant').fill(tenant)
    await screen.locator('#password').fill(secret)
    await screen.getByRole('button', { name: '登录', exact: true }).click()
    await screen.waitForURL(origin + '/')
    return client
  }
  async function business(client, service, method = 'GET', name = '') {
    const headers = { Origin: origin, 'X-Request-ID': randomUUID() }
    if (method !== 'GET') {
      const csrf = await client.request.get(origin + '/bff/web/csrf')
      assert.equal(csrf.status(), 200)
      const token = (await csrf.json()).data
      headers[token.header_name] = token.token
    }
    return client.request.fetch(origin + '/bff/web/api/v1/' + service + '/items', {
      method,
      headers,
      ...(method === 'POST' ? { data: { name } } : {}),
    })
  }
  // 同一个租户账号在两侧样例中检验真实接口；授权修改继续走管理入口。
  const webClient = (await data('/platform/oauth-clients?q=web-bff')).items.find(
    (x) => x.client_id === 'web-bff',
  )
  assert.ok(webClient)
  const { id: webId, ...webDefinition } = webClient
  const disallow = await api('/platform/oauth-clients/' + webId, 'PUT', {
    ...webDefinition,
    allowed_tenant_ids: webDefinition.allowed_tenant_ids.filter((x) => x !== a.id),
  })
  assert.equal(disallow.status(), 200)
  const deniedContext = await browser.newContext()
  const deniedPage = await deniedContext.newPage()
  try {
    await deniedPage.goto(origin + '/')
    await deniedPage.locator('[name=tenant_code]').fill('alpha')
    await deniedPage.locator('#login button').click()
    await deniedPage.locator('#username').fill(userName)
    await deniedPage.locator('#password').fill(newPassword)
    const deniedResponse = deniedPage.waitForResponse(
      (response) => response.url().startsWith(origin) && response.status() >= 400,
    )
    await deniedPage.getByRole('button', { name: '登录', exact: true }).click()
    assert.ok([400, 401, 403].includes((await deniedResponse).status()))
    assert.equal((await business(deniedContext, services[0])).status(), 401)
  } finally {
    await deniedContext.close()
    const current = await data('/platform/oauth-clients/' + webId)
    const restored = await api('/platform/oauth-clients/' + webId, 'PUT', {
      ...webDefinition,
      version: current.version,
    })
    assert.equal(restored.status(), 200)
  }
  await grant(null)
  const client = await businessLogin('alpha', userName, newPassword)
  for (const service of services) assert.equal((await business(client, service)).status(), 403)
  await grant('SELF')
  const own = 'own-' + randomUUID()
  for (const service of services) {
    assert.equal((await business(client, service, 'POST', own)).status(), 201)
    const listed = await business(client, service)
    assert.equal(listed.status(), 200)
    assert.ok((await listed.text()).includes(own))
  }
  // 第二个账号复用同一角色，专用测试数据用于区分 SELF 与 TENANT。
  const created = await api(target + '/users', 'POST', {
    username: 'peer-' + userName,
    display_name: '范围验收对照用户',
  })
  assert.equal(created.status(), 200)
  const peerUser = (await data(target + '/users?q=peer-' + userName)).items[0]
  const snap = await data(target + '/users/' + peerUser.id + '/roles')
  const roleNow = await data(target + '/role-permissions/' + role.id)
  const assignment = await api(target + '/users/' + peerUser.id + '/roles', 'PUT', {
    user_version: snap.version,
    roles: [{ id: role.id, version: roleNow.role.version }],
  })
  assert.equal(assignment.status(), 200)
  const issued = await api(target + '/users/' + peerUser.id + '/password-reset', 'POST', {
    version: (await data(target + '/users/' + peerUser.id + '/roles')).version,
  })
  assert.equal(issued.status(), 200)
  const peerToken = (await issued.json()).data.reset_token
  const peerPassword = 'QA peer ' + randomUUID()
  const temporary = await browser.newContext()
  const form = await temporary.newPage()
  await form.goto(origin + '/identity/password/reset')
  await form.locator('input[name=token]').fill(peerToken)
  await form.locator('input[name=password]').fill(peerPassword)
  await form.locator('input[name=confirmation]').fill(peerPassword)
  await form.locator('button[type=submit]').click()
  await expect(form.getByText(/密码已更新/)).toBeVisible()
  await temporary.close()
  const peer = await businessLogin('alpha', 'peer-' + userName, peerPassword)
  const otherRecord = 'peer-' + randomUUID()
  for (const service of services) {
    assert.equal((await business(peer, service, 'POST', otherRecord)).status(), 201)
    const listed = await business(client, service)
    assert.equal(listed.status(), 200)
    assert.ok(!(await listed.text()).includes(otherRecord), 'SELF 不得返回另一用户数据')
  }
  await grant('TENANT')
  for (const service of services) {
    const listed = await business(client, service)
    assert.equal(listed.status(), 200)
    const content = await listed.text()
    assert.ok(content.includes(own) && content.includes(otherRecord))
  }
  stage('Java Python 跨租户数据隔离与停用')
  const betaTarget = '/platform/tenants/' + b.id + '/identity'
  const betaName = 'beta-' + userName
  assert.equal(
    (
      await api(betaTarget + '/users', 'POST', {
        username: betaName,
        display_name: '跨租户对照用户',
      })
    ).status(),
    200,
  )
  assert.equal(
    (
      await api(betaTarget + '/roles', 'POST', {
        code: roleCode,
        name: '跨租户对照角色',
        description: '',
      })
    ).status(),
    200,
  )
  const betaUser = (await data(betaTarget + '/users?q=' + betaName)).items[0]
  const betaRole = (await data(betaTarget + '/roles?q=' + roleCode)).items[0]
  const betaPermissions = await data(betaTarget + '/role-permissions/' + betaRole.id)
  assert.equal(
    (
      await api(betaTarget + '/role-permissions/' + betaRole.id, 'PUT', {
        role_version: betaPermissions.role.version,
        catalog_version: betaPermissions.catalog_version,
        grants: catalog.items
          .filter((x) => x.code.endsWith('-item:read') || x.code.endsWith('-item:create'))
          .map((x) => ({
            permission_code: x.code,
            data_scope: x.supported_data_scopes.length ? 'TENANT' : null,
          })),
      })
    ).status(),
    200,
  )
  assert.equal(
    (
      await api(betaTarget + '/users/' + betaUser.id + '/roles', 'PUT', {
        user_version: (await data(betaTarget + '/users/' + betaUser.id + '/roles')).version,
        roles: [
          {
            id: betaRole.id,
            version: (await data(betaTarget + '/role-permissions/' + betaRole.id)).role.version,
          },
        ],
      })
    ).status(),
    200,
  )
  const betaIssue = await api(betaTarget + '/users/' + betaUser.id + '/password-reset', 'POST', {
    version: (await data(betaTarget + '/users/' + betaUser.id + '/roles')).version,
  })
  assert.equal(betaIssue.status(), 200)
  const betaPassword = 'QA beta ' + randomUUID()
  const betaResetContext = await browser.newContext()
  const betaReset = await betaResetContext.newPage()
  await betaReset.goto(origin + '/identity/password/reset')
  await betaReset.locator('input[name=token]').fill((await betaIssue.json()).data.reset_token)
  await betaReset.locator('input[name=password]').fill(betaPassword)
  await betaReset.locator('input[name=confirmation]').fill(betaPassword)
  await betaReset.locator('button[type=submit]').click()
  await expect(betaReset.getByText(/密码已更新/)).toBeVisible()
  await betaResetContext.close()
  const betaClient = await businessLogin('beta', betaName, betaPassword)
  const betaRecord = 'beta-' + randomUUID()
  for (const service of services) {
    assert.equal((await business(betaClient, service, 'POST', betaRecord)).status(), 201)
    const betaList = await business(betaClient, service)
    assert.equal(betaList.status(), 200)
    const betaContent = await betaList.text()
    assert.ok(
      betaContent.includes(betaRecord) &&
        !betaContent.includes(own) &&
        !betaContent.includes(otherRecord),
    )
    const alphaList = await business(client, service)
    assert.equal(alphaList.status(), 200)
    assert.ok(!(await alphaList.text()).includes(betaRecord))
  }
  await betaClient.close()
  await grant(null)
  for (const service of services) assert.equal((await business(client, service)).status(), 403)
  await grant('TENANT')
  assert.equal(
    (
      await api(target + '/users/' + user.id, 'PUT', {
        version: (await data(target + '/users/' + user.id + '/roles')).version,
        status: 'DISABLED',
      })
    ).status(),
    200,
  )
  for (const service of services) assert.equal((await business(client, service)).status(), 401)
  await client.close()
  await peer.close()
  stage('租户标签隔离与页面回归')
  const other = await context.newPage()
  await other.goto(origin + '/identity/admin/platform/tenants/' + b.id + '/identity/users')
  await other.getByRole('button', { name: '新建用户', exact: true }).waitFor()
  assert.equal(await other.getByRole('row').filter({ hasText: userName }).count(), 0)
  assert.ok(page.url().includes(a.id))
  assert.ok(other.url().includes(b.id))
  assert.equal(
    (
      await api('/platform/tenants/' + b.id + '/identity/users/' + user.id + '/profile', 'PUT', {
        version: 0,
        display_name: '越界',
      })
    ).status(),
    404,
  )
  assert.equal((await api('/users')).status(), 403)
  assert.equal((await data('/me')).tenant_id, null)
  await other.close()
  stage('租户后台入口已关闭，平台工作区保留')
  for (const tail of ['', '/csrf', '/login', '/callback', '/api/me', '/api/users']) {
    assert.equal((await page.request.get(origin + '/bff/identity/tenant' + tail)).status(), 404)
  }
  for (const tail of ['users', 'roles', 'permissions', 'sessions', 'audits']) {
    assert.equal((await page.request.get(origin + '/identity/admin/' + tail)).status(), 404)
  }
  for (const [tail, name] of [
    ['/platform/clients', 'clients'],
    ['/platform/security', 'security'],
    ['/account', 'account'],
  ]) {
    await page.goto(origin + '/identity/admin' + tail)
    await page.locator('main h1').waitFor()
    await page.waitForLoadState('networkidle')
    await shot(name)
  }
  assert.equal(errors, 0)
  phases.push(phase)
  await writeFile(
    path.join(binding.artifacts, 'result.json'),
    JSON.stringify(
      { ok: true, phases, page_errors: errors, tenant: a.id, user: user.id, role: role.id },
      null,
      2,
    ),
  )
  console.log('PASS：平台目标租户、角色授权、重置和页面流程')
} catch (error) {
  console.error('FAIL：' + phase + '；' + error.name)
  console.error(
    error.stack
      ?.split('\n')
      .filter((line) => line.trimStart().startsWith('at '))
      .slice(0, 6)
      .join('\n'),
  )
  const active = browser?.contexts()[0]?.pages()[0]
  if (active) {
    const b = JSON.parse(await readFile(process.env.PLATFORM_TEST_ADMIN_E2E_BINDING, 'utf8'))
    await active
      .screenshot({ path: path.join(b.artifacts, 'failure.png'), fullPage: true })
      .catch(() => {})
    console.error((await active.locator('body').innerText()).slice(-1600))
  }
  process.exitCode = 1
} finally {
  if (browser) await browser.close()
}
