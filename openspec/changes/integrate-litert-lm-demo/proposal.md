## 原因

EdgeAIDemo 当前包含一个无关的嵌套 ViewPager 手势示例以及过时的 Android 构建链，尚未验证 Google AI Edge。项目需要一个聚焦、可复现的验证界面，用于在 Android 上加载 LiteRT-LM 模型、运行私密离线文本生成、观察硬件回退并收集运行时诊断信息。

## 变更内容

- 用包含“模型”“对话”“诊断”页面的 AI 验证控制台替换手势示例。
- 为 Kotlin 2.2 字节码升级 Android 工具链，并固定 LiteRT-LM 为 0.15.0。
- 将项目收敛为应用模块及独立的 `edge-ai` 运行时库。
- 将本地已完成许可审批的 Gemma `.litertlm` 作为未压缩 APK 资源打包，在首次启动时以事务方式安装到应用私有存储中，并保留 SAF 替换能力。
- 应用启动时使用 GPU 优先、CPU 回退策略自动初始化已选中或刚安装的模型。
- 在启动初始化前检测 Android 模拟器，明确给出诊断原因并选择 CPU，避免用户首次提问后才显示延迟出现的 GPU/OpenCL 采样器错误。
- 在合并后的 Manifest 中声明 LiteRT-LM 可选的 Android GPU 系统库依赖，以便兼容的真机可以访问它们。
- 覆盖 LiteRT-LM 0.15.0 发布的协程元数据，并一致解析 `kotlinx-coroutines-core` 与 `kotlinx-coroutines-android` 1.11.0，避免原本成功响应结束后，其完成回调调用缺失的 `SendChannel.close$default` 桥接方法。
- 添加串行化的 Engine 和 Conversation 生命周期管理、GPU 优先初始化与 CPU 回退。
- 流式输出多轮响应，支持取消、重置和卸载，并展示 LiteRT-LM 基准数据。
- 记录模型许可证、真机验证、离线行为与已知限制。

## 能力

### 新增能力

- `model-management`：安全地导入、校验、持久化、替换和删除本地 LiteRT-LM 模型。
- `on-device-chat`：初始化 LiteRT-LM、选择后端、流式输出多轮文本、取消任务并释放原生资源。
- `runtime-diagnostics`：报告设备、SDK、模型、后端回退和推理基准信息。

### 修改能力

无。本仓库尚无既有的 OpenSpec 能力。

## 影响范围

- 替换当前 UI，移除 `base` 和 `biz-app` 模块及其无关依赖。
- 新增 `edge-ai` 模块和 `com.google.ai.edge.litertlm:litertlm-android:0.15.0`。
- 将全部 Android 协程运行时和测试构件固定为 1.11.0，作为 LiteRT-LM 二进制兼容性解决方案；0.15.0 的 POM 错误声明为 1.9.0。
- 将 minSdk 提升到 31，打包原生 ABI 限为 ARM64，并升级 Gradle、AGP、Kotlin 及 AndroidX 测试工具。
- 增加约 584 MB 的本地模型构建输入以及相应的大型 APK；二进制不纳入版本控制，但在接受许可后会被纳入本地构建的 APK。
- 首次启动安装时，APK 资源与私有推理副本同时存在，设备可用存储空间需约为模型大小的两倍。
- 影响 `LiteRtLmRuntime`、后端选择单元测试覆盖以及应用 Manifest；不改变模型、用户提示词或面向云端的行为。
