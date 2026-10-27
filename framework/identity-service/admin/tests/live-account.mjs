// 本地真实浏览器证据入口；仅受控文件引用，日志不输出 DOM、Cookie、凭据或原异常。
import { chromium } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import assert from 'node:assert/strict'

let browser
let phase = 'binding'
try {
  const binding = JSON.parse(await readFile(process.env.PLATFORM_TEST_ADMIN_E2E_BINDING, 'utf8'))
  assert.equal(binding.test_only, true)
  assert.equal(binding.origin, 'http://localhost:18080')
  assert.equal(path.isAbsolute(binding.initial_password_file), true)
  assert.equal(path.isAbsolute(binding.new_password_file), true)
  const initial = (await readFile(binding.initial_password_file, 'utf8')).trim()
  const changed = (await readFile(binding.new_password_file, 'utf8')).trim()
  browser = await chromium.launch({
    headless: true,
    channel: process.env.PLATFORM_TEST_BROWSER_CHANNEL ?? 'msedge',
  })
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } })
  const page = await context.newPage()
  page.setDefaultTimeout(15_000)
  let pageErrors = 0
  let browserToken = false
  page.on('pageerror', () => {
    pageErrors++
  })
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (['access_token', 'refresh_token', 'id_token'].some((key) => url.searchParams.has(key)))
      browserToken = true
    if (url.pathname.startsWith('/bff/') && request.headers().authorization) browserToken = true
  })
  page.on('dialog', (dialog) => dialog.accept())
  phase = 'public-ui'
  await page.goto(binding.origin + '/identity/admin/')
  await page.getByRole('heading', { name: '账号登录', exact: true }).waitFor()
  await page.getByLabel('组织代码').fill(binding.tenant_code)
  await page.getByRole('button', { name: '继续登录', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/identity/login')
  phase = 'initial-login'
  await page.locator('input[name="username"]').fill(binding.username)
  await page.locator('input[name="password"]').fill(initial)
  await page.locator('button[type="submit"]').click()
  await page.waitForURL((url) => url.pathname === '/identity/password/initial')
  phase = 'initial-password'
  await page.locator('input[name="password"]').fill(changed)
  await page.locator('input[name="confirmation"]').fill(changed)
  await page.locator('button[type="submit"]').click()
  await page.waitForURL(
    (url) => url.pathname === '/identity/login' && url.searchParams.has('changed'),
  )
  phase = 'fresh-login'
  await page.locator('input[name="username"]').fill(binding.username)
  await page.locator('input[name="password"]').fill(changed)
  await page.locator('button[type="submit"]').click()
  await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
  phase = 'profile'
  await page.getByLabel('显示名', { exact: true }).fill('真实浏览器资料验证')
  await page.getByRole('button', { name: '保存资料', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '显示名已更新' }).waitFor()
  const me = await page.request.get(binding.origin + '/bff/identity/tenant/api/me')
  assert.equal(me.status(), 200)
  const data = (await me.json()).data
  assert.equal(data.sub === binding.expected_subject, true)
  assert.equal(data.profile.name, '真实浏览器资料验证')
  const profileResponse = await page.request.get(
    binding.origin + '/bff/identity/tenant/api/me/profile',
  )
  const profile = (await profileResponse.json()).data
  const denied = await page.request.put(binding.origin + '/bff/identity/tenant/api/me/profile', {
    data: { version: profile.version, display_name: '无 CSRF 不应改变' },
  })
  assert.equal(denied.status(), 403)
  phase = 'sessions'
  await page.getByRole('tab', { name: '登录会话', exact: true }).click()
  await page.getByText('当前登录', { exact: true }).first().waitFor()
  await page.screenshot({ path: binding.screenshot_file, fullPage: true })
  phase = 'application-logout'
  await page.getByRole('button', { name: '退出本应用', exact: true }).click()
  await page.getByRole('heading', { name: '账号登录', exact: true }).waitFor()
  assert.equal(
    (await page.request.get(binding.origin + '/bff/identity/tenant/api/me')).status(),
    401,
  )
  assert.equal(pageErrors, 0)
  assert.equal(browserToken, false)
  const evidence = {
    ok: true,
    browser: browser.version(),
    page_errors: pageErrors,
    browser_token_seen: browserToken,
    phases: [
      'public-ui',
      'initial-login',
      'initial-password',
      'fresh-login',
      'profile',
      'sessions',
      'application-logout',
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
