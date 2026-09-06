---
title: EdgeAIDemo 架构设计
description: Android 端侧 LiteRT-LM 离线文本生成验证应用的模块、边界、状态与运行约束。
status: current
version: 1.0
last_updated: 2026-08-19
owners:
  - app
  - edge-ai
architecture_style:
  - modular-monolith
  - presentation-model
  - ports-and-adapters
  - unidirectional-state-flow
scope: openspec/project/app
---

# EdgeAIDemo 架构设计

## 1. 架构目标与边界

EdgeAIDemo 是一个 Android 12+、ARM64 的端侧大模型验证应用。它将经过许可批准的 `.litertlm` 模型安装到应用私有目录，并通过 Google AI Edge LiteRT-LM 完成完全离线的多轮文本生成。

架构目标：

- 隔离 UI、模型持久化和 LiteRT-LM/JNI 细节，使界面可在不依赖 SDK 的情况下测试。
- 确保大文件模型导入可恢复、可校验且不会破坏当前可用模型。
- 串行化原生 `Engine` / `Conversation` 调用和释放，避免 JNI 并发与资源泄漏。
- 将 GPU 优先、CPU 回退及诊断结果以可观察状态反馈到 UI。
- 以一个 Activity 级 ViewModel 跨三个页面共享模型、运行时和对话状态。

明确不在本架构范围内：云端 API、模型下载/许可证认证、RAG、Tool Calling、多模态、NPU，以及生产遥测。

## 2. 逻辑架构

```text
┌──────────────────────────────── app : Android Application ────────────────────────────────┐
│ 用户                                                                    ┌────────────────┐ │
│  │                                                                      │ ModelFragment  │ │
│  └────── UI 意图 / uiState 渲染 ──> MainActivity + 三个 Fragment ─────>│ ChatFragment   │ │
│                                         │                               │ Diagnostics... │ │
│                                         │ activity-scoped               └────────────────┘ │
│                                         ▼                                                    │
│                              ┌─────────────────────────┐                                    │
│                              │   EdgeAiViewModel        │                                    │
│                              │ 启动、导入、初始化、对话编排 │                                    │
│                              └───────────┬─────────────┘                                    │
└──────────────────────────────────────────┼──────────────────────────────────────────────────┘
                                           │ 依赖稳定端口（接口）
                  ┌────────────────────────┴────────────────────────┐
                  ▼                                                 ▼
┌──────────────────────── edge-ai : Android Library ─────────────────────────────────────────┐
│     ModelStore                                        EdgeAiRuntime                           │
│        │                                                    │                                │
│        ▼                                                    ▼                                │
│ LocalModelStore ──> ModelFileImporter              LiteRtLmRuntime                           │
│        │              │                              │  单线程 JNI dispatcher                │
│        │              └─> filesDir/models             ├─> filesDir/litertlm-cache/<sha>     │
│        ├─> APK Asset（内置模型）                        └─> LiteRT-LM Engine + Conversation     │
│        └─> SAF（用户手动导入）                                             │                  │
└────────────────────────────────────────────────────────────────────┼──────────────────────┘
                                                                         ▼
                                                               CPU / GPU 设备后端
```

系统是**双模块模块化单体**：`app` 只负责 Android 表现层与用例编排，`edge-ai` 集中承载领域相邻的模型和推理能力。对 `ModelStore` 与 `EdgeAiRuntime` 的依赖采用端口（接口）表达；生产实现留在 `edge-ai`，测试可注入 Fake 实现。

## 3. 模块职责

| 模块/组件 | 职责 | 不负责 |
| --- | --- | --- |
| `app` | 单 Activity、ViewPager2 三页 UI、输入事件、`StateFlow` 渲染、ViewModel 生命周期 | 直接读写模型文件、直接调用 LiteRT-LM 类型 |
| `EdgeAiViewModel` | 启动编排、模型替换前卸载、初始化、流式消息聚合、错误提示、页面共享状态 | SHA-256 计算、JNI 资源管理、SDK 适配 |
| `ModelStore` | 模型选择和导入的抽象端口 | UI 渲染、模型推理 |
| `LocalModelStore` | 内置模型安装、SAF 导入、元数据恢复、替换后的旧文件/缓存清理 | 原生运行时初始化 |
| `ModelFileImporter` | 容量预检、流式复制、SHA-256、`.partial` 清理、原子落盘 | 选中模型的持久化决策 |
| `EdgeAiRuntime` | 运行时状态、生成事件、取消、重置、卸载的抽象端口 | Android 视图与页面状态 |
| `LiteRtLmRuntime` | Engine/Conversation 创建、单线程 JNI 调用、后端回退、指标采集、资源关闭 | 模型文件来源与页面逻辑 |
| LiteRT-LM | 模型加载、Tokenizer、会话与流式生成、CPU/GPU 后端 | 应用的文件事务、UI 状态、业务策略 |

