# OpenTHU Calendar Skill 实现说明

日期：2026-04-25  
状态：Active

## 1. 文档定位

本文档用于解释当前代码中 Calendar Skill 的真实实现逻辑，服务于以下场景：

- 了解 Skill 如何被 Agent 调用
- 排查 `adb` 真机/模拟器执行失败
- 维护者扩展或重构日历能力时做一致性对照

本说明覆盖的是 `agent/langgraph` 侧实现，不替代产品需求文档。

## 2. 实现范围

当前 Calendar Skill 包含 3 个工具：

1. `create_calendar_event`
2. `detect_calendar_conflicts`
3. `delete_calendar_event`

不在当前范围内：

1. 批量写入 `events[]`
2. 更新现有事件
3. 参会人、重复规则（RRULE）、提醒规则高级字段

## 3. 代码入口

- 注册中心：`agent/langgraph/skill_core.py`
- 调度工作流：`agent/langgraph/openthu_agent.py`
- 日历 Handler：`agent/langgraph/calendar_handlers.py`
- 测试脚本：`agent/langgraph/run_calendar_skill_tests.py`

## 4. 调用链路

## 4.1 注册阶段

`build_default_registry()` 完成两件事：

1. 注册 `SkillSpec`（风险等级、审批要求、参数 schema）
2. 调用 `register_calendar_handlers(registry)` 绑定具体 handler

即：没有注册的 skill 不会进入可执行路径。

## 4.2 Plan 阶段

`OpenTHULangGraphAgent._plan_skills()` 先走 LLM 规划，失败则走 fallback。

- LLM 侧会收到 `available_skills`（来自 registry）
- `_sanitize_skill_plan()` 会再次检查 `skill_name` 是否存在于 registry
- 不合法 skill 会被丢弃

结论：调用哪些工具、调用顺序由 Agent 的 plan 决定，不由 handler 决定。

## 4.3 Safety 阶段

`_safety_check()` 会重算风险并决定是否阻断：

- `medium/high` 且本次未授权：进入 `pending_approval`
- 已授权：进入 `approved`

## 4.4 Execute 阶段

`_execute_skills()` 仅遍历 `approved_skills`，按列表顺序执行：

1. `get_handler(skill_name)`
2. `handler.invoke(invocation, session, state)`
3. 写回 `skill_results`

结论：handler 接收的是已经确定好的具体调用（skill + args），不参与跨 skill 排序决策。

## 5. Skill 协议

## 5.1 create_calendar_event

必填参数：

- `title`
- `start_time`（ISO8601）
- `end_time`（ISO8601）

可选参数：

- `description`
- `conflict_decision`：`prompt_user|skip_write|coexist|delete_conflicts`
- `allow_conflict_delete`：当 `delete_conflicts` 时必须为真

核心返回：

- `code=OK` + `data.status=created`
- `code=APPROVAL_REQUIRED` + `data.status=conflict_detected`（冲突且 `prompt_user`）
- `code=OK` + `data.status=skipped_conflict`
- `code=ACTION_NOT_ALLOWED`（删除冲突但未显式授权）

## 5.2 detect_calendar_conflicts

必填参数：

- `start_time`
- `end_time`

返回：

- `code=OK`
- `data.status=detected`
- `data.conflicts[]`
- `data.decision_options=["skip_write","coexist","delete_conflicts"]`

## 5.3 delete_calendar_event

必填参数：

- `confirm_delete=true`
- `event_id` 或 `event_ids`（至少一个）

返回：

- `code=OK` + `data.status=deleted`
- `code=APPROVAL_REQUIRED`（缺少 `confirm_delete=true`）
- `code=INVALID_PARAM`（缺少目标事件）

## 6. Handler 内部执行逻辑

## 6.1 CreateCalendarEventHandler

执行顺序：

1. 参数校验与时间解析
2. 冲突查询（`query_conflicts(start_ms, end_ms)`）
3. 根据 `conflict_decision` 分支处理
4. 选择可写 calendar（`resolve_writable_calendar_id()`）
5. 写入事件（`insert_event(...)`）
6. 返回结构化 `SkillResult`

