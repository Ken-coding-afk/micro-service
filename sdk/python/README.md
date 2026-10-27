# Python 接入 SDK

分发包 `ken-platform-sdk`，导入名 `ken_platform_sdk`。提供配置发现、身份校验、租户上下文、HTTP 调用和并发保护；数据库、消息等按业务需要启用。

创建项目时在业务服务的 pyproject.toml 中写入固定源码依赖 `../../sdk/python`。每个服务拥有自己的 uv.lock 和 .venv；开发时 editable 引用，镜像构建使用冻结依赖和非 editable 安装。不把工具环境或其他服务的依赖混入业务环境。

参见[Python 编码规范](../docs/standards/03-Python编码规范.md)与[开发升级指南](../docs/guides/04-新项目开发与规范升级指南.md)。
