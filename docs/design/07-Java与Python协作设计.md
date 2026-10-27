# Java与Python协作设计

> 文档版本：1.6；文档集基线：D-20261027-03；更新：2026-10-27。  
> 状态：已确认的目标设计；不代表代码、脚本、镜像或运行能力已实现。  
> 本文负责：公共包、同步调用与可选消息；服务寻址引用配置设计；不提供业务任务运行时。  
> 导航：[文档总入口](../01-文档导航.md)；任务与证据见[实施计划](../plans/05-混合项目接入与审查修正实施计划.md)。

本文定义目标合同；部分骨架与本地工具已实现，尚不能完整启动框架。各入口的实际交付与验证状态见实施计划。

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

Java 与 Python 示例各有独立依赖/构建/迁移/权限清单，使用已发行包；不依赖 framework 源码路径。单语言项目只复制一个，组合项目使用不同逻辑服务名。示例实现一种普通资源的创建/查询及 SELF/TENANT 检查，权限由服务制品同步到同一个通用管理页。

验收先单语言独立启动、登录、两租户隔离，再验证 Java→Python 与 Python→Java HTTP。启用消息才安装 mq 适配与 RabbitMQ，另验证发送确认、重复投递、提交后 ack 与未知版本隔离；不阻塞纯 HTTP 示例。


## 修订记录

- 2026-10-27，D-20261027-02：明确可选消息适配的行锁、来源账号、拓扑与业务负责的消费者/告警边界。

- 2026-10-27，D-20261027-03：统一混合项目目录及接入路径；业务配置扩展、管理页和发布恢复合同按计划 05 修正；实际验收见计划 05。