## 6.2 DetectCalendarConflictsHandler

执行顺序：

1. 校验时间参数
2. 查询重叠事件
3. 返回冲突列表与可选决策

## 6.3 DeleteCalendarEventHandler

执行顺序：

1. 检查 `confirm_delete`
2. 解析 `event_id/event_ids`
3. 查询现存目标（用于 `deleted` 与 `missing_event_ids`）
4. 删除事件
5. 返回删除结果

## 7. AdbCalendarBridge 关键实现细节

当前通过 `adb shell content` 访问 `CalendarContract`。

## 7.1 命令健壮性

`_run_content()` 不只检查 return code，还会识别以下“返回码 0 但实际失败”模式：

- `usage: adb shell content`
- `[ERROR] ...`
- `Error while accessing provider ...`

并统一抛出 `CalendarBridgeError`。

## 7.2 兼容性处理

针对不同 Android/adb 版本差异，做了以下处理：

1. `--projection` 统一使用冒号分隔（如 `_id:title:dtstart:dtend`）
2. `--where` 使用 shell quote，避免括号表达式被 `/system/bin/sh` 拆坏
3. `--bind` 值使用 shell quote，避免标题/描述包含空格时命令断裂
4. 避免复杂 `--sort "a DESC,b ASC"`，默认使用简单可兼容排序

## 7.3 插入/删除结果兜底

部分设备上：

- `content insert` 成功但 stdout 不返回 URI
- `content delete` 成功但 stdout 不返回删除行数

实现兜底：

1. 插入：按 `title + dtstart + dtend` 回查并推断新 `event_id`
2. 删除：比较删除前后查询结果，计算真实删除数量

## 8. 环境依赖

运行 Calendar Skill（ADB 模式）需要：

1. `adb` 可执行
2. 目标设备/模拟器可连接
3. 存在至少一个可写 calendar（access level >= 500）

可选环境变量：

- `OPENTHU_ADB_BIN`
- `OPENTHU_ADB_SERIAL`
- `OPENTHU_CALENDAR_TIMEZONE`（默认 `UTC`）

## 9. 测试策略

## 9.1 Mock 测试

命令：

```bash
python agent/langgraph/run_calendar_skill_tests.py --mode mock
```

覆盖内容：

1. 注册绑定正确
2. 参数校验
3. 冲突处理分支
4. 删除确认与缺失 ID 行为

## 9.2 ADB 烟测

命令：

```bash
python agent/langgraph/run_calendar_skill_tests.py --mode adb --adb-serial <serial>
```

当前烟测闭环：

1. 定位可写 calendar
2. 创建事件
3. 冲突检测可见新事件
4. 可按 ID 查询到新事件
5. 删除成功
6. 删除后查询为空
7. 异常路径自动清理测试事件

## 10. 常见故障排查

## 10.1 `No writable calendar found on connected device`

优先检查：

1. 是否存在 `calendar_access_level >= 500` 的日历
2. 设备是否正确连接到目标 serial
3. 账号是否完成日历同步

## 10.2 `Unsupported argument` / `syntax error: unexpected '('`

通常是 shell 参数被拆分。优先排查：

1. `projection` 是否使用冒号
2. `where` 是否带正确引号
3. 含空格 bind 是否被 quote

## 10.3 插入成功但拿不到 event_id

当前实现已支持回查推断。若仍失败，重点检查：

1. 事件是否被 Provider 正常写入
2. 事件写入字段是否被 ROM 改写（title/start/end）

## 11. 设计结论

1. Calendar Skill 的“调用内容与顺序”由 Agent 规划与安全节点决定。
2. Handler 只执行单个 skill 的业务逻辑，不做跨 skill 编排。
3. 现版本已实现 create/detect/delete 的可执行闭环，并针对 ADB 差异做了兼容兜底。
