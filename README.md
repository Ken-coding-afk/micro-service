# Java / Python 微服务框架

> 2026-10-27：旧计划冻结。用户已确认[精简实施计划](docs/plans/04-通用微服务框架精简实施计划.md)及评审修正；当前设计基线 D-20261027-03。历史 rc.1 证据保留在计划 04；当前 rc.2 混合项目与审查修正见[计划 05](docs/plans/05-混合项目接入与审查修正实施计划.md)。

为独立业务项目提供基础身份服务、API 网关、Java/Python 接入包、内置身份管理界面与可复制业务示例。采用租户独立账号、多机 Docker Compose；完整目标与边界以设计文档为准。

当前已有可运行的本地候选：仓库外独立项目通过发行 POM/JAR/wheel 接入，启动 PG、Redis、Nacos、身份服务、Gateway、Nginx 和 Java/Python 示例；真实浏览器登录后完成两租户读写、双向调用、SELF/TENANT 与现有会话撤权验证。具体命令见 [examples 接入说明](framework/examples/README.md) 和 [独立项目验证](framework/examples/verification/README.md)。

身份服务已移除管理委派、授权模拟及租户安全策略编辑，保留固定管理员、普通业务角色、实际权限来源和通用管理页面。密码认证复用 Spring 标准 Provider/PasswordEncoder；统一项目安全策略遇到更严格的历史租户策略时阻止切换。历史数据库迁移和审计不删除。

**当前交付为本地候选，不是生产验收。** 第三方 OAuth2 Client/显式绑定、可选可靠消息、发布失败保护、真实 Nginx TLS，以及两个独立项目的接入、升级、兼容回退和隔离恢复已完成相应本地验证。真实第三方提供方、两台 Linux 服务器、ACME 签发和生产 PITR 尚未验证；镜像/包未上传团队制品仓库。当前 Java 候选版本为 `1.0.0-rc.2`，Python 为 `1.0.0rc2`。基础 OAuth2/OIDC 授权服务器和机器认证与第三方登录分别有验证入口。

## 从哪里读起

- [文档总入口](docs/01-文档导航.md)：10 份专题设计、2 份指南、编码规范和实施计划。
- [新项目接入指南](docs/guides/01-新项目接入指南.md)：从选取发行物到初始化、业务开发、部署和升级。
- [开发调试指南](docs/guides/02-开发调试指南.md)：全容器、Java/Python IDE、前端热更新与排错。
- [总体架构与工程结构设计](docs/design/01-总体架构与工程结构设计.md)：模块、技术选型、目录与交付边界。
- [通用微服务框架精简实施计划](docs/plans/04-通用微服务框架精简实施计划.md)：已确认的新范围、开源复用、新项目使用与验收。
- [旧微服务框架实施计划](docs/plans/01-微服务框架实施计划.md)：已冻结，保留历史任务与执行记录。
- [项目编码规范](docs/standards/01-项目编码规范.md)：项目必须遵守的组织、安全与验证规则。

## 协作与实施

Codex 使用 [AGENTS.md](AGENTS.md)，Claude 使用 [CLAUDE.md](CLAUDE.md)，Cursor 使用 [.cursor/rules/karpathy-guidelines.mdc](.cursor/rules/karpathy-guidelines.mdc)。

docs 内文件按目录使用编号中文名称（01-主题.md），章节使用语义锚点，调整行为时同步修改设计与受影响计划。文档编辑不自动授权代码实施；实施前确认范围，完成当次代码后集中补写和执行相关测试，不默认运行全项目套件。未经明确授权不初始化 Git/CodeGraph、提交推送或部署。

当前审查修正与混合项目接入见 [计划 05](docs/plans/05-混合项目接入与审查修正实施计划.md)；计划 04 保留历史证据，本轮验收不追改到旧计划。
