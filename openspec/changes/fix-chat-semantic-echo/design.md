## Context

当前链路为 `ChatFragment -> EdgeAiViewModel -> LiteRtLmRuntime -> LiteRT-LM Conversation -> GenerationEvent.Delta -> RecyclerView`。用户文本已经原样传给 `sendMessageAsync()`，UI 也只追加 SDK 返回的模型内容，因此截图不是客户端直接复制；但 Gemma 3 1B IT q4 在 API 37 `ranchu` 模拟器 CPU 上生成了与问题高度相似的礼貌反问。第三轮字符序列相似度为 0.842，且同一 Conversation 中首轮生成的重复“您好”持续出现在后续回答。

根因置信度为“很可能”：1B 量化模型对缺少能力定义和禁止复述约束的系统指令遵循不稳定，低温度固定种子使错误模式可重复，而原生 Conversation 会把坏模型轮次保留为后续上下文。当前生产代码没有生成日志；LiteRT-LM 0.15.0 的 `ConversationConfig.thinkingConfig` 实际为 `null`，应用又通过 `Message.toString()` 只读取正文，无法从日志判断消息角色、通道或显式思考状态。

约束如下：用户当前轮次必须继续原样作为完整 USER 负载；JNI 调用必须留在单一 dispatcher；Conversation 替换必须先关闭旧实例；模型、网络权限、存储和 GPU 回退不在此次变更范围。现有工作树中的 OpenSpec 改动属于用户，不能重写。

## Goals / Non-Goals

**Goals:**

- 让模型优先直接回答能力和普通问题，降低问题复述、反问和局部重复。
- 自动识别截图所示高相似度回声，重试时清除坏原生历史并保留此前已接受的对话。
- 对显式 thinking、输入、SDK 消息、重试和 Benchmark 建立可核验的 Debug 证据。
- 以确定性单元测试覆盖策略、相似度边界、历史恢复与 UI 替换，并在当前模拟器 CPU 上复现验证。

**Non-Goals:**

- 不把任意能力问题改造成客户端硬编码回答，也不伪造模型回复。
- 不承诺 1B int4 模型具有大模型级事实、推理或中文质量。
- 不展示或声称存在隐藏思维链；Gemma 3 1B 的显式 thinking 固定关闭。
- 不添加云端兜底、网络权限、工具调用、模型升级或生产遥测。

## Decisions

### 系统指令承担回答策略，USER 负载保持原样

扩充 `SYSTEM_INSTRUCTION`，明确应用只能离线文字问答、不能控制手机/联网/读取其他应用，并要求先回答、不得复述或反问问题。通过 `initialMessages` 提供一组能力问答示例，帮助小模型稳定角色。当前用户输入仍完整、未经装饰地传给 SDK。

拒绝把用户问题包进 XML、标签或二层指令；该做法此前已确认会让 1B 模型把问题视为待释义文本。

### 轻量重复抑制

每次发送使用 `RepetitionPenaltyConfig(repetitionPenalty = 1.1f, windowSize = 64)` 与 `NoRepeatNgramConfig(noRepeatNgramSize = 3, windowSize = 64)`。其目标是抑制“您好，您好”等局部重复，不负责判断语义回声。

### 以字符二元组 Dice 系数检测近似回声

新增纯 Kotlin 质量策略：统一大小写、删除空白和标点、剥离重复问候，然后计算字符二元组 Dice 系数。仅当问题不少于 6 个规范化字符、回答长度没有明显超过问题，且满足“包含关系”或相似度不低于 0.72 时判定回声。该算法可确定测试，不依赖网络或额外模型。

拒绝仅比较字符串相等；截图中的人称变化和语气词会绕过。也拒绝在客户端尝试语义生成，因为这会产生另一个不可审计回答源。

### 坏回答触发干净 Conversation 重试

运行时维护仅由已通过质量检查的 USER/MODEL 轮次组成的 `acceptedHistory`。首轮生成仍流式发送给 UI；若完成后判定回声，先发出 `GenerationEvent.Reset` 清空当前助手气泡，关闭受污染 Conversation，以系统指令、示例和 `acceptedHistory` 重建实例，再对同一原始 prompt 重试一次。

