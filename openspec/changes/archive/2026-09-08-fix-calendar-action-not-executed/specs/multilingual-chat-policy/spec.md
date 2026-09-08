## MODIFIED Requirements

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
