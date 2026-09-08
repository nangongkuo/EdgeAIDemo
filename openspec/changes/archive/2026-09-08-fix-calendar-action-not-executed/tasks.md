## 1. 公共契约与动作路由

- [x] 1.1 增加 ActionResolver、PreparedToolInvocation、ToolAction 与工具 Android 权限契约
- [x] 1.2 在 RunScheduler 中接入确定性动作解析、WAITING_USER_INPUT 和继续执行
- [x] 1.3 在 ProviderAgentEngine 中复用 ToolRuntime 执行直接 ToolAction 并生成结果驱动终态
- [x] 1.4 增加 Agent 能力白名单与 ToolRegistry 一致性校验

## 2. Android 日历能力

- [x] 2.1 实现保守的中文日程动作解析器与固定 Clock/ZoneId 日期时间测试
- [x] 2.2 实现 AndroidCalendarToolProvider 的 Schema、可写日历发现和参数校验
- [x] 2.3 使用 CalendarContract applyBatch 原子创建事件和开始时提醒
- [x] 2.4 使用 CUSTOM_APP_URI 幂等标记实现重放查询、复用回执和未知状态保护
- [x] 2.5 注册共享 ToolRegistry、根 Agent 日历白名单和 READ/WRITE_CALENDAR Manifest 权限

## 3. 审批、权限与界面闭环

- [x] 3.1 在对话页增加待审批参数预览、批准和拒绝入口
- [x] 3.2 在对话页与 Agent 页批准前按需申请日历运行时权限
- [x] 3.3 处理权限拒绝、无可写日历、多个日历选择和过期审批的明确未创建状态
- [x] 3.4 使用最近 ToolResult 回答“定了吗”并显示真实事件回执

## 4. 提示词和质量策略隔离

- [x] 4.1 将兼容聊天与 Agent 系统指令分离
- [x] 4.2 让 LiteRT Host 仅用 originalUserInput 执行语义复述与原文保留检测
- [x] 4.3 增加内部角色标签不泄漏以及 URL、命令、文件名继续保留的回归测试

## 5. 验证

- [x] 5.1 运行 OpenSpec 严格校验并修复全部规格错误
- [x] 5.2 运行 API、Core、Capabilities、ADK、LiteRT 与 App 单元/契约测试
- [x] 5.3 完成 Debug 构建和静态检查，确认 LOCAL_ONLY 不产生云端请求
- [x] 5.4 在指定 Android 模拟器验证审批、权限、旋转、后台和失败路径
- [x] 5.5 在具备可写日历的 Android 12+ ARM64 测试设备验证真实事件与提醒；若环境缺失则记录阻塞证据
  - 当前仅有 API 37、ARM64-v8a 的 `emulator-5554`；`content://com.android.calendar/calendars` 查询结果为 `No result found.`，不存在可写日历，无法在该环境验证真实事件 ID 和提醒落库。设备用例已验证授权后查询真实 Provider 并明确返回“设备上没有可写日历”；原子 Event/Reminder、回执和去重逻辑由契约测试覆盖。
