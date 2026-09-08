# EdgeAIDemo / EdgeAgent SDK 1.0

EdgeAIDemo 已从单会话 LiteRT-LM 示例升级为 Android 客户端 Agent SDK。SDK 采用“单 Agent 快速路径 + 确定性 Workflow + Supervisor/逻辑 Worker + 共享模型执行器 + Room 持久化任务闭环”，面向 Android 12+、ARM64 设备。

当前固定集成 Google ADK Kotlin `0.8.0` 与 LiteRT-LM `0.15.0`。ADK、LiteRT-LM、Room、OkHttp 等实现类型均不会暴露到 `edge-agent-api` 公共协议。

## 已实现能力

- `EdgeAgentClient`：提交、事件重放、Snapshot、审批/人工输入续跑、取消、检查点重试和释放。
- Durable Run：Room Event Journal、投影表、进程恢复、幂等键、非幂等副作用状态未知保护。
- 编排：单 Agent、Sequential/Parallel/Loop/HumanGate、Supervisor/Agent-as-Tool 逻辑 Worker、受控 Handoff。
- 模型：LiteRT-LM GPU→CPU 回退、双模型注册、全局本地串行 Broker、云端并发、模型失败回退。
- Tool Calling：手动执行、JSON Schema、能力白名单、参数修复、风险审批、结果截断与 Artifact。
- Skill：APK Asset/应用私有目录安装、SHA-256/可选签名、渐进加载、指令/声明式 Workflow/隔离 JavaScript，并统一映射为 `skill.*` Tool。
- MCP Client：Streamable HTTP、SSE 兼容、Session 重连、Schema 缓存、私密请求头和不可信内容边界。
- A2A Client：JSON-RPC `message/send` Remote Worker、隐私/预算约束、取消和本地 Worker 降级。
- 数据与安全：Session/长期记忆、FTS 与可选 Embedding、Artifact、Keystore Secret、Guardrail、脱敏 Trace。
- Demo：模型、对话、Agent、诊断四个页面；展示 Run 时间线、Worker 图、审批、人工输入、Artifact 和能力状态，支持可选前台服务承载与强制离线模式。

## 模块

```text
edge-agent-api             稳定公共协议与扩展 SPI
edge-agent-core            Run Coordinator、Router、Workflow、Supervisor、上下文与 Trace
edge-agent-adk             ADK 0.8.0 Runner/AgentTool/Workflow 适配
edge-agent-litertlm        LiteRT Engine Host、Model Adapter、双模型与诊断
edge-agent-capabilities    Tool、Skill、MCP、A2A、审批、Keystore、JS 沙箱
edge-agent-storage-room    Run/Event/Session/Memory/Artifact 与数据库迁移
edge-agent-cloud-openai    OpenAI-compatible 流式模型 Provider
edge-ai                    旧接口兼容门面与模型导入
app                        SDK 演示应用
```

Worker 是独立目标、上下文、权限和预算的逻辑子 Agent，不是 Android WorkManager Worker，也不持有 LiteRT Engine。多个 Worker 可以并行等待 I/O，但端侧模型调用始终由共享 Broker 串行执行。

## 最小接入

宿主先准备至少一个 `ModelProvider` 和根 Agent，再创建客户端：

```kotlin
val client = EdgeAgentSdk.create(
    applicationContext,
    AgentSdkConfig(
        rootAgent = AgentDefinition(
            id = AgentId("root"),
            name = "root_agent",
            description = "根 Agent",
            instructions = "先核验工具和 Worker 证据，再给出唯一最终答复。",
            preferredModelId = ModelId("gemma3-local"),
            capabilityAllowlist = setOf(CapabilityId("device.read_status")),
        ),
        modelProviders = listOf(localModelProvider),
        toolProviders = listOf(deviceToolProvider),
    ),
)

val handle = client.submit(
    AgentRequest(
        input = "检查设备状态并总结",
        modelPolicy = ModelPolicy.LOCAL_ONLY,
        privacyLevel = PrivacyLevel.LOCAL_ONLY,
    )
)
handle.events.collect { event -> render(event) }
```

`submit` 会先持久化 Run，再进入调度。重新进入页面可使用 `observe(runId)` 重放事件，使用 `snapshot(runId)` 恢复当前 Worker、审批、输入和 Artifact UI。HIGH 风险工具通过 `continueRun` 续跑：

```kotlin
client.continueRun(
    runId,
    UserContinuation.Approval(approvalId, ApprovalDecision.APPROVE_ONCE),
)
```

完整的配置、Skill/MCP/A2A、安全、迁移和公共接口说明见 [Agent SDK 1.0 集成手册](docs/agent-sdk-1.0.md)。

## 模型准备

Demo 可内置许可批准后的 Gemma `.litertlm`。构建者必须自行接受 Gemma 许可，再把已校验模型放到：

```text
app/src/main/assets/models/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm
```

本机验收文件为 584,417,280 bytes，SHA-256：

```text
1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be
```

模型被 `.gitignore` 排除，不得提交到仓库。首次启动会以 `.partial` + 原子移动安装到应用私有目录。

## 构建与验证

环境要求为 JDK 21、Android SDK 36；应用 `minSdk=31`、`targetSdk=34`，仅打包 `arm64-v8a`。

```bash
./gradlew verifyAgentApiBoundary testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew :app:verifyBundledGemmaModel :app:verifyLiteRtLmCoroutineAbi
openspec validate build-agent-sdk-v1 --strict
```

instrumentation APK 的编译不等于真机验收。发布前仍须在 Android 12+ ARM64 真机验证 GPU/CPU 回退、连续多任务、双模型内存压力、进程死亡、旋转、前后台切换、低内存回收、飞行模式、审批恢复和取消。

## 兼容与边界

- 旧 `EdgeAiRuntime.generate()` 在 1.x 保留并标记废弃，内部转发到新 Agent 路径。
- MCP 与 A2A 只实现 Client，不在 Android 上启动 stdio 子进程或对外提供 Server。
- `LOCAL_ONLY` 同时约束模型、MCP/A2A 和标记为 `local=false` 的 Tool；不会静默回退云端。
- 不支持任意 Shell、Root、未授权 Accessibility、公共路径 Skill 或未隔离动态代码。
- 长期记忆默认仅在本机，云同步和跨设备记忆不属于 1.0。
