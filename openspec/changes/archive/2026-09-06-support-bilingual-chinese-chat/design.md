## 背景

`edge-ai` 负责 LiteRT-LM 的 `Engine` 和 `Conversation` 创建；`app` 负责 XML 对话 UI 及其 Activity 作用域的 ViewModel。当前对话配置只包含英文系统指令，并以 0.8 的 temperature 采样；ViewModel 在转发用户文本前还会执行裁剪。两种行为都不能建立英文、中文或混合语言请求所需的中文优先契约。

模型是本地内置的 Gemma3-1B-IT int4 `.litertlm` 资源。它必须保持离线、仅 ARM64，并通过 `EdgeAiRuntime` 与 UI 代码隔离。所有原生调用继续运行在运行时单一 dispatcher 上，既有 Engine/Conversation 生命周期和 GPU 到 CPU 回退保持不变。

## 目标与非目标

**目标：**

- 建立一项确定性的对话策略：接受原始混合语言文本，默认用简体中文回答。
- 保留字面技术内容和引用文本，同时让用户明确请求的目标语言优先。
- 在启动初始化、手动初始化和每个重置后的 Conversation 中应用同一策略。
- 添加 UI 说明和回归覆盖，然后在已连接 Android 12+ ARM64 硬件上验证真实离线推理。

**非目标：**

- 不引入云端翻译、网络语言检测、第三方语言 SDK 或随区域设置进行的改写。
- 不引入用户语言选择器、模型替换、分词器转换、微调，也不改变 GPU 回退和模型存储。
- 不宣称 1B int4 模型具备生产级翻译质量；验收测试仅验证语言行为和流式稳定性。

## 设计决策

### `edge-ai` 中静态的中文优先策略

在 `edge-ai` 引入项目自有的 `ChatLanguagePolicy`。它持有 Unicode 安全的系统指令字符串和低方差采样器配置。创建 `ConversationConfig` 时由 `LiteRtLmRuntime` 使用；app 模块从不构造提示词，也不导入 LiteRT-LM 类。

该策略指示模型理解中文、英文和混合句子，默认使用简体中文回答；对代码、命令、URL、文件名、产品名、缩写、引用文本和请求术语保留源拼写；并遵循明确指定的目标语言或翻译请求。ViewModel 和 `EdgeAiRuntime.generate()` 原样传递原始字符串，`LiteRtLmRuntime` 则将精确字符串直接传给 `Conversation.sendMessageAsync()`。

### 直接回答回归修复

**根因：已确认。** 在已连接的 ARM64 设备上，已完成的助手消息 `你问我呢？`、`你能做什么呢？` 和 `你是什么模型？` 分别复现了相应用户消息的可见症状。UI 层级同样捕获到彼此不同的用户/助手气泡。 `EdgeAiViewModel.sendPrompt()` 与 `Conversation.sendMessageAsync()` 之间唯一的生产转换是 `ChatLanguagePolicy.wrapPrompt(prompt)`；它会使用包含 `<user_prompt>…</user_prompt>` 的多段指令替换模型的用户轮次。这使 Gemma 3 1B 将用户问题作为嵌套文本而非直接对话轮次处理，从而释义该嵌套文本。没有 UI 适配器或 ViewModel 路径会将用户消息写成助手文本。

修复会完全移除面向模型的信封。 `ConversationConfig.systemInstruction` 保持唯一策略承载者，原始非空提示词成为完整的 `sendMessageAsync()` 参数。这将恢复 LiteRT-LM 的角色模板，并阻止应用创建易回显的嵌套任务。有意不引入客户端答案合成、语言检测、翻译或模型替换。

当前 1B 模型已观察到的“重置后未能对每一条英文提示词用中文回答”独立于此回归，仍是未完成验收项。移除信封优先保证直接回答，而不是试图通过第二条用户轮次指令覆盖该模型限制。

### 确定性的字面内容保留回退

