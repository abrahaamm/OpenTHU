# OpenTHU Calendar Skill 实现说明（Python 决策 / Kotlin 执行）

日期：2026-04-26  
状态：Active

## 1. 文档定位

本文档说明当前 Calendar Skill 的真实实现：  
Python LangGraph 负责决策与编排，Kotlin Runtime 负责系统能力执行。

目标读者：

1. 需要理解日历 skill 执行链路的开发者
2. 需要排查 Python-Kotlin 桥接问题的维护者
3. 需要扩展 calendar 能力的实现者

## 2. 架构结论

当前日历执行链路为：

1. Python 规划 `SkillInvocation`
2. Python Handler 做参数校验
3. Python Handler 通过桥接器把 invocation 交给 Kotlin
4. Kotlin `ActionExecutor` 执行 CalendarContract 写/查/删
5. Kotlin 返回 `SkillResult` 风格 JSON
6. Python 写回 `skill_results` 进入审计与记忆流程

## 3. 代码入口

Python 侧：

- `agent/langgraph/openthu_agent.py`（plan/safety/execute 主流程）
- `agent/langgraph/skill_core.py`（SkillRegistry 与 spec）
- `agent/langgraph/calendar_handlers.py`（calendar handler + bridge）
- `agent/langgraph/run_calendar_skill_tests.py`（桥接模型测试）

Kotlin 侧：

- `app/src/main/java/ai/opencray/app/execution/ActionExecutor.kt`（系统日历执行）
- `app/src/main/java/ai/opencray/app/bridge/PythonSkillBridgeExecutor.kt`（桥接入口）
- `app/src/main/java/ai/opencray/app/runtime/OpenCrayRuntime.kt`（运行时暴露桥接方法）

## 4. 调用链路（谁决定调用什么）

## 4.1 注册阶段

`build_default_registry()` 注册 calendar 的 3 个 skill spec，并调用 `register_calendar_handlers` 完成 handler 绑定。

## 4.2 Plan 阶段

`_plan_skills` 决定调用哪些 skill 以及顺序。  
无效 skill 会在 `_sanitize_skill_plan` 被过滤。

结论：跨 skill 编排由 Agent 决定，不由 handler 决定。

## 4.3 Safety 阶段

`medium/high` 风险在未授权时会被标记为 `pending_approval`，不会进入执行。

## 4.4 Execute 阶段

`_execute_skills` 仅对 `approved_skills` 调 `handler.invoke`。  
calendar handler 不直接访问 Android Provider，而是走桥接器转发给 Kotlin。

## 5. Calendar Skill 协议

## 5.1 create_calendar_event

必填：

1. `title`
2. `start_time`（ISO8601）
3. `end_time`（ISO8601）

可选：

1. `description`
2. `conflict_decision`：`prompt_user|skip_write|coexist|delete_conflicts`
3. `allow_conflict_delete`（仅 delete_conflicts 使用）

## 5.2 detect_calendar_conflicts

必填：

1. `start_time`
2. `end_time`

## 5.3 delete_calendar_event

必填：

1. `confirm_delete=true`
2. `event_id` 或 `event_ids`

## 6. Python Handler 行为

`calendar_handlers.py` 当前职责是：

1. 入参校验（时间格式、必填项、删除确认等）
2. 统一构造 `SkillInvocation` payload
3. 调 `KotlinSkillBridge.execute(...)`
4. 将桥接结果归一化为 `SkillResult`

桥接失败时返回：

- `code=SKILL_EXECUTION_FAILED`
- `data.status=bridge_error`

## 7. 桥接器实现

## 7.1 接口

`KotlinSkillBridge` 协议：

```python
execute(invocation: dict, state: dict) -> dict
```

返回需兼容：

```json
{
  "request_id": "req_xxx",
  "code": "OK|INVALID_PARAM|APPROVAL_REQUIRED|ACTION_NOT_ALLOWED|SKILL_EXECUTION_FAILED",
  "source": "android_kotlin_bridge",
  "data": {}
}
```

## 7.2 默认实现

`JsonFileKotlinBridge`：文件桥接（本地调试/宿主集成用）

环境变量：

1. `OPENTHU_CALENDAR_BRIDGE_MODE=json_file`
2. `OPENTHU_KOTLIN_BRIDGE_REQUEST_FILE`
3. `OPENTHU_KOTLIN_BRIDGE_RESPONSE_FILE`
4. `OPENTHU_KOTLIN_BRIDGE_TIMEOUT_SEC`（可选）

未配置桥接时，默认是 `UnconfiguredKotlinBridge`（显式失败，避免误执行）。

## 8. Kotlin 执行器行为

`PythonSkillBridgeExecutor` 负责：

1. 接收 Python 传入的 invocation JSON
2. 映射成 `SystemAction`
3. 调用 `ActionExecutor.execute(action, goal)`
4. 将结果映射回 `SkillResult` 风格 JSON

`OpenCrayRuntime.executeSkillInvocationFromPython(invocationJson)` 提供运行时入口。
`OpenCrayRuntime.processPythonBridgeFiles(requestPath, responsePath)` 提供文件桥接落地入口。

## 9. 测试策略

`run_calendar_skill_tests.py` 现为桥接模型测试，不再提供 ADB 模式。

命令：

```bash
python agent/langgraph/run_calendar_skill_tests.py --mode mock
```

覆盖点：

1. 注册绑定正确
2. Handler 入参校验
3. Python -> Bridge 调用链
4. create/detect/delete 三类行为
5. 冲突策略与删除确认

## 10. 设计边界

1. Python 负责决策和协议一致性，不直接执行系统写入。
2. Kotlin 负责 Android 权限上下文和系统调用。
3. 两侧通过 `SkillInvocation/SkillResult` 协议解耦，可替换桥接传输层（文件、RPC、嵌入式调用）。
