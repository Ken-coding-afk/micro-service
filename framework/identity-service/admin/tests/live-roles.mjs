// 本机真实角色管理证据；只输出阶段和非敏感结果。
import { chromium } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
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
  mark('login-and-create')
  await page.goto(binding.origin + '/identity/admin/')
  await page.getByLabel('组织代码', { exact: true }).fill(binding.tenant_code)
  await page.getByRole('button', { name: '继续登录', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/identity/login')
  await page.locator('input[name="username"]').fill(binding.username)
  await page.locator('input[name="password"]').fill(password)
  await page.locator('button[type="submit"]').click()
  await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
  await page.getByRole('link', { name: /^角色管理/ }).click()
  await page.getByRole('button', { name: '新建角色', exact: true }).click()
  const code = 'gui-role-' + randomUUID()
  await page.getByLabel('角色编码', { exact: true }).fill(code)
  await page.getByLabel('角色名称', { exact: true }).fill('浏览器新角色')
  await page.getByRole('button', { name: '创建', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色已创建' }).waitFor()
  let row = page.getByRole('row').filter({ hasText: code })
  mark('edit-and-disable')
  await row.getByRole('button', { name: '编辑资料', exact: true }).click()
  await page.getByLabel('角色名称', { exact: true }).fill('浏览器停用角色')
  await page.getByLabel('状态', { exact: true }).selectOption('DISABLED')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色资料已更新' }).waitFor()
  await row.getByText('停用', { exact: true }).waitFor()
  mark('copy-disabled-role')
  await row.getByRole('button', { name: '复制角色', exact: true }).click()
  const copy = 'gui-copy-' + randomUUID()
  await page.getByLabel('角色编码', { exact: true }).fill(copy)
  await page.getByLabel('角色名称', { exact: true }).fill('浏览器复制角色')
  await page.getByRole('button', { name: '确认复制', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色已复制' }).waitFor()
  row = page.getByRole('row').filter({ hasText: copy })
  await row.getByText('停用', { exact: true }).waitFor()
  const response = await page.request.get(
    binding.origin + '/bff/identity/tenant/api/roles?q=' + copy,
  )
  assert.equal(response.status(), 200)
  const data = (await response.json()).data
  assert.equal(data.can_create, true)
  assert.equal(data.items.length, 1)
  const created = data.items[0]
  assert.equal(created.status, 'DISABLED')
  assert.equal(created.protected, false)
  assert.equal(created.can_manage, true)
  await page.screenshot({ path: binding.screenshot_file, fullPage: true })
  mark('direct-authorization-link')
  await row.getByRole('link', { name: '查看授权', exact: true }).click()
  await page.waitForURL(
    (url) =>
      url.pathname === '/identity/admin/permissions' && url.searchParams.get('role') === created.id,
  )
  await page.getByLabel('选择角色', { exact: true }).waitFor()
  await page.getByText('浏览器复制角色', { exact: true }).last().waitFor()
  assert.equal(await page.getByLabel('选择角色', { exact: true }).inputValue(), created.id)
  await page.getByLabel('搜索资源、操作或说明', { exact: true }).fill('identity:user:read')
  await page.getByText('identity:user:read', { exact: true }).waitFor()
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: binding.screenshot_file + '.permissions.png', fullPage: true })
  assert.equal(errors, 0)
  assert.equal(tokens, false)
  const evidence = {
    ok: true,
    browser: browser.version(),
    copied_role_id: created.id,
    page_errors: errors,
    browser_token_seen: tokens,
    phases: [
      'login-and-create',
      'edit-and-disable',
      'copy-disabled-role',
      'direct-authorization-link',
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