重试成功后才把该轮写入 `acceptedHistory`。重试仍为回声时再次重建干净 Conversation，并返回可恢复质量错误。`resetConversation()`、重新初始化、卸载都会清空已接受历史。所有创建、关闭、生成和重建均在既有单线程 dispatcher 上执行。

### 显式关闭 thinking 并记录 SDK 消息

Conversation 配置显式使用 `ThinkingConfig(enableThinking = false)`。Debug 构建使用固定日志标签记录请求 ID、模型 SHA、后端、采样/抑制/thinking 配置、原始提示词与回答的受限预览、每个 `Message.role`、`channels.keys`、尝试次数、回声分数、首 Token 时间、Prefill/Decode/会话 Token 和完成/失败状态；Release 构建不输出对话正文。

`RuntimeDiagnostics` 增加最近请求 ID、尝试次数、回声拒绝状态和显式 thinking 状态，诊断页展示这些非正文指标。

## Risks / Trade-offs

- [阈值误判正常短回答] → 同时限制最小问题长度、回答长度和包含关系，并覆盖边界语料；用户可继续换一种问法。
- [首次坏回答短暂可见] → 完成检测后立即发送 Reset；避免缓冲所有 Token 而破坏正常流式体验。
- [重建历史占用上下文] → 仅在回声发生时重建，复用已接受的原生 USER/MODEL 内容；模型上下文上限仍由 LiteRT-LM 管理。
- [重复抑制损伤必要重复] → 使用温和 penalty 和三元组窗口，并覆盖代码/URL 字面保留用例。
- [不同 CPU/GPU 输出仍可能不同] → 以质量契约而非固定句子验收；当前模拟器 CPU 必测，真机 GPU 仍是发布前必测项。
- [Debug 日志含用户文本] → 仅 Debug 输出受限长度预览，Release 只记录长度、哈希和非正文指标。

## Migration Plan

无需数据迁移。安装新包会创建带新策略的 Conversation；模型文件和 SharedPreferences 保持不变。回滚时恢复运行时、策略、事件及诊断字段即可，不涉及用户文件。

## Test Matrix

| ID | 层级 | 必需 | 前置条件 / 命令或步骤 | 预期结果 | 证据 |
|---|---|---:|---|---|---|
| ECHO-UNIT-01 | 单元 | 是 | `./gradlew :edge-ai:testDebugUnitTest` | 截图两组问答命中回声；真实解释、短输入和字面内容边界不误判 | Gradle XML/输出 |
| ECHO-UNIT-02 | 单元 | 是 | 同上 | 系统指令、示例、thinking=false、重复抑制和原始 USER 负载保持固定 | Gradle XML/输出 |
| ECHO-APP-01 | 单元 | 是 | `./gradlew :app:testDebugUnitTest` | Reset 清除已流出的坏回答；重试成功显示新回答；失败不残留回声 | Gradle XML/输出 |
| ECHO-BUILD-01 | 构建/lint | 是 | `./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | 编译和 lint 通过，无新增依赖或 INTERNET 权限 | Gradle/APK 分析输出 |
| ECHO-CPU-01 | 模拟器设备 | 是 | 在 `emulator-5554` 安装调试 APK，依次运行 `hi`、`你能在这台手机上做什么`、`我想先看看你的能力再决定做什么，你告诉我你能做什么` | 三轮完成且最终助手回答不是高相似度反问；进程存活；日志显示 thinking=false、尝试和质量判定 | `build/bugfix-reports/fix-chat-semantic-echo/` |
| ECHO-GPU-01 | 真机设备 | 是 | Android 12+ ARM64 真机 GPU 运行相同三轮语料 | 与 CPU 使用同一质量契约，未发生崩溃或错误回显 | 同上；无真机时保持未完成 |

## Open Questions

无。模型升级作为后续独立变更；如果当前 1B 模型在干净重试后仍持续不合格，本变更会返回明确错误并保持真机验收未完成，而不是静默展示伪回答。
