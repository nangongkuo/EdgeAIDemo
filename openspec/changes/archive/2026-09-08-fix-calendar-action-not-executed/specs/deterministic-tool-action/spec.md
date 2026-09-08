## ADDED Requirements

### Requirement: 确定性客户端动作解析
系统 SHALL 在模型路由前使用可扩展 ActionResolver 识别高置信度客户端动作，并 MUST 对建议类请求保持普通问答行为。

#### Scenario: 明确创建日程
- **WHEN** 用户明确请求创建、加入或安排带日期和时间的日程
- **THEN** Resolver MUST 生成规范化 PreparedToolInvocation，且请求 MUST 保持单 Agent 快速路径

#### Scenario: 仅请求规划建议
- **WHEN** 用户请求“帮我规划明天的日程”或作息建议而未要求写入日历
- **THEN** Resolver MUST NOT 产生写工具调用，请求 SHALL 进入普通问答

#### Scenario: 当前只有通用模型
- **WHEN** 未安装 FunctionGemma 且模型能力声明 toolCalling=false
- **THEN** 明确日历动作 MUST 仍能通过确定性 ToolAction 完成

### Requirement: 统一工具执行链
PreparedToolInvocation MUST 经由共享 ToolRuntime 完成能力发现、Schema 校验、策略检查、审批、审计和执行，不得由 Resolver 直接产生副作用。

#### Scenario: 根 Agent 声明能力未注册
- **WHEN** SDK 初始化时根 Agent 白名单包含 Registry 无法发现的能力
- **THEN** 初始化 MUST 失败并明确列出缺失能力

#### Scenario: LOCAL_ONLY 动作
- **WHEN** LOCAL_ONLY 请求命中本地日历 ToolAction
- **THEN** 系统 SHALL 执行本地能力且 MUST NOT 产生云端模型或工具请求

#### Scenario: 工具事件顺序
- **WHEN** 用户批准并成功执行直接 ToolAction
- **THEN** Journal 中事件 MUST 按 PROPOSED、WAITING_APPROVAL、RUNNING、SUCCEEDED、Terminal 的因果顺序出现

### Requirement: 工具结果驱动最终回答
系统 MUST 根据持久化 ToolResult 或执行回执回答副作用状态，未获得成功结果前不得声称操作完成。

#### Scenario: 成功后的追问
- **WHEN** 最近一次日历 ToolResult 成功且用户追问“定了吗”
- **THEN** 系统 MUST 从回执回答已创建事件的准确标题和时间

#### Scenario: 未成功时追问
- **WHEN** 当前 Session 没有成功日历回执且用户追问“定了吗”
- **THEN** 系统 MUST 回答“还没有创建”，不得反问或虚构完成状态
