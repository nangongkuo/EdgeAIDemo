## 背景

现有仓库从一个库模块打包唯一的 Activity，并依赖包含无关网络、媒体和工具库的依赖聚合模块。UI 验证的是嵌套手势仲裁而非 AI。LiteRT-LM 0.15.0 发布了 Kotlin 2.2 元数据和原生库，因此构建、模块边界、存储流程、线程模型与原生生命周期都需要协同调整。

验证目标为 Android 12+ ARM64 硬件。模型是数百 MB、受许可证控制的文件。已批准模型作为本地且被 Git 忽略的构建输入包含在 APK 中，而令牌和模型二进制继续保留在仓库外。内置资源安装至应用私有存储后，推理必须在无网络访问的情况下运行。

## 目标与非目标

**目标：**

- 提供一个小型 Android 应用，验证 LiteRT-LM 模型导入、初始化、离线流式对话、取消、重置、卸载、GPU 回退和基准采集。
- 将 LiteRT-LM 类型隔离在稳定的项目自有 API 后，使 UI 测试可使用假的运行时。
- 让模型替换具备事务性，并明确原生资源所有权。
- 通过安装内置资源并自动初始化，使全新安装无需手动选择模型即可使用。
- 产出可重复执行的 OpenSpec 和 Gradle 验证命令。

**非目标：**

- 应用内模型下载、Hugging Face 认证或模型转换。许可证接受和构建期模型下载仍是外部准备步骤。
- 多模态输入、工具调用、RAG、NPU 集成、云端推理或生产遥测。
- 跨设备性能通过/失败阈值；示例仅记录设备特定的测量数据。

## 设计决策

### 双模块架构

项目将包含 `app` 和 `edge-ai`。 `app` 负责 Android 页面、适配器和 Activity 作用域的 ViewModel；`edge-ai` 负责模型持久化与全部 LiteRT-LM 交互。旧的 `base` 和 `biz-app` 模块将被移除。相较于将 LiteRT-LM 添加到当前依赖聚合器，此方案可防止原生/运行时依赖泄漏到无关 UI 代码，并清除过时库。

### 固定的兼容构建链

构建使用 AGP 8.10.1、Gradle 8.11.1、Kotlin 2.2.21、JDK 17、LiteRT-LM 0.15.0 和 `kotlinx-coroutines` 1.11.0。minSdk 调整为 31，打包仅限 `arm64-v8a`；compileSdk 和 targetSdk 维持 34。固定版本优于 `latest.release`，以保证验证可复现。

### LiteRT-LM 协程 ABI 解决方案

**根因置信度：已确认。** `litertlm-android:0.15.0` AAR 字节码从 `Conversation.sendMessageAsync()` 完成回调中，以接口静态方法调用 `SendChannel.close$default(SendChannel, Throwable, int, Object)`。其发布的 POM 却声明 `kotlinx-coroutines-android:1.9.0`。在 1.9.0 中，这个合成默认参数桥接方法位于 `SendChannel$DefaultImpls`；因此成功推理结束后，Android 会在 LiteRT-LM 回调线程抛出 `NoSuchMethodError`。捕获的 `crash_004.txt` 堆栈正是这条调用路径。

应用 SHALL 显式将 `kotlinx-coroutines-core`、`kotlinx-coroutines-android` 与测试构件解析为 1.11.0——这是 `SendChannel` 接口提供 AAR 所需桥接方法的最低版本。该版本统一配置并用于两个模块；Gradle 依赖验证将断言未选中更低版本的 core 或 Android 构件。这是针对 LiteRT-LM 发布缺陷的消费端解决方案，不改变生成、UI 或后端行为。

**已拒绝替代方案：** 保留 POM 声明的 1.9.0 已知会在完成时崩溃；1.10.x 未提供所需接口静态桥接；在 `generate()` 中捕获异常无效，因为异常源自 LiteRT-LM 独立的 JNI 回调线程。等待未来 SDK 发布会使示例持续可确定地崩溃。

### 事务式应用私有模型存储

应用接受打包模型资源或来自 Storage Access Framework 的文档 URI，并将其复制到 `filesDir/models`。计算 SHA-256 时先写入 `.partial` 文件，完成后以原子方式重命名为 `<sha256>.litertlm`。仅在重命名成功后持久化元数据；替换完成前，先前已选模型保持完好。LiteRT-LM 需要文件系统路径，不能直接从 `AssetManager` 流或任意内容提供方 URI 推理，因此必须采用这一方式。

