# 认证与OAuth2设计

> 文档版本：1.6；文档集基线：D-20261027-03；更新：2026-10-27。  
> 状态：已确认的目标设计；不代表代码、脚本、镜像或运行能力已实现。  
> 本文负责：登录、OAuth2/OIDC、token/BFF、第三方身份和机器认证。  
> 导航：[文档总入口](../01-文档导航.md)；任务与证据见[实施计划](../plans/05-混合项目接入与审查修正实施计划.md)。

本文定义目标合同；部分骨架与本地工具已实现，尚不能完整启动框架。各入口的实际交付与验证状态见实施计划。

| 章节 | 阅读目的 |
| --- | --- |
| [1. 组件、协议端点与接入登记](#protocol) | 组件、协议端点与接入登记 |
| [2. 客户端数据权威、统一管理与生效规则](#client-storage) | 客户端数据权威、统一管理与生效规则 |
| [3. 浏览器登录与 BFF](#bff) | 浏览器登录与 BFF |
| [4. 对外授权、PKCE 与用户同意](#external-authorization) | 对外授权、PKCE 与用户同意 |
| [5. 第三方登录与账号绑定](#federation) | 第三方登录与账号绑定 |
| [6. 机器身份与后台委托](#service-identity) | 机器身份与后台委托 |
| [7. Token、会话与撤销周期](#tokens) | Token、会话与撤销周期 |
| [8. 密码、登录保护与安全策略字段](#security-policy) | 密码、登录保护与安全策略字段 |
| [9. 内省、应用权限与最终授权交集](#introspection) | 内省、应用权限与最终授权交集 |
| [10. 环境地址、代理与回调](#origins) | 环境地址、代理与回调 |
| [11. 故障、密钥轮换和安全审计](#failures) | 故障、密钥轮换和安全审计 |

<a id="protocol"></a>

## 1. 组件、协议端点与接入登记

| 场景 | 模式 | 结果 |
| --- | --- | --- |
| 自有 Web 登录 | Gateway BFF 作为机密客户端；Authorization Code + PKCE | 浏览器只持有 HttpOnly 会话 cookie，token 保存在 Gateway 服务端 |
| 外部 Web / 移动应用接入本系统 | Authorization Code + PKCE；需要身份信息时使用 OIDC | 用户明确授权指定 scope；应用获取本系统签发的 token |
| 第三方账号登录本系统 | identity-service 作为 OAuth2/OIDC Client | 外部身份验证后映射到当前租户内账号，再进入本系统授权流程 |
| 服务无用户调用 | Client Credentials | 获取机器 token，仅有 client 权限，不冒充某个用户 |

不支持 Password Grant 和 Implicit Grant。登录表单提交账号密码属于 identity-service 自身的登录动作，不等于开放 OAuth2 密码模式。

授权服务器标准端点使用框架标准协议响应，**不套业务 JSON envelope**：

| 端点 | 用途与调用者 |
| --- | --- |
| `GET /oauth2/authorize` | 客户端发起授权；校验 client、redirect_uri、scope、PKCE、租户授权上下文 |
| `POST /oauth2/token` | 用授权码、refresh token 或客户端凭证换 token |
| `POST /oauth2/introspect` | 获准的资源服务验证 opaque access token |
| `POST /oauth2/revoke` | 客户端撤销授权令牌 |
| `GET /.well-known/openid-configuration` | OIDC discovery |
| `GET /oauth2/jwks` | ID Token 签名公钥 |
| `/userinfo` | 使用包含 openid scope 的 access token 获取许可的用户资料 |
| `/connect/logout` | OIDC 会话退出；和 token 撤销按下文组合处理 |

测试/生产 issuer 为项目固定 HTTPS 地址，如 `https://doc-insight.example.com`；本地例外见 [本地运行与地址规则](03-基础设施与环境设计.md#local-development)；Gateway 转发这些路径时不改写 issuer 或协议路径。只信任网关/边缘代理提供的 Forwarded 信息，禁止由任意客户端 Host 头决定回调和 issuer。OIDC 默认不开动态客户端注册，客户端由项目管理员审批登记。

项目接入时要登记的内容如下；这些是身份数据库中的配置，不是 Nacos 配置。

| 接入方 | 管理员登记 | 调用方使用 |
| --- | --- | --- |
| 自有 web-bff | client_id、秘密、准确 callback/logout、租户、audiences/scopes、PKCE | Gateway OAuth2 Client；浏览器只使用会话 |
| 外部 Web/移动应用 | public/confidential 类型、回调、允许租户/scopes、同意策略 | 标准 code + S256 PKCE，校验 state/nonce |
| 第三方提供方 | registration_id、issuer 或授权/token/UserInfo 地址、提供方凭据、适用租户 | 身份服务客户端适配，未绑定按注册/显式绑定流程 |
| 机器服务 | Client Credentials、允许租户/audience/scopes、secret | 标准 token 请求，只缓存自己的 token 至过期前 |
| 资源服务内省 | 独立凭据、固定目标 audience | Java/Python 资源服务，不交给浏览器 |

初始化职责：identity-bootstrap 只创建平台初始账号、固定管理 BFF 客户端和受限目录发布身份。sync-permissions.py 仅同步版本化服务权限目录及受控服务/audience 映射，不创建租户账号、不分配角色、不扩大 OAuth 客户端。项目管理员在管理页面或等价受保护管理 API 中登记业务 BFF、机器及资源内省客户端，返回的一次性秘密存入该项目受控文件并注入对应服务；重复核对不轮换秘密。业务进程启动前检查所需客户端已登记，缺失则停止，不静默生成高权限客户端。

授权请求额外携带 tenant_code，服务端解析并绑定授权上下文，不能把参数当可信 tenant_id。机器换 token 使用 POST /oauth2/token、application/x-www-form-urlencoded、client_secret_basic，正文 grant_type=client_credentials 与获准 scope；audience 取登记策略，不接受任意表单扩张。它不使用 refresh token。

<a id="client-storage"></a>

## 2. 客户端数据权威、统一管理与生效规则

RegisteredClient 与 sys_client_policy 是同一 identity 数据库中的协议记录和一对一扩展，不是两个相互同步的客户端系统。

| 数据 | 唯一主要来源 | 禁止的副本 |
| --- | --- | --- |
| client_id、client_name、凭据散列、认证方法、grant_types、redirect/logout URI、scopes、client_settings、token_settings | 官方 RegisteredClient 持久化模型 | sys_client_policy 不再存 client_id/allowed_scopes/回调/秘密 |
| principal_type、owner_tenant_id、allowed_tenant_ids、audiences、status（ACTIVE/DISABLED）、version | sys_client_policy | 不在 client_settings JSON 另存相同业务策略 |
| scope 到业务操作的映射 | 服务权限目录 | 客户端不得通过任意表达式改变权限语义 |
| 已签发授权实际 scopes/audiences、关联租户和客户端版本 | Authorization 及其明确的关联字段/关系 | 不用当前允许集合覆盖历史 token 实际上限 |

sys_client_policy.registered_client_id 唯一且外键指向官方记录内部 id；client_id 在官方表增加唯一约束。普通租户客户端的 owner_tenant_id 与 allowed_tenant_ids 只能指向本租户；跨租户技术身份仅平台受控入口登记。删除采用先 DISABLED，再按授权/审计保留规则清理，不级联清除历史授权。

所有创建、更新、轮换和停用统一经过 identity-service 内部 ClientManagementService（拟新增内部类，不新增服务）。页面和 bootstrap 使用同一验证/写入规则；普通页面通过管理 API，bootstrap 通过受控初始化入口。同一 DataSource、Spring 事务写 RegisteredClient、policy 和成功审计；任何一步失败整体回滚。更新带 expected_version，锁定对应 policy 并重查管理权限；版本冲突 409 CLIENT_POLICY_VERSION_CONFLICT。创建时唯一约束兜底并发 client_id 冲突。禁止只更新官方 Repository 而绕过策略管理入口。

协议读取通过受控 RegisteredClientRepository 适配取得完整客户端快照，并在相关 grant 验证环节执行扩展校验；不自写 OAuth 协议引擎。授权码签发/消费、client credentials、refresh、内省和 /me 均检查当前 status、租户范围、audience 及官方 scopes。必须以同一一致性视图读取两部分；缺失扩展记录拒绝并记录配置错误，不默认启用。成功快照不作为跨请求的陈旧授权缓存。

停用后不再签发或刷新，后续内省判 inactive；已有请求不能保证强行撤回。scopes、audiences 或租户范围收紧后，旧授权受当前策略与签发上限的交集限制；扩大策略不得扩大存量 token 的签发 scopes/audiences。并发 token 签发在最终写入前重新核对 policy.version；版本变化重新验证或失败，不使用过期校验结果。撤销事实、策略与审计提交后再清理缓存/会话，不能以异步双写实现安全一致性。

[Spring 官方 RegisteredClient 模型](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/core-model-components.html)定义协议字段和 Repository 接口；租户、audience、状态属于本框架扩展。实际 JDBC/PG 类型和事务集成由精简计划 T-04 验证，不假定“用了官方表”就自动具备多租户策略。

<a id="bff"></a>

## 3. 浏览器登录与 BFF

浏览器从固定入口选择平台或租户登录 → BFF 保存 state/PKCE 与客户端命名空间 → 身份服务校验账号及租户 → 返回一次性授权码 → BFF 服务端换 token → 浏览器获得 HttpOnly 会话 Cookie。业务前端不持有 client_secret，也不把 ID Token 当 API access token。

平台管理、租户管理、业务 Web 使用独立客户端和 Cookie/会话命名空间，具体路径见管理界面文档。登录已有其他租户会话时显式重新选择/认证，不能静默改变 token 的 tenant_id。写请求同时满足主体校验和 CSRF；页面关闭不等于 token 撤销。

退出本应用撤销对应授权并清理该 BFF 命名空间，另提供明确的全端退出。单独 OIDC logout 不等于撤销所有 API token，组合动作和会话记录必须一致。

<a id="external-authorization"></a>

## 4. 对外授权、PKCE 与用户同意

外部应用由管理员登记为 public 或 confidential，设置精确 redirect_uri、允许租户、audiences、scopes 与同意策略。只支持设计内已声明 grant；不启用 Password/Implicit 或任意动态客户端注册。

授权码流程强制 S256 PKCE，校验 client、redirect_uri、租户、state/nonce 和授权码单次使用。scope 从已登记集合选择；同意页面显示申请的实际操作类别，不把同意解释为授予用户原本没有的角色。撤销应用同意同时撤销该用户该客户端现存授权，未来重新授权。

原有角色发生变更时内省重算；客户端策略收紧立即限制后续内省；策略扩大不能扩大旧 token 中已经签发的 scopes。

<a id="federation"></a>

## 5. 第三方登录与账号绑定

1. 登录前确定 tenant_code；identity-service 将解析后的 tenant_id、提供方、原授权请求及一次性 state 存在服务器端。
2. 跳转外部身份提供方。回调严格验证 state；OIDC 同时验证 issuer、签名、audience、nonce 和时间。
3. 以 `(tenant_id, provider_registration_id, provider_subject)` 查绑定；禁止仅凭同名用户名或相同邮箱自动合并账号。
4. 已绑定且账号有效：完成本租户登录。未绑定：提示先登录本租户账号完成显式绑定；无账号者联系管理员开户；回调不自动开户。
5. provider 回调本身不能覆盖服务器保存的 tenant_id。第三方 token 只用于该身份交换，不直接作为我们业务 API 的凭证。

提供方采用 Spring `ClientRegistration` 固定部署配置，由项目运维维护，秘密通过仅身份进程可读的文件注入。平台不提供在线提供方编辑器或插件注册系统；支持的注册项、允许主机、适用租户和精确回调均在启动时检查。OIDC 用标准组件处理 state/nonce/PKCE/ID Token；GitHub OAuth2 固定官方端点及用户 id。外部授权状态最多保留 5 分钟，成功或失败后不能重放。

登录、本人绑定和解绑入口是 `/identity/external` 及其固定 POST 操作；绑定需要近期本租户认证，回调复查该会话仍有效，绑定成功撤销旧会话后重新登录。解绑复核本人密码，保留本地密码登录，不自动撤销所有现有会话；需要注销时使用既有会话撤销入口。`sys_external_identity` 是租户、注册项、subject 到本地用户的唯一绑定，另锁定对应 authority，配置同名提供方不能静默接管旧绑定。第三方 token 不落库、Redis 或业务日志。

本设计交付通用 OIDC 接入和 GitHub OAuth2 接入示例。企业微信等提供方有其专用协议/参数，后续接入应通过提供方适配和真实凭证测试，不能因为支持 OAuth2 就声称所有厂商可零配置登录。

<a id="service-identity"></a>

## 6. 机器身份与后台委托

普通机器客户端绑定一个租户、目标 audience 和 scopes；一个服务需要代表多个租户执行通用后台任务时，使用专门登记的工作客户端，只允许领取和回写任务等少数内部动作，不获得任意租户 CRUD 权限。

Client Credentials 的 token 不接受通过任意 `tenant_id` 参数扩大授权。客户端凭证可换发哪些租户/服务范围来自登记策略。client_secret 只展示一次，数据库保存验证散列；客户端侧用 secret 文件装载。轮换采用新增客户端凭证/客户端版本、迁移调用者、撤销旧凭证的受控过程。

同步用户调用默认传递原用户 access token，前提是 token 的 aud 明确包含目标服务，且授权 scopes 覆盖下游所需能力；不能将上游服务身份覆盖成用户身份。仅内部接口另需 `X-Service-Authorization: Bearer <机器token>` 验证调用者；外部入口统一剥离该 header，不能直接由浏览器提供。普通对外 API 只要求用户身份，不无谓增加双 token。

本设计不提供通用 Token Exchange；不能为解决 audience 不匹配而忽略校验。项目预先为业务调用链登记允许的 audience/scopes，跨边界新调用必须更新并重新授权。

<a id="tokens"></a>

## 7. Token、会话与撤销周期

- Access token 统一使用成熟组件生成的 opaque token，有效期 10 分钟；服务通过标准内省验证，不能在 Python 中假装把它当 JWT 解码。
- ID Token 使用非对称签名 JWT，面向登录客户端；API 不接受 ID Token 作为 access token。签名密钥以密钥文件/密钥服务持久化，不能每次容器重启生成新密钥；轮换保留旧公钥直至相关 token 过期。
- 机密用户客户端可获得 refresh token：绝对有效期 8 小时，刷新时轮换；旧 refresh token 不得再次使用。实现时通过持久化授权与事务锁防止并发双重刷新。
- 公共客户端不保存 client_secret；本设计不为其发 refresh token。过期后重新走授权码流程，仍有效的登录会话可减少重复输入。该边界与官方公共客户端指引一致。[PKCE 指引](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html)
- 测试/生产浏览器 cookie 使用 Secure、HttpOnly、SameSite=Lax；[首次安装顺序](09-应用部署与运维设计.md#first-install)的 loopback local profile 仅允许明确的开发 HTTP 例外，BFF/SSO 会话上限统一见[安全策略字段](#security-policy)。跨租户登录创建不同授权上下文，不通过修改现有会话的 tenant_id 实现切租户。
- 本设计资源服务**不缓存成功内省结果**。内省服务实时检查授权是否撤销、账号与租户状态及当前角色权限；避免给“权限立即失效”附加隐藏缓存窗口。
- 禁用账号/租户、修改密码和全端退出后，新内省请求必须返回 inactive；已经通过检查且正在执行的请求不承诺强行中断。长任务另按 [Java与Python协作设计·消息、Outbox 与 Inbox](07-Java与Python协作设计.md#messaging) 在领取及关键副作用前复核。
- 登录会话由 Spring Session + Redis 管理；OAuth2 客户端、授权和同意记录持久化 PostgreSQL。Redis 故障不能退化成匿名登录或绕过校验。

认证中心不可用时，依赖内省的受保护请求返回 503；凭证错误为 401；有身份但权限不足为 403。优先保证正确性，后续只有在真实压测证明有需要后，才另行设计有明确撤销时限的鉴权缓存。

单会话撤销事务先将绑定及对应 Authorization 标为撤销并写审计，后清理 Redis 登录会话；清理失败通过 Outbox 重试，内省/刷新检查撤销事实，不等待缓存删除。用户全端退出递增 auth_version 并撤销当前登录状态；撤销应用同意同时撤销该用户对该客户端的既有授权，未来访问重新征求同意。创建客户端、发起重置等非天然幂等动作带 request_id，服务端按操作者/动作/request_id 去重；一次性秘密不在去重结果中明文重放，响应丢失则显式重置/轮换。

授权、凭据、状态、策略变更和成功审计同事务持久化；失败尝试以独立安全事件记录且不包含秘密。客户端/提供方列表永不返回秘密，审计仅记录“已更新凭据”。普通管理编辑不自动注销整个租户；与账号/租户禁用、密码重置、会话撤销的机制明确区分。

<a id="security-policy"></a>

## 8. 密码、登录保护与安全策略字段

安全策略只保存在 identity 数据库，由平台管理员维护一份项目策略；Nacos 不承载账号策略。sys_security_policy 保留既有表与 PLATFORM 行，TENANT 历史行停止编辑。上线前检查历史租户差异：无法在不削弱保护前提下统一时停止切换并明确统一值，不静默忽略更严格的租户策略。字段名、默认值与合法范围如下，未知字段/非法范围整体返回 400 SECURITY_POLICY_INVALID，版本冲突 409。

| 字段 | 项目默认 / 合法范围 | 项目策略调整与生效 |
| --- | --- | --- |
| password_min_chars | 15；15—32 个 Unicode 码点 | 只能提高；仅设置/修改密码时校验 |
| password_max_chars | 固定 64 | 不可调整；不截断输入 |
| password_max_utf8_bytes | 固定 72（当前 BCrypt 边界） | 不可调整；超限明确拒绝，不截断 |
| password_blocklist_enabled | true；固定启用 | 不可关闭；使用随发行锁定的常见/泄露弱密码集合，项目可追加拒绝项 |
| password_required_character_classes | 固定空集合 | 不强制大小写/数字/符号组合；页面不提供自定义正则 |
| login_failure_threshold | 10；1—10 次失败 | 只能降低；按租户+规范化账号计数 |
| login_failure_window_seconds | 900；900—3600 | 只能提高；滑动窗口内统计 |
| login_lock_seconds | 900；900—3600 | 只能提高；达到阈值后临时锁定 |
| login_ip_attempt_limit | 60；1—60 次请求 | 平台按真实可信来源 IP 控制，所有账号/租户合计 |
| login_ip_window_seconds | 60；60—300 | 平台底线，不允许租户绕开 |
| session_absolute_seconds | 28800；300—28800 | 只能缩短；SSO/BFF 最大会话期限 |
| session_idle_seconds | 1800；60—1800，且不得大于 absolute | 只能缩短；按最后活动时间计算 |
| recent_auth_seconds | 300；60—300 | 只能缩短；改密、绑定/解绑、凭据轮换等敏感动作重新认证时限 |

新密码使用标准 PasswordEncoder 的算法前缀与 BCrypt cost=12；匹配通过 DelegatingPasswordEncoder 受控算法映射，接受批准的 bcrypt 格式及合法成本，不用固定成本正则阻止已有密码；不启用 noop；算法/cost 不对普通租户开放。密码保留用户输入，不 trim，不截断；码点数和 UTF-8 字节上限同时满足。默认不要求定期换密，首次临时密码、已知泄露或主动重置另有流程。参考 NIST 对单因素密码最小长度、弱密码名单及避免字符组合规则的建议；本方案保留 BCrypt 字节限制，**不据此宣称完整满足 NIST 的所有要求**。[NIST 密码与限速要求](https://pages.nist.gov/800-63-4/sp800-63b.html)

Redis 按原子操作维护限速/临时锁记录，账号 key 包含已解析租户和规范化账号的不可逆摘要；不存在账号也走同类限制，响应不泄露存在性。可信来源 IP 的确定见入口设计，不信任外部任意 X-Forwarded-For。锁定期请求不继续延长 locked_until，防止无限滑动锁死；到期清除该轮账号失败计数，成功登录清除账号失败记录但不清 IP 请求计数。登录失败统一 401 INVALID_CREDENTIALS，受限返回 429 LOGIN_RATE_LIMITED 和 Retry-After；不区分账号存在与否。限速存储故障返回 503，不关闭保护。平台受保护的恢复流程可解锁并审计，普通用户不能借解锁重置凭据。

保存策略后，下一次密码设置、认证及会话检查读取当前有效策略；缩短会话期限按原始创建/活动时间重算，不能复活已过期会话。已存在临时锁保留其到期时间，新锁按新策略；已有失败记录按当前窗口评估。OAuth token 上限仍归 Token 生命周期章节，收紧会话使相关授权按撤销规则失效，不能用延长 Cookie 绕过 refresh 的绝对期限。

管理页只允许平台管理员编辑项目策略，租户策略编辑入口关闭；生效与失败路径由同一服务端校验。保留锁定、密码保护、会话撤销和审计，不因去掉继承而降低防护。

<a id="introspection"></a>

## 9. 内省、应用权限与最终授权交集

标准内省响应保留 RFC 字段，并添加框架字段。有效用户 token 返回：`active`、`iss`、`sub`、`client_id`、`aud`、`exp`、`scope`、`principal_type=user`、`tenant_id`、`permissions` 和 `data_scopes`。`permissions` 为按当前客户端、token 与目标资源过滤后的有效权限码数组；`data_scopes` 为需对象范围权限码到 SELF/TENANT 的映射；无对象操作只出现于 permissions，不在该映射中。机器 token 使用 `principal_type=service`，不含伪造的 user_id。

每个资源服务使用自己独立的内省客户端凭证调用 identity-service；一个内省凭证绑定一个资源 audience，只返回属于该 audience 的授权，不能由请求参数选择其他服务，不具有发放用户授权的能力。scope 与业务权限的映射属于服务端固定目录，例如 `documents.read` 只允许 `document:read`，不能覆盖全部业务权限。owner_service 到 audience 的绑定由受控服务登记维护，不由普通租户用户修改。

用户有效操作集合 = 当前有效角色权限 ∩ 当前客户端策略允许的操作 ∩ token scopes 映射的操作 ∩ 目标 audience 的操作 ∩ 当前租户已启用能力。对象数据范围从上述交集中的角色授权合并取得；权限被过滤时，其 data_scopes 同时删除。客户端策略变窄后，旧 token 在下一次内省也不能继续使用被取消的权限；策略变宽不能突破旧 token 的 scopes。机器主体没有用户角色，其操作集合改取机器身份登记的授权，仍与客户端策略、token scopes、租户能力和目标 audience 取交集。

identity-service 内使用同一个 `AuthorizationEvaluator`（拟新增内部组件，不新增服务）计算角色来源、授权交集和拒绝原因。内省、当前应用 `/me`、管理查询复用该计算过程；SDK 根据内省结果检查声明的权限，不再为了同一权限额外远程调用一个“鉴权服务”。成功内省不缓存的合同不变。

用户访问的最终条件为：

```text
token 有效
AND issuer 与预配置一致
AND aud 包含当前服务
AND token 的 tenant_id 与当前业务上下文一致
AND scope 覆盖该 API 声明的授权范围
AND 过滤后的有效权限包含该 API 权限
AND 目标数据满足该权限的数据范围
```

OAuth scope 决定“这个客户端获准代表用户做哪些事”；角色决定“用户在租户中能做哪些事”；数据范围决定“能操作哪些记录”。三者取交集，外部应用即使获得 scope 也不能超过用户原有权限。

授权解释采用本节 AuthorizationEvaluator：返回 `ALLOWED`、`NO_ROLE_GRANT`、`ROLE_DISABLED`、`PERMISSION_DISABLED`、`CLIENT_SCOPE_LIMITED`、`TOKEN_SCOPE_LIMITED`、`AUDIENCE_MISMATCH`、`TENANT_CAPABILITY_DISABLED` 等稳定原因码及中文说明，必要时附多个原因。不提供管理模拟接口；运行时以 token 实际 scopes 计算。实际权限查询校验目标管理范围，不能用于枚举其他用户/租户。普通内省和业务 403 只返回必要信息，详细角色来源留在受权管理入口。

<a id="origins"></a>

## 10. 环境地址、代理与回调

公开来源 PUBLIC_ORIGIN 固定决定 issuer、浏览器授权/回调/退出链接；内部来源 IDENTITY_INTERNAL_ORIGIN 决定 BFF、资源服务与机器客户端连接身份服务的地址。二者可不同，但验证 issuer 时仍使用公开固定值，不因为走内网而接受另一个 issuer。

仅 APP_ENV=local 且 PUBLIC_ORIGIN 的主机严格为 localhost、127.0.0.1 或 ::1 时，允许明确配置的 HTTP 开发入口和非 Secure Cookie；HttpOnly、SameSite=Lax、CSRF、state/PKCE/nonce 校验仍启用。非 loopback HTTP 直接拒绝，不能把 local profile 部署到测试/生产。测试/生产统一 HTTPS 和受信任内部 TLS。容器里的 localhost 不是宿主或另一服务，不能把 discovery 返回的外部 loopback token URL不加处理地用于容器服务端连接。BFF 使用显式内部 token/UserInfo/JWKS 地址，浏览器跳转仍走公开来源；这组差异必须经过真实登录测试。

Nginx/Gateway 仅信任登记的代理链，固定 Host/Proto 与回调不接受外部任意覆盖。OAuth token/revoke 等标准端点使用协议本身的客户端认证，不统一套要求已有用户会话的 BFF 过滤器。需要 CSRF 的浏览器写操作与校验 state/nonce 的回调分别实施相应保护。

<a id="failures"></a>

## 11. 故障、密钥轮换和安全审计

| 事件 | 结果与恢复边界 |
| --- | --- |
| token 不存在/过期/已撤销 | 401 或标准 inactive；不继续执行业务 |
| 已认证但 scope/角色/租户能力不足 | 403；管理解释可指出限制来源 |
| 身份服务/Redis/PG 无法完成必要验证 | 503，失败关闭；不按匿名继续 |
| 客户端超时重试 token 请求 | 遵守 grant 单次消费/refresh 轮换；不重复发放同一授权码 |
| 签名密钥轮换 | 新签名使用新 key，旧验证公钥保留到相关 ID Token 过期 |
| secret 丢失或只展示一次的响应丢失 | 受控轮换，不通过查询接口取回原秘密 |
| 单会话撤销时 Redis 清理失败 | 数据库撤销事实先阻止内省/刷新，清理任务重试 |
| 外部提供方不可用 | 显示该登录方式不可用；不自动放宽绑定或密码验证 |

不记录 token、Cookie、密码、OAuth code/state、提供方秘密和重置凭证。审计记录主体、租户、client、动作、结果码、request_id 和时间；敏感失败不能通过不同文案枚举用户。真实第三方接入必须用测试应用和真实回调证明，mock 只能验证本地协议分支。

参考资料：以下为组件参考资料；本次只重新核验了认证文档新增引用的 Spring 客户端模型与 NIST 密码建议，其余在线版本未重新核验。框架具体默认策略由本文定义，不能认为引用组件就自动实现了全部业务合同；实施兼容冻结任务须复核版本与能力。

| 来源 | 参考用途 |
| --- | --- |
| [Spring Authorization Server 进入 Spring Security 7](https://spring.io/blog/2025/09/11/spring-authorization-server-moving-to-spring-security-7-0/) | 授权服务器组件的发布体系 |
| [Spring 授权服务器配置模型](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/configuration-model.html) | 标准端点、OIDC 显式启用与可定制配置 |
| [Spring PKCE 与公共客户端](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html) | 公共客户端、PKCE 与 refresh token 边界 |
| [Spring OAuth2 Client 授权模式](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html) | 授权码与 Client Credentials 客户端支持 |
| [Spring opaque Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/opaque-token.html) | 通过内省验证 opaque token |

### 新服务登记与受限目录发布入口

平台 `POST /api/v1/identity/platform/service-audiences` 接收 service/audience，要求 platform:client:manage 和近期认证，目录锁→平台账号复查→唯一绑定检查→写入与审计同事务。相同绑定可重复确认，不一致返回409；不能覆盖既有服务/audience 或维护 identity-service 保留绑定。此登记只连接业务权限目录和 audience，不授予账号权限。

目录发布使用现有授权服务器签发的项目级机器 access token：当前及签发 audience 仅 identity-service、无租户，scope 为 catalog.publish:目标服务。独立 Bearer 链从官方 Authorization 记录校验有效性、撤销及当前客户端范围；事务写入前再次校验，失效拒绝。不使用用户 Token，不授目录发布账号业务访问权。

`GET /internal/v1/permission-catalogs/version` 返回 catalog_version，只有有效发布身份可访问。`POST /internal/v1/permission-catalogs:sync` 接收 service/release_version/checksum/content/catalog_version；content 是制品中的原始 UTF-8 YAML，checksum 为 SHA-256。字段和同步语义见权限设计；409 后人工核对目录再重试，不自动覆盖。内部入口不发布到公网 Gateway。


## 修订记录

- 2026-10-27，D-20261027-02：落实已确认计划的固定提供方配置、标准协议、显式绑定与本地会话转换；不扩展在线提供方管理。

- 2026-10-27，D-20261027-03：统一混合项目目录及接入路径；业务配置扩展、管理页和发布恢复合同按计划 05 修正；实际验收见计划 05。
