// 三个独立真实浏览器上下文，验证指定撤销/全端退出及其他账号不受影响。
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
  async function login(kind) {
    const password = (await readFile(binding[kind + '_password_file'], 'utf8')).trim()
    const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } }),
      page = await context.newPage()
    page.setDefaultTimeout(15_000)
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
      if (url.pathname.startsWith('/bff/identity/tenant/api/') && request.method() !== 'GET')
        writes++
    })
    await page.goto(binding.origin + '/identity/admin/')
    await page.getByLabel('组织代码', { exact: true }).fill(binding.tenant_code)
    await page.getByRole('button', { name: '继续登录', exact: true }).click()
    await page.waitForURL((url) => url.pathname === '/identity/login')
    await page.locator('input[name="username"]').fill(binding[kind + '_username'])
    await page.locator('input[name="password"]').fill(password)
    await page.locator('button[type="submit"]').click()
    await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
    return page
  }
  const prefix = binding.origin + '/bff/identity/tenant/api'
  phase = 'three-logins'
  const actor = await login('actor'),
    first = await login('target'),
    second = await login('target')
  async function current(page) {
    const response = await page.request.get(prefix + '/me/sessions')
    assert.equal(response.status(), 200)
    const own = (await response.json()).data.filter((item) => item.current)
    assert.equal(own.length, 1)
    return own[0].id
  }
  const firstId = await current(first),
    secondId = await current(second)
  assert.notEqual(firstId, secondId)
  phase = 'csrf-denial'
  const noCsrf = await actor.request.post(prefix + '/sessions/' + firstId + ':revoke', {
    headers: { Origin: binding.origin, 'Sec-Fetch-Site': 'same-origin' },
    data: {},
  })
  assert.equal(noCsrf.status(), 403)
  assert.equal((await first.request.get(prefix + '/me')).status(), 200)
  phase = 'user-to-sessions'
  await actor.getByRole('link', { name: /^用户管理/ }).click()
  await actor.getByLabel('搜索账号或显示名', { exact: true }).fill(binding.target_username)
  await actor.getByRole('button', { name: '查询', exact: true }).click()
  await actor
    .getByRole('row')
    .filter({ hasText: binding.target_username })
    .getByRole('link', { name: '查看会话', exact: true })
    .click()
  await actor.getByRole('heading', { name: '登录会话', exact: true }).waitFor()
  await expect(actor.getByLabel('筛选用户 ID', { exact: true })).toHaveValue(binding.target_id)
  const firstRow = actor.getByRole('row').filter({ hasText: firstId }),
    secondRow = actor.getByRole('row').filter({ hasText: secondId })
  await firstRow.getByRole('button', { name: '撤销会话', exact: true }).waitFor()
  await secondRow.getByRole('button', { name: '撤销会话', exact: true }).waitFor()
  phase = 'revoke-one'
  await firstRow.getByRole('button', { name: '撤销会话', exact: true }).click()
  await actor.getByRole('status').filter({ hasText: '指定会话撤销已完成' }).waitFor()
  await expect(firstRow).toContainText('已撤销')
  const firstAfterOne = (await first.request.get(prefix + '/me')).status(),
    secondAfterOne = (await second.request.get(prefix + '/me')).status()
  assert.equal(firstAfterOne, 401)
  assert.equal(secondAfterOne, 200)
  phase = 'sign-out-all'
  await secondRow.getByRole('button', { name: '用户全端退出', exact: true }).click()
  await actor.getByRole('status').filter({ hasText: '该用户全端退出已完成' }).waitFor()
  await expect(secondRow).toContainText('已撤销')
  const secondAfterAll = (await second.request.get(prefix + '/me')).status(),
    actorAfterAll = (await actor.request.get(prefix + '/me')).status()
  assert.equal(secondAfterAll, 401)
  assert.equal(actorAfterAll, 200)
  await actor.screenshot({ path: binding.screenshot_file, fullPage: true })
  assert.equal(errors, 0)
  assert.equal(tokens, false)
  assert.equal(writes, 2)
  const result = {
    ok: true,
    browser: browser.version(),
    page_errors: errors,
    browser_token_seen: tokens,
    csrf_denied: true,
    management_writes: writes,
    first_after_one: firstAfterOne,
    second_after_one: secondAfterOne,
    second_after_all: secondAfterAll,
    actor_after_all: actorAfterAll,
    phases: ['three-logins', 'csrf-denial', 'user-to-sessions', 'revoke-one', 'sign-out-all'],
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
      source_line: failure?.stack?.match(/live-managed-sessions\.mjs:(\d+):\d+/)?.[1] ?? null,
    }) + '\n',
  )
  process.exitCode = 1
} finally {
  if (browser) await browser.close()
}
