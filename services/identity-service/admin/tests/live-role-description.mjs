// 角色说明的真实页面/版本/复制证据，不输出凭据或原始响应。
import { chromium, expect } from '@playwright/test'
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
  phase = 'create-description'
  await page.getByRole('link', { name: /^角色管理/ }).click()
  await page.getByRole('button', { name: '新建角色', exact: true }).click()
  const code = 'gui-desc-' + randomUUID(),
    first = '第一行说明\n第二行支持😀'
  await page.getByLabel('角色编码', { exact: true }).fill(code)
  await page.getByLabel('角色名称', { exact: true }).fill('浏览器说明角色')
  await page.getByLabel('角色说明（可选）', { exact: true }).fill(first)
  await page.getByRole('button', { name: '创建', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色已创建' }).waitFor()
  async function lookup(value) {
    const response = await page.request.get(
      binding.origin + '/bff/identity/tenant/api/roles?q=' + value,
    )
    assert.equal(response.status(), 200)
    const rows = (await response.json()).data.items
    assert.equal(rows.length, 1)
    return rows[0]
  }
  const created = await lookup(code)
  assert.equal(created.description, first)
  phase = 'description-only-change'
  const row = page.getByRole('row').filter({ hasText: code })
  await row.getByRole('button', { name: '编辑资料', exact: true }).click()
  const changed = '更新后的操作说明\n仍保留换行与😀'
  await page.getByLabel('角色说明（可选）', { exact: true }).fill(changed)
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色资料已更新' }).waitFor()
  const updated = await lookup(code)
  assert.equal(updated.description, changed)
  assert.equal(updated.version, created.version + 1)
  assert.equal(updated.authorization_version, created.authorization_version)
  phase = 'copy-description'
  await row.getByRole('button', { name: '复制角色', exact: true }).click()
  await expect(page.getByLabel('角色说明（可选）', { exact: true })).toHaveValue(changed)
  const copy = 'gui-desc-copy-' + randomUUID()
  await page.getByLabel('角色编码', { exact: true }).fill(copy)
  await page.getByLabel('角色名称', { exact: true }).fill('浏览器说明副本')
  await page.getByRole('button', { name: '确认复制', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色已复制' }).waitFor()
  assert.equal((await lookup(copy)).description, changed)
  await page.screenshot({ path: binding.screenshot_file, fullPage: true })
  assert.equal(errors, 0)
  assert.equal(tokens, false)
  const evidence = {
    ok: true,
    browser: browser.version(),
    copied_role_id: (await lookup(copy)).id,
    page_errors: errors,
    browser_token_seen: tokens,
    phases: ['login', 'create-description', 'description-only-change', 'copy-description'],
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
