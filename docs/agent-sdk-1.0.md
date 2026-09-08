# EdgeAgent SDK 1.0 集成手册

## 一、运行架构

请求先写入 Room Run/Event Journal，再由进程内 Coroutine Actor 调度。`RequestRouter` 选择单 Agent、固定 Workflow 或 Supervisor；逻辑 Worker 只持有目标、分支上下文、能力白名单和预算。模型请求进入 `ModelExecutionBroker`，本地全局并发固定为 1，网络/I/O 默认并发为 3。

```text
Host UI / Voice / Notification / Foreground Host
                    │
              EdgeAgentClient
                    │
   RunScheduler + Event Journal + Recovery
                    │
 Single Agent ─ Workflow ─ Supervisor/Worker
          │                         │
 Tool / Skill / MCP / A2A      ModelRouter/Broker
          └──────────┬──────────────┘
          Room / Memory / Artifact / Trace
```

ADK 0.8.0 位于内部适配层。无 Tool 的快速路径可交给 ADK Runner；需要项目级审批、恢复、Supervisor 或 Workflow 语义时，由适配器委托给项目自有 `ProviderAgentEngine`。公共事件和数据库从不保存 ADK 类型。

## 二、公共接口与恢复

- `EdgeAgentSdk.create(context, config)`：创建 Room、能力注册表、模型 Broker 与 Scheduler。
- `submit(request)`：持久化后执行，返回 `RunHandle`。
- `observe(runId)`：按单调递增 sequence 重放并继续观察事件。
- `snapshot(runId)`：读取 Run、Worker、待审批、待输入、Artifact 与终态。
- `continueRun(...)`：提交审批或 HumanGate 输入。
- `cancel(runId)`：向 Worker、远程 Agent、工具和模型传播取消。
- `retry(runId, fromStepId)`：仅允许终态、暂停或等待输入的 Run 重试。
- `close()`：停止调度、关闭 Engine/Provider/Tool 与数据库，不删除持久化数据。

`AgentRequest.modelPolicy` 是 Run 级策略。`LOCAL_ONLY/CLOUD_ONLY` 是硬约束；Agent 定义中的硬约束也不会被 Run 突破。`PREFER_*` 允许在健康检查或生成失败后回退。

UI 应以 Event 为增量、Snapshot 为重建入口。关键事件包括 Run/Route/Plan/Worker/Handoff、ModelToken/Reset、Tool/Approval、UserInput、Artifact/Memory 和 Terminal。

进程重启后，幂等读取和模型步骤自动进入 `RECOVERING`；等待审批与输入的请求原样保留；已开始但结果未知的非幂等写操作不会自动重放。Tool Call 使用稳定的本地幂等 ID，云端 Provider 的 `tool_call_id` 单独保留用于协议往返。

## 三、Tool Calling 与审批

每个 Tool 用 `ToolDescriptor` 声明命名空间、版本、JSON Schema、风险、幂等性和 `local` 属性。所有调用依次经过发现、白名单、Schema、离线策略、风险审批、执行、输出 Schema、Artifact 外置和持久化。

- `LOW`：本地只读，自动执行。
- `MEDIUM`：敏感读取或远程出站，首次按 Session/Capability 授权。
- `HIGH`：写入、删除、支付、发消息或系统设置，每次确认。
- `BLOCKED`：直接拒绝。

远程 Tool 必须设置 `local=false`。这样 `LOCAL_ONLY` Run 会在审批和网络请求前被拒绝。非幂等 Tool 不会自动重试。

## 四、Skill

Skill 包必须包含 `manifest.json`、入口文件（默认 `SKILL.md`）和 Manifest 声明的资源。完整性哈希按“排序后的相对路径 + 文件内容”计算 SHA-256；存在 `signature` 时必须配置 `SkillSignatureVerifier`。

```kotlin
AgentSdkConfig(
    rootAgent = root,
    modelProviders = providers,
    skillSources = listOf(
        SkillSource.Asset("agent-skills/report"),
        SkillSource.PrivateDirectory(stagedSkill.absolutePath),
    ),
    skillSignatureVerifier = verifier,
)
```

私有目录来源必须位于 `filesDir`、`cacheDir` 或 `noBackupFilesDir`。列表阶段只读 Manifest；模型发现到的是 `skill.<id>` 能力，真正调用时才加载并再次校验入口与资源。

- `INSTRUCTION`：把受 Guardrail 处理后的说明作为工具结果返回模型。
- `DECLARATIVE_WORKFLOW`：逐步调用 Manifest 白名单内的 Tool；内部 HIGH Tool 仍触发原审批。1.0 禁止 Skill 递归嵌套。
- `JAVASCRIPT_SANDBOX`：运行在 Android `isolatedProcess`，Rhino 解释模式、ClassShutter 禁止 Java 访问，并限制时间、指令、输入、输出和堆增量。文件、网络和系统 API 默认不可见。

重复安装相同版本和哈希是幂等的；同 ID 不同版本要求宿主显式迁移，避免静默覆盖正在恢复的任务。

## 五、MCP 与 A2A

### MCP Client

```kotlin
secretStore.put("mcp.crm.headers", "Authorization: Bearer …")

AgentSdkConfig(
    rootAgent = root,
    modelProviders = providers,
    secretStore = secretStore,
    mcpServers = listOf(
        McpServerConfig(
            id = "crm",
            endpoint = "https://agent.example.com/mcp",
            headersSecretId = "mcp.crm.headers",
        )
    ),
)
```

