## Context

现有 `LiteRtAgentController` 只注册通用 Gemma3，并用未注入 `ToolRuntime` 的 `AdkAgentEngine` 创建客户端。根 Agent 未声明日历能力，应用也没有 Calendar Provider 实现与日历权限，因此创建日程请求只能进入普通模型生成。当前 LiteRT 适配器还把 system、history 和用户消息拼成单个提示，再交给兼容聊天的语义质量策略，造成内部角色标签泄漏。

该改动横跨公共 API、持久化运行协调、ADK 适配、本地模型、能力实现和 Android UI。Android 12+、ARM64、应用私有模型存储以及 LiteRT Engine/Conversation 串行生命周期保持不变。日历副作用仅通过宿主进程中的受控 ToolRuntime 执行，不占用模型执行许可。

## Goals / Non-Goals

**Goals:**

- 明确的创建日程请求在只有 Gemma3、完全离线时也能进入真实工具链。
- 写入前完成参数预览、HIGH 风险审批和运行时权限授权；写入后返回可核验事件 ID。
- 缺少必要信息、目标日历歧义、权限拒绝和 Provider 异常均形成明确、可恢复终态。
- 进程退出和重试不会无条件重复创建日程。
- 兼容聊天质量策略只分析真实用户输入，不再泄漏内部角色标签。

**Non-Goals:**

- 不实现闹钟、事件更新或删除。
- 不要求安装 FunctionGemma，不增加云端回退。
- 不改变端侧模型串行 Broker、GPU→CPU 回退或模型存储策略。
- 不把逻辑 Worker 映射为 Android Worker，也不为简单日历动作创建 Worker。

## Decisions

### 1. 确定性动作优先于模型 Tool Calling

新增 `ActionResolver`，在普通 `RequestRouter` 前解析明确的创建日程意图，产出 `PreparedToolInvocation` 或 `UserInputRequired`。确定性解析器只匹配“创建/加入/安排日程”等有副作用的表达，“规划/建议作息”等继续走聊天。选用该方案是因为当前 Gemma3 不支持结构化 Tool Calling；强制安装 FunctionGemma会增加模型体积、内存驻留和启动成本，云端回退又违反 `LOCAL_ONLY`。

### 2. 直接动作复用统一 ToolRuntime

新增 `AgentEngineRequest.ToolAction`，由 `ProviderAgentEngine` 直接执行预备调用。它复用 ToolRegistry 的 Schema、能力白名单、策略、审批、审计和取消，但不先让模型生成 JSON。成功终态来自工具的结构化回执，未获得成功 `ToolResult` 时不得输出“已创建”。根 Agent 和 ToolRegistry 使用同一实例，初始化阶段校验所声明能力可发现。

### 3. 日历写入是 HIGH、非幂等、本地能力

能力 ID 固定为 `android.calendar.create_event`。工具声明 `HIGH`、`idempotent=false`、`local=true`，需要 `READ_CALENDAR` 与 `WRITE_CALENDAR`。输入在审批前固定为 ISO 时间、设备时区、30 分钟默认时长和 0 分钟提醒；执行时用一次 `ContentResolver.applyBatch` 写入 Event 与 Reminder。

虽然官方通常推荐普通应用使用系统日历 Insert Intent，本方案选择直接 Provider 写入，因为产品要求在 SDK 内得到事件 ID、执行状态和可恢复回执。宿主仍负责运行时权限 UI。

### 4. 审批与 Android 权限分层

ToolRuntime 先持久化 Approval；用户点击批准时，对话页或 Agent 页检查工具声明的 Android 权限。首次使用才发起权限请求，全部授予后才提交 `APPROVE_ONCE`。拒绝权限会提交拒绝决定并明确“未创建”。审批预览持久化绝对时间；若恢复时开始时间已过去，执行器返回过期错误，要求用户重新确认。

### 5. 使用外部标记关闭重复写入窗口

稳定 `idempotencyKey` 的 SHA-256 写入 `Events.CUSTOM_APP_URI`。每次执行先查询该 URI：存在则返回原事件且 `reused=true`；确认不存在才执行批量写入。ToolCall Journal 继续记录 PENDING/RUNNING/SUCCEEDED/UNKNOWN 和结果正文。Provider 查询失败代表外部状态不可确认，运行保持需用户确认，不自动重放。

### 6. 结构化输入与质量策略隔离

LiteRT 模型适配层分别传递 system、history、current user input 和 `originalUserInput`。重建模型上下文可以包含角色信息，但语义复述检测与字面保留只接收 `originalUserInput`。兼容 `EdgeAiRuntime` 保留“仅文字问答”指令；Agent 指令改为只能使用已注册/授权能力，并禁止在成功工具回执前宣称完成。

## Risks / Trade-offs

- [中文时间表达覆盖不完整] → 解析器采取保守匹配；不支持或歧义表达进入用户输入门，不猜测写入。
- [Calendar Provider 实现差异] → 限定标准 `CalendarContract` 字段，使用契约测试与模拟 Provider；真机再验证事件和提醒。
- [用户批准后才弹系统权限会增加一步] → 两个页面使用相同批准入口，授权仅首次出现，拒绝产生明确终态。
- [写入成功但进程在记录回执前退出] → 用 `CUSTOM_APP_URI` 在重试前查询并复用外部事件。
- [多个可写日历选择造成流程延长] → 优先已保存偏好；否则生成 HumanGate，用户选择后再生成最终审批。
- [模拟器没有真实账户日历] → 模拟器验证权限、UI 和错误路径；Calendar Provider 真写入在配置有可写日历的测试设备上验收。

## Migration Plan

1. 先引入 API 契约、动作路由和单元测试，不改变普通聊天路径。
2. 注册 Android 日历能力并让 Controller 共享 ToolRegistry；加入初始化能力校验。
3. 接入两个页面的审批与权限流程，再启用确定性日历 Resolver。
4. 修复 LiteRT 原始用户输入边界并运行旧多语言回归测试。
5. 完成模块测试、Debug 构建、模拟器 UI/权限验证；有可写日历的 ARM64 真机完成最终写入验收。

回滚时移除日历 Resolver 注册即可停止副作用入口；已创建的系统日历事件不会自动删除。

## Open Questions

- 1.0 之后是否增加日历偏好管理 UI，以及事件更新/删除能力，留待独立变更决定。
