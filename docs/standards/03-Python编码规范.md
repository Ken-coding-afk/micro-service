# Python 编码规范

> 版本：1.0；基线：D-20261028-02；更新：2026-10-28。  
> 配合[共同规范](01-项目编码规范.md)与[开发指南](../guides/04-新项目开发与规范升级指南.md)使用。

## 1. 服务结构与依赖方向

每个服务独立 pyproject.toml/uv.lock，源码使用 src/业务包。main.py 只负责 create_app/main 和资源装配；config.py 保存本服务设置；api 负责路由、schemas 保存协议类型、services 保存用例、repositories 访问数据库、clients 访问远端。只有实际使用 ORM 时建 models；不预建 agents/tasks/mq。权限清单保留为包资源，迁移位于服务根 migrations。

API → Service → Repository/Client；底层不得 import main。不要通过 app.state 任意字典贯穿业务；装配层可存 Service 实例，入口通过明确类型的依赖取出。导入模块不能联网、启动服务、加载模型或读取机器秘密。

SDK 按能力组织，不套用业务分层。本服务业务配置由自己的 Pydantic 模型校验，框架不认识其字段。根项目 uv 环境属于运维工具，不代替每个业务服务的环境。

## 2. 命名、类型和配置

包/模块/函数 snake_case，类 PascalCase，常量 UPPER_SNAKE_CASE。公共入口、SDK API、业务用例及跨层结果必须有类型标注。Pydantic 处理协议输入，简单内部结果可用 frozen dataclass；不要无边界 dict[str,Any] 贯穿所有层，不机械复制相同类型。

Pydantic 的允许字段、strict/extra 策略按既有合同保持，不能为类型检查更换运行时校验语义。动态第三方响应只在边界收窄或显式 cast；cast 不等于验证，不以它代替用户输入校验。

配置来源沿用框架环境/秘密与 Nacos 划分。不得把密码读入日志、repr 或异常字符串；导入时不读取环境凭据。本地、测试、生产配置分离，不写可用生产默认值。

## 3. 生命周期、事务与异步

FastAPI lifespan 管理 runtime、内省客户端、DB engine；同进程复用池，finally 关闭；不在每次请求新建客户端。安装顺序为 runtime.install(app)，再 install_resource_security(app, introspector)，以保留上下文/并发/安全中间件合同。

async 中不调用阻塞 IO、同步 DB 或长 CPU/GPU 工作。需要同步执行时使用有界执行方式，取消 HTTP 不代表底层线程已经结束，不提前归还仍在工作的资源名额。不得吞掉 CancelledError 转成假成功。

每个用例使用独立 DB session 和 tenant_transaction；用例负责事务，Repository 接收该 session，不自行 commit。tenant/owner 来自可信 principal，上下文由 SDK 设置/清理。远程调用在事务之外经 runtime.http 进行，不重复实现重试、池或熔断；Python SDK 已提供 runtime.http 出站熔断，未提供任意业务函数熔断器。

## 4. 错误和可测试性

入口限制参数，用例决定业务边界，Repository 参数化 SQL；API 不直接写 SQL。稳定错误码与现有 HTTP 状态保持，未知结果不得变成成功。脱敏日志说明阶段及关联 ID，不直接打印第三方异常内容或请求 body。

用例依赖由装配层传入，可在测试中替换 Repository/Client/事务上下文；不因此强制每个对象增加抽象基类。应用导入测试应无需 PG/Nacos/秘密文件。协议测试检查具体 JSON/状态，数据库隔离另用真实受限账号验证。

## 5. 可执行开发基线

Python 3.12；示例 dev 组锁定 Ruff 0.17.2、mypy 2.4.0、pytest 9.1.1、pytest-asyncio 1.4.1。Ruff 100 列、py312、E4/E7/E9/F/I；mypy 对业务入口启用 disallow_untyped_defs、check_untyped_defs、warn_return_any。第三方缺桩只在具体边界处理，禁止全项目 ignore_errors。

在服务目录执行：

```powershell
uv sync --frozen --group dev
uv run --frozen ruff check src tests
uv run --frozen ruff format --check src tests
uv run --frozen mypy src
uv run --frozen pytest tests/test_business_config.py tests/test_items.py tests/test_application.py
# 以下两条会修改源码；需要修复格式时显式执行
uv run --frozen ruff check --fix src tests
uv run --frozen ruff format src tests
```

不要依靠全局 pip/pytest 或框架 .venv 补齐服务缺少的依赖。锁文件必须与 pyproject 同步，frozen 失败先核对版本来源，不用取消 frozen 掩盖。

生产使用服务 uv.lock 在 Linux 构建阶段校验并冻结安装，SDK/业务非 editable；不要求手工 wheelhouse。dev 工具不进入生产环境。运维脚本采用项目根 .venv 和根 uv.lock；未修改的存量脚本不因本规范进行全量格式化。

目录同步：2026-10-27，D-20261027-07，目录和工具路径按[总体架构](../design/01-总体架构与工程结构设计.md#framework-layout)；命名与编码行为不扩大。

变更范围检查：从项目根运行 `uv run --frozen python scripts/check_python.py --paths <本次文件>`，或提供 `--base REF --head REF`。按所属 pyproject 执行 Ruff E4/E7/E9/F/I、py312 与格式检查，覆盖源码和测试；业务模块及 SDK 在各自环境执行 mypy。SDK 保持现有非 strict 强度，运维脚本不新增全量 mypy gate。修订：2026-10-28，D-20261028-02。

修订：2026-10-28，D-20261028-02：落实整体整改的边界校验、共享机制与可执行检查，实际验证见整体整改计划。
