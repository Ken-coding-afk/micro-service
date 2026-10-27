# Java 编码规范

> 版本：1.0；基线：D-20261027-05；更新：2026-10-27。  
> 配合[共同规范](01-项目编码规范.md)与[开发指南](../guides/04-新项目开发与规范升级指南.md)使用。

## 1. 适用范围与目录

业务服务采用一个独立 POM/部署单元。启动类放自身根包；controller 转换 HTTP，controller/dto 保存接口类型；service 处理用例；dal 保存 JDBC Repository；client 封装远端访问。使用 MyBatis/ORM 时再按需增加 mapper/dataobject，不强制迁移持久化技术。简单只读业务投影可放 model，并与持久化实体区别；允许 Service/Repository 共用该明确字段集合，禁止反向依赖 controller。SQL 迁移在 src/main/resources/db/migration，权限清单在 src/main/resources/permissions.yaml。

框架 java-sdk 按能力组织，身份服务按身份领域组织，网关没有业务 DAL。本次不要求已有框架服务整体重排。模块之间只通过发布的公共接口调用，业务构建引用固定 vendor 父 POM/java-sdk；禁止引用机器上项目之外的框架 checkout 或内部私有类。

## 2. 命名、注入与类型

- 类/record 用 PascalCase，变量/方法 camelCase，包名小写；常量 UPPER_SNAKE_CASE。不使用无法说明职责的 Manager/Helper/Util 大杂烩。
- 必须构造器注入依赖，字段尽量 final。一个实现默认具体类，多个实现或稳定替换边界才使用接口，不生成 ServiceImpl 壳。
- 请求用 Bean Validation 和明确允许字段；结果使用 record/明确类型，避免 Map<String,Object> 贯穿用例。JSON 保持既有命名，必要时显式映射，不因 Java camelCase 改掉 page_size/request_id。
- 简单只读 Item/Page 结果可在用例中定义嵌套 record；API 通过 DTO 显式映射，DAL 不依赖 controller。不是每个类型都必须拆独立文件。
- 输入限制在入口验证；业务上限、状态和授权在用例处理；数据库约束兜底。不得用反射复制 tenant/owner/权限字段。

## 3. 事务、权限与持久化

用例负责事务边界。租户数据使用现有 TenantTransactions，必须与实际 JDBC 连接在同一事务内；Repository 不自行提交。已有 Spring 事务依赖代理时要核对自调用/异步传播，不凭注解猜测生效。

Service 使用可信 CurrentPrincipal 判断 tenant/owner/SELF，Repository 使用明确参数及参数化 SQL。API 保留框架权限注解，不把授权完全放在前端。查询字段/排序白名单，分页有上限；跨租户/跨库能力必须专门设计，不能在通用方法里增加跳过开关。

Repository 只读写本服务数据库。远程调用通过 client 包中的适配使用 ServiceHttpClient；不在 JDBC 事务内等待网络。禁止为了统一持久化风格引入第二套 ORM 或复制业务实体到公共基础库。

## 4. 异步、资源和异常

Servlet 用例可在既有请求预算内等待受控客户端；不要另开无界线程池。Gateway/WebFlux 事件循环禁止 block/get/JDBC 或同步内省，身份上下文走响应式链。

跨异步任务显式传递可信调用上下文，不假设 ThreadLocal 自动传播。取消和实际完成按组件合同释放资源；连接池/客户端由装配层负责关闭，不每次请求创建。

异常保持稳定 code/status，不吞错返回空成功；日志保留脱敏阶段/request_id，不输出含令牌的 URL 或完整 cause。业务可以定义自己的错误码，但不能改变框架错误协议。写请求不擅自重试，熔断打开不能伪造业务成功。

## 5. 格式、检查与测试

JDK 21；继承固定 platform-parent。Spotless 3.0.0 + google-java-format 1.29.0 是现有格式基线，validate 已执行检查；不用人工缩进或另加格式检查器对抗它。

以下从独立项目根执行；Linux 用 ./vendor/micro-service/mvnw，Windows 用 ./vendor/micro-service/mvnw.cmd：

```powershell
# 只读格式检查
./vendor/micro-service/mvnw.cmd -f pom.xml -pl :equipment-service -am spotless:check
# 显式修复格式，会修改文件
./vendor/micro-service/mvnw.cmd -f pom.xml -pl :equipment-service -am spotless:apply
# 只运行本次示例相关测试
./vendor/micro-service/mvnw.cmd -f pom.xml -pl :equipment-service -am -Dtest=BusinessSettingsTest,ItemServiceTest,ItemControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
# 已测试后构建干净制品；此命令本身不证明测试通过
./vendor/micro-service/mvnw.cmd -f pom.xml -pl :equipment-service -am clean package -DskipTests
```

业务服务按实际修改替换测试过滤器，不直接照抄其他项目所有用例。用例测试可以注入受控依赖；输入、响应和异常测试必须断言真实行为，不只断言某方法被调用。SQL/RLS 改动另用真实 PG；不以 mock 宣称跨租户隔离成功。

目录同步：2026-10-27，D-20261027-07，目录和工具路径按[总体架构](../design/01-总体架构与工程结构设计.md#framework-layout)；命名与编码行为不扩大。
