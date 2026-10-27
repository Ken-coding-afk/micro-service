// 真实租户管理流程：只输出阶段和非敏感结果，不记录 DOM、凭据、Cookie 或原始异常。
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
  const password = (await readFile(binding.initial_password_file, 'utf8')).trim()
  browser = await chromium.launch({
    headless: true,
    channel: process.env.PLATFORM_TEST_BROWSER_CHANNEL ?? 'msedge',
  })
  let errors = 0,
    tokens = false
  async function context() {
    const value = await browser.newContext({ viewport: { width: 1440, height: 1050 } })
    value.on('page', (page) => {
      page.setDefaultTimeout(15_000)
      page.on('pageerror', () => {
        errors++
      })
      page.on('dialog', (dialog) => dialog.accept())
    })
    value.on('request', (request) => {
      const url = new URL(request.url())
      if (
        ['access_token', 'refresh_token', 'id_token'].some((key) => url.searchParams.has(key)) ||
        (url.pathname.startsWith('/bff/') && request.headers().authorization)
      )
        tokens = true
    })
    return value
  }
  async function activate(page, username, initial) {
    await page.goto(binding.origin + '/identity/admin/')
    await page.getByRole('heading', { name: '账号登录', exact: true }).waitFor()
    await page.getByLabel('组织代码', { exact: true }).fill(binding.tenant_code)
    await page.getByRole('button', { name: '继续登录', exact: true }).click()
    await page.waitForURL((url) => url.pathname === '/identity/login')
    await page.locator('input[name="username"]').fill(username)
    await page.locator('input[name="password"]').fill(initial)
    await page.locator('button[type="submit"]').click()
    await page.waitForURL((url) => url.pathname === '/identity/password/initial')
    const changed = 'UI user password ' + randomUUID()
    await page.locator('input[name="password"]').fill(changed)
    await page.locator('input[name="confirmation"]').fill(changed)
    await page.locator('button[type="submit"]').click()
    await page.waitForURL((url) => url.pathname === '/identity/login')
    await page.locator('input[name="username"]').fill(username)
    await page.locator('input[name="password"]').fill(changed)
    await page.locator('button[type="submit"]').click()
    await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
  }
  mark('manager-first-login')
  const manager = await context(),
    page = await manager.newPage()
  await activate(page, binding.username, password)
  mark('create-user')
  await page.getByRole('link', { name: /^用户管理/ }).click()
  await page.getByRole('button', { name: '新建用户', exact: true }).click()
  const account = 'gui-learner-' + randomUUID()
  await page.getByLabel('登录账号', { exact: true }).fill(account)
  await page.getByLabel('显示名', { exact: true }).fill('浏览器新学员')
  await page.getByRole('button', { name: '创建', exact: true }).click()
  await page.getByRole('heading', { name: '初始凭据交付', exact: true }).waitFor()
  const issued = await page.getByLabel('初始密码', { exact: true }).inputValue()
  assert.equal(issued.length >= 15, true)
  await page.getByRole('button', { name: '已记录，关闭', exact: true }).click()
  assert.equal(await page.getByLabel('初始密码', { exact: true }).count(), 0)
  let row = page.getByRole('row').filter({ hasText: account })
  assert.equal(await row.getByRole('button', { name: '启停', exact: true }).isDisabled(), true)
  mark('update-profile-and-permissions')
  await row.getByRole('button', { name: '修改资料', exact: true }).click()
  await page.getByLabel('显示名', { exact: true }).fill('浏览器学员资料已更新')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '用户资料已更新' }).waitFor()
  await row.getByRole('button', { name: '有效权限', exact: true }).click()
  await page.getByText('缺少角色授权', { exact: true }).first().waitFor()
  const userList = await page.request.get(
    binding.origin + '/bff/identity/tenant/api/users?q=' + account,
  )
  assert.equal(userList.status(), 200)
  const createdUser = (await userList.json()).data.items[0]
  const evaluated = await page.request.get(
    binding.origin + '/bff/identity/tenant/api/users/' + createdUser.id + '/effective-permissions',
  )
  assert.equal(evaluated.status(), 200)
  const snapshot = (await evaluated.json()).data
  assert.equal(snapshot.view, 'ROLE_GRANTS')
  assert.equal(snapshot.user_id, createdUser.id)
  assert.equal(Object.keys(snapshot.permissions).length > 0, true)
  assert.equal(
    Object.values(snapshot.permissions).every(
      (decision) =>
        !decision.allowed &&
        decision.sources.length === 0 &&
        decision.reasons.includes('NO_ROLE_GRANT'),
    ),
    true,
  )
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  mark('user-activation')
  const learner = await context(),
    learnerPage = await learner.newPage()
  await activate(learnerPage, account, issued)
  assert.equal(await learnerPage.getByRole('link', { name: /^用户管理/ }).count(), 0)
  const own = await learnerPage.request.get(binding.origin + '/bff/identity/tenant/api/me')
  assert.equal(own.status(), 200)
  const userId = (await own.json()).data.sub
  assert.equal(
    (await learnerPage.request.get(binding.origin + '/bff/identity/tenant/api/users')).status(),
    403,
  )
  mark('disable-user')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  row = page.getByRole('row').filter({ hasText: account })
  await row.getByRole('button', { name: '启停', exact: true }).click()
  await page.getByLabel('状态', { exact: true }).selectOption('DISABLED')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '用户状态已更新' }).waitFor()
  assert.equal(
    (await learnerPage.request.get(binding.origin + '/bff/identity/tenant/api/me')).status(),
    401,
  )
  assert.equal(
    (await page.request.get(binding.origin + '/bff/identity/tenant/api/me')).status(),
    200,
  )
  await page.screenshot({ path: binding.screenshot_file, fullPage: true })
  mark('logout')
  await page.getByRole('button', { name: '退出本应用', exact: true }).click()
  await page.getByRole('heading', { name: '账号登录', exact: true }).waitFor()
  assert.equal(errors, 0)
  assert.equal(tokens, false)
  const evidence = {
    ok: true,
    browser: browser.version(),
    user_id: userId,
    page_errors: errors,
    browser_token_seen: tokens,
    phases: [
      'manager-first-login',
      'create-user',
      'update-profile-and-permissions',
      'user-activation',
      'disable-user',
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
