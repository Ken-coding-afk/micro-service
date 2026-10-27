# Java 接入 SDK

Maven 坐标为 `com.ken.platform:java-sdk`。提供身份和租户上下文、权限校验、配置发现、HTTP、数据隔离及可选消息集成；不是独立运行服务，不放业务对象。

新项目通过根 Maven reactor 构建固定 `vendor/micro-service/java-sdk`，业务 POM 引用 `java-sdk`；不需要先手工安装 JAR。内部 Java 包仍是 `com.ken.platform.foundation`，本次目录整理不修改公共类型名称。

参见[Java 编码规范](../docs/standards/02-Java编码规范.md)和[新项目接入指南](../docs/guides/01-新项目接入指南.md)。
