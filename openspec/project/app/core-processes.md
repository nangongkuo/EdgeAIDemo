---
title: EdgeAIDemo 核心流程
description: 内置模型安装、手动导入、运行时初始化、流式对话及资源释放的执行流程与状态约束。
status: current
version: 1.0
last_updated: 2026-08-19
owners:
  - app
  - edge-ai
scope: openspec/project/app
---

# EdgeAIDemo 核心流程

## 1. 流程总览

```text
┌──────────┐    已存在且有效    ┌──────────────────┐    初始化成功    ┌──────────┐
│ 应用启动 ├──────────────────> │ 私有模型恢复完成 │ ─────────────> │ 模型就绪 │
└────┬─────┘                    └──────────────────┘                 └────┬─────┘
     │ 无有效模型                                                       │
     ▼                                                                   ├──> 流式多轮对话 ──> 模型就绪
┌──────────────────┐                                                     │
│ 释放/校验内置模型 │ ─────────────────────> 初始化运行时 ───────────────┘
└──────────────────┘                                                     │
     │ 失败                                                               ├──> SAF 导入替换 ──> 等待用户初始化
     ▼                                                                   │
┌──────────────────┐                                                     └──> 卸载/删除/销毁 ──> 资源已释放
│ 可恢复错误（模型页）│
└──────────────────┘
```

所有入口都由 `EdgeAiViewModel` 编排，模型文件事务由 `LocalModelStore` / `ModelFileImporter` 执行，原生推理事务由 `LiteRtLmRuntime` 执行。

## 2. 应用启动与内置模型自动初始化

**触发条件：** `EdgeAiViewModel` 创建完成。

```text
 EdgeAiViewModel             LocalModelStore / Importer       LiteRtLmRuntime        LiteRT-LM
       │                                  │                         │                  │
  1. 创建并读取 selectedModel             │                         │                  │
       ├───────────── selectedModel ─────>│                         │                  │
       │<──────────── ModelDescriptor / null ───────────────────────│                  │
       │                                  │                         │                  │
  2a. 有有效私有模型：直接使用 ModelDescriptor                      │                  │
  2b. 无模型：installBundledModel()       │                         │                  │
       ├─────────────────────────────────>│                         │                  │
       │                                  ├─ Asset -> .partial      │                  │
       │<──── Started / Progress ─────────┤                         │                  │
       │                                  ├─ SHA-256 + 原子发布     │                  │
       │                                  ├─ 校验内置模型大小/哈希   │                  │
       │<──────────── Completed(model) ───┤                         │                  │
  3. initialize(model, GPU)               │                         │                  │
       ├──────────────────────────────────────────────────────────>│                  │
       │                                  │            Engine.initialize() + createConversation
       │                                  │                         ├────────────────>│
       │                                  │                         │<── 成功 / 失败 ─┤
       │<────────── Ready / Error + diagnostics ────────────────────┤                  │
```

执行规则：

1. `LocalModelStore.restoreModel()` 仅恢复存在、非空、扩展名正确的私有模型；无效元数据会被清除。
2. 没有模型时，ViewModel 将 UI 标记为“正在释放内置模型”，并订阅导入进度。
3. 内置模型经过与手动导入相同的文件事务，但在完成后额外比对预设大小和 SHA-256；不匹配立即删除。
4. 获得模型后自动调用 `runtime.initialize()`，默认首选 GPU。
5. 任一步失败都保持可恢复：结束导入状态、清除进度，并将错误写入 `EdgeAiUiState.errorMessage`；用户可在模型页手动导入或再次初始化。

### 2.1 启动链路的输入、输出与提交点

| 环节 | 输入 | 处理与提交点 | 成功输出 | 失败处理 |
| --- | --- | --- | --- | --- |
| 模型恢复 | `SharedPreferences` 元数据、`filesDir/models` | 同时确认文件存在、非空、扩展名正确；否则清空元数据 | 已选 `ModelDescriptor` 或 `null` | 视为无模型，不影响应用启动 |
| 内置模型安装 | APK 未压缩 Asset、内置模型规格 | 仅在最终文件落盘并通过固定大小/SHA-256 后持久化选中模型 | `ModelImportEvent.Completed` | 删除不匹配文件，提示可手动导入 |
| 自动初始化 | 模型绝对路径、默认 GPU 首选 | Engine 与 Conversation 都创建成功后才写入 `Ready` | 可交互对话页、初始化诊断 | 保持模型文件，进入可重试错误状态 |

