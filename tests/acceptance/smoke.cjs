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

async function listAll(context, service) {
  const items = [];
  for (let page = 0; page <= 10000; page++) {
    const result = await request(context, service, 'GET', `?page=${page}&size=2`);
    assert.equal(result.status(), 200);
    const body = await result.json();
    assert.equal(body.code, 'OK');
    assert.equal(typeof body.request_id, 'string');
    assert.equal(body.data.page, page);
    assert.equal(body.data.size, 2);
    assert.equal(typeof body.data.has_more, 'boolean');
    assert.ok(body.data.items.length <= 2);
    assert.equal(body.data.has_more, (page + 1) * 2 < body.data.total);
    items.push(...body.data.items);
    if (!body.data.has_more) {
      assert.equal(items.length, body.data.total);
      assert.equal(new Set(items.map(item => item.id)).size, items.length);
      return items;
    }
  }
  throw new Error('分页未终止');
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    const anonymous = await browser.newContext();
    for (const service of services) {
      const rejected = await request(anonymous, service);
      assert.equal(rejected.status(), 401);
    }
    await anonymous.close();
    const alpha = await login(browser, 'alpha', 'writer');
    const createdIds = new Map();
    const visibleItems = new Map();
    for (const service of services) {
      const ids = [];
      for (let index = 0; index < 5; index++) {
        const created = await request(alpha.context, service, 'POST');
        assert.equal(created.status(), 201, service + ' create');
        const item = (await created.json()).data;
        assert.equal(item.name, marker);
        ids.push(item.id);
      }
      createdIds.set(service, ids);
      const listed = await listAll(alpha.context, service);
      visibleItems.set(service, listed);
      assert.deepEqual(listed.filter(item => item.name === marker).map(item => item.id).sort(), [...ids].sort());
      const empty = await request(alpha.context, service, 'GET', '?page=10000&size=100');
      assert.equal(empty.status(), 200);
      const emptyPage = (await empty.json()).data;
      assert.deepEqual(emptyPage.items, []);
      assert.equal(emptyPage.has_more, false);
      for (const query of ['page=-1', 'page=10001', 'size=0', 'size=101', 'page_size=20']) {
        assert.equal((await request(alpha.context, service, 'GET', '?' + query)).status(), 400);
      }
      await alpha.page.locator('#service').selectOption(service);
      await alpha.page.locator('#list').click();
      await alpha.page.waitForFunction(() => document.querySelector('#result').textContent.startsWith('HTTP 200'));
    }
    for (const service of services) {
      const peer = await request(alpha.context, service, 'GET', '/peer');
      assert.equal(peer.status(), 200, service + ' peer');
      const data = (await peer.json()).data;
      assert.equal(data.page, 0);
      assert.equal(data.size, 25);
      assert.equal(typeof data.has_more, 'boolean');
      const other = services.find(name => name !== service);
      assert.deepEqual(data.items, visibleItems.get(other).slice(0, data.size));
    }
    await alpha.context.close();
    const beta = await login(browser, 'beta', 'writer');
    for (const service of services) {
      const listed = await listAll(beta.context, service);
      assert.ok(!listed.some(item => createdIds.get(service).includes(item.id)), 'cross-tenant record leak');
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
