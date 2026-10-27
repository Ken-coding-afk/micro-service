// 仅本地示例：通过真实页面登记机器客户端、轮换秘密、保存统一策略。
require("./local-only.cjs");
const { chromium } = require("@playwright/test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
(async () => {
  const browser = await chromium.launch({ headless: true, channel: "chrome" });
  const context = await browser.newContext({
    storageState: path.join(process.cwd(), ".local/platform-browser.json"),
  });
  const page = await context.newPage();
  const base = process.env.SAMPLE_ORIGIN;
  const id = "review-reader-" + Date.now();
  const results = [];
  try {
    await page.goto(base + "/identity/admin/platform/clients");
    await page.getByRole("button", { name: "登记应用", exact: true }).waitFor();
    await page.getByRole("button", { name: "登记应用", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "应用登记与编辑" });
    await dialog.getByLabel("Client ID", { exact: true }).fill(id);
    await dialog.getByLabel("名称", { exact: true }).fill("本地验收内省身份");
    await dialog.getByLabel("主体类型").selectOption("service");
    await dialog.getByLabel("Audiences（每行一个）").fill("identity-service");
    await dialog
      .getByLabel("Scopes（每行一个，来自业务权限清单）")
      .fill("internal.introspect:sample-java-service");
    await dialog.getByLabel("已核对租户、scope、回调和授权失效影响").check();
    await dialog.getByRole("button", { name: "确认登记", exact: true }).click();
    const secret = dialog.getByLabel("一次性 client_secret（关闭后清除）");
    await secret.waitFor();
    const original = await secret.inputValue();
    assert(original.length >= 32);
    results.push("真实页面创建机器客户端并交付一次性秘密");
    await dialog.getByRole("button", { name: "关闭并清除秘密" }).click();
    assert.equal(await page.locator("textarea[readonly]").count(), 0);
    await page.getByLabel("应用搜索").fill(id);
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await page
      .getByRole("row")
      .filter({ hasText: id })
      .getByRole("button", { name: "查看与编辑", exact: true })
      .click();
    await dialog.getByLabel("已核对租户、scope、回调和授权失效影响").check();
    await dialog
      .getByRole("button", { name: "轮换秘密并使旧授权失效" })
      .click();
    await secret.waitFor();
    const next = await secret.inputValue();
    assert(next.length >= 32 && next !== original);
    // 核对 OAuth 实际客户端认证；不记录 token/秘密。
    const token = async (value) =>
      context.request.post(base + "/oauth2/token", {
        form: {
          grant_type: "client_credentials",
          scope: "internal.introspect:sample-java-service",
        },
        headers: {
          Authorization:
            "Basic " + Buffer.from(id + ":" + value).toString("base64"),
        },
      });
    assert.equal((await token(original)).status(), 401);
    assert.equal((await token(next)).status(), 200);
    results.push("真实轮换后旧秘密拒绝，新秘密可取得机器 token");
    await dialog.getByRole("button", { name: "关闭并清除秘密" }).click();
    await page.goto(base + "/identity/admin/platform/security");
    await page.getByLabel("密码最少字符数").waitFor();
    assert(Number(await page.getByLabel("密码最少字符数").inputValue()) >= 12);
    await page.getByLabel("已核对密码、登录限制及现有会话影响").check();
    await page.getByRole("button", { name: "保存策略", exact: true }).click();
    await page.getByText("项目统一安全策略已保存。", { exact: true }).waitFor();
    results.push("真实页面读取并幂等保存统一安全策略");
    await page.screenshot({
      path: ".local/platform-security.png",
      fullPage: true,
    });
    fs.writeFileSync(
      ".local/platform-settings-results.json",
      JSON.stringify({ client_id: id, passed: results }, null, 2),
    );
    for (const result of results) console.log("PASS " + result);
  } catch (error) {
    console.error(
      "平台页面验收失败:",
      new URL(page.url()).pathname,
      error.name,
    );
    console.error(error.message);
    const alerts = await page.getByRole("alert").allTextContents();
    console.error(alerts.join("\n"));
    throw error;
  } finally {
    await browser.close();
  }
})().catch(() => {
  process.exitCode = 1;
});
