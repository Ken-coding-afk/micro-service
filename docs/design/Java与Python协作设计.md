# Java与Python协作设计

> 文档版本：1.0；文档集基线：D-20261026-02；更新：2026-10-26。  
> 状态：目标设计，待整体评审；不代表代码、脚本、镜像或运行能力已实现。  
> 本文负责：公共包、同步调用、消息与算法任务；服务寻址引用配置设计，聊天运行细节引用 Agent 设计。  
> 导航：[文档总入口](../README.md)；任务与证据见[实施计划](../plans/2026-10-26-微服务框架实施计划.md)。

文中的路径、接口、配置和命令均为拟交付合同；当前仓库只有文档，不能直接执行这些命令启动系统。

| 章节 | 阅读目的 |
| --- | --- |
| [1. 服务职责和公共包范围](#section-1) | 服务职责和公共包范围 |
| [2. Gateway 与各服务的安全边界](#section-2) | Gateway 与各服务的安全边界 |
| [3. 跨语言 HTTP 合同](#section-3) | 跨语言 HTTP 合同 |
| [4. 调用发现、超时与故障处理](#section-4) | 调用发现、超时与故障处理 |
| [5. 消息、Outbox 与 Inbox](#section-5) | 消息、Outbox 与 Inbox |
| [6. 长时间算法任务](#section-6) | 长时间算法任务 |
| [7. 文档分析项目的完整链路](#section-7) | 文档分析项目的完整链路 |

<a id="section-1"></a>
<a id="d09"></a>
<a id="d09-1"></a>

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
| 聊天运行支持 | 使用标准 HTTP/SSE 与 Python 协作；不重复实现 Agent 执行引擎 | 可选 RunStore/RunCoordinator、EventLog、CheckpointStore、ToolLedger 与 LangGraph 适配 | 提供会话模型、Graph/工具、上下文构造与恢复策略；详见 [聊天Agent多节点设计·部署角色与数据归属](聊天Agent多节点设计.md#d11) |

不统一包装 MyBatis、SQLAlchemy、Redis、所有模型 SDK 的全部 API。公共包提供接入钩子与少量规范能力，项目仍直接使用成熟库处理自身业务。只出现一次的辅助函数留在项目；确有多个项目复用且合同稳定后再进入框架。

Java 注解只做方法前置检查，不能自动知道业务数据“属于谁”；必须通过查询条件或领域校验落实数据权限。Python 的 dependency 同理。任何 `skip_auth`、`ignore_tenant` 都不能作为业务代码的常规便利开关；平台特例限定到具体入口并配反向测试。

<a id="section-2"></a>
<a id="d09-2"></a>

## 2. Gateway 与各服务的安全边界

路由使用显式白名单：`/api/v1/identity/**` 到 identity-service；`/api/v1/documents/**`、`/api/v1/analysis-jobs/**` 到项目业务服务；确需开放的 `/api/v1/ai/**` 到 Python API。OAuth2/OIDC 端点单独路由。不得自动把 Nacos 中所有服务和 `/internal/**` 暴露到公网。

Gateway 对 API 校验 access token 或 BFF 会话；资源服务再次独立校验自己的 audience、scope、业务权限和数据范围。Gateway 统一移除外部 `login-user`、`X-User-Id`、`X-Tenant-Id`、`X-Service-Authorization` 等可伪造身份头；下游身份来自 token。可接收 request_id，但格式不合法则重新生成；traceparent 仅用于追踪，不具备授权含义。

BFF 登录流程由 Gateway OAuth2 Client 完成，保存授权客户端和刷新凭证到服务端存储；浏览器后续通过会话访问业务。Cookie 模式的写请求强制 CSRF 校验，OAuth state 和 PKCE 不能替代业务 CSRF。外部 API 客户端可直接发送 Bearer token；BFF cookie 与 bearer 的入口处理明确分离，避免不清楚当前使用哪种身份。

<a id="section-3"></a>
<a id="d09-3"></a>

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

<a id="section-4"></a>

## 4. 调用发现、超时与故障处理

服务发现的唯一合同见[配置与服务发现设计](配置与服务发现设计.md#d09-4)。SDK 对外以逻辑服务名调用，项目代码不拼接 Nacos 地址或缓存实例列表。调用链传递经过验证的租户、主体、trace 和截止时间；不把客户端传入的身份头作为可信身份。

同步调用适用于可在请求预算内完成的读取或短操作。调用前必须有连接、读取和整体超时，整体预算向下游递减。幂等读取可在剩余预算内有限重试并换实例；写请求只有协议明确支持幂等键时才可重试。超时表示未得到结果，不表示下游未执行。外部模型/工具单独配置超时，不直接套用普通 API 的较短预算。

Java 可调用 Python，Python 也可调用 Java；选择哪一方发起由领域职责决定。Python 的算法接口可以独立作为项目业务服务，不强制为每个 Python 服务再增加一个 Java 转发服务。后台任务调用业务 API 时必须使用登记的机器身份或明确的用户委托，不能沿用过期浏览器 token。

<a id="section-5"></a>
<a id="d10"></a>
<a id="d10-1"></a>

## 5. 消息、Outbox 与 Inbox

生产者在同一数据库事务里写业务变化和 Outbox；投递器以行锁/租约领取未投递记录，发送持久消息并等待 publisher confirm 且确认没有 mandatory return，再标记已投递。发布成功但回写失败会产生重复消息，所以消费者必须去重。

消息 envelope 最小合同：

| 字段 | 约定 |
| --- | --- |
| `event_id` | UUID；同一事件重投保持不变 |
| `event_type` | 如 `document.analysis.requested` |
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

RabbitMQ 的 publisher confirm 和 consumer ack 是两个独立确认，只分别证明对应阶段的处理，不提供数据库与消息的原子提交。[RabbitMQ 确认机制](https://www.rabbitmq.com/docs/confirms)

<a id="section-6"></a>
<a id="d10-2"></a>

## 6. 长时间算法任务

“文档分析”示例由 Java `document-service` 拥有任务状态。全 Python 项目可由 Python 业务服务承担相同角色，不要求另加 Java 业务服务。identity-service 不存放所有项目任务。

流程如下：

1. 用户提交 `POST /api/v1/analysis-jobs`，包含 document_id 和模型配置标识，header 带 Idempotency-Key。
2. 业务服务检查当前用户、租户、scope、`document:analyze` 和文档归属；同事务写任务 QUEUED、输入快照/版本与 Outbox。返回 202 和 job_id。
3. Outbox 发布 `document.analysis.requested`，只含 job_id、tenant_id、输入版本；不把用户 access token 放进 MQ。
4. Python Worker 用 Client Credentials 取得仅面向任务内部接口的机器 token。收到消息后调用任务所有者的 claim 接口；由所有者核对队列类型、租户状态、提交者当前权限、任务状态及 worker 授权。
5. claim 成功返回 attempt_id、lease_expires_at、输入元信息/短时文件访问许可、deadline。Worker 只根据可信返回开始计算，不执行消息体中任意 URL 或 shell 指令。
6. Worker 定期续租并检查取消标志；计算输出写入受限对象 key 后，调用 complete。所有者校验 attempt、版本、租约和对象归属，在事务中写终态和完成事件 Outbox。
7. complete 已持久化或确认该任务已终态后，Worker ack。连接中断重投时先查状态，不重新运行已成功任务。
8. 前端使用任务查询 API；需要交互体验时增加 SSE 通知，数据库任务状态始终是事实来源。

任务最小字段：`id/tenant_id/owner_user_id/type/status/input_ref/input_version/idempotency_key/request_hash/attempt_id/worker_id/lease_expires_at/deadline_at/cancel_requested/result_ref/error_code/version/created_at/updated_at`。

幂等唯一键为 `(tenant_id, owner_user_id, operation, idempotency_key)`。相同 key、相同 request_hash 返回原任务；相同 key、不同请求返回 409。任务记录至少保留 30 天；清理后不承诺相同 key 跨保留期去重，客户端必须每个新的操作生成新 key。

内部合同，路径以任务所有者的服务地址为前缀：

| 接口 | 输入 | 原子检查与结果 |
| --- | --- | --- |
| `POST /internal/v1/analysis-jobs/{id}/claim` | event_id、tenant_id、worker_id | QUEUED → RUNNING；一次生成 attempt_id；已终态返回终态；已被领取返回冲突，不能再次执行 |
| `POST .../{id}/heartbeat` | attempt_id | 核对当前 worker 与 attempt；续租并返回 cancel_requested；失效 attempt 返回 409 |
| `POST .../{id}/complete` | attempt_id、status、result_ref 或 error_code | 只接受当前有效 attempt；同 attempt 同内容可重放；冲突结果 409；终态不可覆盖 |
| `POST /api/v1/analysis-jobs/{id}/cancel` | 无额外权限声明 | 校验用户有该任务取消权限及数据范围；QUEUED 直接取消，RUNNING 只置取消请求 |
| `POST /api/v1/analysis-jobs/{id}/retry` | reason，非空 | 仅 `document:recover` + TENANT；FAILED/RECOVERY_REQUIRED → QUEUED；清除旧租约、写新派发 Outbox；其他状态拒绝 |
| `GET /api/v1/analysis-jobs/{id}` | job_id | 根据当前用户的数据范围读取状态；不返回内部凭证 |

租约默认 120 秒、心跳 30 秒；任务默认最长 30 分钟，算法需要更长时间时必须由类型配置显式声明。RabbitMQ consumer timeout 必须大于任务最大执行时间及收尾窗口；prefetch 默认 1，按 CPU/GPU 配额扩容 Worker，不能无限并发。

| 现态 | 触发 | 后态 / 处理 |
| --- | --- | --- |
| QUEUED | 领取并提交事务 | RUNNING |
| QUEUED | 用户取消 | CANCELLED，后续重复投递直接确认 |
| QUEUED | 合法 Worker 领取时确认提交者/租户已失效 | CANCELLED，记录 AUTH_REVOKED；不继续循环重投 |
| QUEUED | 超过配置的排队上限，默认 60 分钟 | RECOVERY_REQUIRED；由扫描器标记并告警，不能永久显示“排队中” |
| RUNNING | 当前 attempt 成功完成 | SUCCEEDED |
| RUNNING | 明确的业务失败 | FAILED，记录可理解的错误码 |
| RUNNING | 取消请求且 Worker 确认已停止 | CANCELLED |
| RUNNING | 租约过期、进程失联、结果无法判定 | RECOVERY_REQUIRED；不自动重新执行可能已有副作用的任务 |
| RECOVERY_REQUIRED / FAILED | 有权限的操作者明确重试 | 检查副作用风险后生成新 attempt 的派发流程；旧 attempt 永久无效 |

取消与完成竞态采用状态行锁：取消标志先提交时拒绝普通成功 complete，Worker 收到取消后报告停止或进入待恢复；成功终态先提交时取消返回已完成。已经对外发送邮件、扣费或修改外部系统的操作不能伪装成“取消已回滚”，必须记录事实并人工确认补偿。

租约过期的迟到结果拒绝写入正式结果，可记录到受限的恢复审计中供人工判定。重复消息发现仍在运行时可以 ack 该副本；恢复由持久任务扫描负责。Worker 与 MQ 同时失联但仍计算的情形不能保证只执行一次；副作用工具需要自己的幂等键和结果查询。

领取时如果是 identity-service/数据库暂时不可用，保留 QUEUED，按有限投递规则处理；不能把依赖故障误判成权限撤销。扫描器每分钟检查过期租约和排队期限，使用状态/version 条件更新，和 claim 竞态只允许一方成功。

AI Agent 工具调用也经过服务权限控制，模型输出不能指定任意 tenant_id、user_id 或调用任意内网地址。任务保存模型/提示词/工具版本、用量和输出引用；默认日志不记录完整敏感提示词。每类任务声明超时、最大步骤、token/费用预算与允许工具。python-sdk 基础包不强制依赖 Agent 库；聊天示例采用可选 LangGraph 适配。文档批任务遵循本节，聊天 Run 的排队、人工等待、恢复与 SSE 行为以 [聊天Agent多节点设计·部署角色与数据归属](聊天Agent多节点设计.md#d11) 为准，不把两套状态机机械混用。

<a id="section-7"></a>

## 7. 文档分析项目的完整链路

以 `examples/java/document-service` 和 `examples/python/analysis-service` 为例：

1. 用户上传文档；Java 验证当前租户及文档创建权限，将对象存储引用、文档记录、分析任务和 Outbox 事件在可覆盖的本地事务中关联。文件先上传产生临时对象时，需要明确过期清理；不能宣称对象存储与数据库天然同事务。
2. Outbox 发布分析请求到约定 exchange/routing key，携带事件 ID、任务 ID、租户、版本及受控对象引用；消息不携带浏览器 token、模型密钥或大文件正文。
3. Python 接收后验证来源与合同，以 Inbox 去重，并按算法任务合同领取执行权；不能仅凭收到消息就假定当前 Worker 有权执行。对象引用访问仍验证租户和任务授权。
4. 算法实现、解析流程、提示词、模型选择与结果 schema 由项目提供。框架提供上下文、日志、消息、执行状态和受控客户端，不替业务决定算法质量。
5. 结果持久化并发布完成事实；Java 按任务 ID、attempt 和版本接受有效结果，更新文档业务状态。迟到旧结果不覆盖新任务，重复通知不重复收费或创建记录。
6. 前端从 Java 查询业务状态；需要进度时使用项目明确的进度合同，不能把聊天 SSE 协议自动套到所有算法任务。
7. Python 或 Rabbit 中断后，由任务状态及持久化事件恢复。Java 页面展示处理中、失败或需要人工恢复，不因 HTTP 连接丢失把已提交任务判为不存在。

业务项目必须实现的内容是文档实体、文件规则、算法与结果解释、领域权限和页面。基础身份/权限管理页、鉴权、隔离、日志、发现与消息通用能力直接复用。演示必须同时包含成功、重复消息、Worker 失联和跨租户拒绝，不能只演示一次 happy path。


参考资料：以下为前期调研保留的原始项目/官方资料入口；本轮没有重新核验其在线版本。框架具体默认策略由本文定义，不能认为引用组件就自动实现了全部业务合同；实施兼容冻结任务须复核版本与能力。

| 来源 | 参考用途 |
| --- | --- |
| [FastAPI 生命周期](https://fastapi.tiangolo.com/advanced/events/) | 用 lifespan 管理进程资源 |
| [RabbitMQ confirms / acknowledgements](https://www.rabbitmq.com/docs/confirms) | 发布者确认和消费者确认的不同语义 |
