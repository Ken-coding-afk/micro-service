// 本地验收工具：复用已登记的示例账号，秘密只写入被忽略的 .local。
const { chromium, login, state } = require("./tenant-onboarding.cjs");
const tenant = process.argv[2] || "beta";
if (!["alpha", "beta"].includes(tenant)) throw new Error("仅支持本地示例租户");
(async () => {
  const browser = await chromium.launch({ headless: true, channel: "chrome" });
  try {
    const context = await browser.newContext();
    await login(context, tenant, "writer", state[tenant + "-writer"].initial_password,
      tenant + "-writer-password", true);
    await context.storageState({ path: ".local/resilience-" + tenant + "-browser.json" });
    console.log("本地验收会话已保存，不输出 Cookie 或令牌");
  } finally {
    await browser.close();
  }
})().catch(() => { console.error("本地验收登录失败"); process.exit(1); });
