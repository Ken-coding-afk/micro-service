// 本地工作区运行验收；不参与项目初始化，不输出账号秘密。
const { chromium } = require('../../services/identity-service/admin/node_modules/@playwright/test');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const assert = require('node:assert/strict');

const root = path.resolve(process.argv[2] || '');
const values = Object.fromEntries(fs.readFileSync(path.join(root, '.local/environment.env'), 'utf8')
  .split(/\r?\n/).filter(line => line.includes('=') && !line.startsWith('#'))
  .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
assert.equal(values.APP_ENV, 'local');
assert.match(values.PUBLIC_ORIGIN, /^http:\/\/localhost:\d+$/);
const origin = values.PUBLIC_ORIGIN;
const saved = path.join(root, '.local/workspace-smoke-passwords.json');
const passwords = fs.existsSync(saved) ? JSON.parse(fs.readFileSync(saved, 'utf8')) : {};
const services = process.argv.slice(3);
assert.equal(services.length, 2);
const marker = 'workspace-' + crypto.randomUUID();

async function login(browser, tenant, user) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(origin + '/');
  await page.locator('[name=tenant_code]').fill(tenant);
  await page.locator('#login button').click();
  const key = tenant + '-' + user;
  async function enter(password) {
    await page.locator('#username').waitFor({state: 'visible'});
    await page.locator('#realm').selectOption('TENANT');
    await page.locator('#tenant').fill(tenant);
    await page.locator('#username').fill(user);
    await page.locator('#password').fill(password);
    await page.getByRole('button', {name: '登录', exact: true}).click();
  }
  const initial = fs.readFileSync(path.join(root, '.local/runtime/identity-bootstrap-secrets', key + '.password'), 'utf8').trim();
  await enter(passwords[key] || initial);
  if (!passwords[key]) {
    await page.waitForURL('**/identity/password/initial');
    const password = 'Aa1!' + crypto.randomBytes(24).toString('base64url');
    await page.locator('#password').fill(password);
    await page.locator('#confirmation').fill(password);
    await page.getByRole('button', {name: '保存并重新登录'}).click();
    passwords[key] = password;
    fs.writeFileSync(saved, JSON.stringify(passwords), {mode: 0o600});
    await enter(password);
  }
  await page.waitForURL(origin + '/');
  return {context, page};
}

async function request(context, service, method = 'GET', suffix = '') {
  const headers = {Origin: origin, 'X-Request-ID': crypto.randomUUID()};
  if (method === 'POST') {
    const csrf = await context.request.get(origin + '/bff/web/csrf');
    assert.equal(csrf.status(), 200);
    const token = (await csrf.json()).data;
    headers[token.header_name] = token.token;
  }
  return context.request.fetch(origin + '/bff/web/api/v1/' + service + '/items' + suffix,
    {method, headers, ...(method === 'POST' ? {data: {name: marker}} : {})});
}

(async () => {
  const browser = await chromium.launch({headless: true, channel: 'chrome'});
  try {
    const alpha = await login(browser, 'alpha', 'writer');
    for (const service of services) {
      const created = await request(alpha.context, service, 'POST');
      assert.equal(created.status(), 201, service + ' create: ' + await created.text());
      const listed = await request(alpha.context, service);
      assert.equal(listed.status(), 200);
      assert.ok((await listed.text()).includes(marker));
      await alpha.page.locator('#service').selectOption(service);
      await alpha.page.locator('#list').click();
      await alpha.page.waitForFunction(() => document.querySelector('#result').textContent.startsWith('HTTP 200'));
    }
    for (const service of services) {
      const peer = await request(alpha.context, service, 'GET', '/peer');
      assert.equal(peer.status(), 200, service + ' peer: ' + await peer.text());
      assert.ok((await peer.text()).includes(marker));
    }
    await alpha.context.close();
    const beta = await login(browser, 'beta', 'writer');
    for (const service of services) {
      const listed = await request(beta.context, service);
      assert.equal(listed.status(), 200);
      assert.ok(!(await listed.text()).includes(marker), 'cross-tenant record leak');
    }
    await beta.context.close();
    const denied = await login(browser, 'alpha', 'denied');
    for (const service of services) {
      assert.equal((await request(denied.context, service)).status(), 403);
      assert.equal((await request(denied.context, service, 'POST')).status(), 403);
    }
    await denied.context.close();
    console.log('PASS: login/initial-password, Java/Python create/list/UI, bidirectional peer, tenant isolation, denied permissions');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
