## 1. 回声策略与运行时

- [x] 1.1 在 `ChatLanguagePolicy` 中加入直接回答/能力限制系统约束、能力问答初始消息、`thinking=false` 及温和重复抑制配置；保持原始 USER 负载不变。
- [x] 1.2 实现纯 Kotlin 回声质量策略，输出规范化文本、字符二元组 Dice 分数及是否拒绝的确定结果。
- [x] 1.3 在 `LiteRtLmRuntime` 维护已接受历史；回声时发送 Reset、关闭并从干净历史重建 Conversation、最多重试一次，二次失败后恢复干净状态并返回质量错误。

## 2. 可观测性与 UI

- [x] 2.1 增加 Debug 生成追踪：请求/尝试、模型/后端/配置、SDK role/channel/content 预览、质量分数、Benchmark 和完成状态；Release 不记录正文。
- [x] 2.2 扩展 `RuntimeDiagnostics` 与诊断页，展示最近请求 ID、尝试次数、回声拒绝及显式 thinking 状态，不展示完整对话正文。
- [x] 2.3 扩展 `GenerationEvent` 和 ViewModel，使 Reset 能清除已流出的坏回答，并保证失败或重试不会残留旧文本。

## 3. 自动化回归

- [x] 3.1 `ECHO-UNIT-01`（必需，单元）：为截图两组回声、直接解释、短输入和字面内容编写质量策略测试；运行 `./gradlew :edge-ai:testDebugUnitTest`，期望全部通过，证据为 Gradle 输出/XML。
- [x] 3.2 `ECHO-UNIT-02`（必需，单元）：验证系统策略、示例、thinking=false、重复抑制、原始 USER 负载、一次重试和坏历史恢复；运行 `./gradlew :edge-ai:testDebugUnitTest`，期望全部通过。
- [x] 3.3 `ECHO-APP-01`（必需，单元）：验证 ViewModel 在 Delta 后收到 Reset 会清空文本、随后接受重试回答，以及 Reset 后失败不保留回声；运行 `./gradlew :app:testDebugUnitTest`，期望全部通过。
- [x] 3.4 `ECHO-BUILD-01`（必需，构建/lint）：运行 `./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`，期望编译/lint 成功且 APK 无 INTERNET 权限。

## 4. 设备验收

- [x] 4.1 `ECHO-CPU-01`（必需，模拟器设备）：在 `emulator-5554` 安装调试 APK并运行截图三轮语料；期望最终回答不是高相似度反问、进程存活、日志含 thinking=false 和质量判定；证据保存到 `build/bugfix-reports/fix-chat-semantic-echo/`。
- [ ] 4.2 `ECHO-GPU-01`（必需，真机设备）：在 Android 12+ ARM64 真机 GPU 运行同一语料；期望满足相同质量契约且无崩溃；无真机时保持未完成并记录阻塞。

## 5. 收尾

- [x] 5.1 运行 `git diff --check`、`openspec validate fix-chat-semantic-echo --strict` 并审查最终状态；仅在全部必需设备测试通过后归档。
