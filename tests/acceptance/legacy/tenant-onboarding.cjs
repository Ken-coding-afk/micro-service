// 仅对本地隔离示例操作，禁止用于生产账号初始化。
require("./local-only.cjs");
const { chromium } = require("@playwright/test");
const fs = require("fs"),
  crypto = require("crypto"),
  path = require("path");
const origin = process.env.SAMPLE_ORIGIN,
  root = process.cwd();
const stateFile = path.join(root, ".local/business-credentials.json");
const state = JSON.parse(fs.readFileSync(stateFile, "utf8"));
function save() {
  fs.writeFileSync(stateFile, JSON.stringify(state), { mode: 0o600 });
}
async function login(context, tenant, username, initial, key, web = false) {
  const page = await context.newPage();
  await page.goto(origin + (web ? "/" : "/identity/admin/"));
  if (web) {
    await page.locator("[name=tenant_code]").fill(tenant);
    await page.locator("#login button").click();
  } else {
    await page.locator("#tenant-code").fill(tenant);
    await page.getByRole("button", { name: "继续登录", exact: true }).click();
  }
  await page.locator("#username").waitFor({ state: "visible" });
  async function enter(password) {
    await page.locator("#realm").selectOption("TENANT");
    await page.locator("#tenant").fill(tenant);
    await page.locator("#username").fill(username);
    await page.locator("#password").fill(password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
  }
  await enter(state[key] || initial);
  if (!state[key]) {
    await page.waitForURL("**/identity/password/initial");
    const password = "Aa1!" + crypto.randomBytes(24).toString("base64url");
    await page.locator("#password").fill(password);
    await page.locator("#confirmation").fill(password);
    await page.getByRole("button", { name: "保存并重新登录" }).click();
    await page.locator("#username").waitFor({ state: "visible" });
    state[key] = password;
    save();
    await enter(password);
  }
  await page.waitForURL(web ? origin + "/" : "**/identity/admin/**");
  await page.waitForLoadState("networkidle");
  return page;
}
async function api(context, method, url, data) {
  const r = await context.request.get(origin + "/bff/identity/tenant/csrf");
  const csrf = (await r.json()).data;
  const response = await context.request.fetch(
    origin + "/bff/identity/tenant/api" + url,
    {
      method,
      data,
      headers: {
        Origin: origin,
        [csrf.header_name]: csrf.token,
        "X-Request-ID": crypto.randomUUID(),
      },
    },
  );
  const result = await response.json();
  if (!response.ok())
    throw new Error(
      method +
        " " +
        url +
        " HTTP " +
        response.status() +
        " " +
        JSON.stringify(result),
    );
  return result.data;
}
module.exports = { login, state, origin, api, chromium };
if (require.main === module)
  (async () => {
    const browser = await chromium.launch({
      headless: true,
      channel: "chrome",
    });
    try {
      for (const tenant of ["alpha", "beta"]) {
        const context = await browser.newContext();
        await login(
          context,
          tenant,
          "admin",
          state[tenant].initial_password,
          tenant + "-admin-password",
        );
        console.log(tenant + " administrator login passed");
        for (const who of ["writer", "denied"]) {
          const key = tenant + "-" + who;
          if (!state[key]) {
            state[key] = await api(context, "POST", "/users", {
              username: who,
              display_name: who,
            });
            save();
          }
        }
        const roleKey = tenant + "-role";
        if (!state[roleKey]) {
          state[roleKey] = await api(context, "POST", "/roles", {
            code: "sample-writer",
            name: "示例业务角色",
            description: "独立项目验收",
          });
          save();
        }
        const roleId = state[roleKey].id;
        const permissions = await api(
          context,
          "GET",
          "/role-permissions/" + roleId,
        );
        await api(context, "PUT", "/role-permissions/" + roleId, {
          role_version: permissions.role.version,
          catalog_version: permissions.catalog_version,
          grants: ["java", "python"].flatMap((lang) => [
            { permission_code: lang + "-item:read", data_scope: "TENANT" },
            { permission_code: lang + "-item:create", data_scope: null },
          ]),
        });
        const current = await api(
          context,
          "GET",
          "/role-permissions/" + roleId,
        );
        const user = await api(
          context,
          "GET",
          "/users/" + state[tenant + "-writer"].id + "/roles",
        );
        await api(
          context,
          "PUT",
          "/users/" + state[tenant + "-writer"].id + "/roles",
          {
            user_version: user.version,
            roles: [{ id: roleId, version: current.role.version }],
          },
        );
        await context.storageState({
          path: path.join(root, ".local/" + tenant + "-admin-browser.json"),
        });
        await context.close();
        console.log(tenant + " role and user assignment passed");
      }
    } finally {
      await browser.close();
    }
  })().catch((e) => {
    console.error(e.message.replace(/Aa1![A-Za-z0-9_-]+/g, "[redacted]"));
    process.exit(1);
  });
