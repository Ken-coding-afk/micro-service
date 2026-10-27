// 两个已初始化本地项目的互斥边界；只读，不复制账号或改配置。
const { chromium } = require("@playwright/test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

(async () => {
  const [rootA, originA, rootB, originB] = process.argv.slice(2);
  for (const origin of [originA, originB]) {
    const url = new URL(origin);
    assert.equal(url.protocol, "http:");
    assert(["localhost", "127.0.0.1"].includes(url.hostname));
    assert.equal(url.pathname, "/");
  }
  assert.notEqual(originA, originB);
  const browser = await chromium.launch({ headless: true, channel: "chrome" });
  try {
    const roots = [rootA, rootB];
    const origins = [originA, originB];
    const tenantIds = [];
    for (let i = 0; i < roots.length; i++) {
      const context = await browser.newContext({
        storageState: path.join(roots[i], ".local/platform-browser.json"),
      });
      const endpoint = "/bff/identity/platform/api/platform/tenants";
      const own = await context.request.get(origins[i] + endpoint);
      assert.equal(own.status(), 200, "自身会话必须有效，不能用过期会话证明隔离");
      const data = (await own.json()).data;
      const items = Array.isArray(data) ? data : data.items;
      assert(items.length >= 2);
      tenantIds.push(items.map((item) => item.id));
      const foreign = await context.request.get(origins[1 - i] + endpoint);
      assert.equal(foreign.status(), 401, "其他项目不能接受此会话");
      await context.close();
    }
    assert(tenantIds[0].every((id) => id && !tenantIds[1].includes(id)));
    for (let i = 0; i < roots.length; i++) {
      const state = JSON.parse(fs.readFileSync(path.join(roots[i], ".local/business-credentials.json")));
      const other = JSON.parse(fs.readFileSync(path.join(roots[1 - i], ".local/business-credentials.json")));
      assert.notEqual(state["java-resource"].client_secret, other["java-resource"].client_secret);
    }
    console.log("PASS two independent projects: own sessions valid, foreign sessions rejected, tenant IDs and credentials isolated");
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