### 内置模型与自动启动

已接受许可的 Gemma 文件放在 `app/src/main/assets/models/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm`，通过 `noCompress` 打包，使 `AssetManager` 暴露原始长度并避免浪费时间重新压缩模型数据。预期大小和 SHA-256 同时固定于构建校验任务和 `BundledModelSpec`；运行时会在不匹配的提取结果成为已选模型前将其删除。ViewModel 创建时，已有的有效私有模型优先；否则，通过同一导入器以事务方式复制内置资源。随后以 GPU 为首选、CPU 仅一次回退自动初始化模型。资源缺失或初始化失败仍可从“模型”页恢复。

模型被 Git 忽略，因为上游仓库受限、二进制超出普通 GitHub 文件限制，且再分发义务需由分发方处理。可复现的本地构建必须在打包 APK 前准备该外部输入。

### 串行化原生运行时

所有 Engine 和 Conversation 操作均在一个自有、由 executor 支持的 CoroutineDispatcher 中运行；导入 I/O 在 `Dispatchers.IO` 中运行。Mutex/状态保护将拒绝并发初始化或生成，从而避免 JNI 调用重叠，并提供确定性的关闭顺序。

### GPU 优先并仅回退一次 CPU

选择 GPU 时尝试 GPU 初始化。若抛出异常，则关闭部分创建的 Conversation 和 Engine，保留错误文本作为回退原因，并且只尝试一次 CPU 初始化。显式选择 CPU 时不探测 GPU。UI 始终显示实际后端。

### Android 模拟器 GPU 预检

**根因置信度：已确认。** 在 Android Studio ARM64 模拟器（`ro.kernel.qemu=1`、硬件为 `ranchu`、API 37）上，`Engine.initialize()` 接受 `Backend.GPU()` 并通过 WebGPU 初始化模型。LiteRT-LM 会延迟到 `Conversation.sendMessageAsync()` 时才创建采样器。捕获的运行时日志随后报告 `libLiteRtTopKWebGpuSampler.so` 和 `libLiteRtTopKOpenClSampler.so` 都不可用，接着出现 `Can not find OpenCL library on this device`。现有回退函数仅包围 `initializeBackend` 调用，而 `generate()` 会将后续失败直接转换为 `GenerationEvent.Failed`；因此尽管 CPU 推理可用，用户仍会看到看似无法恢复的消息。

修正后的启动路径如下：

```text
首选 GPU
  -> Android Studio 模拟器特征（generic/ranchu/goldfish）
  -> 在创建 Engine 前跳过 GPU
  -> 仅初始化一次 CPU
  -> Ready(preferred=GPU, effective=CPU, fallbackReason=emulator/OpenCL)
```

Android 真机保留当前 GPU 优先尝试及初始化阶段 CPU 回退。Manifest 将增加 LiteRT-LM 文档中说明的可选 `libvndksupport.so` 和 `libOpenCL.so` 声明；这些声明允许兼容硬件访问它们，但不会在模拟器中伪造 OpenCL。因此，CPU 预检是所报告模拟器场景的兼容性方案。

**已拒绝替代方案：** 打包合成 OpenCL 库不安全，且无法提供真实模拟器 GPU 驱动；将成功的 `Engine.initialize()` 调用视作 GPU 能力测试，已被捕获的延迟采样器失败推翻；在全新 CPU `Conversation` 上重试失败的多轮 GPU 提示词会静默丢失既有对话状态。

**必需回归矩阵：**