## 3. 手动导入与模型替换

**触发条件：** 用户在模型页选择文件，`OpenDocument` 返回 URI。

```text
用户 / ModelFragment       EdgeAiViewModel          LocalModelStore / Importer       filesDir/models
       │                         │                            │                            │
  1. 选择文件                    │                            │                            │
       ├─ OpenDocument ─────────>│                            │                            │
       │                         │                            │                            │
  2. 阻断旧运行时                 ├─ cancel() + unload() ──────> LiteRtLmRuntime             │
       │                         │                            │                            │
  3. 读取并校验 URI               ├─ importModel(uri) ────────>│                            │
       │                         │                            ├─ 扩展名/大小/空间预检      │
       │                         │<──── Started(displayName) ──┤                            │
       │                         │                            │                            │
  4. 复制与进度                   │                            ├─ 创建 <uuid>.partial ───>│
       │                         │<──── Progress(bytes,total) ─┤<─ 每 1 MiB 缓冲持续写入 ──┤
       │                         │                            ├─ 同时计算 SHA-256          │
       │                         │                            │                            │
  5. 发布新模型                   │                            ├─ 原子移动为 <sha>.litertlm ─>│
       │                         │                            ├─ 持久化新 metadata          │
       │                         │                            ├─ 删除旧模型/旧缓存          │
       │                         │<──── Completed(model) ──────┤                            │
  6. UI 收尾                      ├─ 清空可见消息；显示“导入完成” │                            │
       │                         │                            │                            │
  7. 用户显式初始化               └─ initializeModel()（不自动执行）                           │
```

### 3.1 文件事务细节

| 阶段 | 处理 | 失败结果 |
| --- | --- | --- |
| 元数据读取 | 从 SAF 查询显示名和可选大小 | 使用 URI 路径段作为回退显示名 |
| 输入校验 | 只接受 `.litertlm`，拒绝已知的 0 字节文件 | 抛出 `InvalidModelException` |
| 容量预检 | 已知大小时要求模型大小 + `max(256 MiB, 10%)` 安全余量 | 抛出 `InsufficientStorageException` |
| 复制与摘要 | 1 MiB 缓冲流式读写；每轮确认协程未取消；持续发出进度 | 进入 finally，删除 `.partial` |
| 完整性检查 | 不允许 0 字节；已知总大小必须精确匹配 | 不发布目标文件 |
| 提交 | 文件名取 SHA-256；优先原子移动，不支持时回退覆盖移动 | 原模型与其元数据保持不变 |
| 选中切换 | 先持久化新模型并更新状态，再删除旧模型及对应缓存 | 新模型已发布时可继续恢复使用 |

因此，导入期间崩溃、取消或 I/O 失败不会产生被选中的半成品。应用下次创建 `ModelFileImporter` 时也会清理遗留的 `.partial` 文件。

> 当前导入完成后只更新模型选择和清空对话；用户需在模型页显式点击“初始化”以加载刚导入的模型。

### 3.2 导入失败的精确落点

```text
校验失败 / 空间不足 / 打开 URI 失败 / 复制取消 / 字节数不匹配 / 文件系统异常
                                      │
                                      ▼
                         finally 删除当前 <uuid>.partial
                                      │
                                      ▼
                     不发布 <sha>.litertlm，不更新选中元数据
                                      │
                                      ▼
       ViewModel: isImporting=false，progress/status 清空，errorMessage 写入失败原因
                                      │
                                      ▼
    旧模型文件和旧元数据仍保留；但运行时已在导入开始前卸载，需重新初始化旧模型才可继续对话
```

## 4. 运行时初始化与后端回退

**触发条件：** 启动自动初始化或用户点击初始化。

