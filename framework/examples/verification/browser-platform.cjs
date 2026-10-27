// 仅对本地隔离示例操作，禁止用于生产账号初始化。
require("./local-only.cjs");
const { chromium } = require("@playwright/test");
const fs = require("fs"),
  crypto = require("crypto"),
  path = require("path");
(async () => {
  const root = process.cwd(),
    secretDir = path.join(root, ".local/runtime/identity-bootstrap-secrets");
  const newFile = path.join(secretDir, "admin-current.password");
  const first = !fs.existsSync(newFile);
  const next = first
    ? "Aa1!" + crypto.randomBytes(24).toString("base64url")
    : fs.readFileSync(newFile, "utf8").trim();
  const initial = fs
    .readFileSync(path.join(secretDir, "admin-initial.password"), "utf8")
    .trim();
  const browser = await chromium.launch({ headless: true, channel: "chrome" });
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(process.env.SAMPLE_ORIGIN + "/identity/admin/");
    await page.getByText("平台管理员", { exact: true }).click();
    await page.getByRole("button", { name: "继续登录", exact: true }).click();
    await page.locator("#username").waitFor({ state: "visible" });
    async function login(password) {
      await page.locator("#realm").selectOption("PLATFORM");
      await page.locator("#tenant").fill("");
      await page.locator("#username").fill("admin");
      await page.locator("#password").fill(password);
      await page.getByRole("button", { name: "登录", exact: true }).click();
    }
    await login(first ? initial : next);
    if (first) {
      await page.waitForURL("**/identity/password/initial");
      await page.locator("#password").fill(next);
      await page.locator("#confirmation").fill(next);
      await page.getByRole("button", { name: "保存并重新登录" }).click();
      await page.locator("#username").waitFor({ state: "visible" });
      fs.writeFileSync(newFile, next, { mode: 0o600 });
      await login(next);
    }
    await page.waitForURL("**/identity/admin/**");
    await page.waitForLoadState("networkidle");
    console.log("Platform browser login completed");
    console.log((await page.locator("body").innerText()).slice(0, 900));
    await context.storageState({
      path: path.join(root, ".local/platform-browser.json"),
    });
  } catch (e) {
    console.log("Failure path", new URL(page.url()).pathname);
    console.log((await page.locator("body").innerText()).slice(0, 700));
    throw e;
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e.message.replace(/Aa1![A-Za-z0-9_-]+/g, "[redacted]"));
  process.exit(1);
});
