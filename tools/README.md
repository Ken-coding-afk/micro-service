# 框架工具

对外使用仓库根 `manage.py create` 或生成项目的 `manage.py`。本目录保存实现，不由业务项目复制维护。

| 入口 | 职责 |
| --- | --- |
| create.py / generation.py | 固定 Git 引用、生成混合项目和新增服务 |
| cli.py / local.py | 项目命令、本地初始化与宿主开发编排 |
| build.py | 从固定源码构建镜像、组装正式部署载荷 |
| config/ | 配置校验与 Nacos 发布 |
| infra/ | 组件启动与资源初始化 |
| deploy/ | 迁移、权限同步、发行校验与 Ansible 执行 |
| prepare_local.py | 生成本地运行绑定 |

项目数据和秘密写项目 `.local/`。声明来自项目 `config/infra/deploy`；只有框架仓库独立检查时才默认读取 `template/`。部署包携带本目录中的运维工具及项目声明，Ansible 的入口说明在 [deploy/ansible/README.md](deploy/ansible/README.md)。
