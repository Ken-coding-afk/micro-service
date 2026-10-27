// 真实平台管理浏览器流程。日志只输出阶段，不输出凭据或原始异常。
import { chromium } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import assert from 'node:assert/strict'

let browser
let phase = 'binding'
try {
  const binding = JSON.parse(await readFile(process.env.PLATFORM_TEST_ADMIN_E2E_BINDING, 'utf8'))
  assert.equal(binding.test_only, true)
  assert.equal(binding.origin, 'http://localhost:18080')
  const password = (await readFile(binding.password_file, 'utf8')).trim()
  browser = await chromium.launch({
    headless: true,
    channel: process.env.PLATFORM_TEST_BROWSER_CHANNEL ?? 'msedge',
  })
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } })
  let pageErrors = 0
  let browserToken = false
  context.on('page', (page) => {
    page.on('pageerror', () => {
      pageErrors++
    })
    page.setDefaultTimeout(15_000)
    page.on('dialog', (dialog) => dialog.accept())
  })
  context.on('request', (request) => {
    const url = new URL(request.url())
    if (['access_token', 'refresh_token', 'id_token'].some((key) => url.searchParams.has(key)))
      browserToken = true
    if (url.pathname.startsWith('/bff/') && request.headers().authorization) browserToken = true
  })
  async function enter(page, realm, username, secret, code = '', firstPassword = '') {
    await page.goto(binding.origin + '/identity/admin/')
    const login = page.getByRole('heading', { name: '账号登录', exact: true })
    const change = page.getByRole('button', { name: '重新登录 / 更换账号', exact: true })
    await Promise.race([login.waitFor(), change.waitFor()])
    if (await change.isVisible()) await change.click()
    await login.waitFor()
    await page
      .getByRole('button', { name: realm === 'platform' ? '平台管理员' : '租户账号', exact: true })
      .click()
    if (realm === 'tenant') await page.getByLabel('组织代码', { exact: true }).fill(code)
    await page.getByRole('button', { name: '继续登录', exact: true }).click()
    await page.waitForURL((url) => url.pathname === '/identity/login')
    await page.locator('input[name="username"]').fill(username)
    await page.locator('input[name="password"]').fill(secret)
    await page.locator('button[type="submit"]').click()
    if (firstPassword) {
      await page.waitForURL((url) => url.pathname === '/identity/password/initial')
      await page.locator('input[name="password"]').fill(firstPassword)
      await page.locator('input[name="confirmation"]').fill(firstPassword)
      await page.locator('button[type="submit"]').click()
      await page.waitForURL((url) => url.pathname === '/identity/login')
      await page.locator('input[name="username"]').fill(username)
      await page.locator('input[name="password"]').fill(firstPassword)
      await page.locator('button[type="submit"]').click()
    }
    await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
  }

  const page = await context.newPage()
  phase = 'platform-login'
  await enter(page, 'platform', binding.username, password)
  phase = 'create-tenant'
  await page.getByRole('link', { name: /^租户管理/ }).click()
  await page.getByRole('heading', { name: '租户管理', exact: true }).waitFor()
  await page.getByRole('button', { name: '新建租户', exact: true }).click()
  const code = 'gui-' + randomUUID()
  await page.getByLabel('组织代码', { exact: true }).fill(code)
  await page.getByLabel('租户名称', { exact: true }).fill('浏览器新租户')
  await page.getByLabel('管理员账号', { exact: true }).fill('tenant-admin')
  await page.getByLabel('管理员显示名', { exact: true }).fill('浏览器租户管理员')
  await page.getByRole('button', { name: '创建', exact: true }).click()
  await page.getByRole('heading', { name: '初始凭据交付', exact: true }).waitFor()
  const initial = await page.getByLabel('初始密码', { exact: true }).inputValue()
  assert.equal(initial.length >= 15, true)
  await page.getByRole('button', { name: '已记录，关闭', exact: true }).click()
  assert.equal(await page.getByLabel('初始密码', { exact: true }).count(), 0)
  const response = await page.request.get(
    binding.origin + '/bff/identity/platform/api/platform/tenants?q=' + code,
  )
  assert.equal(response.status(), 200)
  const tenant = (await response.json()).data.items[0]
  assert.equal(tenant.code, code)
  phase = 'edit-tenant'
  await page
    .getByRole('row')
    .filter({ hasText: code })
    .getByRole('button', { name: '编辑', exact: true })
    .click()
  await page.getByLabel('租户名称', { exact: true }).fill('浏览器租户已更新')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '租户已更新' }).waitFor()
  phase = 'tenant-activation-and-isolation'
  const tenantPage = await context.newPage()
  await enter(
    tenantPage,
    'tenant',
    'tenant-admin',
    initial,
    code,
    'GUI Tenant password ' + randomUUID(),
  )
  assert.equal(await tenantPage.getByRole('link', { name: /^租户管理/ }).count(), 0)
  assert.equal(await tenantPage.getByRole('link', { name: /^平台管理员/ }).count(), 0)
  assert.equal(
    (await page.request.get(binding.origin + '/bff/identity/platform/api/me')).status(),
    200,
  )
  phase = 'disable-tenant'
  await page
    .getByRole('row')
    .filter({ hasText: code })
    .getByRole('button', { name: '编辑', exact: true })
    .click()
  await page.getByLabel('状态', { exact: true }).selectOption('DISABLED')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '租户已更新' }).waitFor()
  assert.equal(
    (await tenantPage.request.get(binding.origin + '/bff/identity/tenant/api/me')).status(),
    401,
  )
  await tenantPage.reload()
  await tenantPage.getByRole('heading', { name: '账号登录', exact: true }).waitFor()
  assert.equal(await tenantPage.getByRole('heading', { name: '个人账号', exact: true }).count(), 0)
  assert.equal(
    (await page.request.get(binding.origin + '/bff/identity/platform/api/me')).status(),
    200,
  )
  await page.screenshot({ path: binding.screenshot_file, fullPage: true })
  phase = 'create-platform-admin'
  await page.getByRole('link', { name: /^平台管理员/ }).click()
  await page.getByRole('button', { name: '新建平台管理员', exact: true }).click()
  const admin = 'gui-admin-' + randomUUID()
  await page.getByLabel('管理员账号', { exact: true }).fill(admin)
  await page.getByLabel('管理员显示名', { exact: true }).fill('浏览器平台管理员')
  await page.getByRole('button', { name: '创建', exact: true }).click()
  await page.getByRole('heading', { name: '初始凭据交付', exact: true }).waitFor()
  assert.equal((await page.getByLabel('初始密码', { exact: true }).inputValue()).length >= 15, true)
  await page.getByRole('button', { name: '已记录，关闭', exact: true }).click()
  assert.equal(await page.getByLabel('初始密码', { exact: true }).count(), 0)
  phase = 'disable-created-admin'
  await page
    .getByRole('row')
    .filter({ hasText: admin })
    .getByRole('button', { name: '编辑', exact: true })
    .click()
  await page.getByLabel('状态', { exact: true }).selectOption('DISABLED')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '平台管理员已更新' }).waitFor()
  assert.equal(
    await page
      .getByRole('row')
      .filter({ hasText: admin })
      .getByText('停用', { exact: true })
      .count(),
    1,
  )
  phase = 'logout'
  await page.getByRole('button', { name: '退出本应用', exact: true }).click()
  await page.getByRole('heading', { name: '账号登录', exact: true }).waitFor()
  assert.equal(pageErrors, 0)
  assert.equal(browserToken, false)
  const evidence = {
    ok: true,
    browser: browser.version(),
    tenant_id: tenant.id,
    page_errors: pageErrors,
    browser_token_seen: browserToken,
    phases: [
      'platform-login',
      'create-tenant',
      'edit-tenant',
      'tenant-activation-and-isolation',
      'disable-tenant',
      'create-platform-admin',
      'disable-created-admin',
      'logout',
    ],
  }
  await writeFile(binding.result_file, JSON.stringify(evidence, null, 2) + '\n', 'utf8')
  process.stdout.write(JSON.stringify(evidence) + '\n')
} catch (failure) {
  process.stdout.write(
    JSON.stringify({
      ok: false,
      phase,
      error_type: failure?.name === 'TimeoutError' ? 'TIMEOUT' : 'ASSERTION_OR_DEPENDENCY',
    }) + '\n',
  )
  process.exitCode = 1
} finally {
  if (browser) await browser.close()
}