## 4. 分层、依赖与数据流

### 4.1 表现层（`app`）

`MainActivity` 创建并持有 Activity 作用域的 `EdgeAiViewModel`；三个 Fragment 通过 `activityViewModels()` 获得同一实例。这样旋转屏幕不会销毁已加载的原生运行时，模型页、对话页、诊断页也使用同一份状态。

Fragment 只向 ViewModel 发送意图：导入、初始化、卸载、删除、发送、停止、清空。它们使用 `repeatOnLifecycle(STARTED)` 收集 `uiState` 并渲染，不保存推理或模型状态。

### 4.2 编排层（`EdgeAiViewModel`）

ViewModel 汇总以下输入为不可变 `EdgeAiUiState`：

- `ModelStore.selectedModel`：当前模型描述符；
- `EdgeAiRuntime.state`：运行状态；
- `EdgeAiRuntime.diagnostics`：环境、后端和 BenchmarkInfo 指标；
- 页面意图产生的导入进度、消息列表、首选后端与一次性错误。

它是模型管理与运行时之间的唯一协调者：导入、删除或卸载前先停止生成并释放运行时；成功导入后清空可见对话；启动时安装内置模型并自动初始化。

### 4.3 基础设施层（`edge-ai`）

模型存储使用 `filesDir/models` 和 `SharedPreferences`。每个最终模型以 `<sha256>.litertlm` 命名，元数据记录显示名、路径、大小、哈希和导入时间。LiteRT-LM 缓存放在 `filesDir/litertlm-cache/<sha256>`，从而与模型版本隔离。

运行时将所有 `Engine` / `Conversation` 操作调度到独占的单线程 `CoroutineDispatcher`；导入 I/O 使用 `Dispatchers.IO`。这一职责划分保证大文件 I/O 不阻塞原生调用，同时禁止交叉 JNI 操作。

## 5. 核心契约

### 5.1 模型契约

`ModelDescriptor` 是模型跨层传递的稳定值对象，包含显示名、绝对路径、大小、SHA-256 和导入时间。UI 与运行时只依赖此对象，不感知 Asset 或 SAF 的来源。

模型文件必须满足：

- 文件名以 `.litertlm` 结尾且内容非空；
- 已知源大小时，复制后的字节数必须完全相等；
- 最终文件先通过 SHA-256 命名，再以原子移动发布；
- 内置模型还必须匹配固定的大小与 SHA-256；
- 只在最终文件发布成功后持久化选中元数据。

### 5.2 运行时契约

`EdgeAiRuntime` 向上暴露 SDK 无关的 `RuntimeState`、`GenerationEvent` 与 `RuntimeDiagnostics`。调用方无需接触 LiteRT-LM 的 `Engine`、`Conversation` 或 `BenchmarkInfo`。

| 契约 | 成功结果 | 失败/冲突结果 |
| --- | --- | --- |
| `initialize(model, backend)` | 进入 `Ready`，记录首选与实际后端 | 进入 `Error` 并抛出可展示的初始化异常 |
| `generate(prompt)` | 依次产生 `Delta`，最终产生 `Completed` | 产生 `Failed`；空输入和并发生成被拒绝 |
| `cancel()` | 请求 SDK 取消，状态短暂为 `Cancelling` | 无活跃任务时为空操作 |
| `resetConversation()` | 重建 Conversation，保留 Engine/模型 | 生成中拒绝重置 |
| `unload()` / `close()` | 关闭 Conversation 后关闭 Engine，回到 `Unloaded` | 清理采用 best-effort，不遗留可引用的原生对象 |

## 6. 状态模型

```text
                                  initialize / reinitialize
         ┌──────────────────────────────────────────────────────────────────┐
         │                                                                  ▼
┌──────────────┐      ┌────────────────┐      成功      ┌────────────────────┐
│   Unloaded   │ ───> │  Initializing  │ ────────────> │       Ready        │
└──────────────┘      └───────┬────────┘               └───────┬─────┬──────┘
       ▲                      │ 失败                            │     │
       │ unload / close       ▼                                 │     │ generate
       │               ┌──────────────┐                         │     ▼
       └────────────── │    Error     │ <── 失败 ───────────────┘ ┌──────────────┐
                       └──────────────┘                             │ Generating   │
                               │ unload                             └──────┬───────┘
                               └────────────────────────────────────────────┤
                                                                            │ cancel
                                                                            ▼
                                                                     ┌──────────────┐
                                                                     │ Cancelling   │
                                                                     └──────┬───────┘
                                                                            │ Flow 结束
                                                                            ▼
                                                                          Ready
```

