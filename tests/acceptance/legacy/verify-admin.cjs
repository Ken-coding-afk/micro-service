// 对本地独立项目的真实管理页面及后端边界做验证。
const {
  login,
  state,
  origin,
  api,
  chromium,
} = require("./tenant-onboarding.cjs");
const assert = require("node:assert/strict");
(async () => {
  const browser = await chromium.launch({ headless: true, channel: "chrome" });
  try {
    const context = await browser.newContext();
    const page = await login(
      context,
      "alpha",
      "admin",
      state.alpha.initial_password,
      "alpha-admin-password",
    );
    const failures = [];
    page.on("pageerror", (error) => failures.push(error.name));
    for (const route of ["users", "roles", "permissions"]) {
      await page.goto(origin + "/identity/admin/" + route);
      await page.waitForLoadState("networkidle");
      const body = await page.locator("body").innerText();
      assert(!body.includes("管理委派") && !body.includes("授权模拟"));
      assert(!body.includes("暂时无法") && !body.includes("当前主体不能"));
      const rows = await api(
        context,
        "GET",
        route === "permissions" ? "/permissions" : "/" + route,
      );
      assert(rows.items.length > 0);
      console.log("admin page and real API passed:", route);
    }
    for (const route of ["management-grants", "authorization-evaluations"]) {
      const result = await context.request.get(
        origin + "/bff/identity/tenant/api/" + route,
      );
      assert.equal(result.status(), 404);
    }
    assert.deepEqual(failures, []);
    await context.close();
    console.log("administration boundary verification passed");
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
