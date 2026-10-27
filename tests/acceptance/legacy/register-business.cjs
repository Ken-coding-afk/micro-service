// 仅对本地隔离示例操作，禁止用于生产账号初始化。
require("./local-only.cjs");
const { chromium } = require("@playwright/test");
const fs = require("fs"),
  path = require("path"),
  crypto = require("crypto");
(async () => {
  const root = process.cwd(),
    file = path.join(root, ".local/business-credentials.json");
  const state = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file)) : {};
  const browser = await chromium.launch({ headless: true, channel: "chrome" });
  const ctx = await browser.newContext({
    storageState: path.join(root, ".local/platform-browser.json"),
  });
  const base = process.env.SAMPLE_ORIGIN,
    prefix = "/bff/identity/platform";
  const save = () =>
    fs.writeFileSync(file, JSON.stringify(state, null, 2), { mode: 0o600 });
  async function post(url, body, key) {
    if (state[key]) return state[key];
    const csrf = await (await ctx.request.get(base + prefix + "/csrf")).json();
    const r = await ctx.request.post(base + prefix + "/api/" + url, {
      data: body,
      headers: {
        Origin: base,
        [csrf.data.header_name]: csrf.data.token,
        "X-Request-Id": crypto.randomUUID(),
      },
    });
    const result = await r.json();
    if (r.status() !== 200)
      throw Error(url + ": HTTP " + r.status() + " " + result.code);
    state[key] = result.data;
    save();
    console.log(key + " registered");
    return result.data;
  }
  try {
    for (const lang of ["java", "python"])
      await post(
        "platform/service-audiences",
        {
          service: "sample-" + lang + "-service",
          audience: "sample-" + lang + "-service",
        },
        "audience-" + lang,
      );
    for (const tenant of ["alpha", "beta"])
      await post(
        "platform/tenants",
        {
          code: tenant,
          name: tenant + " 示例组织",
          admin_username: "admin",
          admin_display_name: "租户管理员",
        },
        tenant,
      );
    for (const lang of ["java", "python"])
      for (const consumer of ["resource", "gateway"])
        await post(
          "platform/oauth-clients",
          {
            client_id: "sample-" + lang + "-" + consumer,
            name: lang + " " + consumer,
            principal_type: "service",
            owner_tenant_id: null,
            allowed_tenant_ids: [],
            audiences: ["identity-service"],
            scopes: ["internal.introspect:sample-" + lang + "-service"],
            redirect_uris: [],
            logout_uris: [],
            public_client: false,
            first_party: false,
            status: "ACTIVE",
          },
          lang + "-" + consumer,
        );
    await post(
      "platform/oauth-clients",
      {
        client_id: "web-bff",
        name: "示例业务应用",
        principal_type: "user",
        owner_tenant_id: null,
        allowed_tenant_ids: [state.alpha.tenant_id, state.beta.tenant_id],
        audiences: [
          "identity-service",
          "sample-java-service",
          "sample-python-service",
        ],
        scopes: [
          "profile",
          "java.items.read",
          "java.items.write",
          "python.items.read",
          "python.items.write",
        ],
        redirect_uris: [base + "/bff/web/callback"],
        logout_uris: [base + "/bff/web/logged-out"],
        public_client: false,
        first_party: true,
        status: "ACTIVE",
      },
      "web",
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
