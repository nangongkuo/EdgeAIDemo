## Why

当前客户端把“创建日程”当作普通聊天处理，只生成建议文本，既没有调用 Android Calendar Provider，也没有审批、权限和可验证回执，导致模型看似完成但实际没有产生任何日历副作用。同时，内部角色提示被旧聊天质量策略当成用户原文处理，向界面泄漏 `SYSTEM/USER/ASSISTANT`。

## What Changes

- 新增 `android.calendar.create_event` 原生日历工具，通过 `CalendarContract` 原子写入事件与提醒并返回真实事件回执。
- 新增确定性 `ActionResolver` 与 `ToolAction` 执行路径，使明确的创建日程请求不依赖 FunctionGemma，也不创建 Supervisor Worker。
- 将日历写入纳入统一工具注册、Schema 校验、HIGH 风险审批、Android 运行时权限、审计、取消和恢复链路。
- 在对话页和 Agent 页同时提供审批入口；权限拒绝、无可写日历和执行异常均明确显示“未创建”。
- 使用稳定幂等标记查询并复用已创建事件，避免进程退出或重试导致重复日程。
- 缺少日期、时间或目标日历存在歧义时进入用户输入门，不猜测执行。
- 分离兼容聊天与 Agent 提示词，保证未收到成功工具结果前不声称操作完成，并阻止内部角色标签进入语义复述与原文保留策略。
- 保持现有单 Agent 快速路径、`LOCAL_ONLY` 行为和 Gemma3-only 安装可用；不新增强制 FunctionGemma 或云端回退。

## Capabilities

### New Capabilities

- `android-calendar-action`: Android 日历事件的解析、审批、权限、原子写入、回执、幂等恢复和错误闭环。
- `deterministic-tool-action`: 明确客户端动作的确定性识别、信息补全和不依赖模型 Tool Calling 的统一工具执行路径。

### Modified Capabilities

- `multilingual-chat-policy`: 语义复述与原文保留仅处理真实用户输入，Agent 与兼容聊天使用不同系统指令。

## Impact

- 公共 Agent API 增加动作解析、预备工具调用、工具权限和直接工具执行契约。
- `edge-agent-core` 增加动作路由、等待用户输入与恢复执行逻辑。
- `edge-agent-capabilities` 增加 Android Calendar Provider 实现和日程解析器。
- `edge-agent-adk`/Provider engine 增加不依赖模型的 `ToolAction` 执行和确定性终态回执。
- `edge-agent-litertlm` 共享同一 ToolRegistry，并修复结构化提示与真实用户输入边界。
- `app` 增加日历权限声明、对话内审批与运行时权限请求。
- 测试覆盖日期解析、审批/权限、原子写入、幂等恢复、提示泄漏和 UI 闭环。

非目标：不实现闹钟、日程更新/删除、Android MCP Server、强制 FunctionGemma、云端 Tool Calling 回退或任意动态代码执行。
