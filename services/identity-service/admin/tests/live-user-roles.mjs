// 真实用户角色分配与撤回；日志不包含凭据、Cookie、DOM 或完整响应。
import { chromium } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
let browser
let phase = 'binding'
function mark(value) {
  phase = value
  process.stdout.write(JSON.stringify({ phase }) + '\n')
}
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
  const page = await context.newPage()
  page.setDefaultTimeout(15_000)
  let errors = 0,
    tokens = false
  page.on('pageerror', () => {
    errors++
  })
  page.on('dialog', (dialog) => dialog.accept())
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (
      ['access_token', 'refresh_token', 'id_token'].some((key) => url.searchParams.has(key)) ||
      (url.pathname.startsWith('/bff/') && request.headers().authorization)
    )
      tokens = true
  })
  mark('login-and-target')
  await page.goto(binding.origin + '/identity/admin/')
  await page.getByLabel('组织代码', { exact: true }).fill(binding.tenant_code)
  await page.getByRole('button', { name: '继续登录', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/identity/login')
  await page.locator('input[name="username"]').fill(binding.username)
  await page.locator('input[name="password"]').fill(password)
  await page.locator('button[type="submit"]').click()
  await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
  await page.getByRole('link', { name: /^用户管理/ }).click()
  await page.getByLabel('搜索账号或显示名', { exact: true }).fill(binding.target_username)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: binding.target_username })
    .getByRole('button', { name: '分配角色', exact: true })
    .click()
  const dialog = page.getByRole('dialog', { name: '分配用户角色', exact: true })
  await dialog.getByLabel('分配角色 zz-retained', { exact: true }).waitFor()
  assert.equal(await dialog.getByLabel('分配角色 zz-retained', { exact: true }).isChecked(), true)
  const prefix = binding.origin + '/bff/identity/tenant/api'
  const pageZero = await page.request.get(
    prefix + '/roles?assignable_to=' + binding.target_user + '&page=0&size=20',
  )
  assert.equal(pageZero.status(), 200)
  assert.equal(
    (await pageZero.json()).data.items.some((role) => role.id === binding.retained_role),
    false,
  )
  async function roles() {
    const response = await page.request.get(prefix + '/users/' + binding.target_user + '/roles')
    assert.equal(response.status(), 200)
    return (await response.json()).data.roles
  }
  async function decision() {
    const response = await page.request.get(
      prefix + '/users/' + binding.target_user + '/effective-permissions',
    )
    assert.equal(response.status(), 200)
    return (await response.json()).data.permissions[binding.permission]
  }
  mark('assign-with-off-page-retained')
  await dialog.getByLabel('分配角色 business', { exact: true }).check()
  await dialog.getByRole('button', { name: '预览角色差异', exact: true }).click()
  await dialog.getByText('增加 · 业务角色 · business', { exact: true }).waitFor()
  assert.equal(await dialog.getByText('移除 · 跨页保留角色', { exact: false }).count(), 0)
  await dialog.getByRole('button', { name: '确认分配角色', exact: true }).click()
  await dialog.getByRole('status').filter({ hasText: '用户角色已保存并重新读取' }).waitFor()
  assert.deepEqual(
    (await roles()).map((role) => role.id).sort(),
    [binding.business_role, binding.retained_role].sort(),
  )
  assert.equal((await decision()).allowed, true)
  assert.equal((await decision()).data_scope, 'SELF')
  mark('revoke-business-role')
  await dialog.getByLabel('分配角色 business', { exact: true }).uncheck()
  await dialog.getByRole('button', { name: '预览角色差异', exact: true }).click()
  await dialog.getByText('移除 · 业务角色 · business', { exact: true }).waitFor()
  await dialog.getByRole('button', { name: '确认分配角色', exact: true }).click()
  await dialog.getByRole('status').filter({ hasText: '用户角色已保存并重新读取' }).waitFor()
  assert.deepEqual(
    (await roles()).map((role) => role.id),
    [binding.retained_role],
  )
  const final = await decision()
  assert.equal(final.allowed, false)
  assert.equal(final.reasons.includes('NO_ROLE_GRANT'), true)
  await dialog.evaluate((element) => element.scrollTo(0, 0))
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: binding.screenshot_file, fullPage: true })
  await dialog.getByRole('button', { name: '关闭角色分配', exact: true }).click()
  assert.equal(errors, 0)
  assert.equal(tokens, false)
  const evidence = {
    ok: true,
    browser: browser.version(),
    target_user: binding.target_user,
    retained_role: binding.retained_role,
    business_role: binding.business_role,
    page_errors: errors,
    browser_token_seen: tokens,
    phases: ['login-and-target', 'assign-with-off-page-retained', 'revoke-business-role'],
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
