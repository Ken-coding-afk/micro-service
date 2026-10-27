# SDK使用参考

本文随生成项目交付，面向业务开发者。Java 公共代码在 `sdk/java`，Python 在 `sdk/python`；业务直接修改自己的服务。真实环境安全验证见[安全验收清单](07-系统安全验收清单.md)，工程检查与可选监控见[工程检查指南](05-工程检查与监控指南.md)。

## 1. 接入与入口

从项目根打开 VS Code。按 README 安装 JDK 21、Python 3.12、uv、Node/pnpm；Java 改名后先 `./mvnw.cmd -pl :sample-java-service -am spotless:apply`，Linux 使用 `./mvnw`。Python 使用该服务的锁和 `.venv`，不混用根脚本环境。首次创建按 README 生成 Python 业务锁，后续 `uv sync --frozen --project services/sample-python-service`。

Java 服务声明 java-sdk 依赖，启动类保留 `@PlatformService` 和 Spring Boot 自动装配。Python 在 `create_app()` 中创建 `PlatformRuntime`，先 `runtime.install(app)`、再 `install_resource_security(app, introspector)`；lifespan 中 start/stop，导入模块不连接组件。数据库依赖使用 `[db]`，消息使用 `[mq]`，未使用的能力无需启动对应组件。

## 2. 常用API速查

| 目的 | Java | Python | 责任 |
| --- | --- | --- | --- |
| 可信身份 | CurrentPrincipal / @RequirePermission | current_principal / require_permission | 框架验证身份、scope与权限；业务声明操作与数据范围 |
| 请求上下文 | CallContexts.current()，响应式读取 CallContext.class | current_context.get() | 不信任外部 tenant/user header |
| 租户事务 | TenantTransactions.execute(callback) | async with tenant_transaction(sessions) | 业务写参数化 SQL、显式筛选数据；框架绑定事务级 RLS |
| 数据库启动验证 | 自动装配 DatabaseReadiness | DatabaseReadiness.verify() | 核对真实账号/库和最小权限；业务再验证本服务表/迁移 |
| 动态就绪 | 服务现有健康检查 | await runtime.is_ready() | startup checks 只装配一次；readiness_checks 只读且可重复 |
| 类型化调用 | ServiceHttpClient.request(..., ParameterizedTypeReference<T>) | await runtime.http.request_model(service, method, path, Model) | 业务拥有 DTO/Client；SDK 发现、预算、凭据、保护 |
| 通用业务错误 | new PlatformException(code, message, status) | PlatformError(code, message, status) | message 必须可安全展示，不放 SQL、秘密或原始上游正文 |
| 剩余预算 | CallContexts.remainingBudget(Duration) | remaining_seconds(default_seconds=10) | 上限不超过上游剩余时间 |
| 到期检查 | CallContexts.checkDeadline() | check_deadline() | 合作式检查，不抢占任意计算/自动撤销写入 |
| 可信任务作用域 | CallContexts.task(tenant, service, budget) | task_context(tenant_id=...,service=...,timeout_seconds=...) | 显式身份、不携带用户 token；不授予权限 |
| Executor 传播 | CallContexts.wrap(runnable) | contextvars.copy_context().run(...) | 只包当前请求内的受控工作；不要用于无限期后台任务 |
| 消息 | RabbitEvents.enqueue/dispatchOne/consume | enqueue + RabbitEvents.dispatch_one/consume | 已有 Outbox/Inbox；业务提供事件、事务、消费者和运行周期 |
| 缓存/对象边界 | TenantResources | TenantResources | 仅提供租户键/授权边界，不是 Redis/S3 客户端 |

## 3. 请求、日志与错误

普通业务调用经过鉴权后取得可信租户和主体。Java Servlet/MDC、Reactor/Context Propagation、Python contextvars 自动关联 request_id、tenant_id、trace_id，服务字段为 service。未认证、平台身份没有租户时留空，不伪造默认租户。

Java 默认使用 Boot 结构化 console 日志，MDC 在退出时恢复。Python 进程入口调用 `configure_logging(service)`，默认 JSON；样例 Uvicorn 使用 `log_config=None` 保留此配置。已有业务 handler 不被替换，会获得日志字段；自定义 formatter 必须包含这些字段，后加 handler 要装 `ContextLogFilter(service)`。框架未知异常日志保留异常类型及最多32帧文件/函数/行号，帮助定位；Python logger.exception 的默认 formatter 同样只提取这些安全帧，不输出异常消息、源码行或局部变量。外部响应不带栈。不要在 logger message 中记录 token、Cookie、密码、签名 URL 或完整个人资料。

业务 API/BFF 的错误为：

```json
{"code":"VALIDATION_ERROR","message":"请求参数不合法","data":null,"request_id":"request-123"}
```

