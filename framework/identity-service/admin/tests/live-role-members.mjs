// 真实只读角色成员流程，不输出凭据或完整响应。
import { chromium } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
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
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } }),
    page = await context.newPage()
  page.setDefaultTimeout(15_000)
  let errors = 0,
    tokens = false,
    managementWrites = 0
  page.on('pageerror', () => {
    errors++
  })
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (
      ['access_token', 'refresh_token', 'id_token'].some((key) => url.searchParams.has(key)) ||
      (url.pathname.startsWith('/bff/') && request.headers().authorization)
    )
      tokens = true
    if (url.pathname.startsWith('/bff/identity/tenant/api/') && request.method() !== 'GET')
      managementWrites++
  })
  phase = 'login'
  await page.goto(binding.origin + '/identity/admin/')
  await page.getByLabel('组织代码', { exact: true }).fill(binding.tenant_code)
  await page.getByRole('button', { name: '继续登录', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/identity/login')
  await page.locator('input[name="username"]').fill(binding.username)
  await page.locator('input[name="password"]').fill(password)
  await page.locator('button[type="submit"]').click()
  await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
  phase = 'members'
  await page.getByRole('link', { name: /^角色管理/ }).click()
  await page.getByLabel('搜索角色编码或名称', { exact: true }).fill('business')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: 'business' })
    .getByRole('button', { name: '查看成员', exact: true })
    .click()
  const dialog = page.getByRole('dialog', { name: '角色成员', exact: true })
  await dialog.getByText(binding.target_username, { exact: true }).waitFor()
  await dialog.getByText(binding.username, { exact: true }).waitFor()
  const response = await page.request.get(
    binding.origin +
      '/bff/identity/tenant/api/roles/' +
      binding.role_id +
      '/members?page=0&size=20',
  )
  assert.equal(response.status(), 200)
  const members = (await response.json()).data.items
  assert.equal(members.length, 3)
  for (const member of members)
    assert.deepEqual(
      Object.keys(member).sort(),
      ['id', 'username', 'display_name', 'status', 'must_change_password', 'version'].sort(),
    )
  await page.screenshot({ path: binding.screenshot_file, fullPage: true })
  await dialog.getByRole('button', { name: '关闭成员列表', exact: true }).click()
  assert.equal(errors, 0)
  assert.equal(tokens, false)
  assert.equal(managementWrites, 0)
  const evidence = {
    ok: true,
    browser: browser.version(),
    member_count: members.length,
    page_errors: errors,
    browser_token_seen: tokens,
    management_writes: managementWrites,
    phases: ['login', 'members'],
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
