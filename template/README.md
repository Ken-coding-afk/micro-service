# 新项目模板

这里是新项目骨架的唯一来源，由根目录 `manage.py create` 读取。不要把本目录直接复制成项目，也不要在这里保存运行秘密或验收脚本。

| 目录 | 创建项目后的用途 |
| --- | --- |
| services/java-service | 按指定服务名和 Java 包名生成领域服务、权限清单、迁移和测试 |
| services/python-service | 按指定服务名和 Python 包名生成计算服务、权限清单、迁移和测试 |
| config | 环境样例、Nacos 配置和服务登记 |
| infra | PostgreSQL、Redis、Nacos 等组件声明与资源声明 |
| deploy | 应用 Compose、Nginx 与发布声明 |
| web | 两个业务服务共用的起始页面；随后由项目接管 |

模板中的 sample 标识是生成占位内容，会替换成项目参数。生成器还产生根 POM、manage.py、README、协作规则和 IDE 任务；这些组合文件不另存一份模板。工具实现位于 [tools](../tools/README.md)，回归脚本位于 [tests](../tests/README.md)。

创建与开发见[接入指南](../docs/guides/01-新项目接入指南.md)。升级框架不会重新套模板覆盖业务代码。
