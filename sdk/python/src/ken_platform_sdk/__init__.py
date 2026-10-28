"""ken-platform-sdk：Ken 微服务框架 Python 接入包。

提供配置发现、身份校验、可信租户上下文、HTTP 调用和可选数据库接入。
业务项目自行选择算法、聊天或 Agent 框架；本包不提供业务运行时。
模块边界见《Java 与 Python 协作设计》。
"""

from importlib.metadata import version

__version__ = version("ken-platform-sdk")