即使以中文回答，1B 模型也可能遗漏用户明确要求的 URL。收集流式增量时，运行时会保留已生成文本的本地副本。完成后，它从未经修改的用户提示词中提取 URL、内联代码、常见文件名、全大写缩写和常见 shell 命令片段；仅将模型输出中缺失的片段追加到 `原文保留` 后缀。这是本地确定性的保留，不是翻译，也不改写用户消息或模型解释。

### 原始提示词转发

`EdgeAiViewModel` SHALL 仅用 `isBlank()` 拒绝全空白输入，对用户消息保留原始非空字符串，并将精确字符串传给 `EdgeAiRuntime.generate()`。这移除既有 `trim()` 改写，同时保留空输入处理。

### 可复现的采样

使用既有 topK 10、topP 0.95、`temperature = 0.2` 和 `seed = 0`。降低 temperature 可让内置 1B 量化模型更少随机地遵循语言策略；512 Token 输出上限保持不变。

### 生命周期与 UI

`defaultConversationConfig()` 仍是唯一创建路径，因此其策略会在自动启动、手动重新加载、后端回退重试和 `resetConversation()` 中应用。每次 `generate()` 调用均将原始提示词直接提供给 LiteRT-LM。对话页添加不可交互的状态说明：接受中文、英文和混合输入，回答默认简体中文。不持久化语言设置。

## 风险与权衡

- **1B int4 模型仍可能忽略指令** → 在 ARM64 真机上验证固定语料；若语言契约失败，则不完成该变更。
- **降低 temperature 会减少创意多样性** → 此验证示例优先选择确定性的语言行为而非创造力。
- **字面片段保留对应概率性模型行为** → 将验收限定为代表性代码、URL、文件名、缩写和引用文本用例；不进行不安全的客户端转换。
- **策略变更可能遗漏于新 Conversation** → 保留一个配置工厂，并通过测试和设备验收覆盖初始化/重置路径。
- **模型即使被明确指示仍会遗漏字面内容** → 流式完成后仅追加缺失的已识别片段；保持原始用户消息和模型生成解释完整。
- **1B 模型在会话重置后忽略中文策略** → 语言验收任务保持未完成，并在宣称完全兼容前，通过单独 OpenSpec 变更评估更强的 LiteRT-LM 模型。
- **当前轮次语言信封会让问题看似引用文本** → 永不添加当前轮次信封；在单元覆盖中验证精确 SDK 负载转发，并在真机验收语料中验证直接回答行为。

## 测试矩阵

| ID | 层级 | 必需 | 前置条件 | 步骤 | 预期结果 | 证据 |
| --- | --- | --- | --- | --- | --- | --- |
| BI-1 | 单元测试 | 是 | JVM 测试运行时 | 运行 `:edge-ai:testDebugUnitTest` | 策略没有当前轮次包装器，原始混合提示词在运行时边界辅助方法中保持不变。 | Gradle 输出 |
| BI-2 | app 单元测试 | 是 | JVM 测试运行时 | 运行 `:app:testDebugUnitTest` | ViewModel 保留原始空白/CJK 文本，且绝不将用户消息写成助手响应。 | Gradle 输出 |
| BI-3 | 构建/lint | 是 | JDK 17 | 运行 `:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | 调试和测试构件均可编译，lint 通过。 | Gradle 输出 |
| BI-4 | 设备/视觉 | 是 | Android 12+ ARM64、内置模型已初始化，应用未声明 `INTERNET` 权限 | 经真实 LiteRT-LM 运行时发送 `请用一句话说明你能做什么，不要复述这句话。`，再发送英文和混合语言提示词。捕获测试日志、打包应用启动截图和 UI 层级。 | 每条流均完成；中文能力回答不是被规范化的回显；无崩溃；输入仍为原始文本。AndroidX 插桩无法在 LiteRT 启动初始化期间可靠地等待 Activity 空闲，因此 ViewModel UI 消息角色映射由 BI-2 覆盖。这是直接回答回归测试，不是对 1B 模型算术或事实准确性的基准测试。 | `build/bugfix-reports/support-bilingual-chinese-chat/` |
