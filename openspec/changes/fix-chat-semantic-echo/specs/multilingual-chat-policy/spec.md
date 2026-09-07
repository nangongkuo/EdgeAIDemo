## MODIFIED Requirements

### Requirement: 直接回答完整性
应用 MUST 使用 LiteRT-LM 的会话模板实现角色分离，且 SHALL NOT 在第二条当前轮次指令中把用户问题表示为引用、分隔或嵌套内容。对于代表性的中文问题，可见助手响应 SHALL 是生成的直接回答，而不是用户消息的客户端回显、近似复述或礼貌反问。运行时 MUST 检测高相似度回声；命中后 MUST 清除当前可见坏回答、移除受污染的原生轮次并在干净 Conversation 中最多重试一次。重试仍不合格时 MUST 返回明确的可恢复质量错误，且坏回答 MUST NOT 进入后续会话历史。

#### Scenario: 提问中文能力问题
- **WHEN** 就绪运行时收到 `请用一句话说明你能做什么，不要复述这句话。`
- **THEN** LiteRT-LM MUST 收到完全相同的字符串，且最终可见助手响应 MUST 非空、MUST 直接说明能力或限制，并且 MUST NOT 与该用户问题构成高相似度复述或反问

#### Scenario: 多轮问候后提问能力
- **WHEN** 用户依次发送 `hi`、`你能在这台手机上做什么` 和 `我想先看看你的能力再决定做什么，你告诉我你能做什么`
- **THEN** 每轮最终可见回答 MUST NOT 延续“您好 + 复述问题 + 问号”的回声模式，且任何被拒绝的模型回答 MUST NOT 污染下一轮上下文

#### Scenario: 干净重试仍产生回声
- **WHEN** 首次生成和一次干净 Conversation 重试都被判定为高相似度回声
- **THEN** 应用 MUST 清除回声文本、保留此前已接受的历史并显示可恢复质量错误，且 MUST NOT 无限重试

#### Scenario: 正常回答包含用户术语
- **WHEN** 模型直接解释用户问题并合理复用其中的产品名、代码、URL 或技术术语
- **THEN** 质量策略 MUST NOT 仅因存在相同字面片段而拒绝明显长于问题且包含解释内容的回答

## ADDED Requirements

### Requirement: 小模型直接回答策略
每个新 Conversation SHALL 收到明确的离线文字问答能力和限制说明、先回答而非复述或反问的约束、能力问答示例、显式关闭的 thinking 配置以及温和的重复抑制配置。应用 MUST 继续把原始非空用户文本作为完整 USER 负载发送，不得把防回声指令拼接到当前用户轮次。

#### Scenario: 创建或重建 Conversation
- **WHEN** 应用自动初始化、手动初始化、清空会话或因回声重建 Conversation
- **THEN** 新 Conversation MUST 使用相同系统策略、示例、thinking=false 和重复抑制配置

#### Scenario: 回声后重发原始问题
- **WHEN** 首次回答被拒绝并开始干净重试
- **THEN** 重试调用 MUST 再次接收与用户输入完全相同的 USER 负载，且 MUST NOT 添加隐藏的当前轮次包装器
