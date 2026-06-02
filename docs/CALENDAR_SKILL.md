# Calendar Skill 实现说明（Server Dispatch 模式）

日期：2026-04-27  
状态：Active

## 1. 当前架构结论

Calendar skill 当前主链路是：

1. Python Agent-Core Server 只做 `normalize/plan/safety/audit/memory`
2. Android 端通过 HTTP 轮询 `/api/v1/agent/tasks/next`
3. Kotlin `OpenCrayRuntime` 将 skill invocation 映射为 `SystemAction`
4. Kotlin `ActionExecutor` 执行 Calendar Provider 写入/检测/删除
5. Android 回传执行结果到 `/api/v1/agent/tasks/{task_id}/result`

说明：

- 这是生产推荐路径（Server + Device Executor）
- Python 本地 `calendar_handlers.py` 仍可用于单进程调试，但不是当前主执行链路

## 2. Calendar 相关能力

Calendar 对应三类 skill：

1. `create_calendar_event`
2. `detect_calendar_conflicts`
3. `delete_calendar_event`

对应 Kotlin 执行入口：

- [ActionExecutor.kt](/d:/Shared/2026%20Spring/Android/project/OpenTHU/app/src/main/java/ai/opencray/app/execution/ActionExecutor.kt)

执行映射：

1. `create_calendar_event` -> `CalendarContract.Events` 插入
2. `detect_calendar_conflicts` -> `CalendarContract.Events` 重叠查询
3. `delete_calendar_event` -> 按 `event_id/event_ids/title_keyword` 删除

## 3. 与 Agent-Core 协议对齐点

### 3.1 计划分发

Android 从 `plan_only_response.data.approved_skills` 解析 skill：

- [AgentCoreHttpClient.kt](/d:/Shared/2026%20Spring/Android/project/OpenTHU/app/src/main/java/ai/opencray/app/gateway/AgentCoreHttpClient.kt)
- [OpenCrayRuntime.kt](/d:/Shared/2026%20Spring/Android/project/OpenTHU/app/src/main/java/ai/opencray/app/runtime/OpenCrayRuntime.kt)

### 3.2 结果回传 code

`OpenCrayRuntime` 回传结果时，已按 calendar 语义映射错误码：

- `OK`
- `APPROVAL_REQUIRED`
- `INVALID_PARAM`
- `ACTION_NOT_ALLOWED`
- `SKILL_EXECUTION_FAILED`

映射逻辑位置：

- [OpenCrayRuntime.kt](/d:/Shared/2026%20Spring/Android/project/OpenTHU/app/src/main/java/ai/opencray/app/runtime/OpenCrayRuntime.kt)

## 4. 本地无 Agent 的预设 Plan 测试

在不启动真实 `agent_core_server.py` 的情况下，用预设脚本模拟服务端分发：

- 脚本： [run_calendar_preset_gateway_server.py](../scripts/run_calendar_preset_gateway_server.py)
- 预设流程：`create_calendar_event -> delete_calendar_event(title_keyword)`

### 4.1 启动预设网关

```bash
python scripts/run_calendar_preset_gateway_server.py --host 0.0.0.0 --port 28791
```

### 4.2 运行安卓模拟器端到端测试

测试文件：

- [CalendarPresetPlanGatewayFlowTest.kt](../app/src/androidTest/java/ai/opencray/app/runtime/CalendarPresetPlanGatewayFlowTest.kt)

执行命令：

```bash
gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.opencray.app.runtime.CalendarPresetPlanGatewayFlowTest
```

该测试会验证：

1. App 能连上预设网关并拿到 plan
2. `create_calendar_event` 与 `delete_calendar_event` 都被执行并回传结果
3. 删除后按 `title_keyword` 查询不到残留测试事件

### 4.3 手动联调

如果你不跑 instrumentation，也可以手工在 App 中：

1. Connect 到 `10.0.2.2:<port>`（TLS 关闭）
2. `Plan Goal`
3. `Run Agent`

然后在服务端看 `/api/v1/agent/tasks/{task_id}` 的 `device_results` 与 `status`。

具体见文件 [AGENT_CORE_SERVER.md](AGENT_CORE_SERVER.md)

## 5. Time Argument Contract

Calendar writes and conflict detection require concrete absolute datetimes:

- `create_calendar_event.start_time`
- `create_calendar_event.end_time`
- `detect_calendar_conflicts.start_time`
- `detect_calendar_conflicts.end_time`

All four fields must be ISO-8601 datetime strings with an explicit UTC offset, for example:

```json
{
  "start_time": "2026-06-03T14:00:00+08:00",
  "end_time": "2026-06-03T15:00:00+08:00",
  "timezone": "Asia/Shanghai"
}
```

Do not pass natural-language or relative time values such as `明天`, `今晚`, `下周`, or `tomorrow`.

When the user uses relative time, the planner must call `get_current_time` first. The calendar action may be planned only after the intended wall-clock time has been resolved into ISO-8601 offset datetimes. The Android executor intentionally has no fallback that guesses a time. If the time is missing or invalid, it returns an error and does not create a calendar event.
