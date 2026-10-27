# Java / Python 微服务框架

为独立业务项目提供 identity-service、api-gateway、java-sdk 和 python-sdk。每租户独立账号，Spring OAuth2/OIDC、统一角色权限、Nacos、PostgreSQL RLS、Sentinel 和 Python 并发保护；业务功能由项目实现。

当前改造采用固定源码工作区：新项目自己的 services 同时放 Java/Python，vendor/micro-service 固定框架提交。管理页随身份服务提供；不要求私有 Maven/PyPI 仓库、手工安装 JAR/wheel 或复制工具源码。

## 目录

- `java-sdk/`、`python-sdk/`：两种语言的接入库。
- `services/`：identity-service 与 api-gateway。
- `template/`：Java/Python 混合项目骨架、配置和部署声明。
- `tools/`：创建、开发、构建与运维工具，由根 manage.py 调用。
- `tests/`：工具测试、集成测试和验收夹具。
- `docs/`：设计、指南、规范与实施计划。

## 创建和使用

从包含创建器的已发布标签/提交检出本仓库：

```powershell
python manage.py create --output ../equipment-platform --ref <固定标签或提交> --project equipment-platform --java-service equipment-service --java-package com.acme.equipment --python-service inspection-service --python-package inspection
```

进入生成项目，先阅读 README，提交项目文件和 gitlink，再执行 init --local；两个终端用 dev 分别启动业务。创建器不会提交、推送或部署。详见[接入指南](docs/guides/01-新项目接入指南.md)、[开发与升级](docs/guides/04-新项目开发与规范升级指南.md)。

## 当前边界

本轮源码工作区仍在按[实施计划](docs/plans/2026-10-27-源码工作区与新项目接入改造-plan.md)验证。历史制品候选的验收不能算作新路径已通过；真实双机、外部提供方、生产 ACME、容量与生产恢复仍未验证。没有对外推送本轮镜像或源码。

权威设计、指南与编码规范分别维护，入口见[文档导航](docs/01-文档导航.md)。协作规则见[AGENTS.md](AGENTS.md)、[CLAUDE.md](CLAUDE.md)。完成的历史计划保留原证据，不追改；最新设计基线 D-20261027-08。
