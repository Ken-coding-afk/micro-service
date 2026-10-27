const fs = require("node:fs");
const path = require("node:path");
const origin = new URL(process.env.SAMPLE_ORIGIN || "http://localhost:18080");
if (
  origin.protocol !== "http:" ||
  !["localhost", "127.0.0.1"].includes(origin.hostname) ||
  origin.username ||
  origin.password ||
  origin.pathname !== "/" ||
  origin.search ||
  origin.hash
) {
  throw new Error("验证脚本仅允许明确的本机 HTTP 示例入口");
}
if (!fs.existsSync(path.resolve("release-manifest.json"))) {
  throw new Error("请在独立候选发行目录根运行验证脚本");
}
process.env.SAMPLE_ORIGIN = origin.origin;
