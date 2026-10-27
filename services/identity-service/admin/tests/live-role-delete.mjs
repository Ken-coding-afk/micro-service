// 真实空角色删除和CSRF证据；只输出阶段/非敏感结果。
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
  phase = 'login'
  await page.goto(binding.origin + '/identity/admin/')
  await page.getByLabel('组织代码', { exact: true }).fill(binding.tenant_code)
  await page.getByRole('button', { name: '继续登录', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/identity/login')
  await page.locator('input[name="username"]').fill(binding.username)
  await page.locator('input[name="password"]').fill(password)
  await page.locator('button[type="submit"]').click()
  await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
  const prefix = binding.origin + '/bff/identity/tenant/api'
  phase = 'csrf-denial'
  const denied = await page.request.delete(prefix + '/roles/' + binding.role_id, {
    headers: { Origin: binding.origin, 'Sec-Fetch-Site': 'same-origin' },
    data: { version: 0 },
  })
  assert.equal(denied.status(), 403)
  assert.equal(
    (await page.request.get(prefix + '/role-permissions/' + binding.role_id)).status(),
    200,
  )
  phase = 'review-and-delete'
  await page.getByRole('link', { name: /^角色管理/ }).click()
  await page.getByLabel('搜索角色编码或名称', { exact: true }).fill('business')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: 'business' })
    .getByRole('button', { name: '删除角色', exact: true })
    .click()
  await page.getByText(/删除将清理此角色的 1 项权限关系/).waitFor()
  await page.screenshot({ path: binding.screenshot_file + '.confirmation.png', fullPage: true })
  await page.getByRole('button', { name: '确认删除角色', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色已删除' }).waitFor()
  await page.getByText('暂无符合条件的角色。', { exact: true }).waitFor()
  assert.equal(
    (await page.request.get(prefix + '/role-permissions/' + binding.role_id)).status(),
    404,
  )
  const directory = await page.request.get(prefix + '/permissions')
  assert.equal(directory.status(), 200)
  assert.equal(
    (await directory.json()).data.items.some((item) => item.code === binding.permission),
    true,
  )
  await page.screenshot({ path: binding.screenshot_file, fullPage: true })
  assert.equal(errors, 0)
  assert.equal(tokens, false)
  const evidence = {
    ok: true,
    browser: browser.version(),
    deleted_role_id: binding.role_id,
    csrf_denied: true,
    directory_preserved: true,
    page_errors: errors,
    browser_token_seen: tokens,
    phases: ['login', 'csrf-denial', 'review-and-delete'],
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
