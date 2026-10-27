# 独立项目验证

> 维护者历史回归材料：固定 sample 服务，不作为新项目接入入口。新项目使用根 scripts/ 入口；本轮未运行这些 Docker/浏览器脚本。

这些脚本是示例的验证夹具，不是框架运行依赖，只允许本机 HTTP 入口。它们通过真实浏览器、BFF 和管理 API 操作，不向身份数据库插入账号、客户端或角色。运行前按上级 README 完成发行、基础设施、迁移、bootstrap 和两个权限清单发布。

工作目录始终为独立发行目录根。安装此目录自己的工具依赖，机器须已有 Chrome。Windows PowerShell：

```powershell
npm ci --prefix tests/acceptance/legacy
$env:SAMPLE_ORIGIN='http://localhost:18080'
node tests/acceptance/legacy/browser-platform.cjs
node tests/acceptance/legacy/register-business.cjs
node tests/acceptance/legacy/tenant-onboarding.cjs
```

依次完成平台登录及首次改密、登记两个服务 audience/两租户/五个客户端、各租户创建 writer/denied 账号及业务角色。账号初始密码、一次性交付的客户端秘密和测试 Cookie 只保存在 `.local/`，不得提交或打包。正式项目按自身租户与客户端进行登记，不使用这组固定测试账号。

在完成两个业务库初始化和迁移、构建自己的业务镜像后，将测试登记结果转为运行绑定：

```powershell
uv run --frozen python tests/acceptance/legacy/bind_business.py --env-file .local/environment.env --java-image local/sample-java:0.1.0 --python-image local/sample-python:0.1.0
```

该脚本只生成示例服务的受限凭据绑定，并在已准备的 Gateway 绑定中加入 web/readers；不替换已有不同凭据。将 `config/nacos/` 的业务服务配置及 Gateway 路由合入项目 `config/nacos/`。在全新示例项目可直接复制此目录的 YAML 和 Sentinel JSON；已有项目必须核对差异后合并。

```powershell
uv run --frozen python config/publish.py --env-file .local/environment.env
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml -f deploy/compose.local.yaml -f tests/acceptance/legacy/compose.yaml --profile java --profile python up -d --wait sample-java-service sample-python-service api-gateway
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml -f deploy/compose.local.yaml exec nginx nginx -t
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml -f deploy/compose.local.yaml exec nginx nginx -s reload
node tests/acceptance/legacy/verify-business.cjs
node tests/acceptance/legacy/verify-admin.cjs
```

业务脚本验证 22 组场景：匿名拒绝、两租户独立登录、两语言创建/查询、伪造租户头隔离、Nacos 双向调用、两语言各自 business 配置生效、无角色账号拒绝、现有会话收紧为 SELF 后按本人过滤、撤销角色后现有会话拒绝；结果写入 `.local/onboarding-results.json`。管理脚本另验真实用户/角色/权限页面、真实目录 API 和已移除接口 404。它们不证明撤权并发、消息重投、多机发布、第三方登录或生产容量。

重复执行不会重置已记录的密码或重新登记客户端。若浏览器成功改密后本地状态文件丢失，应由管理员按账号恢复流程处理，不能靠删库或绕过首次改密继续验证。跨机器共享验证目录前须移除 `.local` 与 `node_modules`。


两个项目都完成上述登记后，分别重新运行 browser-platform.cjs 取得有效平台会话，再从安装了验证依赖的任一项目运行：

```powershell
node tests/acceptance/legacy/verify-project-isolation.cjs <项目A绝对目录> http://localhost:<A端口> <项目B绝对目录> http://localhost:<B端口>
```

检查自身会话有效、跨项目会话拒绝、租户 ID 和内省客户端秘密独立；不输出秘密、不修改账号。真实数据库恢复用框架 deploy/tests/test_database_recovery.py 的显式本地项目输入，恢复到随机新库，不覆盖运行库。

新增平台页面验收：先运行 browser-platform.cjs 获取新会话，再运行 verify-platform-settings.cjs。它从页面登记独立的本地测试内省身份并轮换秘密，验证旧秘密换 token 被拒绝、新秘密成功，然后幂等保存项目策略；只记录脱敏结果，不记录 token/秘密。

## 流量保护故障验收

仅对已完成上面混合接入的**本机隔离示例**执行。故障夹具会短暂锁住本项目的 `sample_item` 表、发布非法/删除 Sentinel 规则、暂停本项目 Nacos，并恢复原规则；不要用共享测试环境或真实用户项目运行。多个完整示例会占用较多 Docker 内存，应分批运行。

先保存两个示例租户的浏览器会话，不能在限流测试桶启用后再登录并加载业务页面：

```powershell
node tests/acceptance/legacy/save-resilience-session.cjs beta
node tests/acceptance/legacy/save-resilience-session.cjs alpha
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml -f deploy/compose.local.yaml -f tests/acceptance/legacy/compose.yaml -f tests/acceptance/legacy/compose.resilience.yaml --profile java --profile python up -d --wait api-gateway gateway-peer sample-java-peer
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml -f deploy/compose.local.yaml exec nginx nginx -s reload
```

覆盖文件增加第二个网关和 Java 实例，使用不同 Nacos 注册地址；网关测试端口默认只绑定 loopback 的 19092/19093，可用 `RESILIENCE_GATEWAY_A_PORT/B_PORT` 改为未占用端口。所有相对路径以首个 Compose 文件所在目录解析，不改变 `-f` 的顺序。

把下列 `项目代码` 换成 environment.env 中 PROJECT_CODE 的值：

```powershell
uv run --frozen python tests/acceptance/legacy/verify-resilience.py --env-file .local/environment.env --cookies-file .local/resilience-beta-browser.json --java-container 项目代码-local-apps-sample-java-service-1 --java-container 项目代码-local-apps-sample-java-peer-1
```

此脚本等待两个实例收到新规则，再通过数据库锁和真实 HTTP 请求验证并发拒绝。随后验证非法规则、首次启动拒绝、配置删除、短暂 Nacos 断连和 `[]` 清空；最后恢复初始源。断连场景只覆盖发现缓存仍有效的短暂中断，不宣称依赖可无限离线。结果保存到 `.local/resilience-results.json`；失败时检查已经输出的 PASS 与容器日志，不把流程中途失败记作全部通过。

共享额度测试单独执行：先备份本项目 `config/nacos/api-gateway.yaml`，仅将 `java-items` 路由的 replenishRate/burstCapacity/requestedTokens 改为 `1/120/120`。通过 config/publish.py 发布，再用上面的完整 Compose 文件组合加 `--force-recreate` 重启 `api-gateway gateway-peer`，等两者 healthy。该桶在空桶恢复前只容许一次请求，适合观察跨实例共享，不是生产默认额度。

```powershell
uv run --frozen python tests/acceptance/legacy/verify-gateway-quota.py --gateway-a http://localhost:19092 --gateway-b http://localhost:19093 --cookies .local/resilience-beta-browser.json --other-tenant-cookies .local/resilience-alpha-browser.json
```

脚本验证同一租户在 A 准入、B 返回 429，伪造租户头不改变桶，另一租户获得自己的额度，无效令牌先返回 401。完成或失败后均恢复之前备份的 YAML、重新发布并重启两个网关，再 reload Nginx。不要删除 Redis 数据或重跑 bootstrap。测试结束停止 `gateway-peer sample-java-peer` 即可，不删除数据库卷。