```text
initialize(model, preferredBackend)  [运行时独占单线程]
                 │
                 ▼
     ┌─────────────────────────────────┐
     │ runtime 已 close 或正在生成？     │── 是 ──> 拒绝：抛出异常，不改变当前资源
     └──────────────┬──────────────────┘
                    │ 否
                    ▼
     关闭旧 Conversation → 关闭旧 Engine → 清空 readyState
                    │
                    ▼
  state=Initializing；diagnostics 写入模型摘要与首选后端
                    │
        ┌───────────┴───────────────────────────┐
        │ preferred = CPU                        │ preferred = GPU
        ▼                                        ▼
  仅初始化 CPU                          ┌──────────────────────┐
        │                                │ 是 Android 模拟器？    │
        │                                └───────┬─────────┬────┘
        │                                    是  │         │ 否
        │                                        ▼         ▼
        │                              记录预检原因     尝试 GPU
        │                                        │         │
        └──────────────────────────┬─────────────┘         ├── 成功 ──> Ready(GPU)
                                   ▼                         │
                         尝试 CPU（仅一次）                  └── 失败 ──> 尝试 CPU（仅一次）
                                   │                                           │
                    ┌──────────────┴──────────────┐                            │
                    │ 成功                         │ 失败                       │
                    ▼                              ▼                            ▼
       Ready(CPU, 回退原因可选)       Error(仅 CPU 原因)        Error(GPU 原因 + CPU 原因)
```

初始化按运行时独占单线程执行，步骤为：

1. 检查运行时尚未 `close` 且当前没有生成任务。
2. 以 **Conversation → Engine** 顺序释放旧实例，清空上次就绪状态。
3. 设置 `RuntimeState.Initializing`，写入模型与首选后端的基础诊断信息。
4. 为模型创建独立缓存目录 `filesDir/litertlm-cache/<sha256>`，构造 `EngineConfig`。
5. 调用 `Engine.initialize()`，成功后创建 `Conversation`；两者都成功才成为当前实例。
6. 调用 `getBenchmarkInfo()`，可用时记录初始化耗时。

后端选择规则：

- 显式选择 CPU：只尝试 CPU，不探测 GPU。
- 首选 GPU 且设备像 Android 模拟器：跳过 GPU，直接初始化 CPU，并保留 OpenCL 预检原因。
- 首选 GPU 且真实设备初始化 GPU 失败：关闭失败候选 Engine，只回退尝试一次 CPU。
- GPU 与 CPU 均失败：关闭所有原生资源，将两段原因合并为可恢复错误。

### 4.1 初始化阶段的数据、资源与状态变化

| 步骤 | `RuntimeState` | 原生资源 | 诊断数据 | 关键约束 |
| --- | --- | --- | --- | --- |
| 前置检查 | 当前状态不变 | 不新建 | 不新建 | `closed=false` 且 `generationActive=false` |
| 清理旧实例 | `Unloaded` 或旧状态过渡 | 按 Conversation → Engine 关闭 | 保留环境基线 | 不允许关闭顺序反转 |
| 选择后端 | `Initializing(preferred)` | 尚无当前实例 | 写入模型、首选后端 | 模拟器不创建 GPU Engine |
| 建立候选实例 | `Initializing` | `Engine` 成功后再创建 `Conversation` | 尚无最终后端 | 任一步失败立即关闭候选 Engine |
| 提交就绪 | `Ready(preferred,effective,reason)` | 两个实例一并成为当前实例 | 实际后端、回退原因、初始化耗时 | Engine 与 Conversation 都成功才提交 |
| 全部失败 | `Error(message)` | 两个引用均为 `null` | 回退/错误原因 | 不保留半初始化资源 |

## 5. 流式多轮对话

**前置条件：** `RuntimeState.Ready`，输入不为空，且没有活跃生成任务。