远程工具映射为 `mcp.<serverId>.<toolName>`，默认 `MEDIUM`、非幂等、`local=false`。Client 支持 JSON-RPC initialize、Streamable HTTP、SSE 响应、Session ID、过期重连、发现缓存和 Schema 原子替换。OAuth 刷新由宿主完成，SDK 只从 `SecretStore` 读取最终请求头，不把 Token 写入 Event、Memory 或 Trace。

### A2A Client

`A2aAgentConfig.endpoint` 指向远程 Agent 的 HTTPS JSON-RPC 端点。SDK 发送 A2A `message/send`，携带逻辑 Worker 目标、输入、能力范围、步数和隐私元数据；返回内容始终标记为不可信。远程失败、超时或非法内容会生成可恢复结果，并由 Supervisor 降级到同定义的普通 Worker。`LOCAL_ONLY` 在任何网络调用前拒绝 A2A。

SDK 1.0 不提供 MCP Server、A2A Server，也不启动任意 stdio 子进程。

## 六、本地与云端模型

`LiteRtEngineHost` 是唯一持有 Engine/Conversation 的组件。Conversation 每次请求可重建，Event/Session 才是上下文真相源。`LocalModelRegistration` 最多注册两个模型：Function Calling/规划模型和通用生成模型；低内存设备只驻留一个，其他设备最多驻留两个，但推理仍串行。

OpenAI-compatible Provider 要求 HTTPS 和 Keystore/自定义 `SecretStore` 中的密钥，支持 SSE、非流式响应及标准 Tool Call ID 往返。云端发送前会执行隐私 Guardrail 和凭证脱敏。Provider 失败后只有在 Run/Agent 策略允许时才选择下一候选。

Demo 默认启用“强制离线”，同时设置 `ModelPolicy.LOCAL_ONLY` 与 `PrivacyLevel.LOCAL_ONLY`。

## 七、记忆、Artifact 与上下文

上下文优先级固定为安全规则、Agent/Worker 目标、相关长期记忆、当前分支事件、工具摘要/Artifact 引用和压缩历史。Worker 不获得兄弟分支完整历史。

Room Memory 支持 Session 隔离、FTS/中文 LIKE 回退、置信度/重要性/时间排序、去重、TTL、禁用、删除和可选 Embedding 重排。密码、Token、认证头、私钥和 JWT 会在写入前拒绝。远程 Worker 只能提出 MemoryCandidate，由本地 Supervisor 经过 `MEMORY_WRITE` Guardrail 后提交。

大型 Tool 结果写入应用私有 Artifact Store；Event 和 Snapshot 只保存 `ArtifactRef`、哈希与有界摘要，恢复后仍可读取。

## 八、安全与可观测性

- User Input、Model Output、Tool Arguments/Result、Skill、MCP、A2A、Memory Write 和 Cloud Egress 分阶段 Guardrail。
- 网页、MCP、云模型和远程 Agent 内容保持 `UNTRUSTED_EXTERNAL`，提示词明确要求只能作为数据。
- Android Keystore 使用 AES-GCM；Room、日志和长期记忆不保存明文凭证。
- Trace 记录 Run/sequence/category/name/耗时/错误码等结构化元数据，默认不记录 Prompt、Tool 参数和结果正文。
- Artifact 与 Skill 位于应用私有目录；路径规范化和符号链接检查阻止越界。

## 九、Demo 与前台承载

`EdgeAiViewModel` 只消费 `EdgeAgentClient`，同时保留旧模型导入、GPU/CPU 选择和诊断。Agent 页展示 Run 状态、Worker 图、审批参数预览、HumanGate 输入、Artifact、能力状态和事件时间线。

`AgentForegroundService` 只用于让同一进程中的 Scheduler 在用户离开页面时继续运行。它不创建 Agent Worker、不持有模型，也不改变 Event Journal/恢复语义。宿主必须遵守 Android 前台服务启动限制并展示持续通知。

## 十、从旧接口迁移

1. 保留原模型文件和 `ModelStore`；将新代码的生成入口改为 `EdgeAgentClient.submit`。
2. 将逐 Token UI 改为消费 `AgentEvent.ModelToken/ModelReset/Terminal`。
3. 保存 `runId`，页面重建时先读 Snapshot，再订阅 `observe(runId)`。
4. 把外部操作迁移为 `ToolProvider`，明确 Schema、风险、幂等性和本地/远程属性。
5. 需要后台承载时接入前台服务，但不要把业务 Worker 改成 WorkManager Worker。
6. 旧 `EdgeAiRuntime` 可在 1.x 继续使用；不要再向它增加 Tool、Memory 或编排职责。

## 十一、发布验收

```bash
./gradlew verifyAgentApiBoundary testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
openspec validate build-agent-sdk-v1 --strict
```

真机门禁：Android 12+ ARM64 上验证模型校验、GPU→CPU 回退、双模型切换与内存压力、连续多任务、进程死亡恢复、HIGH 审批只执行一次、非幂等未知状态、取消、旋转、前后台、飞行模式和低内存回收。没有连接设备时只能完成 instrumentation 编译，不能把真机项标记为通过。
