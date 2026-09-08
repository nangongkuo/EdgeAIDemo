## Context

EdgeAIDemo 当前以 `app` 和 `edge-ai` 两个 Android 模块提供单模型、单 `Conversation` 的离线聊天。`LiteRtLmRuntime` 同时持有模型生命周期、会话历史、生成状态和单线程 JNI dispatcher；并发生成会被直接拒绝。这个结构适合验证 LiteRT-LM，但不能表达持久化 Run、逻辑 Worker、人工审批、工具结果、恢复或跨本地/云端模型路由。

Agent SDK 1.0 面向 Android 12+ ARM64 宿主应用。端侧原生推理仍须串行并显式按 Conversation→Engine 顺序释放；模型和数据继续位于应用私有目录。Google ADK Kotlin 0.8.0 仍为 Pre-GA，因此只能作为内部执行内核，不能成为公共 API 或持久化协议。

## Goals / Non-Goals

**Goals:**

- 交付稳定、可复用且不泄漏 ADK/LiteRT-LM 类型的 Android Agent SDK API。
- 同时支持单 Agent 快速路径、确定性 Workflow、Supervisor/Worker、Agent-as-Tool 和受控 Handoff。
- 以持久化事件日志完成提交、暂停、审批、恢复、取消、重试和终态闭环。
- 统一 Tool、Skill、MCP、A2A、Memory、Artifact、Guardrail 和 Trace。
- 共享并仲裁本地模型资源，保留现有模型校验、GPU 回退、诊断和回复质量策略。
- 支持 FunctionGemma 规划/工具选择、Gemma3 对话/汇总以及受隐私策略控制的云端模型。

**Non-Goals:**

- 不提供 MCP Server 或 A2A Server。
- 不把 Android WorkManager 当作 Agent Worker；后台承载仅作为可选宿主适配器。
- 不允许任意 Shell、Root、未授权 Accessibility 或未隔离动态代码。
- 不为每个 Worker 创建一份 LiteRT Engine，也不承诺本地模型物理并行。
- 不在 1.0 提供跨设备同步或云端长期记忆。

## Decisions

### 1. 分离 Agent 编排、任务调度和模型推理

系统分为公共 API、Durable Run Coordinator、Agent Engine、Capability Plane、Model Plane 和 Data Plane。`LiteRtLmRuntime` 拆成 `LiteRtEngineHost` 与 `EdgeLiteRtModelAdapter`，只负责原生资源和模型请求；Run Queue 和 Worker 生命周期由上层管理。

选择该分层是因为 Worker 是逻辑执行单元，而 Engine 是稀缺硬件资源。备选“每 Worker 一个 Runtime”会复制约数百 MB 模型权重、争用 GPU 并扩大 JNI 风险，因此拒绝。

### 2. 使用 ADK Kotlin 作为内部 Agent Engine

新增 `AgentEngine` SPI，首个实现 `AdkAgentEngine` 使用固定版本的 ADK Runner、LlmAgent、AgentTool、Sequential/Parallel/Loop Agent 和事件循环。所有 ADK 事件立即转换为项目自有 `AgentEvent`；Room 不保存 ADK 私有类型。

相比从零实现完整 ReAct/Workflow/Transfer，ADK 能提供与 Google Android Agent 方向一致的执行语义。相比直接对外暴露 ADK，内部适配能隔离 Pre-GA API 变化，并允许通过契约测试替换执行内核。

### 3. 单 Agent 快速路径与 Supervisor/Worker 共存

`RequestRouter` 先执行确定性分类：普通对话、摘要和单工具请求进入单 Agent；显式工作流进入代码定义的 Workflow；只有多目标、跨能力、可独立拆分或要求验证的任务进入 Supervisor。

Supervisor 通过 FunctionGemma 生成类型化 `ExecutionPlan`，代码验证节点类型、依赖、预算、能力和环路后才创建 WorkerRun。Worker 采用独立 Session Branch，只接收任务摘要、相关记忆和能力白名单。Manager/Agent-as-Tool 为默认委派；Handoff 仅用于交互式专业 Agent 接管。

### 4. Room 事件日志是任务真相源

`RunStore` 持久化 Run、Worker、Step、Event、ToolCall、Approval、Checkpoint、Artifact 和 Memory。写入事件和对应 Snapshot 更新必须位于同一事务。Scheduler 使用单 Coroutine Actor 串行处理调度状态变更；独立执行 Job 只通过命令和事件回写状态。

进程启动时把非终态 `RUNNING/PLANNING` 转为 `RECOVERING`。模型步骤和幂等读取可重放；外部写入若没有确定结果则进入 `WAITING_USER_INPUT`。这比仅保存在 ViewModel/Conversation 中更能形成移动端任务闭环。

### 5. 模型 Broker 统一控制本地与云端资源

`ModelRouter` 根据模型能力、`ModelPolicy`、隐私、网络和失败类型选择 Provider。`ModelExecutionBroker` 为每个本地 Engine 和全局本地推理各配置一个许可，默认全局并发为 1；远程与 I/O 默认并发为 3。