```text
 ChatFragment             EdgeAiViewModel                 LiteRtLmRuntime              Conversation
      │                         │                                │                          │
  1. 点击发送                  │                                │                          │
      ├─ sendPrompt(prompt) ───>│                                │                          │
      │                         ├─ 检查 Ready、非空、无 Job        │                          │
      │                         ├─ 加入用户消息                   │                          │
      │                         ├─ 加入空助手消息(streaming=true)  │                          │
      │                         │                                │                          │
  2. 开始生成                  ├─ generate(rawPrompt) ──────────>│                          │
      │                         │                                ├─ CAS: generationActive  │
      │                         │                                ├─ state=Generating        │
      │                         │                                ├─ sendMessageAsync ──────>│
      │                         │                                │                          │
  3. 每个增量                  │                                │<──── text delta ─────────┤
      │                         │<── GenerationEvent.Delta(text) ─┤                          │
      │                         ├─ 追加至同一 assistantId 气泡     │                          │
      │<── uiState(messages) ───┤                                │                          │
      │                         │                                │                          │
  4. 正常完成                  │                                │<──── Flow complete ───────┤
      │                         │                                ├─ 读取 BenchmarkInfo      │
      │                         │<── Completed(diagnostics) ──────┤                          │
      │                         ├─ streaming=false                │                          │
      │                         │                                ├─ generationActive=false  │
      │                         │                                └─ state=Ready              │
      │<── 最终 uiState ────────┤                                │                          │
```

处理规则：

- `generationActive` 以原子方式保证同时只有一次生成；竞争请求返回 `Failed`。
- 生成在运行时单线程 dispatcher 上执行，`flowOn(dispatcher)` 保证 SDK 回调和会话操作不与初始化/释放交叉。
- 语言策略通过 Conversation 的系统指令设定为默认简体中文；当前轮传给 SDK 的内容保持为原始 prompt，避免再次包装指令。
- 每个 `Delta` 只更新对应助手气泡；完成、失败或取消后都会将气泡的 `streaming` 标记置为 `false`。
- 正常完成后读取 `BenchmarkInfo`，更新首 Token 时延、Prefill/Decode Token、吞吐率和会话 Token 总数。
- SDK 异常经路径脱敏后映射为 `GenerationEvent.Failed`，不把本机模型绝对路径暴露给 UI。

### 5.1 对话链路的事件与可见状态

| 时点 | Runtime 事件/状态 | ViewModel 数据变化 | UI 可见结果 |
| --- | --- | --- | --- |
| 提交前 | 必须为 `Ready` | 无 | 输入框和发送按钮可用 |
| 提交成功 | `Generating` | 追加一条用户消息、一条空助手消息 | 出现正在流式输出的助手气泡；停止按钮可用 |
| 每个 Token/片段 | `GenerationEvent.Delta` | 仅拼接该 `assistantId` 的 `text` | 同一气泡持续增长，列表滚动到底部 |
| 正常结束 | `Completed` → `Ready` | `streaming=false`；诊断 Flow 更新 | 完整回复和最新性能指标 |
| SDK 失败 | `Failed` → `Ready` | 空气泡写入“生成失败：原因”；`streaming=false` | 可见错误提示，允许再次发送 |
| 用户取消 | `Cancelling` → `Ready` | 取消 Job；所有流式气泡标记结束 | 已收到的文本保留，不再追加 |

## 6. 停止生成、清空会话与卸载

### 6.1 停止生成

```text
ChatFragment          EdgeAiViewModel              LiteRtLmRuntime             Conversation
     │                       │                             │                       │
     ├─ stopGeneration() ───>│                             │                       │
     │                       ├─ cancel() ─────────────────>│                       │
     │                       │                             ├─ state=Cancelling    │
     │                       │                             ├─ cancelProcess() ───>│
     │                       ├─ cancel generationJob        │                       │
     │                       ├─ 所有 streaming=false         │                       │
     │<──── uiState ─────────┤                             │                       │
     │                       │                             │<─ Flow 取消/结束 ────┤
     │                       │                             ├─ generationActive=false
     │                       │                             └─ state=Ready / Unloaded
```

ViewModel 同时调用运行时取消和取消自己的收集 Job，以避免用户继续看到旧流输出。运行时捕获协程取消时会再尝试 `cancelProcess()`，确保 SDK 收到中止信号。

停止不是回滚：已经收到的模型文本继续保留在助手消息中，只是停止后续增量；`Conversation` 也不会重建，因此用户可在同一上下文中继续提问。

### 6.2 清空会话

