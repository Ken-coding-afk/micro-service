# examples：可运行的业务示例与新项目复制起点

> 状态：T-01 仅建立本说明。各示例源码按实施计划交付：
> 文档示例 `java/document-service`、`python/analysis-service` 随 T-06/T-12B；
> 聊天示例 `python/agent-service` 随 T-08—T-10/T-12C；前端演示 `web` 随 T-09/T-12。

## 项目组合选择

| 目标项目 | 复制内容 | 说明 |
| --- | --- | --- |
| 全栈文档分析（doc-insight） | `java/document-service`、`python/analysis-service`、`web`、`config`、`infra`、`deploy` | Java 管领域数据，Python 管分析；完整消息与异步任务链路 |
| 纯 Python 聊天（chat-assistant） | `python/agent-service`、`web`、`config`、`infra`、`deploy` | 同镜像 API/Worker 双角色、SSE 与恢复 |
| 仅 Java 业务 | `java/document-service` 为模板改造 | 去除 Python 服务与 AI 依赖组合 |

复制后按《新项目接入指南》逐步执行：项目身份 → 环境与秘密 → 组件与资源 → 迁移与身份初始化 → 权限与客户端登记 → 业务开发。

## 约束

- examples 内各服务独立构建，从制品仓库解析 `platform-parent`/`foundation`/`ken-platform-sdk`，不依赖框架源码相对路径（设计 01·规范约束 7）。
- 不复制 foundation、identity-service、api-gateway、python-sdk 源码；基础服务使用发行镜像。
