// 仅对本地隔离示例操作，禁止用于生产账号初始化。
require("./local-only.cjs");
const {
  login,
  state,
  origin,
  api,
  chromium,
} = require("./tenant-onboarding.cjs");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const fs = require("fs");
(async () => {
  const browser = await chromium.launch({ headless: true, channel: "chrome" });
  const evidence = [];
  const passed = (name) => {
    evidence.push(name);
    console.log("PASS " + name);
  };
  async function call(context, method, lang, data, tail = "", headers = {}) {
    if (method !== "GET") {
      const csrf = (
        await (await context.request.get(origin + "/bff/web/csrf")).json()
      ).data;
      headers = { ...headers, [csrf.header_name]: csrf.token, Origin: origin };
    }
    const response = await context.request.fetch(
      origin + "/bff/web/api/v1/" + lang + "-items" + tail,
      { method, data, headers },
    );
    let body;
    try {
      body = await response.json();
    } catch {
      body = { message: await response.text() };
    }
    return { status: response.status(), body };
  }
  try {
    const anonymous = await browser.newContext();
    assert.equal((await call(anonymous, "GET", "java")).status, 401);
    assert.equal((await call(anonymous, "GET", "python")).status, 401);
    passed("anonymous denied both services");
    await anonymous.close();
    const contexts = {};
    const run = crypto.randomUUID();
    for (const tenant of ["alpha", "beta"]) {
      const context = await browser.newContext();
      contexts[tenant] = context;
      await login(
        context,
        tenant,
        "writer",
        state[tenant + "-writer"].initial_password,
        tenant + "-writer-password",
        true,
      );
      passed(tenant + " business browser login and mandatory password change");
      for (const lang of ["java", "python"]) {
        const result = await call(context, "POST", lang, {
          name: tenant + "-" + run,
        });
        assert.equal(result.status, 201, JSON.stringify(result));
        passed(tenant + " " + lang + " create");
      }
    }
    for (const tenant of ["alpha", "beta"])
      for (const lang of ["java", "python"]) {
        const result = await call(
          contexts[tenant],
          "GET",
          lang,
          undefined,
          "",
          {
            "X-Tenant-ID":
              state[tenant === "alpha" ? "beta" : "alpha"].tenant_id,
          },
        );
        assert.equal(result.status, 200, JSON.stringify(result));
        const serialized = JSON.stringify(result.body);
        assert.ok(serialized.includes(tenant + "-" + run));
        assert.ok(
          !serialized.includes(
            (tenant === "alpha" ? "beta" : "alpha") + "-" + run,
          ),
        );
        passed(tenant + " " + lang + " tenant isolation with forged header");
        const limited = await call(contexts[tenant], "GET", lang, undefined, "?page_size=100");
        assert.equal(limited.status, 200);
        assert.equal(limited.body.data.page_size, 50);
        passed(tenant + " " + lang + " service-owned business configuration");
        const peer = await call(
          contexts[tenant],
          "GET",
          lang,
          undefined,
          "/peer",
        );
        assert.equal(peer.status, 200, JSON.stringify(peer));
        assert.ok(JSON.stringify(peer.body).includes(tenant + "-" + run));
        passed(tenant + " " + lang + " peer discovery and token forwarding");
      }
    const denied = await browser.newContext();
    await login(
      denied,
      "alpha",
      "denied",
      state["alpha-denied"].initial_password,
      "alpha-denied-password",
      true,
    );
    for (const lang of ["java", "python"]) {
      assert.equal((await call(denied, "GET", lang)).status, 403);
      assert.equal(
        (await call(denied, "POST", lang, { name: "forbidden" })).status,
        403,
      );
    }
    passed("authenticated ungranted account denied read and create");
    const admin = await browser.newContext();
    await login(
      admin,
      "alpha",
      "admin",
      state.alpha.initial_password,
      "alpha-admin-password",
    );
    const roleId = state["alpha-role"].id;
    const userId = state["alpha-denied"].id;
    const original = await api(admin, "GET", "/role-permissions/" + roleId);
    async function setGrants(grants) {
      const current = await api(admin, "GET", "/role-permissions/" + roleId);
      await api(admin, "PUT", "/role-permissions/" + roleId, {
        role_version: current.role.version,
        catalog_version: current.catalog_version,
        grants,
      });
    }
    async function assignTemporary(enabled) {
      const user = await api(admin, "GET", "/users/" + userId + "/roles");
      const current = await api(admin, "GET", "/role-permissions/" + roleId);
      await api(admin, "PUT", "/users/" + userId + "/roles", {
        user_version: user.version,
        roles: enabled ? [{ id: roleId, version: current.role.version }] : [],
      });
    }
    try {
      await assignTemporary(true);
      for (const lang of ["java", "python"]) {
        assert.equal(
          (await call(denied, "POST", lang, { name: "second-owner-" + run }))
            .status,
          201,
        );
      }
      await setGrants(
        original.grants.map((grant) => ({
          ...grant,
          data_scope: grant.data_scope === "TENANT" ? "SELF" : grant.data_scope,
        })),
      );
      for (const lang of ["java", "python"]) {
        const first = await call(contexts.alpha, "GET", lang);
        const second = await call(denied, "GET", lang);
        assert.equal(first.status, 200);
        assert.equal(second.status, 200);
        assert.ok(JSON.stringify(first.body).includes("alpha-" + run));
        assert.ok(!JSON.stringify(first.body).includes("second-owner-" + run));
        assert.ok(JSON.stringify(second.body).includes("second-owner-" + run));
        assert.ok(!JSON.stringify(second.body).includes("alpha-" + run));
      }
      passed(
        "SELF scope filters each owner in both services without new login",
      );
    } finally {
      await setGrants(original.grants);
      await assignTemporary(false);
    }
    for (const lang of ["java", "python"])
      assert.equal((await call(denied, "GET", lang)).status, 403);
    passed(
      "role revocation rejects an existing business session in both services",
    );
    fs.writeFileSync(
      ".local/onboarding-results.json",
      JSON.stringify({ at: new Date().toISOString(), evidence }, null, 2),
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e.message.replace(/Aa1![A-Za-z0-9_-]+/g, "[redacted]"));
  process.exit(1);
});