前置条件是没有生成任务。ViewModel 先停止生成，再调用 `resetConversation()`：运行时关闭旧 Conversation、从现有 Engine 创建一个新 Conversation，并清空与上轮会话有关的 Token/吞吐诊断字段。Engine 与已加载模型不变，UI 消息列表也被清空。

### 6.3 卸载、删除与最终清理

| 入口 | 编排顺序 | 结果 |
| --- | --- | --- |
| 卸载模型 | 停止生成 → `runtime.unload()` → 清空 UI 消息 | 释放原生资源，保留已导入模型文件 |
| 删除模型 | 停止生成 → 卸载 → 删除模型与模型缓存 → 清空元数据/UI | 下次启动不再恢复该模型 |
| 导入替换 | 停止生成 → 卸载 → 文件事务 → 切换模型 | 避免运行时引用被替换的文件 |
| ViewModel 销毁 | 取消启动/生成 Job → `runtime.close()` | 同步卸载、关闭 dispatcher/executor，完成最终释放 |

运行时无论从何种入口卸载，始终先关闭 Conversation，再关闭 Engine。此顺序是 LiteRT-LM 原生资源生命周期的硬约束。

### 6.4 资源释放的完整决策链

```text
触发：卸载 / 删除 / 导入替换 / ViewModel.onCleared
  │
  ├─ 是否正在生成？── 是 ──> 请求 Conversation.cancelProcess()
  │                         （取消失败不阻断后续清理）
  │
  ▼
closeNative()
  │
  ├─ conversation?.close()  ──> conversation = null
  │
  ├─ engine?.close()        ──> engine = null
  │
  └─ 清理逻辑状态：generationActive=false、activeModel=null、readyState=null、state=Unloaded

仅 close() 额外执行：取消 runtimeScope → 关闭 dispatcher → shutdown executor
```

## 7. 诊断数据流

`LiteRtLmRuntime` 初始化时生成环境诊断，并随着模型加载、后端选择、完成生成和重置会话更新 `RuntimeDiagnostics`。`EdgeAiViewModel` 持续汇总该 `StateFlow`，诊断页直接渲染。

| 时机 | 更新字段 |
| --- | --- |
| 创建运行时/卸载 | LiteRT-LM 版本、设备厂商/型号、API、ABI |
| 初始化开始 | 模型摘要、首选后端 |
| 初始化完成 | 实际后端、GPU 回退原因、初始化耗时 |
| 一次生成完成 | 首 Token、Prefill/Decode Token、吞吐率、会话 Token |
| 重置会话 | 清空与上一会话生成有关的性能指标 |

诊断用于设备差异分析，而非跨设备的性能准入阈值。模拟器的诊断可验证 CPU 回退和错误路径，但不能替代 Android 12+ ARM64 真机推理验收。

## 8. 核心不变量检查表

- [ ] UI 不直接依赖 LiteRT-LM 的 `Engine`、`Conversation` 或 `BenchmarkInfo`。
- [ ] 任一时刻至多一个活跃生成任务。
- [ ] 所有 Engine/Conversation 调用在同一独占 dispatcher 上串行执行。
- [ ] 每次释放均遵循 Conversation 先于 Engine。
- [ ] 模型仅在完整复制、哈希命名并发布后才成为选中模型。
- [ ] 导入失败不会删除旧的已选模型；替换成功后才清理旧模型和缓存。
- [ ] GPU 回退只在 GPU 首选路径发生一次；显式 CPU 不探测 GPU。
- [ ] 所有可恢复错误均可通过模型页重新导入或重新初始化处理。

## 9. 相关实现入口

- `app/src/main/java/com/zjf/edgeai/ui/EdgeAiViewModel.kt`
- `app/src/main/java/com/zjf/edgeai/ui/model/ModelFragment.kt`
- `app/src/main/java/com/zjf/edgeai/ui/chat/ChatFragment.kt`
- `edge-ai/src/main/java/com/zjf/edgeai/runtime/LiteRtLmRuntime.kt`
- `edge-ai/src/main/java/com/zjf/edgeai/runtime/BackendSelection.kt`
- `edge-ai/src/main/java/com/zjf/edgeai/runtime/model/LocalModelStore.kt`
- `edge-ai/src/main/java/com/zjf/edgeai/runtime/model/ModelFileImporter.kt`
