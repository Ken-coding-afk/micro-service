// 真实授权与并发冲突证据；不输出凭据、Cookie、完整响应或 DOM。
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
  mark('login-and-catalog')
  await page.goto(binding.origin + '/identity/admin/')
  await page.getByLabel('组织代码', { exact: true }).fill(binding.tenant_code)
  await page.getByRole('button', { name: '继续登录', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/identity/login')
  await page.locator('input[name="username"]').fill(binding.username)
  await page.locator('input[name="password"]').fill(password)
  await page.locator('button[type="submit"]').click()
  await page.getByRole('heading', { name: '个人账号', exact: true }).waitFor()
  await page.getByRole('link', { name: /^权限与授权/ }).click()
  await page.getByLabel('搜索资源、操作或说明', { exact: true }).fill(binding.permission)
  await page.getByText(binding.permission, { exact: true }).waitFor()
  mark('expand-and-evaluate')
  await page.getByRole('tab', { name: '角色授权', exact: true }).click()
  await page.getByLabel('选择角色', { exact: true }).selectOption(binding.role_id)
  await page.getByLabel('搜索资源、操作或说明', { exact: true }).fill(binding.permission)
  const scope = page.getByLabel('数据范围 ' + binding.permission, { exact: true })
  await scope.selectOption('TENANT')
  await page.getByRole('button', { name: '预览授权差异', exact: true }).click()
  await page.getByText('范围扩大 · ' + binding.permission, { exact: false }).waitFor()
  await page.getByRole('button', { name: '确认保存授权', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色授权已保存并重新读取' }).waitFor()
  const prefix = binding.origin + '/bff/identity/tenant'
  async function effective() {
    const response = await page.request.get(
      prefix + '/api/users/' + binding.target_user + '/effective-permissions',
    )
    assert.equal(response.status(), 200)
    return (await response.json()).data.permissions[binding.permission]
  }
  assert.equal((await effective()).allowed, true)
  assert.equal((await effective()).data_scope, 'TENANT')
  mark('real-version-conflict')
  await scope.selectOption('SELF')
  await page.getByRole('button', { name: '预览授权差异', exact: true }).click()
  const currentResponse = await page.request.get(
    prefix + '/api/role-permissions/' + binding.role_id,
  )
  assert.equal(currentResponse.status(), 200)
  const current = (await currentResponse.json()).data
  const csrfResponse = await page.request.get(prefix + '/csrf')
  assert.equal(csrfResponse.status(), 200)
  const csrf = (await csrfResponse.json()).data
  const concurrent = await page.request.put(prefix + '/api/role-permissions/' + binding.role_id, {
    headers: { [csrf.header_name]: csrf.token },
    data: {
      role_version: current.role.version,
      catalog_version: current.catalog_version,
      management_grant_version: current.role.management_grant_version,
      grants: [],
    },
  })
  assert.equal(concurrent.status(), 200)
  const rejectedResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname.endsWith('/role-permissions/' + binding.role_id) &&
      response.request().method() === 'PUT',
  )
  await page.getByRole('button', { name: '确认保存授权', exact: true }).click()
  assert.equal((await rejectedResponse).status(), 409)
  await page.getByText('提交前必须重新核对当前版本', { exact: true }).waitFor()
  assert.equal(await scope.inputValue(), 'SELF')
  assert.equal((await effective()).allowed, false)
  mark('explicit-rebase-and-save')
  await page.getByRole('button', { name: '保留草稿并重新核对', exact: true }).click()
  await page.getByRole('button', { name: '预览授权差异', exact: true }).click()
  await page.getByText('新增 · ' + binding.permission, { exact: false }).waitFor()
  await page.getByRole('button', { name: '确认保存授权', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '角色授权已保存并重新读取' }).waitFor()
  const final = await effective()
  assert.equal(final.allowed, true)
  assert.equal(final.data_scope, 'SELF')
  assert.equal(
    final.sources.some(
      (source) => source.role_id === binding.role_id && source.data_scope === 'SELF',
    ),
    true,
  )
  await page.screenshot({ path: binding.screenshot_file, fullPage: true })
  assert.equal(errors, 0)
  assert.equal(tokens, false)
  const evidence = {
    ok: true,
    browser: browser.version(),
    role_id: binding.role_id,
    final_data_scope: 'SELF',
    page_errors: errors,
    browser_token_seen: tokens,
    phases: [
      'login-and-catalog',
      'expand-and-evaluate',
      'real-version-conflict',
      'explicit-rebase-and-save',
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