约束：任一时刻最多一个生成任务；`Generating` 或 `Cancelling` 时不能重新初始化、删除或重置会话。UI 依据同一状态禁用冲突操作。

## 7. 并发、资源与生命周期设计

### 7.1 线程模型

| 工作类型 | 执行上下文 | 原因 |
| --- | --- | --- |
| Asset/SAF 读取、哈希、落盘 | `Dispatchers.IO` | 大文件流式 I/O，避免阻塞主线程 |
| Engine 初始化、Conversation 创建、生成、取消、重置、释放 | 运行时独占单线程 dispatcher | SDK/JNI 生命周期串行，避免并发访问 |
| UI 状态聚合与渲染 | `viewModelScope` / Android 主线程 | 保障界面线程安全 |

### 7.2 原生资源所有权

`LiteRtLmRuntime` 是唯一的 `Engine` 与 `Conversation` 所有者。每次初始化会先关闭残留实例；每次卸载、模型切换和 ViewModel 清理都按 **Conversation → Engine** 顺序释放。失败初始化中的候选 Engine 也会立即关闭。

`ViewModel.onCleared()` 调用 `runtime.close()`，随后停止运行时 scope、关闭 dispatcher 并关闭 executor；因此 Activity 真正结束后不会遗留后台原生线程。

## 8. 异常处理与韧性策略

| 风险 | 架构策略 | 可观察结果 |
| --- | --- | --- |
| APK 未带内置模型或模型校验失败 | 安装失败；删除无效副本；保留可恢复的模型页 | 明确错误，可手工导入 |
| 导入中断、进程取消、复制不完整 | 使用 UUID `.partial`；finally 清理；启动时清理遗留 partial | 不会把半成品选为模型 |
| 磁盘不足 | 已知大小时预留模型大小加安全余量 | 导入前失败并提示所需空间 |
| GPU 不可用 | 物理设备初始化失败后回退 CPU；模拟器预检后直接 CPU | `Ready` 展示实际后端与回退原因 |
| GPU 与 CPU 均无法初始化 | 关闭原生资源，转为 `Error` | 包含两段失败原因的可恢复错误 |
| 生成异常 | 将 SDK 异常清洗为不含本机模型路径的 `Failed` | 助手气泡结束流式状态，UI 显示错误 |
| 配置变更 | Activity 作用域 ViewModel 保留 Store/Runtime | 页面重建不丢失已加载模型与会话 |

## 9. 安全与运行约束

- 模型、模型缓存与元数据均位于应用私有目录；应用不申请广泛存储权限，手动导入仅使用 SAF。
- 模型二进制和访问令牌均不提交到仓库；内置模型由构建机在已接受许可证后提供。
- APK 将 `.litertlm` 作为未压缩 Asset 打包，以便读取原始长度并避免重复压缩成本。
- 当前发布边界为 Android 12+、`arm64-v8a`；模拟器仅用于 UI 与错误流程验证，端侧推理验收需要 ARM64 真机。
- LiteRT-LM 依赖的协程 ABI 在工程中固定为 `1.11.0`，构建任务会校验 `core` 与 `android` 的解析版本。

## 10. 可测试性与演进规则

- ViewModel 单测使用 `ModelStore`、`EdgeAiRuntime` 的 Fake 实现，验证编排和状态而不加载真实模型。
- `edge-ai` 单测覆盖模型导入、后端选择和语言策略等 SDK 周边逻辑。
- `LiteRtLmRuntime` 是 SDK 升级的唯一适配点；升级 LiteRT-LM、协程 ABI 或模型格式时，应先创建 OpenSpec change，并重新验证初始化、流式完成、取消、GPU/CPU 回退与诊断字段。
- 新能力优先增加稳定端口和项目自有类型；不要把 LiteRT-LM 类型泄漏到 `app`。

## 11. 相关实现入口

- `app/src/main/java/com/zjf/edgeai/ui/EdgeAiViewModel.kt`
- `edge-ai/src/main/java/com/zjf/edgeai/runtime/RuntimeTypes.kt`
- `edge-ai/src/main/java/com/zjf/edgeai/runtime/LiteRtLmRuntime.kt`
- `edge-ai/src/main/java/com/zjf/edgeai/runtime/model/LocalModelStore.kt`
- `edge-ai/src/main/java/com/zjf/edgeai/runtime/model/ModelFileImporter.kt`
