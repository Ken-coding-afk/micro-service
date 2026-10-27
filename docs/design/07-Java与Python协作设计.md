# Java与Python协作设计

> 文档版本：1.9；文档集基线：D-20261027-07；更新：2026-10-27。  
> 状态：已确认的目标设计；不代表代码、脚本、镜像或运行能力已实现。  
> 本文负责：公共包、同步调用与可选消息；服务寻址引用配置设计；不提供业务任务运行时。  
> 导航：[文档总入口](../01-文档导航.md)；本次流量保护任务与证据见[实施计划](../plans/2026-10-27-限流熔断与并发保护-plan.md)；历史混合接入见[原计划](../plans/2026-10-27-混合项目接入与审查修正-plan.md)。

本文定义目标合同，不记录逐任务进度。已有交付与本次变更的实际验证范围分别见对应实施计划，不能仅凭设计正文推断某个发行已验收。

| 章节 | 阅读目的 |
| --- | --- |
| [1. 服务职责和公共包范围](#sdk) | 服务职责和公共包范围 |
| [2. Gateway 与各服务的安全边界](#gateway) | Gateway 与各服务的安全边界 |
| [3. 跨语言 HTTP 合同](#http) | 跨语言 HTTP 合同 |
| [4. 调用发现、超时与故障处理](#calls) | 调用发现、超时与故障处理 |
| [5. 消息、Outbox 与 Inbox](#messaging) | 消息、Outbox 与 Inbox |
| [6. 业务任务边界](#analysis-jobs) | 业务任务边界 |
| [7. 独立项目示例](#document-flow) | 独立项目示例 |

<a id="sdk"></a>

## 1. 服务职责和公共包范围

项目根 POM 聚合 sdk/java、identity-service、api-gateway 及已登记 Java 业务。服务与 SDK 的 parent relativePath 均为 ../../pom.xml；包/artifact 标识不因目录整理变化。项目根 Maven wrapper 执行 `-pl :equipment-service -am package`，无需预装私有 SDK JAR。IDE 导入根 POM；F5 直接运行源码，scripts/dev.py 可编译后启动，停止只处理自己创建的进程。

Python 每服务拥有 pyproject.toml、uv.lock 和 .venv；开发 SDK 以 ../../sdk/python、editable=true 引用。根 uv 环境仅用于 scripts，不进入业务 workspace。初始 Python 锁在首次依赖准备生成，后续 frozen sync；新增 submodule 服务的锁在入库前同构目录生成，接入不重写子仓锁。具体步骤见[新增服务](11-源码脚手架交付与开发体验设计.md#service-submodules)。

双语 F5/compound、解释器选择及 Windows 事件循环见[调试合同](11-源码脚手架交付与开发体验设计.md#vscode)。代码分仓与 SDK 目录分组不改变运行时服务发现、token audience、scope 和租户校验；调用仍走 HTTP，不共享业务实体。类型化客户端与跨语言契约检查是独立已知缺口，本次 OpenAPI 只提供文档与调试。

增加第三方 Python 依赖只修改该服务的 pyproject/lock，其他项目和兄弟服务的锁文件不变化。生产构建通过锁文件安装依赖并将 SDK/业务转为非 editable 安装；不把开发期的源码挂载带到生产。

以下名称是拟提供的接口，不是现有库可以直接调用的函数。

| 能力 | Java 公共包 | Python 公共包 | 项目开发者做什么 |
| --- | --- | --- | --- |
| 配置与生命周期 | `PlatformProperties`、条件自动配置 | `PlatformSettings`、FastAPI lifespan 集成 | 填服务名、环境和依赖地址 |
| 认证 | Resource Server opaque 内省适配、`CurrentPrincipal` | HTTPX 内省客户端、FastAPI `require_permission` 依赖 | 为路由声明 scope、permission 与身份类型 |
| 租户 | `TenantContext`、事务初始化、MyBatis 租户过滤 | ContextVar、SQLAlchemy 事务入口与自动过滤 | 业务表声明 tenant_id，读写走受控 repository |
| 数据范围 | `DataScope` 解析与查询条件辅助 | 相同范围枚举与查询辅助 | 明确 owner 字段，复杂 SQL 主动补条件 |
| HTTP | 服务发现、连接池、超时、追踪、认证传递 | HTTPX 工厂和发现适配 | 使用明确的服务客户端和 DTO |
| MQ | envelope、Outbox 投递、Inbox 去重 | 同协议发布/消费与可选 DB 适配 | 声明事件类型、处理事务和幂等策略 |
| 错误与日志 | 异常映射、JSON 日志、request/trace 字段 | 异常处理器、结构化日志 | 抛明确业务异常，避免泄密 |
| 健康检查 | liveness/readiness 与依赖指标 | 相同语义的健康端点 | 声明服务真正需要的依赖 |

不统一包装 MyBatis、SQLAlchemy、Redis、所有模型 SDK 的全部 API。公共包提供接入钩子与少量规范能力，项目仍直接使用成熟库处理自身业务。只出现一次的辅助函数留在项目；确有多个项目复用且合同稳定后再进入框架。

Java 注解只做方法前置检查，不能自动知道业务数据“属于谁”；必须通过查询条件或领域校验落实数据权限。Python 的 dependency 同理。任何 `skip_auth`、`ignore_tenant` 都不能作为业务代码的常规便利开关；平台特例限定到具体入口并配反向测试。

<a id="gateway"></a>

## 2. Gateway 与各服务的安全边界

路由使用显式白名单：`/api/v1/identity/**` 到 identity-service；`/api/v1/documents/**`、`/api/v1/analysis-jobs/**` 到项目业务服务；确需开放的 `/api/v1/ai/**` 到 Python API。OAuth2/OIDC 端点单独路由。不得自动把 Nacos 中所有服务和 `/internal/**` 暴露到公网。

Gateway 对 API 校验 access token 或 BFF 会话；资源服务再次独立校验自己的 audience、scope、业务权限和数据范围。Gateway 统一移除外部 `login-user`、`X-User-Id`、`X-Tenant-Id`、`X-Service-Authorization` 等可伪造身份头；下游身份来自 token。可接收 request_id，但格式不合法则重新生成；traceparent 仅用于追踪，不具备授权含义。

BFF 登录流程由 Gateway OAuth2 Client 完成，保存授权客户端和刷新凭证到服务端存储；浏览器后续通过会话访问业务。Cookie 模式的写请求强制 CSRF 校验，OAuth state 和 PKCE 不能替代业务 CSRF。外部 API 客户端可直接发送 Bearer token；BFF cookie 与 bearer 的入口处理明确分离，避免不清楚当前使用哪种身份。

### 原生网关：使用原生路由，框架保留必要安全适配

1. 项目在 `config/nacos/api-gateway.yaml` 使用 `spring.cloud.gateway.server.webflux.routes`。路由使用原生 `id/uri/predicates/filters/metadata`，目标为 `lb://服务名`；服务发现交给现有 Nacos/LoadBalancer。不开启“发现即自动公开全部服务”。
2. 业务路由 metadata 只补必要身份信息：`platform.audience`。逻辑限流名默认等于原生 route id；BFF 派生路由携带原 route id，不按 URL 拼接桶。RedisRateLimiter 本身也把 routeId 纳入 Redis key，因此适配必须将传入 isAllowed 的派生 routeId 归一到原 routeId，不能只归一 KeyResolver。框架不提供任意脚本/表达式执行入口。
3. 原生 routes 是唯一来源。退役 `gateway.routes`、`GatewayRouteSpec` 的自定义路由解析和“禁止 Spring routes”限制；不能把原生配置再转回旧四字段模型。旧格式启动/发布时给出明确迁移错误，不静默忽略。
4. 使用原生路由选择结果。Bearer 鉴权可在选路后的安全 GlobalFilter 中委托 Spring 官方 ReactiveAuthenticationManager；它必须先于限流和 Netty 转发执行。不再单独用一张按路径匹配的安全路由表选择 audience。BFF 的浏览器登录、CSRF 和会话继续由 Spring Security 处理，其代理调用再获得同一 audience 的可信主体。
5. 业务路由必须有 audience；未知路由拒绝，缺失元数据启动失败。固定身份协议/页面例外由框架声明，项目不能通过添加 public=true 绕过认证。仅允许受控的 `lb://` 目标；禁止把 `/internal/**`、内省管理端点或任意外部 URL 借路由公开。
6. BFF 业务入口由同一个原生 RouteDefinition 派生：复制相同原生限流参数，再加入固定前缀处理及凭据头清理。请求只经过其中一个业务入口，不执行两次扣令牌。管理 BFF 的固定路径仍按已有协议生成，不让项目重新配置登录流程。
7. 限流采用官方 RedisRateLimiter 令牌桶；框架只实现可信 KeyResolver、失败适配和统一错误。租户用户桶：`project/env/tenant/逻辑路由`；机器桶：`project/env/client_id/逻辑路由`；已验证的平台账号没有 tenant_id，使用 `project/env/platform/subject/逻辑路由`。身份类型只能来自内省后的主体，缺少应有身份字段则拒绝，不能回退到匿名公共桶；三种类型使用不同前缀防止键碰撞。编码使用结构化字段摘要，不能让标识中的分隔符拼出另一租户的键。
8. Redis 连接从 BFF 条件配置中抽离成网关共用连接配置，复用原有 endpoint、secret、TLS 要求；无 BFF 时限流也可工作。限流和会话使用不同键前缀，不增加 Redis 容器。
9. Redis 故障采用拒绝策略，返回 `503 RATE_LIMIT_UNAVAILABLE`。官方实现把异常转换为 allowed=true、remaining=-1；薄适配继承官方 RedisRateLimiter 并在 isAllowed 结果处识别该标志及缺失/异常结果，保留官方配置绑定和脚本执行，不能只在外层 onErrorResume。固定启用内部 remaining 信息，响应输出可脱敏；升级 SCG 必须重跑这个组件合同测试。不复制一套 Lua 令牌桶。
10. 登录/验证码等匿名保护继续使用既有 LoginProtection；业务租户桶不覆盖匿名攻击防护。入口 DDoS/WAF 属于部署平台。本设计不宣称租户限流可以保护认证前的一切资源。

拟议配置示例（待实现后才能被当前发布工具接受）：

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: sample-python
              uri: lb://sample-python-service
              predicates:
                - Path=/api/v1/python-items/**
              metadata:
                platform.audience: sample-python-service
              filters:
                - name: RequestRateLimiter
                  args:
                    key-resolver: '#{@tenantOrClientKeyResolver}'
                    rate-limiter: '#{@platformRedisRateLimiter}'
                    redis-rate-limiter.replenishRate: 20
                    redis-rate-limiter.burstCapacity: 40
                    redis-rate-limiter.requestedTokens: 1
```

这里是每秒补充 20 个令牌、桶最多 40 个，允许突发；**不是固定一秒最多 20 个**。示例数值用于接入和验证，不是容量承诺。未声明限流过滤器的路由不自动获得配额。网关不叠加 Sentinel、依赖熔断或 Retry；本次不引入 SCG CircuitBreaker 所依赖的另一套熔断库。网关共享租户额度与 Java 进程资源保护各有职责，不在同一边界重复计数。

<a id="http"></a>

## 3. 跨语言 HTTP 合同

对外业务接口统一 `/api/v1/...`，内部接口 `/internal/v1/...`。DTO 使用 JSON `snake_case`，Java 类成员仍采用 camelCase 并由序列化配置转换。ID 为字符串，时间为 UTC RFC3339，精确金额用十进制字符串及币种字段，禁止跨语言浮点金额运算。

普通业务 JSON 响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {"id": "UUID 字符串"},
  "request_id": "请求标识"
}
```

错误同时使用正确 HTTP 状态和稳定业务错误码：参数错误 400、未认证 401、无权限 403、不可见资源 404、版本/幂等冲突 409、限流 429、依赖失败 503、上游超时 504。客户端不能靠中文 message 判断分支。OAuth/OIDC、Actuator/健康检查、OpenAPI、SSE 和二进制响应遵循各自协议，不强行套壳。

分页采用 `page`（从 1 开始）和 `page_size`（默认 20，最大 100），响应 `items/total/page/page_size`；排序字段白名单并追加唯一 ID 保证稳定。耗时导出改为任务，不一次拉取无限数据。

同步调用默认连接超时 2 秒、单次响应总预算 10 秒，同时受上游剩余 deadline 限制。SDK 只允许对无副作用 GET 在预算内最多额外重试 1 次；其他请求默认不重试。需要重试的写入必须声明 `Idempotency-Key` 并实现落库去重；不能仅因网络超时就判断服务端没提交。

OpenAPI 由服务的 DTO/路由生成并随服务发行；Java/Python 客户端可由 OpenAPI Generator 生成，生成代码放 `client/generated`，业务适配放其外层。前后端和两个语言不靠手工各写一份 DTO 保持“差不多一致”。

<a id="calls"></a>

## 4. 调用发现、超时与故障处理

服务发现的唯一合同见[配置与服务发现设计](02-配置与服务发现设计.md#service-discovery)。SDK 对外以逻辑服务名调用，项目代码不拼接 Nacos 地址或缓存实例列表。调用链传递经过验证的租户、主体、trace 和截止时间；不把客户端传入的身份头作为可信身份。

同步调用适用于可在请求预算内完成的读取或短操作。调用前必须有连接、读取和整体超时，整体预算向下游递减。幂等读取可在剩余预算内有限重试并换实例；写请求只有协议明确支持幂等键时才可重试。超时表示未得到结果，不表示下游未执行。外部模型/工具单独配置超时，不直接套用普通 API 的较短预算。

Java 可调用 Python，Python 也可调用 Java；选择哪一方发起由领域职责决定。Python 的算法接口可以独立作为项目业务服务，不强制为每个 Python 服务再增加一个 Java 转发服务。后台任务调用业务 API 时必须使用登记的机器身份或明确的用户委托，不能沿用过期浏览器 token。

### 服务入站：使用 Sentinel 并发流控

- Java 使用 Sentinel `FlowRule`，`grade=0`（并发线程数）、`strategy=0`（直接）、`controlBehavior=0`（快速拒绝）；统一资源名 `http.inbound`。Python 使用 AnyIO CapacityLimiter。两者均不排队，事件循环中禁止阻塞等待。
- **不将 Sentinel 并发流控描述为严格信号量。** 它基于资源当前并发统计检查，检查与计数不是一次原子抢占；极短并发竞争下可能越过阈值。本任务以过载快速拒绝和正确资源生命周期为目标，不为保持旧表述再写一套原子并发引擎。数据库连接池、线程池等真实资源仍应有自身上限；严格 GPU 执行名额由业务资源池负责。Python CapacityLimiter 则提供进程内许可限制。
- 覆盖 `/api/v1/**`、`/internal/v1/**` 业务 HTTP，排除健康检查和 OAuth/OIDC 协议端点。准入在昂贵处理前执行，不依赖外部租户头；忙时可先返回 503，但不能跳过鉴权后执行业务。
- Java 异步请求使用官方 `AsyncEntry`，持有到 Servlet/Reactive 请求实际结束；禁止用同步 `@SentinelResource` 包住“创建 Future”就声称保护整个异步执行。Python 持有许可到 ASGI 请求结束。完成、错误、取消路径确保只 exit/release 一次。
- 客户端断连但同步线程仍执行时，资源占用不能提前结束。Python 不使用 `abandon_on_cancel=True` 把仍运行线程当作完成。HTTP 准入不承担 CPU/GPU 调度或后台任务取消。
- 阈值是每进程的：副本和 Uvicorn worker 各自计数。通过 Nacos 下发相同规则不会共享计数；本次不部署 Sentinel Token Server，不启用集群规则。Gateway 的跨节点租户桶仍使用 Redis。

### 出站调用：Sentinel 原生资源与熔断

Java 保留 `ServiceHttpClient.request(...)`，Python 保留 `await ServiceHttpClient.request(...)`。Java 资源名为 `http.outbound/<逻辑服务名>`，对同一依赖的所有实例合并治理；不能使用租户、用户、完整 URL 动态生成无限资源。资源名由 SDK 固定生成，业务规则文件按此命名。

Java 使用 Spring Cloud Alibaba 2025.1.0.0 BOM 管理的 Sentinel 1.8.9 核心和 `sentinel-datasource-nacos`，沿用官方 AsyncEntry/Tracer/规则管理 API；Reactor 适配按实际生命周期复用。不再引入上一版 Java 熔断组件，不擅升 Boot/Cloud。基础包中的自动装配仅在服务明确启用时执行，不能因 Gateway 引用 java-sdk 就自动重复装配 Servlet、WebClient 或网关埋点；不同时启用自动 WebClient 埋点和 SDK 手工埋点。

**统计单位为一次逻辑 SDK 调用。** 顺序为：检查 deadline 和发现实例 → Sentinel 异步准入（并发与熔断）→ 执行 HTTP 与允许的 GET 重试 → 读取完整普通响应 → 记录最终分类 → exit。整个逻辑调用占一个 Entry；失败后一次 GET 重试仍受原 deadline 和 Entry 约束，不累计成两个熔断样本。半开探针也是一个逻辑调用，可能包含最多两次 HTTP 尝试；不读取或修改 Sentinel 私有状态以另写探针策略。熔断拒绝不重试。

Sentinel 采用原生异常比例策略：`grade=1`、`count=0.5`、`minRequestAmount=10`、`statIntervalMs=10000`、`timeWindow=20`。即 10 秒统计窗口内至少 10 次完成且异常比例**大于** 50% 时打开 20 秒，之后由官方单逻辑探针恢复；不是旧版“最近 20 次调用”的计数窗口。一个逻辑探针仍受现有 10 秒整体 deadline 限制，不虚构组件的半开超时配置。

| 结果 | Sentinel 记录 | 重试 |
| --- | --- | --- |
| 最终传输失败、实际 I/O 超时、500/502/503/504 | 在当前 AsyncEntry 上通过官方 Tracer 记录一次远端失败 | 只有传输失败/超时且 GET、还有预算时最多额外一次；HTTP 错误响应不重试 |
| 2xx/3xx/业务 4xx（含 429） | 无远端异常，计入正常完成样本；不代表业务成功 | 无 |
| 调用前预算耗尽、无实例、Sentinel BlockException | 不创建成功 Entry 或按原生拒绝处理，不主动 trace 为远端故障 | 无 |
| 本地连接池等待失败、客户端取消 | 不主动 trace 为远端异常；如已有 Entry，按官方 exit 语义完成 | 无 |

保留原有 HTTP 响应对象和状态；对 5xx trace 仅用于统计，不能吞掉响应或伪造成功。仅真实网络尝试失败才失效对应实例，本地忙、熔断、取消不摘除健康实例。选用按 Entry 的 Tracer API，不能在异步线程上依赖错误的 ThreadLocal 上下文。

**原生取消语义的边界：** Sentinel 无异常退出的 Entry 可进入总样本，半开取消也可能使原生异常比例熔断器回到 CLOSED。本设计不承诺“取消完全不影响熔断统计”，不为该差异修改其内部状态机；独立记录取消指标（Java platform.http.cancelled、Python platform_protection_cancelled），探针恢复指标不能解释成一次业务成功。取消必须关闭实际网络订阅、结束 Entry，不留永久并发占用。若业务需要更严格的恢复证明，应另行评审，不静默增加自研熔断层。

连接池仍采用 Reactor Netty ConnectionProvider / HTTPX Limits，有界等待并受 deadline 限制；创建者负责关闭，外部注入的客户端由注入方负责。内省/BFF 客户端不与业务调用共用一个小并发桶。绕过 SDK 的外部 HTTP 调用不宣称自动受到保护。

### 错误、超时、流式与观测

| 情况 | HTTP / code | 约束 |
| --- | --- | --- |
| 网关令牌不足 | 429 / RATE_LIMITED | Retry-After 至少 1 秒，按单次请求令牌恢复间隔向上取整；只是建议，不保证等待后必成功 |
| 限流 Redis 故障 | 503 / RATE_LIMIT_UNAVAILABLE | 不冒充 429，不回退为本地独立桶 |
| 服务入站满 | 503 / SERVICE_BUSY | 不排队；健康检查不进入该桶 |
| SDK 出站名额/连接池满 | 503 / DEPENDENCY_BUSY | 不立即重试、不作为远端故障 |
| Java 熔断打开 | 503 / DEPENDENCY_CIRCUIT_OPEN | 不执行下游 I/O，不返回伪造业务成功 |
| 调用超时 | 504 / UPSTREAM_TIMEOUT | 写请求结果未知，由业务按幂等/查询处理 |

继续使用现有错误 envelope、request_id、HTTP 状态；OAuth/OIDC 错误保持协议格式。普通 API SDK 总预算 10 秒、连接 2 秒、网关整体 15 秒（包含认证及响应体）；Nginx 连接 2 秒，普通/流式统一读写空闲 60 秒。将旧设计中普通 API 的 20 秒改为 60 秒，整体截止由 Gateway 执行，避免 Nginx 再维护一套与动态路由重复的超时分类表。网关总预算覆盖完整普通响应，不能把响应头超时或 Nginx 空闲超时冒充整体 deadline。

普通 SDK 当前是缓冲响应 API，不能拿它转发 SSE。本次不新增流式客户端 API：网关流式转发沿用明确流式路由 metadata `platform.streaming: true`，不套普通 15 秒整体截止时间，按现有设计的空闲边界处理；普通/流式路由不得有歧义。入站许可在流结束/断连处理结束后释放，流已开始只能关闭/按业务协议报错，不能重发或改写为 JSON 503。流式业务心跳与重连语义仍由业务实现。

指标使用现有 Actuator/SDK 观测接入，增加拒绝次数、活动名额、连接池等待/超时、熔断状态变化/失败次数、Redis 限流故障；标签只用 service、route、dependency、reason 等有界名称，不带 tenant_id/user_id。复用 Sentinel 资源统计、状态变更观察接口与现有观测设施，记录规则有效摘要；不自建平行状态表，不默认暴露 Sentinel transport 管理端口。不开新指标控制台或公网管理端点。

<a id="messaging"></a>

## 5. 消息、Outbox 与 Inbox

生产者在同一数据库事务里写业务变化和 Outbox；投递器以 `FOR UPDATE SKIP LOCKED` 行锁领取当前租户未投递记录，发送持久消息并等待 publisher confirm 且确认没有 mandatory return，再标记已投递。发布成功但回写失败会产生重复消息，所以消费者必须去重。

消息 envelope 最小合同：

| 字段 | 约定 |
| --- | --- |
| `event_id` | UUID；同一事件重投保持不变 |
| `event_type` | 如 `item.created` |
| `schema_version` | 整数，从 1 起；不兼容变化创建新版本 |
| `tenant_id` | 租户 UUID；消费者须核对资源归属 |
| `producer` | 服务名；结合 RabbitMQ vhost/账号/exchange 发布权限验证来源边界 |
| `occurred_at` | UTC 时间 |
| `traceparent` | 链路关联；重试可建新 span |
| `aggregate_id` / `aggregate_version` | 业务聚合 ID 与版本，接收者据此拒绝过期覆盖 |
| `data` | 业务数据；不包含密码、access token 或任意回调 URL |

消费者对 `(consumer_name, event_id)` 建唯一约束，在同一事务中写 Inbox 与业务结果，事务提交后 ack。已处理事件直接 ack；失败回滚，不能留下“去重成功但业务未执行”的记录。依赖业务先后顺序的场景按 aggregate_version 检查，不能假设多个消费者全局有序。

Inbox 去重索引默认长期保留，只存去重与处理结果所需字段，不保存完整敏感消息正文，不按固定 TTL 自动删除。容量监控和归档必须保留可查询的唯一去重键；去重覆盖范围不能短于消息保留与人工重投窗口。任何不再保有去重证据的历史事件都不得直接重投，只能作为新的、有审计的业务修复操作处理。这是持久化合同，不把清理语义留作实现者自行决定。

持久化事件有限重试，默认 5 秒、30 秒、2 分钟三次重投，之后进入隔离队列并告警；重投发布确认后才 ack 原消息，允许重复，不允许静默丢弃。未知 schema_version 直接隔离。隔离消息修复后可带原 event_id 重投；禁止无限 requeue 热循环。

消息适配由业务显式创建，框架不自动启动 worker 或枚举所有租户。每个队列使用同名 direct exchange，主队列、延迟重试与隔离队列使用 quorum；重试转移启用 at-least-once dead lettering。消费前核对 broker 验证的 user_id 与所允许生产者，重投账户也必须明确登记；服务账号权限限制到实际需要的 exchange/queue。示例共用账号只用于本地。生产告警接收、消费生命周期及外部副作用幂等属于业务/运维责任，SDK 输出隔离错误记录而不建设告警平台。

RabbitMQ 的 publisher confirm 和 consumer ack 是两个独立确认，只分别证明对应阶段的处理，不提供数据库与消息的原子提交。[RabbitMQ 确认机制](https://www.rabbitmq.com/docs/confirms)

<a id="analysis-jobs"></a>

## 6. 业务任务边界

框架不提供算法任务表、Run 状态机、租约、检查点、工具账本或 Celery/LangGraph 包装。长任务由业务项目选择成熟执行组件并负责取消、恢复、预算、幂等及外部副作用。SDK 只提供身份/租户、发现、HTTP、可选消息和数据事务接入。

未来聊天 Agent 可作为普通 Python 服务多节点部署；业务项目负责状态外置与恢复。请求中不依赖进程内会话；共享缓存/存储按租户隔离。流式代理的通用边界见[部署设计](09-应用部署与运维设计.md#proxy)，并不自动带来事件回放。

<a id="document-flow"></a>

## 7. 独立项目示例

Java 与 Python 示例各有独立依赖/构建/迁移/权限清单，生成时引用项目内 sdk 源码。新项目默认同时有两语言服务；明确不需要某个服务时连同配置与资源声明移除。示例实现一种普通资源的创建/查询及 SELF/TENANT 检查，权限由服务制品同步到同一个通用管理页。

验收先单语言独立启动、登录、两租户隔离，再验证 Java→Python 与 Python→Java HTTP。启用消息才安装 mq 适配与 RabbitMQ，另验证发送确认、重复投递、提交后 ack 与未知版本隔离；不阻塞纯 HTTP 示例。


## 修订记录

- 2026-10-27，D-20261027-02：明确可选消息适配的行锁、来源账号、拓扑与业务负责的消费者/告警边界。

- 2026-10-27，D-20261027-03：统一混合项目目录及接入路径；业务配置扩展、管理页和发布恢复合同按计划 05 修正；实际验收见计划 05。

- 2026-10-27，D-20261027-04：按已确认 Sentinel 计划同步原生网关、Java 动态规则、Python 并发和调用边界；目标合同不代表已完成运行验收。

- 2026-10-27，D-20261027-06：按源码工作区计划修订项目创建、来源、初始化与交付边界；验收见该计划，历史结果不追改。

- 2026-10-27，D-20261027-07：同步已确认的根目录、SDK、template/tools/tests 与项目命令职责；行为规则仍按本文定义，运行验收状态见当前计划。
