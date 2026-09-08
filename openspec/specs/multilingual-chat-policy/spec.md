# 多语言对话策略规范

## Purpose
待定——由归档变更 support-bilingual-chinese-chat 创建。归档后请更新本节目的。

## Requirements

### Requirement: 透明的多语言提示词处理
应用 SHALL 将中文、英文和混合语言提示词作为 Unicode 输入接受，并且 MUST 将每一条非空提示词转发到端侧运行时；不得翻译、转写、按区域设置改写、过滤字符、裁剪空白、添加当前轮次指令、XML/标记信封或任何其他提示词装饰。精确的提示词文本 MUST 是传给 LiteRT-LM 的完整用户轮次负载。

#### Scenario: 发送中英文混合提示词
- **WHEN** 就绪运行时收到 `请 explain JVM 的 GC, include a Java example`
- **THEN** 运行时 MUST 精确收到该提示词，LiteRT-LM MUST 将完全相同的字符串作为用户轮次负载接收，且对话 UI SHALL 在用户消息中保留相同文本

#### Scenario: 拒绝全空白提示词
- **WHEN** 用户提交仅包含空白字符的文本
- **THEN** 应用 SHALL 不创建用户或助手对话消息，且 MUST NOT 开始生成

### Requirement: 默认简体中文响应策略
每个新 LiteRT-LM Conversation SHALL 接收与运行模式匹配的中文优先系统指令，采样参数为 topK 10、topP 0.95、temperature 0.2、seed 0，输出上限为 512 Token。兼容聊天模式 SHALL 保留仅文字问答边界；Agent 模式 SHALL 允许调用已注册且已授权的能力，并 MUST 在成功 ToolResult 前禁止声称外部操作已完成。语言策略 MUST NOT 装饰、重复或替换可见的用户轮次负载。

#### Scenario: 回答中文、英文或混合语言请求
- **WHEN** 用户以中文、英文或两者混合发送请求，且未指定输出语言
- **THEN** Conversation SHALL 被指示使用简体中文回答

#### Scenario: 保留字面内容
- **WHEN** 真实用户输入包含代码、命令、URL、文件名、产品名、缩写、引用文本或明确要求的英文术语
- **THEN** Conversation MUST 被指示在使用简体中文解释时原样保留该字面内容；流式完成后，运行时 MUST 只基于真实用户输入追加任何缺失的已识别字面内容

#### Scenario: 忽略内部角色与工具文本
- **WHEN** 重建的模型上下文包含 SYSTEM、USER、ASSISTANT、工具 Schema 或历史角色标签
- **THEN** 语义复述检测和原文保留 MUST NOT 把这些内部内容当成真实用户字面量追加到响应

#### Scenario: 遵循明确的目标语言
- **WHEN** 用户明确请求翻译，或指定以某种目标语言回答
- **THEN** Conversation SHALL 被指示遵循该明确请求，而非默认简体中文响应语言

### Requirement: 直接回答完整性
应用 MUST 使用 LiteRT-LM 的会话模板实现角色分离，且 SHALL NOT 在第二条当前轮次指令中把用户问题表示为引用、分隔或嵌套内容。对于代表性的中文问题，可见助手响应 SHALL 是生成的回答，而不是用户消息的客户端回显。

#### Scenario: 提问中文能力问题
- **WHEN** 就绪运行时收到 `请用一句话说明你能做什么，不要复述这句话。`
- **THEN** LiteRT-LM MUST 收到完全相同的字符串，且验收设备上的已完成助手响应 MUST 非空；忽略普通首尾空白和结尾标点后，MUST NOT 与该用户问题相同

### Requirement: 策略连续性与说明
应用 SHALL 在自动启动初始化、手动模型初始化、后端回退初始化和会话重置期间应用语言策略，并且 MUST 在对话页说明输入和响应行为。

#### Scenario: 清空对话
- **WHEN** 用户清空就绪对话并发送新的英文或混合语言提示词
- **THEN** 替换后的 Conversation MUST 保留中文优先策略

#### Scenario: 查看支持的行为
- **WHEN** 用户打开对话页
- **THEN** UI SHALL 说明支持中文、英文和混合输入，且回答默认使用简体中文

#### Scenario: 模型语言策略未通过设备验收
- **WHEN** 已连接的 Android 12+ ARM64 设备未通过任何必需的离线多语言验收用例
- **THEN** 此变更 MUST 保持未完成，且应用 SHALL 不宣称多语言行为已验证
