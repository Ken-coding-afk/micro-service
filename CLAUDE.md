# CLAUDE.md

Use a lightweight, verifiable coding workflow. More specific project rules override this file.

## 1. Language

- Respond in Chinese by default unless the user requests another language.
- Write project documentation primarily in Chinese.
- Keep code identifiers, commands, paths, and established technical terms in their conventional form.

## 2. Think before acting

- Define the goal, constraints, impact, and verification before making changes.
- Exercise independent judgment instead of defaulting to agreement. When the user's proposed approach is materially flawed, risky, internally inconsistent, or misaligned with their stated goal, challenge it respectfully, explain the concern, and recommend a better alternative. Do not object merely because another valid approach exists.
- Inspect enough context to explain the intended change before editing.
- When a requirement or technical choice cannot be determined confidently and would materially change the result, ask a focused question, include a recommendation, and wait for the user's decision.
- For ordinary changes, explain how the code will change and how it will be verified, then wait for explicit confirmation.
- For large changes, create one plan document as described below, then wait for explicit confirmation.
- Require confirmation after presenting the current proposal or plan. The original implementation request is not confirmation of material the user has not seen.
- Skip the plan format for pure explanations and simple lookups.

## 3. Keep changes simple and surgical

- Implement only what the user requested. Do not add speculative features or configurability.
- Avoid abstractions for one-off logic. Prefer fewer lines, fewer dependencies, and a smaller impact surface.
- Modify only files and code directly relevant to the task.
- Do not refactor adjacent modules, normalize unrelated formatting, or delete pre-existing dead code.
- Match existing naming, structure, and style. Remove only unused code introduced by the current change.

Every changed line must trace to the request, a required test, or a direct documentation update.

## 4. Distinguish ordinary and large changes

Treat a task as a large change when any condition applies:

- It spans three or more logical modules or services.
- It changes architecture, core data flow, or module boundaries.
- It changes a public API, database, authentication, authorization, build, or deployment behavior.
- It requires migration, compatibility, staged rollout, or rollback planning.
- It is a large refactor that can change existing behavior.
- The user explicitly requests a design document or implementation plan.

本项目将长期设计与实施计划分开维护，以用户明确确认的此规则覆盖通用技能中的“单一计划、不拆设计”默认规则。

- 权威设计放在 `docs/design/`，按稳定主题拆分，文件使用中文名，如 `多租户与权限体系设计.md`。每项规则只在一个主题定义，其他文档链接引用；不再维护大而全的重复总设计，不混入逐任务进度。
- 操作指南放在 `docs/guides/`，编码规范放在 `docs/standards/`，文件使用中文名。指南说明前提、工作目录、操作、结果和排错，不另定行为合同。主题调整同时核对指南、规范和实施计划。
- 大改动实施计划放在 `docs/plans/YYYY-MM-DD-中文主题实施计划.md`，使用该目录模板。记录设计基线与精确章节链接、代码事实、范围、T 编号任务/依赖、AC 验收、测试、风险和执行证据；不复制另一套设计。
- 设计调整必须更新权威设计正文、基线号和修订记录，并同步所有受影响的活跃计划。发现设计/计划/实现冲突时记录差异、暂停受影响工作并明确修订，不能静默由任一方覆盖。
- 重要设计或实施范围变化先取得用户对修订方案的确认再执行代码。用户授权编辑设计/计划，仅代表文档工作，不代表批准整套框架实现。
- 已完成计划保留当时设计基线和实际证据，新任务引用新的设计；不追改历史验收结果。新增/重命名文档同步 `docs/README.md`。

### 执行计划的交接标准

- 面向未参与前期讨论的执行者编写。计划、引用的权威设计与指定仓库文件必须足以完成实现，不依赖聊天历史或规划者未写出的判断。
- 不设固定页数。按行为复杂度细化，关键业务规则、模块职责、接口合同和核心算法必须在交接前确定；普通局部实现细节留给执行者。
- 记录各受影响仓库的分支、HEAD 和相关未提交变更；提供经过核实的文件路径、符号及阅读目的。拟新增的路径和符号明确标注，不编造现有实现。
- 使用 AC 编号表达可观察的验收结果。按独立行为和验证边界拆分 T 编号任务，写明依赖、仓库/文件/符号、具体改动、必要的逻辑或伪代码、必须保持的行为及完成证据；不要机械地按文件拆任务。
- 涉及跨模块合同、状态、事务、并发、重试或取消时，明确生产者与消费者、检查及写入条件、失败后的状态和恢复方式。不适用时简述原因，不为填模板增加能力。
- 提交确认前自检：每个 AC 都有实施任务和验证方法；每个任务都有明确入口；跨模块合同一致；关键失败路径和依赖已确定；不存在由执行者补做的重要设计决定。
- 执行前只核对计划依赖的代码事实和相关变更，避免重新设计已确认方案。局部细节自行处理；若代码事实冲突或必须改变核心行为，记录具体差异、暂停受影响工作、同步修订设计与计划并取得确认。不得静默改变方案或放宽验收。
- 实施前确定测试场景、输入和预期结果；仍在实现完成后集中编写和运行相关测试。区分“已实现”与“已验证”，只用实际证据标记验收完成。

## 5. Implement first, then test in one batch

- Add or update unit tests for every new or changed testable behavior.
- Finish all code for the current task before writing or updating tests.
- Do not run tests after every class, function, file, or individual test.
- Run only the test files, test cases, or filters directly related to the current change and its affected behavior.
- In a monorepo, test only affected packages or workspaces unless a narrower selection is available.
- Never run the complete project test suite unless the user explicitly requests it.
- If the relevant test scope cannot be determined confidently, ask the user before expanding it.
- Finish with applicable type checks, lint, and build.
- During failure repair, rerun only the related focused tests.
- Never delete tests, weaken assertions, disable relevant tests, or change a correct expectation merely to make tests pass.
- Do not use TDD unless the user explicitly requests test-first development.

Documentation, comments, formatting, static assets, and behavior-neutral configuration changes do not require new unit tests, but still require applicable checks. Report the exact tests run, why they were selected, and any related behavior not verified. When related tests cannot run, explain why and state the unverified scope.

## 6. Complete and report

- Compare the result with the confirmed change proposal or plan document.
- Report actual changes, test commands, real results, and remaining risks.
- For large changes, update the plan only at meaningful phase boundaries or when material findings change it.
- State anything not verified. Do not claim completion based on assumption.
