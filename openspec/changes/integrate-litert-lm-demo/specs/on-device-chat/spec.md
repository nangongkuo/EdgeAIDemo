## 新增需求

### 需求：自动启动初始化
应用 SHALL 在启动配置完成后自动初始化有效的已选模型，而无需用户点击“初始化”。

#### 场景：自动 GPU 启动成功
- **WHEN** 已选模型或刚安装模型可用，且 GPU 初始化成功
- **THEN** 运行时 SHALL 自动进入就绪状态，并启用对话输入

#### 场景：自动 GPU 启动需要 CPU 回退
- **WHEN** 自动 GPU 初始化失败而 CPU 初始化成功
- **THEN** 运行时 MUST 在 CPU 上进入就绪状态，并展示 GPU 失败原因

#### 场景：自动启动初始化失败
- **WHEN** 两种后端都无法初始化模型
- **THEN** 应用 SHALL 保持模型管理控件可用，并展示可恢复错误，以便重试、替换或删除

### 需求：感知后端的运行时初始化
运行时 SHALL 在主线程外使用首选后端初始化已选模型，并在状态中暴露实际后端。

#### 场景：Android 模拟器 GPU 预检
- **WHEN** 在由标准 generic、ranchu 或 goldfish 运行时特征识别出的 Android 模拟器上首选 GPU
- **THEN** 运行时 MUST 在不尝试 GPU Engine 的情况下初始化 CPU，并 SHALL 报告 GPU 为首选、CPU 为实际后端和模拟器/OpenCL 兼容性原因

#### 场景：保留真机 GPU 行为
- **WHEN** 在非模拟器 Android 设备上首选 GPU
- **THEN** 运行时 SHALL 在现有 CPU 回退行为之前继续尝试 GPU 初始化

#### 场景：GPU 初始化成功
- **WHEN** 首选 GPU 且 LiteRT-LM 成功初始化
- **THEN** 运行时 SHALL 以 GPU 作为实际后端进入就绪状态

#### 场景：GPU 初始化失败
- **WHEN** 首选 GPU 且初始化抛出异常
- **THEN** 运行时 MUST 关闭部分原生资源，保留 GPU 错误作为回退原因，并且只尝试一次 CPU 初始化

#### 场景：CPU 回退同样失败
- **WHEN** CPU 回退无法初始化模型
- **THEN** 运行时 SHALL 进入可恢复错误状态，且 MUST NOT 保留 Engine 或 Conversation

### 需求：离线流式对话
运行时 SHALL 在模型导入后通过 LiteRT-LM Flow 进行本地多轮文本生成，无需网络访问。

#### 场景：流式生成响应
- **WHEN** 运行时处于就绪状态且用户提交非空提示词
- **THEN** 运行时 SHALL 按序发出文本增量，随后发出一个完成事件，并保留对话上下文

#### 场景：拒绝并发生成
- **WHEN** 已有生成任务处于活动状态，且又提交了另一条提示词
- **THEN** 运行时 MUST 拒绝第二条提示词，而不得启动另一项原生生成

### 需求：LiteRT-LM 完成回调 ABI 兼容性
对于每一种包含 LiteRT-LM 0.15.0 的打包运行时变体，应用 MUST 解析 `org.jetbrains.kotlinx:kotlinx-coroutines-core` 和 `org.jetbrains.kotlinx:kotlinx-coroutines-android` 的 1.11.0 版本。

#### 场景：验证打包协程运行时
- **WHEN** 解析调试运行时依赖图
- **THEN** Gradle SHALL 为协程 core 和 Android 构件均选择 1.11.0，且 MUST NOT 因 LiteRT-LM 已发布元数据或其他传递依赖选择更低版本

#### 场景：完成流式响应
- **WHEN** LiteRT-LM 完成一次原本成功的流式响应
- **THEN** 应用 MUST 保持存活、发出正常完成事件，并且 MUST NOT 因 `SendChannel.close$default` 的 `NoSuchMethodError` 而终止

### 需求：生成取消
应用 SHALL 允许用户取消活动生成，并让运行时回到可用的就绪状态。

#### 场景：取消活动响应
- **WHEN** 用户在生成期间选择“停止”
- **THEN** 运行时 MUST 调用 LiteRT-LM 取消、停止收集输出，并准备好接收下一条提示词

### 需求：重置对话
运行时 SHALL 在不重新加载 Engine 的情况下清除对话历史。

#### 场景：清空对话历史
- **WHEN** 运行时就绪时用户确认“清空对话”
- **THEN** 运行时 MUST 关闭旧 Conversation，使用默认采样配置新建 Conversation，并保留已加载的 Engine

### 需求：确定性资源释放
运行时 MUST 在模型变更、显式卸载或最终所有者清理时先关闭 Conversation，再关闭 Engine。

#### 场景：卸载模型
- **WHEN** 用户卸载活动模型
- **THEN** 运行时 SHALL 取消活动任务、释放全部原生资源，并回到未加载状态