| ID | 层级 | 必需 | 前置条件 / 步骤 | 预期结果 | 证据 |
|---|---|---:|---|---|---|
| GPU-EMU-01 | 单元测试 | 是 | 运行 `./gradlew :edge-ai:testDebugUnitTest` | 模拟器特征选择 CPU 而不尝试 GPU，并保留回退原因；真机特征仍为 GPU 优先 | JUnit 结果 |
| GPU-EMU-02 | 构建 | 是 | 运行 `./gradlew :app:assembleDebug` 并检查合并 APK Manifest | 存在可选原生库声明 | Gradle / `aapt` 输出 |
| GPU-EMU-03 | 设备 + 视觉 | 是 | 在 `emulator-5554` 安装已批准调试 APK，以内置模型启动并提交一条提示词 | 状态显示实际 CPU，且提示词产生非空流式文本；无 `Can not find OpenCL library` 错误 | 截图、UI dump、过滤后的 logcat |
| ABI-01 | 依赖图 | 是 | 对 `debugRuntimeClasspath` 上的 `kotlinx-coroutines-core` 和 `kotlinx-coroutines-android` 运行 Gradle dependency insight | 两者均只解析至 1.11.0 | Gradle 输出 |
| ABI-02 | 设备 + 视觉 | 是 | 在 Android 12+ ARM64 真机或 `emulator-5554` 上提交提示词并等待 LiteRT-LM 完成 | 完成响应后进程仍存活；未出现 `SendChannel.close$default` 的 `NoSuchMethodError` | 过滤后的 logcat、截图/UI dump |

归档前仍必须单独完成 Android 12+ ARM64 真机验收；模拟器证据不能替代它。

### 运行时状态与生命周期

一个 Activity 作用域的 ViewModel 持有一个运行时和一个模型存储。屏幕旋转时 ViewModel 会保留。运行时仅允许一项活动生成，使用 LiteRT-LM Flow 流式输出；取消时调用 `cancelProcess()`，重置时只重建 Conversation，并在卸载或最终清理时先关闭 Conversation、再关闭 Engine。

### 稳定且不依赖 SDK 的 API

`EdgeAiRuntime`、`RuntimeState`、`GenerationEvent`、`RuntimeDiagnostics` 和 `ModelDescriptor` 不包含 LiteRT-LM 类。 `BenchmarkInfo` 数值会映射为项目自有诊断信息。这样可实现确定性的单元测试，并将未来 SDK 迁移局部化。

### XML 验证控制台

应用使用一个 Activity，并通过 ViewPager2/TabLayout 提供“模型”“对话”“诊断” Fragment。共享 ViewModel 暴露由 StateFlow 驱动的 UI 状态。未纳入 Compose 迁移，因为它不对 SDK 验证产生贡献。

## 风险与权衡

- **大型导入可能耗尽存储或被中断** → 以安全余量检查已知源大小与可用空间，流式复制，并在启动和失败时删除遗留 `.partial` 文件。
- **内置模型会增加 APK 大小和首次启动磁盘使用量** → 资源未压缩存储，展示安装进度，要求足够空间，并仅保留一个选中的私有副本。
- **Gemma 受许可证限制** → 不嵌入凭据或自动接受条款；要求构建者在外部接受许可证，并让二进制保持在 Git 外。
- **不同厂商的 GPU 支持存在差异** → 声明可选 OpenCL/VNDK 库，捕获初始化失败，关闭部分原生状态，并明确展示 CPU 回退。
- **LiteRT-LM 已发布 POM 低估协程 ABI 要求** → 将 core、Android 和测试协程构件显式固定为 1.11.0，并在设备验证前校验解析后的运行时图。
- **原生初始化可能需要数秒** → 在主线程外运行并暴露进度状态。
- **格式错误的 `.litertlm` 文件可能通过扩展名检查** → 将 SDK 初始化视为最终校验，并保持可恢复的模型管理页面可用。
- **大模型可能在低内存硬件上失败** → 记录真机要求，展示错误，且不宣称模拟器性能。
- **ABI 过滤排除 x86 设备** → 这是选择 ARM64 验证基线的有意设计。

## 迁移计划

1. 添加并严格验证 OpenSpec 工件。
2. 升级工具链，引入 `edge-ai`，将 Manifest/资源迁移到 `app`，并移除旧模块。
3. 在本地准备已批准许可的模型资源，在接口后实现内置/私有模型持久化和运行时，再添加验证 UI。
4. 运行单元测试、插桩构建、lint、APK 打包和 ABI 检查。
5. 在归档变更前，于 Android 12+ ARM64 真机上验证模型导入和推理。

回滚方式为将 Git 还原至初始项目提交并移除本地被忽略资源；已安装模型文件位于应用私有运行时存储。

## 待确认事项

无。设备特定性能结果将在验收期间记录，不会改变实现契约。
