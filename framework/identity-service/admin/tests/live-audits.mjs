// 两个真实身份域的只读审计与服务端脱敏；不打印原始记录/凭据/异常。
import { chromium, expect } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
let browser,
  phase = 'binding'
try {
  const binding = JSON.parse(await readFile(process.env.PLATFORM_TEST_ADMIN_E2E_BINDING, 'utf8'))
  assert.equal(binding.test_only, true)
  assert.equal(binding.origin, 'http://localhost:18080')
  browser = await chromium.launch({
    headless: true,
    channel: process.env.PLATFORM_TEST_BROWSER_CHANNEL ?? 'msedge',
  })
  let errors = 0,
    tokens = false,
    writes = 0
  for (const realm of ['tenant', 'platform']) {
    phase = realm + '-login'
    const password = (await readFile(binding[realm + '_password_file'], 'utf8')).trim()
    const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } }),
      page = await context.newPage()
    page.setDefaultTimeout(15_000)
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
      if (
        url.pathname.startsWith('/bff/identity/') &&
        url.pathname.includes('/api/') &&
        request.method() !== 'GET'
      )
        writes++
    })
    await page.goto(binding.origin + '/identity/admin/')
    if (realm === 'platform')
      await page.getByRole('button', { name: '平台管理员', exact: true }).click()
    else await page.getByLabel('组织代码', { exact: true }).fill(binding.tenant_code)
    await page.getByRole('button', { name: '继续登录', exact: true }).click()
    await page.waitForURL((url) => url.pathname === '/identity/login')
    await page.locator('input[name="username"]').fill(binding[realm + '_username'])
    await page.locator('input[name="password"]').fill(password)
    await page.locator('button[type="submit"]').click()
    await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
    await page.getByRole('link', { name: /^管理审计/ }).click()
    await page.getByRole('heading', { name: '审计记录', exact: true }).waitFor()
    await page.getByLabel('动作编码', { exact: true }).fill(binding.action)
    await page.getByLabel('目标标识', { exact: true }).fill(binding.target)
    await page.getByLabel('执行结果', { exact: true }).selectOption('SUCCESS')
    const path = `/bff/identity/${realm}/api${realm === 'platform' ? '/platform' : ''}/audits`
    async function query(expectedId) {
      const response = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return url.pathname === path && url.searchParams.get('target_id') === binding.target
      })
      await page.getByRole('button', { name: '查询审计', exact: true }).click()
      const received = await response
      assert.equal(received.status(), 200)
      const body = await received.json()
      assert.deepEqual(
        body.data.items.map((item) => item.id),
        [expectedId],
      )
      assert.equal(JSON.stringify(body).includes('AUDIT_UI_TEST_SECRET_NOT_TO_SHOW'), false)
      assert.equal(JSON.stringify(body).includes('FOREIGN_AUDIT_UI_NOTE'), false)
      const row = page.getByRole('row').filter({ hasText: 'fixture-' + expectedId })
      await row.getByRole('button', { name: '查看明细', exact: true }).click()
      return page.getByRole('dialog', { name: '审计明细', exact: true })
    }
    phase = realm + '-sanitized-detail'
    let dialog = await query(binding[realm + '_entry'])
    await expect(dialog.locator('pre')).toContainText('[REDACTED]')
    await expect(dialog.locator('pre')).toContainText('新名称')
    await expect(dialog.locator('pre')).toContainText('<img src=x')
    assert.equal(await dialog.locator('pre img').count(), 0)
    await page.screenshot({
      path: binding.screenshot_file + (realm === 'platform' ? '.platform.png' : ''),
      fullPage: true,
    })
    await dialog.getByRole('button', { name: '关闭明细', exact: true }).click()
    if (realm === 'tenant') {
      phase = 'tenant-truncated-detail'
      await page.getByLabel('执行结果', { exact: true }).selectOption('FAILURE')
      dialog = await query(binding.truncated_entry)
      await expect(dialog).toContainText('服务端已截断明细')
      await page.screenshot({ path: binding.screenshot_file + '.truncated.png', fullPage: true })
    }
    await context.close()
  }
  assert.equal(errors, 0)
  assert.equal(tokens, false)
  assert.equal(writes, 0)
  const result = {
    ok: true,
    browser: browser.version(),
    page_errors: errors,
    browser_token_seen: tokens,
    management_writes: writes,
    tenant_and_platform_isolated: true,
    details_redacted: true,
    truncated_detail_visible: true,
    phases: [
      'tenant-login',
      'tenant-sanitized-detail',
      'tenant-truncated-detail',
      'platform-login',
      'platform-sanitized-detail',
    ],
  }
  await writeFile(binding.result_file, JSON.stringify(result, null, 2) + '\n', 'utf8')
  process.stdout.write(JSON.stringify(result) + '\n')
} catch (failure) {
  process.stdout.write(
    JSON.stringify({
      ok: false,
      phase,
      error_type: failure?.name === 'TimeoutError' ? 'TIMEOUT' : 'ASSERTION_OR_DEPENDENCY',
      selector_ambiguous: failure?.message?.includes('strict mode violation') === true,
      source_line: failure?.stack?.match(/live-audits\.mjs:(\d+):\d+/)?.[1] ?? null,
    }) + '\n',
  )
  process.exitCode = 1
} finally {
  if (browser) await browser.close()
}