响应 `X-Request-Id` 与 body 相同，错误禁缓存。参数校验400、未知路由404、方法405、限流429、依赖预算504；未知异常500只返回安全固定文案。具体业务异常保留 code。不要让前端用 message 做程序判断。OAuth token/authorize 等端点使用 OAuth 标准错误；浏览器重定向和已经开始的 SSE/流式响应不能改发另一个 JSON。

## 4. 数据库与就绪

Java 保留已有数据库自动装配。Python 使用可复用检查器：

```python
database = DatabaseReadiness(engine, os.environ["DB_USER"], os.environ["DB_NAME"])
await runtime.start(checks=[assemble_business], readiness_checks=[database.check])
# assemble_business 中先 await database.verify()，再检查业务迁移/表并装配依赖。
# readiness 路由返回：200 if await runtime.is_ready() else 503。
```

运行账号必须匹配环境绑定，不能是 super/BYPASSRLS/DDL/owner 或其可继承角色。业务迁移用独立账号，运行进程不做建库授权。readiness_checks 最多2秒，必须无注册、迁移或重新装配副作用。数据库断开时返回 DOWN，恢复后下一次探测重新验证；liveness 不因为短暂外部故障反复杀进程。`runtime.ready` 只表示启动状态，HTTP readiness 必须调用 `is_ready()`。

事务内 `set_config(..., true)` 只在本事务生效；不能在连接池会话上永久 SET 租户。DAO 仍使用参数绑定，客户端给出的 tenant_id 不是授权依据。对高成本 SQL，业务可把剩余预算设置到当前事务的 `statement_timeout`，不得修改连接池全局设置或认为 SQL 超时后其他外部副作用必然撤销。

## 5. 同步调用与 deadline

参考 `services/sample-java-service` 的 PeerClient 和 `services/sample-python-service` 的 clients/peer.py。业务只填逻辑服务名和相对路径；不要手工转发外部用户/租户头。DTO 允许兼容新增字段，必填/类型错误会明确失败。

```java
CallContexts.checkDeadline();
var timeout = CallContexts.remainingBudget(Duration.ofSeconds(3));
// 将 timeout 传给实际使用的成熟依赖客户端；进入昂贵操作前再次检查。
```

```python
check_deadline()
timeout = remaining_seconds(3)
async with asyncio.timeout(timeout):
    result = await external_client_operation()
```

SDK HTTP 已消费预算，不需要再包一层重试。业务长循环在阶段边界检查到期；CPU 密集 Python 应使用自己的进程执行策略，不能靠 asyncio timeout 抢占阻塞循环。写调用默认不重试；超时后先按业务幂等键/查询确认结果。deadline 不是事务提交的原子截止保证。

## 6. 消息和可信后台任务

定时任务必须明确部署方式；多副本中裸定时器会重复。当前不提供调度器。简单任务可以独立单实例运行并保证幂等；需要抢占、补跑和持久化计划时再接成熟调度组件。

任务入口先验证来源，并从本项目受控任务配置获得租户；不要从未认证 HTTP body 直接拿租户创建作用域：

```java
try (var scope = CallContexts.task(tenantId, "sample-java-service", Duration.ofMinutes(2))) {
  CallContexts.checkDeadline();
  // 在租户事务内处理该租户工作；外部调用另用受控机器凭据。
}
```

```python
with task_context(tenant_id=tenant_id, service="sample-python-service", timeout_seconds=120):
    check_deadline()
    async with tenant_transaction(sessions) as session:
        await process_tenant(session)
```

已有请求上下文时 task 入口拒绝，不能借它切换租户或伪装系统管理员。`asyncio.create_task` 默认复制上下文，脱离请求的任务须用干净 `contextvars.Context()` 启动，再在任务内显式 task_context。请求内并发可以正常继承；请求结束后不要持有 bearer。Java 自建线程池使用 wrap 仅传播同一请求的受控工作。

消息消费者已经验证 producer/type/版本/可信 Rabbit 用户，并建立租户作用域；无需业务重复绑定。发布在业务事务中 enqueue 到 Outbox，业务调用派发器；消费在 Inbox 去重与业务事务提交后确认消息。失败走有界重试/隔离。业务负责事件语义、顺序要求、运行周期以及外部副作用幂等；Inbox 不保证第三方邮件/支付 exactly-once。相关表由本服务迁移管理，不能共用身份服务业务库。

## 7. 升级与检查

将 SDK、自动装配、样例接入、配置和新测试成组合并到项目分支。新增 API 属增量；通用参数错误格式发生变化，前端需使用 code/status，勿依赖 FastAPI 默认 detail 或 Spring 默认错误页。已有明确业务/身份管理 handler 保留语义；自行安装的 handler 由项目对照同一合同验证。

使用当前项目的固定依赖和 CI 定向集合；`tests/fixtures/api-errors-v1.json` 是两语言共享的通用错误样本。纯测试/构建不代表数据库、真实登录、消息或网关部署已验收。
