# 认证与OAuth2设计

> 文档版本：1.0；文档集基线：D-20261026-02；更新：2026-10-26。  
> 状态：目标设计，待整体评审；不代表代码、脚本、镜像或运行能力已实现。  
> 本文负责：登录、OAuth2/OIDC、token/BFF、第三方身份和机器认证。  
> 导航：[文档总入口](../README.md)；任务与证据见[实施计划](../plans/2026-10-26-微服务框架实施计划.md)。

文中的路径、接口、配置和命令均为拟交付合同；当前仓库只有文档，不能直接执行这些命令启动系统。

| 章节 | 阅读目的 |
| --- | --- |
| [1. 组件、协议端点与接入登记](#section-1) | 组件、协议端点与接入登记 |
| [2. 浏览器登录与 BFF](#section-2) | 浏览器登录与 BFF |
| [3. 对外授权、PKCE 与用户同意](#section-3) | 对外授权、PKCE 与用户同意 |
| [4. 第三方登录与账号绑定](#section-4) | 第三方登录与账号绑定 |
| [5. 机器身份与后台委托](#section-5) | 机器身份与后台委托 |
| [6. Token、会话与撤销周期](#section-6) | Token、会话与撤销周期 |
| [7. 内省、应用权限与最终授权交集](#section-7) | 内省、应用权限与最终授权交集 |
| [8. 环境地址、代理与回调](#section-8) | 环境地址、代理与回调 |
| [9. 故障、密钥轮换和安全审计](#section-9) | 故障、密钥轮换和安全审计 |

<a id="section-1"></a>
<a id="d07"></a>
<a id="d07-1"></a>

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

测试/生产 issuer 为项目固定 HTTPS 地址，如 `https://doc-insight.example.com`；本地例外见 [开发调试指南·地址、issuer、回调和容器连通](../guides/开发调试指南.md#d05-2)；Gateway 转发这些路径时不改写 issuer 或协议路径。只信任网关/边缘代理提供的 Forwarded 信息，禁止由任意客户端 Host 头决定回调和 issuer。OIDC 默认不开动态客户端注册，客户端由项目管理员审批登记。

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

<a id="section-2"></a>

## 2. 浏览器登录与 BFF

浏览器从固定入口选择平台或租户登录 → BFF 保存 state/PKCE 与客户端命名空间 → 身份服务校验账号及租户 → 返回一次性授权码 → BFF 服务端换 token → 浏览器获得 HttpOnly 会话 Cookie。业务前端不持有 client_secret，也不把 ID Token 当 API access token。

平台管理、租户管理、业务 Web 使用独立客户端和 Cookie/会话命名空间，具体路径见管理界面文档。登录已有其他租户会话时显式重新选择/认证，不能静默改变 token 的 tenant_id。写请求同时满足主体校验和 CSRF；页面关闭不等于 token 撤销。

退出本应用撤销对应授权并清理该 BFF 命名空间，另提供明确的全端退出。单独 OIDC logout 不等于撤销所有 API token，组合动作和会话记录必须一致。

<a id="section-3"></a>

## 3. 对外授权、PKCE 与用户同意

外部应用由管理员登记为 public 或 confidential，设置精确 redirect_uri、允许租户、audiences、scopes 与同意策略。只支持设计内已声明 grant；不启用 Password/Implicit 或任意动态客户端注册。

授权码流程强制 S256 PKCE，校验 client、redirect_uri、租户、state/nonce 和授权码单次使用。scope 从已登记集合选择；同意页面显示申请的实际操作类别，不把同意解释为授予用户原本没有的角色。撤销应用同意同时撤销该用户该客户端现存授权，未来重新授权。

原有角色发生变更时内省重算；客户端策略收紧立即限制后续内省；策略扩大不能扩大旧 token 中已经签发的 scopes。

<a id="section-4"></a>
<a id="d07-4"></a>

## 4. 第三方登录与账号绑定

1. 登录前确定 tenant_code；identity-service 将解析后的 tenant_id、提供方、原授权请求及一次性 state 存在服务器端。
2. 跳转外部身份提供方。回调严格验证 state；OIDC 同时验证 issuer、签名、audience、nonce 和时间。
3. 以 `(tenant_id, provider_registration_id, provider_subject)` 查绑定；禁止仅凭同名用户名或相同邮箱自动合并账号。
4. 已绑定且账号有效：完成本租户登录。未绑定：提示先登录本租户账号完成显式绑定；无账号者仅能进入该租户允许的注册/邀请流程，关闭注册时联系管理员；回调不自动开户。
5. provider 回调本身不能覆盖服务器保存的 tenant_id。第三方 token 只用于该身份交换，不直接作为我们业务 API 的凭证。

本设计交付通用 OIDC 接入和 GitHub OAuth2 接入示例。企业微信等提供方有其专用协议/参数，后续接入应通过提供方适配和真实凭证测试，不能因为支持 OAuth2 就声称所有厂商可零配置登录。

<a id="section-5"></a>
<a id="d07-5"></a>

## 5. 机器身份与后台委托

普通机器客户端绑定一个租户、目标 audience 和 scopes；一个服务需要代表多个租户执行通用后台任务时，使用专门登记的工作客户端，只允许领取和回写任务等少数内部动作，不获得任意租户 CRUD 权限。

Client Credentials 的 token 不接受通过任意 `tenant_id` 参数扩大授权。客户端凭证可换发哪些租户/服务范围来自登记策略。client_secret 只展示一次，数据库保存验证散列；客户端侧用 secret 文件装载。轮换采用新增客户端凭证/客户端版本、迁移调用者、撤销旧凭证的受控过程。

同步用户调用默认传递原用户 access token，前提是 token 的 aud 明确包含目标服务，且授权 scopes 覆盖下游所需能力；不能将上游服务身份覆盖成用户身份。仅内部接口另需 `X-Service-Authorization: Bearer <机器token>` 验证调用者；外部入口统一剥离该 header，不能直接由浏览器提供。普通对外 API 只要求用户身份，不无谓增加双 token。

本设计不提供通用 Token Exchange；不能为解决 audience 不匹配而忽略校验。项目预先为业务调用链登记允许的 audience/scopes，跨边界新调用必须更新并重新授权。

<a id="section-6"></a>
<a id="d07-2"></a>

## 6. Token、会话与撤销周期

- Access token 统一使用成熟组件生成的 opaque token，有效期 10 分钟；服务通过标准内省验证，不能在 Python 中假装把它当 JWT 解码。
- ID Token 使用非对称签名 JWT，面向登录客户端；API 不接受 ID Token 作为 access token。签名密钥以密钥文件/密钥服务持久化，不能每次容器重启生成新密钥；轮换保留旧公钥直至相关 token 过期。
- 机密用户客户端可获得 refresh token：绝对有效期 8 小时，刷新时轮换；旧 refresh token 不得再次使用。实现时通过持久化授权与事务锁防止并发双重刷新。
- 公共客户端不保存 client_secret；本设计不为其发 refresh token。过期后重新走授权码流程，仍有效的登录会话可减少重复输入。该边界与官方公共客户端指引一致。[PKCE 指引](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html)
- 测试/生产浏览器 cookie 使用 Secure、HttpOnly、SameSite=Lax；[新项目接入指南·完整启动顺序与命令合同](../guides/新项目接入指南.md#d05)的 loopback local profile 仅允许明确的开发 HTTP 例外，默认 BFF 会话最长 8 小时、闲置 30 分钟失效。跨租户登录创建不同授权上下文，不通过修改现有会话的 tenant_id 实现切租户。
- 本设计资源服务**不缓存成功内省结果**。内省服务实时检查授权是否撤销、账号与租户状态及当前角色权限；避免给“权限立即失效”附加隐藏缓存窗口。
- 禁用账号/租户、修改密码和全端退出后，新内省请求必须返回 inactive；已经通过检查且正在执行的请求不承诺强行中断。长任务另按 [Java与Python协作设计·消息、Outbox 与 Inbox](Java与Python协作设计.md#d10) 在领取及关键副作用前复核。
- 登录会话由 Spring Session + Redis 管理；OAuth2 客户端、授权和同意记录持久化 PostgreSQL。Redis 故障不能退化成匿名登录或绕过校验。

认证中心不可用时，依赖内省的受保护请求返回 503；凭证错误为 401；有身份但权限不足为 403。优先保证正确性，后续只有在真实压测证明有需要后，才另行设计有明确撤销时限的鉴权缓存。

<a id="section-7"></a>
<a id="d07-3"></a>

## 7. 内省、应用权限与最终授权交集

标准内省响应保留 RFC 字段，并添加框架字段。有效用户 token 返回：`active`、`iss`、`sub`、`client_id`、`aud`、`exp`、`scope`、`principal_type=user`、`tenant_id`、`permissions` 和 `data_scopes`。`permissions` 为按当前客户端、token 与目标资源过滤后的有效权限码数组；`data_scopes` 为需对象范围权限码到 SELF/TENANT 的映射；无对象操作只出现于 permissions，不在该映射中。机器 token 使用 `principal_type=service`，不含伪造的 user_id。

每个资源服务使用自己独立的内省客户端凭证调用 identity-service；一个内省凭证绑定一个资源 audience，只返回属于该 audience 的授权，不能由请求参数选择其他服务，不具有发放用户授权的能力。scope 与业务权限的映射属于服务端固定目录，例如 `documents.read` 只允许 `document:read`，不能覆盖全部业务权限。owner_service 到 audience 的绑定由受控服务登记维护，不由普通租户用户修改。

用户有效操作集合 = 当前有效角色权限 ∩ 当前客户端策略允许的操作 ∩ token scopes 映射的操作 ∩ 目标 audience 的操作 ∩ 当前租户已启用能力。对象数据范围从上述交集中的角色授权合并取得；权限被过滤时，其 data_scopes 同时删除。客户端策略变窄后，旧 token 在下一次内省也不能继续使用被取消的权限；策略变宽不能突破旧 token 的 scopes。机器主体没有用户角色，其操作集合改取机器身份登记的授权，仍与客户端策略、token scopes、租户能力和目标 audience 取交集。

identity-service 内使用同一个 `AuthorizationEvaluator`（拟新增内部组件，不新增服务）计算角色来源、授权交集和拒绝原因。内省、当前应用 `/me`、管理查询与授权预览复用该计算过程；SDK 根据内省结果检查声明的权限，不再为了同一权限额外远程调用一个“鉴权服务”。成功内省不缓存的合同不变。

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

<a id="section-8"></a>

## 8. 环境地址、代理与回调

公开来源 PUBLIC_ORIGIN 固定决定 issuer、浏览器授权/回调/退出链接；内部来源 IDENTITY_INTERNAL_ORIGIN 决定 BFF、资源服务与机器客户端连接身份服务的地址。二者可不同，但验证 issuer 时仍使用公开固定值，不因为走内网而接受另一个 issuer。

本地 loopback HTTP 是明确 local profile 例外；测试/生产统一 HTTPS 和受信任内部 TLS。容器里的 localhost 不是宿主或另一服务，不能把 discovery 返回的外部 loopback token URL不加处理地用于容器服务端连接。BFF 使用显式内部 token/UserInfo/JWKS 地址，浏览器跳转仍走公开来源；这组差异必须经过真实登录测试。

Nginx/Gateway 仅信任登记的代理链，固定 Host/Proto 与回调不接受外部任意覆盖。OAuth token/revoke 等标准端点使用协议本身的客户端认证，不统一套要求已有用户会话的 BFF 过滤器。需要 CSRF 的浏览器写操作与校验 state/nonce 的回调分别实施相应保护。

<a id="section-9"></a>

## 9. 故障、密钥轮换和安全审计

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


参考资料：以下为前期调研保留的原始项目/官方资料入口；本轮没有重新核验其在线版本。框架具体默认策略由本文定义，不能认为引用组件就自动实现了全部业务合同；实施兼容冻结任务须复核版本与能力。

| 来源 | 参考用途 |
| --- | --- |
| [Spring Authorization Server 进入 Spring Security 7](https://spring.io/blog/2025/09/11/spring-authorization-server-moving-to-spring-security-7-0/) | 授权服务器组件的发布体系 |
| [Spring 授权服务器配置模型](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/configuration-model.html) | 标准端点、OIDC 显式启用与可定制配置 |
| [Spring PKCE 与公共客户端](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html) | 公共客户端、PKCE 与 refresh token 边界 |
| [Spring OAuth2 Client 授权模式](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html) | 授权码与 Client Credentials 客户端支持 |
| [Spring opaque Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/opaque-token.html) | 通过内省验证 opaque token |
