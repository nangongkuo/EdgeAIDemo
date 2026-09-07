## 1. 规范与构建基础

- [x] 1.1 为 Codex 初始化 OpenSpec，并严格验证完整的变更工件
- [x] 1.2 升级 Gradle、AGP、Kotlin、JDK 目标、AndroidX 测试、SDK 基线和 ARM64 打包
- [x] 1.3 用 `app` 和 `edge-ai` 替换旧模块图，并移除无关依赖

## 2. 模型管理

- [x] 2.1 定义模型描述符、导入事件和模型存储接口
- [x] 2.2 实现事务式 SAF 导入、SHA-256 命名、进度、元数据持久化和遗留临时文件清理
- [x] 2.3 实现与运行时卸载协同的安全模型替换和删除
- [x] 2.4 实现从未压缩内置资源进行的首次启动事务式安装
- [x] 2.5 在用户接受许可证后下载受限的 Gemma 模型，并置于本地资源路径（584,417,280 字节；SHA-256 `1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`）

## 3. LiteRT-LM 运行时

- [x] 3.1 定义不依赖 SDK 的运行时状态、生成事件、后端、诊断信息和运行时接口
- [x] 3.2 实现串行化的 Engine/Conversation 初始化，以及 GPU 优先、CPU 回退
- [x] 3.3 实现流式生成、单请求约束、取消、会话重置、基准映射和确定性关闭
- [x] 3.4 在 ViewModel 启动期间自动初始化既有或刚安装的模型

## 4. 验证控制台

- [x] 4.1 创建 Activity 作用域的 ViewModel 和共享 UI 状态
- [x] 4.2 使用 XML View 和 ViewPager2 构建“模型”“对话”“诊断”标签页
- [x] 4.3 添加消息渲染、导入进度、后端控制、错误恢复和基准展示

## 5. 验证与文档

- [x] 5.1 添加模型存储、运行时协作和 ViewModel 状态行为的单元测试
- [x] 5.2 运行 OpenSpec 严格验证、单元测试、lint、调试 APK 和 AndroidTest APK 构建
- [x] 5.3 验证 applicationId、仅 ARM64 的原生打包、被忽略的本地/模型工件及清晰的错误路径
- [x] 5.4 记录模型许可/下载、离线工作流、真机验收和已知限制
- [x] 5.5 在归档 OpenSpec 变更前完成 Android 12+ ARM64 真机推理验收（Xiaomi 2206123SC、Android 15 / API 35、`arm64-v8a`：GPU 初始化和五轮完成响应；进程保持存活，按 PID 过滤的日志不含 `NoSuchMethodError`）
- [x] 5.6 在准备受限资源后构建并检查包含模型的 APK（621,168,273 字节；已验证模型资源以原始方式存储且原生代码仅含 `arm64-v8a`）

## 6. Android Studio 模拟器 GPU 兼容性

- [x] 6.1 添加已记录的可选 GPU 原生库 Manifest 声明，并在 Engine 初始化前预检 Android 模拟器特征、选择 CPU
- [x] 6.2 为模拟器 CPU 选择添加必需的单元回归覆盖，并保留真机 GPU 优先选择（`GPU-EMU-01`）
- [x] 6.3 执行必需的构建/Manifest 验证（`GPU-EMU-02`）
- [ ] 6.4 在连接 Android Studio ARM64 模拟器时执行提示词、日志和视觉验收（`GPU-EMU-03`）

## 7. LiteRT-LM 完成回调 ABI 兼容性

- [x] 7.1 将应用、运行时模块和协程测试依赖固定为 `kotlinx-coroutines` 1.11.0；这是已发布 LiteRT-LM 0.15.0 AAR 所需的最低运行时 ABI
- [x] 7.2 添加依赖解析验证并运行单元/构建验证，证明 `kotlinx-coroutines-core` 和 `kotlinx-coroutines-android` 仅解析到 1.11.0（`ABI-01`）
- [x] 7.3 在 Android 12+ ARM64 真机或 `emulator-5554` 上验证：完成响应后进程仍存活且不出现 `SendChannel.close$default`（`ABI-02`）；已在连接的真机上通过
