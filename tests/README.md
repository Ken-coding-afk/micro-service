# 框架验证

模块单元测试仍在 Java 模块 `src/test`、Python SDK 的 `tests` 中；这里集中跨模块和工具测试。

- `project/`：项目生成、来源、文件保护与部署载荷测试，不启动 Docker。
- `config/`、`infra/`、`deploy/`、`identity/`：各自的合同及集成测试；部分集成用例会启动真实组件，运行前必须确认范围。
- 当前服务绑定、凭据隔离、轮换保护和生产环境拒绝在 `project/test_workspace.py`；不再通过旧脚本间接测试当前流程。
- `fixtures/`：共享协议数据与可选协议测试材料。
- `acceptance/legacy/`：历史验收链脚本，仅用于维护者回归，不是新项目接入步骤。

`acceptance/smoke.cjs` 与两个共享协议 JSON 随新项目交付；其余维护者测试不整套复制。新项目仍携带各 SDK 和服务自己的测试。

不要运行全仓测试来代替选择受影响用例。根 pytest 默认禁止 Docker 子进程；真实组件夹具必须显式启用 `--run-integration`，启用后环境故障应失败。Docker 验收当前暂停，不运行该开关。当前范围和实际证据见[合并实施计划](../docs/plans/2026-10-27-脚手架精简与工程能力补齐-plan.md)；历史验收不追改。
