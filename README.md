# EdgeAIDemo

This is a Google AI Edge SDK Demo.

`EdgeAIDemo` 是一个用于验证 Google AI Edge 端侧文本大模型能力的 Android Demo。应用不调用云端 API：APK 内置 Gemma 模型，首次启动将其安装到应用私有目录并自动初始化，之后的多轮对话完全在设备上完成。

当前实现固定使用 `com.google.ai.edge.litertlm:litertlm-android:0.15.0`，应用包名为 `com.zjf.edgeai`。

## Google AI Edge 技术栈

- **LiteRT**：模型转换、硬件委托和底层推理运行时。
- **LiteRT-LM**：建立在 LiteRT 之上的端侧大模型 API，提供模型加载、Tokenizer、Conversation、Kotlin Flow 流式响应和 CPU/GPU/NPU 后端。
- **MediaPipe Tasks**：面向视觉、音频、文本等常见任务的高层任务 API。

本项目只验证 LiteRT-LM 的离线文本生成，不包含多模态、Tool Calling、RAG、NPU 或云端服务。参考 [LiteRT-LM 概览](https://developers.google.com/edge/litert-lm/overview) 和 [Android 集成指南](https://developers.google.com/edge/litert-lm/android)。

## 已实现能力

- 将许可批准后的 Gemma `.litertlm` 作为不压缩 Asset 打进 APK；首次启动事务性释放并自动初始化。
- 已存在有效私有模型时直接复用，避免每次启动重复复制；用户手动导入的替换模型优先于内置模型。
- 使用 Storage Access Framework 导入 `.litertlm`，无需广泛存储权限。
- 导入时复制到应用私有目录、计算 SHA-256、展示进度，并通过 `.partial` + 原子移动避免保留半成品。
- 持久化模型元数据，应用重启后仍能识别已导入模型。
- 默认尝试 GPU；初始化失败后完整释放失败实例并自动回退一次 CPU，UI 展示实际后端与回退原因。
- 单线程串行管理 `Engine` / `Conversation` 的 JNI 生命周期。
- 支持多轮对话、Flow 流式输出、停止生成、清空 Conversation 和显式卸载。
- 展示 LiteRT-LM 版本、设备/ABI、模型摘要、初始化耗时、首 Token 延迟、Prefill/Decode Token 与吞吐率。
- APK 只打包 `arm64-v8a`，最低支持 Android 12（API 31）。

## 工程结构

```text
app/       Activity、ViewPager2 三个验证页、Activity 级 ViewModel
edge-ai/   LiteRT-LM 封装、模型存储、后端回退与诊断数据
openspec/  integrate-litert-lm-demo 的 proposal/design/tasks/delta specs
```

`Conversation` 和 `Engine` 按此顺序关闭。旋转屏幕时 Activity 级 ViewModel 会保留运行时；显式卸载、切换模型或 ViewModel 最终销毁时释放原生资源。

## 准备模型

推荐使用 [LiteRT Community Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main) 中约 584 MB 的 INT4 `.litertlm` 模型。仓库中的文件名可能随版本调整；本方案最初验收文件为：

```text
Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm
```

当前文件列表也可能显示为 `gemma3-1b-it-int4.litertlm`。应用接受任意非空 `.litertlm` 文件，但模型是否与 LiteRT-LM 0.15.0 兼容仍由运行时校验。

本地预置版本使用以下已校验的二进制（584,417,280 bytes）：

```text
SHA-256: 1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be
```

Gemma 模型受 Gemma 许可约束。构建者必须先登录 Hugging Face、阅读并接受许可，再把文件放到以下本机路径：

```text
app/src/main/assets/models/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm
```

该文件会被打进本机生成的 APK，但仍被 Git 忽略：它超过 GitHub 普通文件限制，且模型再分发需要由发布者履行 Gemma 许可义务。项目不会保存 Hugging Face Token。

## 构建与运行

环境要求：JDK 17、Android SDK 34，以及 Android 12+ ARM64 设备。

```bash
./gradlew :app:verifyBundledGemmaModel :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

运行步骤：

1. 首次启动等待约 584 MB 内置模型复制完成；进度会显示在“模型”页。
2. 应用自动尝试 GPU 初始化，失败时自动回退 CPU；无需手动点击初始化。
3. 在“对话”页发送问题，观察同一条助手消息的流式更新。
4. 切换飞行模式后继续对话，确认推理不依赖网络。
5. 可在“模型”页手动替换模型、切换后端或重新初始化，并在“诊断”页记录指标。

GPU 模式需要 Manifest 中声明 `libvndksupport.so` 和 `libOpenCL.so`。两项均为 `required=false`；设备不提供兼容 OpenCL 时，应用会展示错误并回退 CPU。

## 验证

```bash
openspec validate integrate-litert-lm-demo --strict
./gradlew :app:assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

模拟器只用于导航、无模型状态和错误流程，不作为推理性能结论。最终验收必须在 Android 12+ ARM64 实机执行：重启恢复模型、GPU 成功或可观测回退、飞行模式流式回答、连续五轮、停止、清空、旋转、卸载再初始化，并确认诊断指标可读取。

## OpenSpec 工作流

长期方案位于 [`openspec/changes/integrate-litert-lm-demo`](openspec/changes/integrate-litert-lm-demo)。实现和实机验收完成前不归档；验收后再将 delta specs 合并到 `openspec/specs/`。OpenSpec 使用说明见 [Fission-AI/OpenSpec](https://github.com/Fission-AI/OpenSpec)。

## 已知限制

- 仅支持 ARM64 和 Android 12+。
- 内置模型会使 APK 增大约 584 MB；首次安装至少需要同时容纳 APK 和应用私有模型副本，并预留导入安全空间。
- 第一阶段最大输出为 512 Token，采样参数固定为 `topK=10`、`topP=0.95`、`temperature=0.8`。
- 性能和 GPU 可用性取决于设备、驱动、内存与模型；不设置跨设备统一阈值。
- BenchmarkInfo 在 LiteRT-LM 0.15.0 中属于实验性 API，升级 SDK 必须单独创建并验证 OpenSpec change。
- 模型文件、`.partial`、Token、本机路径和构建产物均被 Git 忽略。
