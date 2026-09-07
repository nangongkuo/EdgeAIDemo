## ADDED Requirements

### Requirement: 可关联的生成诊断
调试构建 MUST 为每个端侧生成请求记录可关联的诊断事件，至少包含请求 ID、模型标识、实际后端、采样/重复抑制/thinking 配置、尝试序号、SDK 消息角色、消息通道名称、回声质量判定、生成完成或失败状态和可用的 Benchmark Token 指标。发布构建 MUST NOT 输出用户问题或模型回答正文。

#### Scenario: 调试构建完成正常生成
- **WHEN** Debug 构建完成一轮非回声生成
- **THEN** 日志 MUST 能通过同一请求 ID 关联开始、首消息、质量判定、Benchmark 和完成事件，并明确记录 `thinkingEnabled=false`

#### Scenario: 调试构建触发干净重试
- **WHEN** Debug 构建拒绝首次回答并重建 Conversation
- **THEN** 日志 MUST 记录首次相似度、Conversation 重建、第二次尝试及最终接受或拒绝结果

#### Scenario: 发布构建执行生成
- **WHEN** Release 构建处理包含用户内容的提示词和回答
- **THEN** 日志 MUST NOT 包含问题或回答正文，只能保留非正文指标和不可逆关联信息

### Requirement: 诊断页显示最近质量状态
应用诊断页 SHALL 显示最近生成请求 ID、尝试次数、是否因回声被拒绝以及显式 thinking 状态，并且 MUST NOT 在该页面持久化或展示完整用户问题与模型回答。

#### Scenario: 查看回声重试诊断
- **WHEN** 最近一轮生成曾触发回声重试并已结束
- **THEN** 诊断页 MUST 显示对应请求 ID、尝试次数大于一、回声拒绝为是且 thinking 为关闭
