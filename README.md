# Java / Python 微服务框架

为独立业务项目提供基础身份服务、API 网关、Java/Python 接入包、内置身份管理界面与可复制业务示例。采用租户独立账号、多机 Docker Compose；完整目标与边界以设计文档为准。

当前仅完成设计与实施计划整理，尚未实现框架。文档中的代码目录、脚本、镜像和命令是拟交付合同，不能当作已有可运行能力。

## 从哪里读起

- [文档总入口](docs/01-文档导航.md)：10 份专题设计、2 份指南、编码规范和实施计划。
- [新项目接入指南](docs/guides/01-新项目接入指南.md)：从选取发行物到初始化、业务开发、部署和升级。
- [开发调试指南](docs/guides/02-开发调试指南.md)：全容器、Java/Python IDE、前端热更新与排错。
- [总体架构与工程结构设计](docs/design/01-总体架构与工程结构设计.md)：模块、技术选型、目录与交付边界。
- [微服务框架实施计划](docs/plans/01-微服务框架实施计划.md)：依据设计安排任务、依赖、验收和执行记录。
- [项目编码规范](docs/standards/01-项目编码规范.md)：项目必须遵守的组织、安全与验证规则。

## 协作与实施

Codex 使用 [AGENTS.md](AGENTS.md)，Claude 使用 [CLAUDE.md](CLAUDE.md)，Cursor 使用 [.cursor/rules/karpathy-guidelines.mdc](.cursor/rules/karpathy-guidelines.mdc)。

docs 内文件按目录使用编号中文名称（01-主题.md），章节使用语义锚点，调整行为时同步修改设计与受影响计划。文档编辑不自动授权代码实施；实施前确认范围，完成当次代码后集中补写和执行相关测试，不默认运行全项目套件。未经明确授权不初始化 Git/CodeGraph、提交推送或部署。
