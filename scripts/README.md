# 项目脚本

全部命令从项目根运行，使用根 uv.lock。没有 manage.py 和框架 vendor 路径。

| 入口 | 职责 | 交付位置 |
| --- | --- | --- |
| create.py / generation.py | 从固定提交创建混合项目 | 仅脚手架维护仓库 |
| create_service.py | 从 services 样例创建独立服务 | 仅脚手架维护仓库 |
| add_service.py | 预检后挂载固定业务 submodule 并登记 | 项目源码 |
| init.py | 按当前服务集准备 local 资源与技术身份 | 项目源码 |
| dev.py / ide.py | 本地启动与共享 VS Code 配置 | 项目源码 |
| compose.py | 本地基础服务状态、日志、停止 | 项目源码 |
| build.py / package.py | 固定源码构建与部署包 | 项目源码 |
| migrate.py / deploy/migrate.py | 服务迁移与锁、历史、失败状态管理 | 项目源码及部署包 |
| config/ | 配置校验与 Nacos 发布 | 项目源码及部署包 |
| infra/ | 组件启动和资源初始化 | 项目源码及部署包 |
| deploy/ | 权限发布、发行校验、证书与恢复工具 | 项目源码及部署包 |

运行材料写被忽略的 .local/，配置源归根 config/，组件归 infra/，部署声明与 Ansible 归根 deploy/。脚本是命令入口，不增加常驻发布服务。

使用路径见[接入指南](../docs/guides/01-新项目接入指南.md)，生产操作见[Ansible 说明](../deploy/ansible/README.md)。Docker 验收当前暂停。
