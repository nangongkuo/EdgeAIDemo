## Why

EdgeAIDemo 当前只提供单模型、单会话的 LiteRT-LM 文本生成能力，无法承载可恢复任务、受控工具调用、Skill/MCP、长期记忆以及多 Agent 协作。需要在保留现有端侧推理稳定性和兼容入口的基础上，建设一套面向 Android 宿主应用、公共 API 稳定且具备完整任务闭环的 Agent SDK 1.0。

## What Changes

- 将 `LiteRtLmRuntime` 从对话编排器下沉为共享本地模型后端，增加独立的模型路由、资源租约与串行推理仲裁。
- 新增稳定的 `EdgeAgentClient` 公共 API、版本化事件和可序列化任务类型，同时保留旧 `EdgeAiRuntime` 兼容门面。
- 新增单 Agent 快速路径、确定性 Workflow、Supervisor/逻辑 Worker、Agent-as-Tool 和受控 Handoff。
- 新增基于 Room 事件日志的持久化 Run Queue、检查点、层级取消、幂等重试、审批暂停及进程恢复。
- 新增手动 Tool Calling、JSON Schema 校验、风险分级、人工审批、Skill、MCP Client、A2A Client 和受限 JavaScript 沙箱。
- 新增本地与 OpenAI-compatible 云端模型路由，默认由 FunctionGemma 规划/选工具、Gemma3 对话/汇总，并受隐私策略约束。
- 新增 Working/Session/Long-term Memory、Artifact Store、上下文压缩、追踪、指标和可回放事件。
- 演示应用迁移到新 SDK，展示任务时间线、Worker、审批、记忆、Artifact、能力连接和运行诊断。
- 明确不支持 Android 端 MCP/A2A Server、任意 Shell/Root、未授权 Accessibility、未隔离动态代码或每 Worker 独占 LiteRT Engine。

## Capabilities

### New Capabilities

- `agent-sdk-api`: SDK 初始化、任务提交、事件观察、快照、继续、取消、重试和旧接口兼容契约。
- `agent-orchestration`: 请求路由、单 Agent、Workflow、Supervisor、逻辑 Worker、Agent-as-Tool 与 Handoff 行为。
- `durable-run-lifecycle`: Room Run Queue、事件日志、检查点、幂等副作用、任务恢复和层级取消。
- `model-execution-routing`: LiteRT-LM Engine 生命周期、共享推理 Broker、FunctionGemma/Gemma3 路由及云端 Provider。
- `agent-capability-runtime`: Tool Calling、风险审批、Skill、MCP Client、A2A Client 和受限脚本执行。
- `context-memory-artifacts`: 上下文预算、历史压缩、长期记忆、Artifact 持久化和 Worker 数据隔离。
- `agent-safety-observability`: Guardrail、隐私边界、敏感数据处理、Trace、指标、回放和诊断。

### Modified Capabilities

无。现有 `multilingual-chat-policy` 的语言与字面量要求继续生效，但本 change 不改变其对外行为。

## Impact

- 将当前双模块 Demo 演进为公共 API、核心编排、ADK 适配、LiteRT-LM、能力、Room、云端 Provider 和示例应用组成的模块化 SDK。
- 引入 Google ADK Kotlin 0.8.0、Room、网络/序列化、Keystore、MCP/A2A 和受限脚本运行所需依赖，并将构建 Toolchain 升级至 JDK 21。
- 重构 `LiteRtLmRuntime` 和 `EdgeAiViewModel`，但保留当前模型导入、GPU 到 CPU 回退、生成诊断、语义复述防护和旧运行时调用方式。
- 新数据默认保存在应用私有目录；云端调用只有在模型策略、隐私策略和授权均允许时发生。
