# Android 日历动作规范

## Purpose
定义 Android 客户端 Agent 创建系统日历事件时的审批、权限、原子写入、回执和恢复要求。

## Requirements

### Requirement: 受控创建 Android 日历事件
系统 MUST 将明确的创建日程请求转换为 `android.calendar.create_event` 调用，并在写入前完成 HIGH 风险审批和 Android 日历权限检查。

#### Scenario: 批准并创建明早起床日程
- **WHEN** 用户请求“帮我定个明天早上八点起床的日程”、批准预览且授予日历权限
- **THEN** 系统 MUST 在设备时区创建标题为“起床”的次日 08:00–08:30 事件，并创建开始时提醒

#### Scenario: 审批前不写入
- **WHEN** 日历工具调用处于待审批状态
- **THEN** Calendar Provider 调用次数 MUST 为 0，且对话页与 Agent 页 MUST 都能展示同一审批

#### Scenario: 用户或权限拒绝
- **WHEN** 用户拒绝工具审批或拒绝 READ_CALENDAR/WRITE_CALENDAR 权限
- **THEN** 系统 MUST NOT 写入事件，并 MUST 明确显示本次日程未创建

### Requirement: 原子写入和可核验回执
系统 SHALL 使用单次 `applyBatch` 写入 Event 与 Reminder，并且只有在获得真实事件 ID 后才能声明创建成功。

#### Scenario: 成功返回事件回执
- **WHEN** Calendar Provider 成功提交批量操作
- **THEN** 工具结果 MUST 包含事件 ID、URI、日历 ID、标题、起止时间、时区、提醒分钟数和 reused 标记

#### Scenario: 提醒写入失败
- **WHEN** Provider 无法原子写入 Event 与 Reminder
- **THEN** 系统 MUST 返回失败且 MUST NOT 生成虚假的成功回执

#### Scenario: 没有可写日历
- **WHEN** 已授予权限但设备上不存在可写日历
- **THEN** 系统 MUST 明确返回“未创建”，不得改为文字日程建议冒充结果

### Requirement: 日历副作用去重与恢复
系统 MUST 使用稳定幂等键标记外部事件，并在重试或恢复前核验外部状态。

#### Scenario: 重放已成功调用
- **WHEN** 相同 idempotencyKey 对应的事件已存在
- **THEN** 工具 MUST 返回原事件且 reused 为 true，不得创建重复事件

#### Scenario: 外部状态未知
- **WHEN** 恢复时无法确认对应事件是否存在
- **THEN** Run MUST 等待用户输入，不得自动重放非幂等写入

### Requirement: 时间与日历选择安全
系统 MUST 在计划提交时把相对时间固定为设备时区中的绝对时间，并对缺失或歧义信息请求用户输入。

#### Scenario: 解析跨月或跨年明天
- **WHEN** 固定 Clock 位于月末或年末且用户指定“明天早上八点”
- **THEN** 系统 MUST 解析为正确下一日的 08:00，并使用 30 分钟默认时长

#### Scenario: 缺少日期或时间
- **WHEN** 创建日程请求缺少日期或开始时间
- **THEN** Run MUST 进入 WAITING_USER_INPUT，Calendar Provider 调用次数 MUST 为 0

#### Scenario: 多个可写日历且无偏好
- **WHEN** 权限通过后发现多个可写日历且没有已保存选择
- **THEN** 系统 MUST 通过 HumanGate 让用户选择目标日历后再执行

#### Scenario: 批准时开始时间已过去
- **WHEN** 用户恢复并批准的绝对开始时间已经过去
- **THEN** 系统 MUST NOT 写入，并 MUST 请求用户重新确认时间
