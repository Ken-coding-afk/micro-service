# 新项目接入第三方登录

未启用时不登记外部客户端、不请求外部网络，不影响本地账号、框架对外 OAuth2/OIDC 或机器认证。外部登录由 Spring OAuth2 Client 完成；第三方 token 不作为业务凭证，不持久化到框架会话。

## 配置与发布

1. 先按 `docs/guides/01-新项目接入指南.md` 跑通项目，创建租户、初始化本地账号密码。身份库应用 V9 迁移；使用包含本适配的 identity/java-sdk/Gateway 候选制品，不能只改 Nacos 路由。
2. 在提供方登记机密授权码客户端，回调精确为 `PUBLIC_ORIGIN/login/oauth2/code/<registrationId>`。以官方 discovery/文档核对授权、token、UserInfo、JWKS 端点。
3. 复制本目录 OIDC 或 GitHub 配置为运行目录 `identity-service-secrets/federation.yaml`，填写实际客户端 ID、端点和明确适用的租户 code。密钥放在 `identity-service-secrets/federation/corp-client-secret` 或 `github-client-secret`，设置仅部署身份可读。不要提交这些运行文件。
4. 在项目 Compose 命令中追加本目录 `compose.yaml`。identity 专属秘密目录已有只读挂载；`SPRING_CONFIG_ADDITIONAL_LOCATION` 让 Spring 读取固定配置，configtree 读取独立密钥。默认配置发布器不会把这些设置发往 Nacos。修改后受控重启 identity，不提供动态提供方插件。
5. Gateway 使用新版 java-sdk 的身份路径白名单；Nginx 代理必须包含 `/login/oauth2/`。测试/生产用有效 HTTPS 域名与证书；本地仅允许显式 local 的 loopback HTTP。提供方端点主机必须出现在 allowed-hosts，内网提供方由运维明确登记；限制服务出口，不为外部请求开任意 URL 代理。

平台运维维护此配置及文件权限。当前交付没有提供方在线编辑/连接测试管理页，不能把配置示例视为该页面已验收。GitHub 示例尚未取得真实应用凭据，是否支持当前注册应用的 PKCE 流程须以实际接入验收为准。

## 用户如何使用

- 本地登录页新增第三方登录表单：填写租户标识，选择已配置提供方。租户和提供方在服务器端与一次性 state 绑定，回调 URL 中的 tenant_id 无效。
- 首次使用未绑定的外部身份会被拒绝，并提示先登录本租户账号。不会根据邮箱、用户名或外部角色创建账号/授予权限。
- 登录本地账号后，在账号页进入“绑定或解绑第三方账号”。绑定要求近期重新认证、CSRF 与本租户一致。通过提供方验证后建立绑定，撤销本次旧会话并提示重新登录。
- 之后可选择该提供方登录，得到框架自己的登录会话，再沿原授权码/BFF 流程进入业务。禁用用户/租户、首次密码未完成或撤销会话仍会拒绝访问。
- 解绑要求近期认证并保留有效本地密码。它停止该外部身份后续登录；需要强制退出已有登录时，使用已有“退出所有会话”操作。

每个租户的账号独立。相同外部 subject 在其他租户没有任何隐式权限；需分别登录那个租户的本地账号并明确绑定。提供方 registrationId 不要重新指向不同身份源；持久化的 authority 会使旧映射在 issuer 改变后拒绝登录，应按身份迁移单独处理。

## 验证边界

本地协议测试入口为 `BrowserLoginHttpTest#federation*`，使用真实 Tomcat、PG、Redis 和本地签名 OIDC HTTP 夹具；它验证 state、nonce、issuer、audience、签名、PKCE、显式绑定与撤销边界。它不证明真实 GitHub/企业平台配置可用。正式接入还需验证真实回调域名、TLS、出口、厂商客户端设置和账号授权流程。
