# 可选可靠消息接入

纯 HTTP 项目不使用此目录、不安装 RabbitMQ。这里没有常驻框架服务：业务进程显式调用 java-sdk `RabbitEvents` 或 Python `ken_platform_sdk.messaging`，底层使用 Spring AMQP / aio-pika。

## 1. 项目需要准备什么

1. 在自己的业务迁移中加入 `V1__message_store.sql` 的表、索引、RLS 和运行角色授权。已有 Flyway 项目改为自己的下一个版本；Alembic 项目将 SQL 放进自己的 revision，不复用示例版本号。
2. Java 使用已有 java-sdk；Python 安装 `ken-platform-sdk[mq]`。配置自己的 PG 运行连接、Rabbit vhost、账号与 secret 文件。不要把组件管理员凭据注入业务容器。
3. 每条事件消费通道固定 queue、producer、event_type、支持的 schema_version 和可信 Rabbit 用户；多个消费者副本使用相同 consumer_name，不能包含随机实例编号。
4. 使用维护身份显式调用 `declare` 初始化 durable direct exchange、quorum queue、三条延时队列和隔离队列。普通请求启动路径不调用它。单节点 Rabbit 仅用于开发；它不提供节点故障高可用。

## 2. 生产者怎么写

在业务已有的租户事务中完成业务 SQL，然后调用 `enqueue(event)`；Python 为 `await enqueue(session, event)`。事务失败时两者一起回滚。事件带 UUID event_id、可信 tenant_id、固定 producer/event_type、schema_version、UTC occurred_at、data；不可从请求头直接取得租户，不写 token、密码或任意回调地址。

业务进程用受管理的定时任务调用 `dispatchOne(tenant)` / `await dispatch_one(factory, tenant)`，每次只投递一条；返回 false 表示该租户本通道当前无待投递记录。租户列表来自业务获准处理的范围，不由 SDK 扫全租户。轮询频率、停止等待和监控由业务运行配置决定。多副本通过 PG `FOR UPDATE SKIP LOCKED` 竞争；发布确认后标记完成。确认后数据库提交失败会重投，这是预期的至少一次语义。

## 3. 消费者怎么写

使用成熟客户端的消费者容器/iterator，manual ack，设置有界 prefetch。收到消息后调用 `consume`，传入固定 consumer_name 和业务 handler。Python handler 接收当前 SQLAlchemy session 与 Event；Java handler 在当前 JDBC 租户事务执行。

SDK 在同一个事务中写 Inbox 和执行 handler，提交后才 ack。相同 event_id 与内容重复投递只返回 duplicate；同一去重键换内容不会再次执行。handler 不要提交事务或另开独立连接。调用支付、发送邮件等外部副作用不能跟 PG 原子提交，必须使用业务幂等键或继续写自己的 Outbox。

失败按 5 秒、30 秒、120 秒三次重试，之后隔离；未知版本、非可信发布账号直接隔离。重投确认失败时关闭消费通道，让未 ack 的原消息恢复；业务监督器负责重建消费者。隔离写 ERROR 日志，项目将日志或隔离队列积压接入自己的告警系统，不会自动向外发送告警。修复后使用原 event_id 重投；Inbox 不自动过期。

## 4. Rabbit 权限与部署

命名规则：主 exchange/queue 均为 `<queue>`；附属 exchange/queue 为 `<queue>.retry.1`、`.retry.2`、`.retry.3`、`.quarantine`。每个 exchange 只绑定同名队列，routing key 同名。

运维使用 RabbitMQ 官方账号/权限管理为每个服务分配独立账号，禁止 impersonator 标签。生产者只获主 exchange 写权限；消费者获主队列读、重试/隔离 exchange 写权限；初始化身份才有 configure/bind 权限。不得授予运行账号 `amq.default` 写权限或全局 `.*`，否则无法限制发布目的地。消费者 accepted_users 包含原生产者和允许执行重投的消费者账号。服务账号被攻破时仍可伪造它被授权生产的事件，因此事件不能替代业务对象归属校验。

`infra/resources-mq.yaml` 当前提供的是项目 vhost 与本地调试共用账号，不能直接作为生产的服务级授权清单。生产通过官方 Rabbit 管理入口预建上述账号和权限，再配置各服务；框架不另造权限编排平台。多个独立订阅者需要各自通道及业务明确的投递设计，本适配不承诺跨多个目的地原子发布或全局消息顺序。

## 5. 本地证据

定向入口：`python-sdk/tests/test_messaging.py`、`java-sdk/.../messaging/RabbitEventsTest.java`；需要显式隔离 PG/Rabbit 绑定。覆盖真实事务回滚、发布确认后回写失败、延时重试、并发去重、未知版本/来源隔离、不可路由返回，以及 Java→Python→Java 互通。测试跳过不算通过。服务器集群故障与真实网络分区尚无验收证据。
