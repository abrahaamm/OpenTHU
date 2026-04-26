# Calendar Skill 实现

日期：2026-04-26

状态：Active

## 1. 范围

本文档描述了日历技能在当前 OpenTHU 架构中的实现方式：

- Python (LangGraph) 是决策/编排层。

- Kotlin (Android 运行时) 是执行层。

- 技能调用在执行处理程序之前，会先经过 `SkillManager` 的模式验证。

日历相关技能：

1. `create_calendar_event`

2. `detect_calendar_conflicts`

3. `delete_calendar_event`

## 2. 逻辑所在

Python 端：

- `agent/langgraph/skill_core.py`

- 注册日历 `SkillSpec`（现在使用严格的 `args_json_schema`）

- `agent/langgraph/skill_manager.py`

- 验证/强制转换技能参数并分发处理程序

- `agent/langgraph/openthu_agent.py`

- 规划技能、运行安全检查、通过 `SkillManager` 执行已批准的技能

- `agent/langgraph/calendar_handlers.py`

- 日历业务验证 + 桥接分发

Kotlin 端：

- `app/src/main/java/ai/opencray/app/bridge/PythonSkillBridgeExecutor.kt`

- 将 Python 调用 JSON 转换为 `SystemAction`

- 通过 `ActionExecutor` 执行

- 将 Android 执行报告映射回 `SkillResult` 风格的 JSON

- `app/src/main/java/ai/opencray/app/execution/ActionExecutor.kt`

- 实际的 Android 日历提供程序读/写/删除逻辑

## 3. 端到端调用链

1. `build_default_registry()` 注册日历规范和处理程序。

2. Planner 从 `SkillManager.list_for_planner()` 获取技能元数据。

3. `plan_skills` 输出 `SkillInvocation` 候选技能。

4. `_sanitize_skill_plan` 调用 `SkillManager.validate_and_normalize_args(...)`。

5. `safety_check` 会阻止中/高风险调用，除非获得批准。

6. `execute_skills` 会将已批准的调用发送到 `SkillManager.execute(...)`。

7. `SkillManager` 会再次验证参数，然后调用日历处理程序。

8. 处理程序执行语义检查（时间范围、删除确认等）。

9. 处理程序将调用发送到 Kotlin 桥接器，并将返回的有效负载规范化为 `SkillResult`。

结论：

- 哪些日历工具被调用以及调用顺序由代理规划+安全管道决定。

- 处理程序不决定全局顺序；它只执行一个具体的调用。

## 4. 日历技能模式（SkillManager 协议）

日历规范现在使用 `args_json_schema`，并设置 `additionalProperties=false`。

- `create_calendar_event`

  - 必填：`title`、`start_time`、`end_time`

  - 可选：`location`、`description`、`conflict_decision`、`allow_conflict_delete`

- `detect_calendar_conflicts`

  - 必填：`start_time`、`end_time`

- `delete_calendar_event`

  - 必填：`confirm_delete`

  - 可选：`event_id`、`event_ids`

这意味着：

- 未知字段在 SkillManager 验证边界处被拒绝

- 常见类型转换（字符串/布尔值/数组）在处理程序调用之前进行

## 5. 处理程序职责

`calendar_handlers.py` 仅保留模式无法很好地表达的业务检查：

- ISO 日期时间解析

- `end_time > start_time`

- 冲突决策合法性

- 删除确认和事件 ID规范化

检查完成后，处理程序会将调用有效负载转发给 `KotlinSkillBridge` 实现。

## 6. Python-Kotlin 桥接协议

桥接请求负载（简化版）：

```json
{

"skill_name": "create_calendar_event",

"request_id": "req_xxx",

"task_id": "task_xxx",

"args": { "...": "..." },

"risk_level": "medium",

"requires_approval": true,

"description": "..."

}
```

预期桥接响应格式：

```json
{

"request_id": "req_xxx",

"code": "OK|INVALID_PARAM|APPROVAL_REQUIRED|ACTION_NOT_ALLOWED|SKILL_EXECUTION_FAILED",

"source": "android_kotlin_bridge",

"data": {}

}
```

Python 运行时支持的桥接模式：

- `json_file` （`JsonFileKotlinBridge`）

文件桥接所需的环境变量：

1. `OPENTHU_CALENDAR_BRIDGE_MODE=json_file`

2. `OPENTHU_KOTLIN_BRIDGE_REQUEST_FILE`

3. `OPENTHU_KOTLIN_BRIDGE_RESPONSE_FILE`

4. `OPENTHU_KOTLIN_BRIDGE_TIMEOUT_SEC`（可选）

## 7. Kotlin 执行映射

`ActionExecutor.execute(...)` 现在处理所有三个日历操作：

1. `create_calendar_event` -> 插入到 `CalendarContract.Events`

2. `detect_calendar_conflicts` -> 查询重叠事件

3. `delete_calendar_event` -> 按 `event_id/event_ids` 删除（显式指定）确认

`PythonSkillBridgeExecutor` 将 Android 执行报告转换为 LangGraph 的技能层结果代码和有效负载。

## 8. 测试

测试入口：

```bash
python agent/langgraph/run_calendar_skill_tests.py --mode mock
```

该测试套件验证以下内容：

1. 所有三个日历技能的注册表绑定

2. 模式验证行为（必填字段、未知字段拒绝、强制转换）

3. 处理程序业务检查

4. Python 到 Kotlin 的桥接调用契约（模拟桥接）

5. 创建/检测/删除行为和冲突分支

Kotlin 测试脚本位于：
```
app\src\test\java\ai\opencray\app\bridge\PythonSkillBridgeExecutorTest.kt
```

执行测试：
```
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

## 9. 已弃用/已移除的路径

- 旧的基于 ADB 的直接执行/测试路径已从日历技能运行时流程中移除。

- 日历技能运行时执行是桥接驱动的（`Python 决策 -> Kotlin 执行`）。