低内存设备最多驻留一个模型；其他设备可驻留 FunctionGemma 和 Gemma3 各一个，但仍串行推理。Conversation 仅是可丢弃 KV/cache 优化，完整请求从持久化 Session/Event 重建。内存压力只卸载无租约 Engine。

### 6. Tool Calling 在 Agent 层手动执行

LiteRT-LM `automaticToolCalling` 固定为 `false`。模型产生的 ToolCall 先进入统一 Registry，经过 JSON Schema、能力范围、Guardrail、风险判级和 Approval，再执行并持久化 ToolResult，然后回传模型。

LOW 自动执行；MEDIUM 首次按会话和能力授权；HIGH 每次确认；BLOCKED 直接拒绝。非幂等工具不自动重试，所有副作用使用 `runId/stepId/callId` 幂等键。该选择牺牲 LiteRT-LM 内置自动循环的简洁性，以换取安全、审计与恢复。

### 7. Skill、MCP、A2A 统一映射为能力

Skill 使用版本化 Manifest、`SKILL.md`、资源、哈希/签名和能力声明。元数据先加载，选中后再加载正文。内置后端为纯指令、声明式 Workflow、受限 JavaScript；沙箱默认无文件、网络或系统 API。

MCP Client 使用 Streamable HTTP，SSE 仅兼容，不启动 stdio 进程。A2A Client 把远程 Agent 映射为 `RemoteWorkerProvider`。两者返回均标记为不可信外部数据，且不能绕过本地策略、审批或隐私分类。

### 8. Context、Memory 与 Artifact 分离

ContextAssembler 依次分配系统规则、Worker 目标、相关长期记忆、当前分支事件、工具摘要和压缩历史。超出预算的大结果进入 Artifact Store，模型只接收摘要与引用。

长期记忆默认本地启用，使用 Room FTS、时间和重要性混合检索；语义向量由可选 Embedding Provider 提供。Worker 只提出记忆候选，由父 Supervisor 去重和提交。凭证、认证头、审批内容和原始敏感工具响应禁止进入长期记忆。

### 9. 模块和所有权

- `edge-agent-api`：公共类型、客户端 API 和扩展 SPI，无 Android UI、ADK 或 LiteRT 依赖。
- `edge-agent-core`：Coordinator、Router、Scheduler、策略、上下文和项目自有事件。
- `edge-agent-adk`：ADK Runner 与项目协议桥接。
- `edge-agent-litertlm`：Engine Host、Model Adapter、Broker、模型存储和诊断。
- `edge-agent-capabilities`：Tool、Skill、MCP、A2A、Approval 和脚本沙箱。
- `edge-agent-storage-room`：Room 数据库、Session/Memory/Artifact 和恢复。
- `edge-agent-cloud-openai`：OpenAI-compatible 模型 Provider。
- `app`：示例 UI，只依赖公共 SDK 门面。

项目使用 JDK 21 Toolchain；Android 最低 API 31、ARM64 和应用私有模型目录保持不变。LiteRT Engine/Conversation 的创建、调用和释放继续由独占 dispatcher 串行化。

## Risks / Trade-offs

- [ADK Kotlin Pre-GA API 变化] → 固定 0.8.0、隔离在适配模块、增加事件/Tool/Transfer 契约测试并禁止公共类型泄漏。
- [一次性交付范围大] → 按依赖顺序实现，每阶段保持全工程可编译，但 1.0 发布门禁要求所有能力完成。
- [双模型导致内存压力] → 自适应驻留、全局单推理许可、LRU 卸载和低内存设备单模型策略。
- [进程死亡导致副作用重复] → 事务事件日志、幂等键、未知写入不自动恢复。
- [小模型产生非法计划或参数] → 类型化输出、代码验证、最多两次修复、步骤/深度/循环限制。
- [外部内容提示注入] → 不可信数据标记、指令/数据隔离、能力白名单和输出净化。
- [Room/Event 数量增长] → Snapshot、分页、压缩和保留策略；Artifact 与正文分离。
- [旧调用方迁移成本] → 保留 `EdgeAiRuntime` 1.x 兼容门面，新能力仅通过 `EdgeAgentClient` 暴露。

## Migration Plan

1. 建立新模块、公共协议、RunStore 和兼容门面，不改变现有 UI 行为。
2. 抽取 LiteRT Engine Host、Model Adapter 和 Broker；通过旧聊天回归测试后切换兼容门面。
3. 接入 ADK AgentEngine 和手动 Tool Calling，再增加 Workflow/Supervisor/Worker。
4. 接入能力、审批、Memory、Artifact、恢复、Trace 和云端 Provider。
5. 将 Demo UI 切换到 `EdgeAgentClient`，保留模型导入和诊断页面并增加 Agent 状态展示。
6. 完成 JDK 21、混淆、ABI、故障注入和 ARM64 真机验收后发布 1.0。

回滚时 Demo 可切回兼容门面；新 Room 表和应用私有数据保留但不被旧路径读取。任何已执行外部副作用不回滚，只保留审计记录。

## Open Questions

无。公共边界、执行语义、默认并发、风险等级、模型策略和 1.0 非目标均已确定。
