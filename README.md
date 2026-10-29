# Java / Python 微服务脚手架

从一份可运行的混合语言项目创建自己的项目。公共 SDK、身份服务、网关及运维脚本直接随项目源码交付；初始两个业务归主仓，后续服务可直接生成到主仓，也可用固定 Git submodule 接入。

当前分页、同仓新增服务与最小发行验收的实现和证据见[实施计划](docs/plans/2026-10-29-API约定与最小发行验收及同仓服务-plan.md)。既有环境与并发验证范围见[全面测试计划](docs/plans/2026-10-27-框架全面测试与并发验收-plan.md)；最小源码验收不替代生产部署或容量验收。

```text
sdk/java/                   Java 公共接入代码
sdk/python/                 Python 公共接入代码
services/
  identity-service/         账号、租户、权限、OAuth2/OIDC、身份管理页
  api-gateway/              入口路由、BFF、流量保护
  sample-java-service/      唯一 Java 样例源码
  sample-python-service/    唯一 Python 样例源码
web/                        业务前端样例
config/                     服务登记、开发端口、环境样例、Nacos 配置
infra/                      第三方组件和资源声明
deploy/                     应用 Compose、Nginx、Ansible
scripts/                    创建、开发、构建、迁移和接入入口
tests/                      跨模块定向测试
docs/                       设计、指南、规范及实施计划
pom.xml                     Java reactor
pyproject.toml + uv.lock     运维脚本工具环境
```

没有第二份 template。新项目从 services 样例生成并改名，不留下原 sample 服务；业务源码、配置和部署文件分别位于新项目对应根目录。

## 创建新项目

准备 Git、Python 3.12、uv、JDK 21；实际启动需要可用的 Docker Engine / Compose，并只操作本项目环境。先在脚手架根安装工具依赖，再从**包含本次改造的固定提交**生成项目。未提交的工作树不会被 --ref 导出。

```bash
uv sync --frozen
uv run --frozen python scripts/create.py --ref <固定提交> --output ../equipment-platform --project equipment-platform --java-service equipment-service --java-package com.acme.equipment --python-service inspection-service --python-package inspection
```

创建本身不启动容器、不创建远程仓库、不提交或推送。进入新项目后按照其 README 准备业务锁、初始化环境、登录授权和调试。生成项目不依赖此仓库的磁盘位置，也不需要私有 Maven/PyPI 仓库。

维护仓库样例也使用根 config/infra/deploy。样例中的业务逻辑仅供接入参考，身份服务不承载算法或聊天业务。

## 使用和维护

- [新项目接入](docs/guides/01-新项目接入指南.md)：创建、依赖、首次登录、新增服务。
- [开发调试](docs/guides/02-开发调试指南.md)：VS Code、运行绑定和排错。
- [开发与升级](docs/guides/04-新项目开发与规范升级指南.md)：SQL、构建、部署和升级。
- [工程检查与监控](docs/guides/05-工程检查与监控指南.md)：CI、可选采集、管理页和验证边界。
- [编码规范](docs/standards/01-项目编码规范.md)：项目、Java、Python 规范。
- [脚本职责](scripts/README.md)与[部署操作](deploy/ansible/README.md)。
- [文档导航](docs/01-文档导航.md)：权威设计与历史计划分开维护。

后续同仓服务由固定兼容脚手架的 `scripts/create_service.py --source local --project-root <项目> --language <java|python> --name <名称> --package <包名> --port <端口>` 直接创建并登记；独立源码仍使用原 create_service/add_service 固定子仓路径。SDK 和基础服务升级以审阅后的源码补丁合并，不自动覆盖项目定制。
