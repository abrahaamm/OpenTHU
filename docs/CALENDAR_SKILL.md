# OpenTHU Calendar Skill 规范文档

版本：v1.2-draft  
日期：2026-04-25  
状态：Draft

## 1. 文档目标

本文档定义 OpenTHU 中 `create_calendar_event` Skill 的完整规范，包括：
- 在 Skill-first 架构中的分层位置与边界
- 功能范围与非目标
- 接口、参数、返回、状态机、错误码、审批协议
- 与当前代码实现的一致性约束与演进计划

本文档是对 `docs/API.md` 中 `create_calendar_event` 条目的专项展开。

## 2. 关联文档与代码基线

关联文档：
- `docs/RD.md`
- `docs/API.md`
- `README.md`

当前代码基线（2026-04-25）：
- `agent/langgraph/skill_core.py`
- `agent/langgraph/openthu_agent.py`
- `app/src/main/java/ai/opencray/app/execution/ActionExecutor.kt`
- `app/src/main/java/ai/opencray/app/safety/PolicyEngine.kt`
- `app/src/main/AndroidManifest.xml`

## 3. 架构定位

## 3.1 总体架构

OpenTHU 当前采用 Skill-first 架构

```text
User Input
  -> LangGraph Workflow
     -> normalize_requirement
     -> plan_skills
     -> safety_check
     -> execute_skills
     -> replan_failed (if needed)
     -> audit_record
     -> memory_update
```

`create_calendar_event` 作为动作类 Skill 参与 `plan -> safety -> execute -> audit` 主链路。

## 3.2 协议边界

1. 编排层（LangGraph）
- 通过 `SkillRegistry` 获取 `SkillSpec`
- 产出 `SkillInvocation`
- 接收 `SkillResult`
- 进行风险门禁、执行调度、审计记录

2. Skill 实现层（Calendar Handler）
- 校验与规范化入参
- 调用 Android 日历能力
- 产出结构化结果与错误码

3. 工具执行层
- 当前默认实现（Android）：`CalendarContract` + `ContentResolver` 直接写入/查询/删除系统日历
- 兼容路径：`Intent.ACTION_INSERT`（仅在 Provider 不可用时回退）

## 4. 功能范围

## 4.1 In Scope（v1.2）

1. 单事件写入（`create_calendar_event`，标题/起止时间必填，描述可选）
2. 冲突检测（`detect_calendar_conflicts`，返回冲突列表与决策选项）
3. 删除事件（`delete_calendar_event`，高风险且需显式确认）
4. 冲突决策协议：`skip_write|coexist|delete_conflicts`
5. 执行结果结构化返回到 `SkillResult.data`
6. 审计字段可追溯：`task_id/request_id/skill_name`

## 4.2 Out of Scope（v1.2）

1. 批量事件写入（`events[]`）
2. 更新既有事件
3. 参会人、重复规则（RRULE）高级字段
4. 强一致写入确认（Intent 模式下不可保证）

## 5. Skill 规格定义

## 5.1 SkillSpec（注册元数据）

Calendar 相关 skill 的注册元数据必须满足：
- `create_calendar_event`: `risk_level=medium`、`requires_approval=true`
- `detect_calendar_conflicts`: `risk_level=low`、`requires_approval=false`
- `delete_calendar_event`: `risk_level=high`、`requires_approval=true`

说明：该约束与 `agent/langgraph/skill_core.py` 中默认注册表一致。

## 5.2 SkillInvocation 入参协议

## 5.2.1 必选字段（v1.1）

```python
{
    "request_id": "req_xxx",
    "title": "期中考试：人工智能导论",
    "start_time": "2026-05-02T09:00:00Z",
    "end_time": "2026-05-02T11:00:00Z"
}
```

## 5.2.2 可选字段（v1.1）

```python
{
    "location": "六教6A001",
    "description": "闭卷考试"
}
```

## 5.2.3 向后兼容扩展字段（建议）

以下字段可在不破坏 v1.1 的前提下增量支持：

```python
{
    "timezone": "Asia/Shanghai",
    "all_day": False,
    "source": "assignment_deadline",
    "external_ref": "assignment_123",
    "dedupe_key": "ddl-ai-2026-05-02"
}
```

## 5.2.4 参数校验规则

1. `title` 长度：`1..200`
2. `start_time/end_time` 必须是 ISO8601 字符串
3. `end_time` 必须晚于 `start_time`
4. 若提供 `timezone`，必须是合法 IANA 时区
5. 参数不合法返回 `INVALID_PARAM`

## 6. SkillResult 返回协议

## 6.1 通用外层

遵循 `SkillResult` 结构：

```python
{
    "skill_name": "create_calendar_event",
    "request_id": "req_xxx",
    "code": "OK",
    "data": {},
    "from_cache": False,
    "fetched_at": "2026-04-25T12:00:00Z",
    "source": "android_calendar_intent"
}
```

## 6.2 `data` 字段定义

```python
{
    "status": "launched|created|pending_user_confirmation|failed",
    "event_id": "string|null",
    "write_mode": "intent|provider",
    "message": "string"
}
```

字段说明：
- `status=launched`：已成功拉起日历创建界面
- `status=pending_user_confirmation`：等待用户在日历 App 内最终确认
- `status=created`：已确认创建成功（通常仅 Provider 模式可稳定给出）
- `event_id`：Intent 模式允许为 `null`

## 6.3 与 API 文档的兼容关系

`docs/API.md` 当前示例为：

```python
{ "event_id": "...", "status": "created" }
```

本规范将其视为“理想成功形态”，并补充当前 Provider / Intent 模式下的可观测状态，避免实现与文档语义冲突。

## 7. 执行模式定义

## 7.1 模式 A：Provider（当前默认）

执行方式：
- 直接写入 `CalendarContract.Events`
- 按时间窗口查询冲突事件
- 按 `event_id(s)` 删除冲突或目标事件

优点：
- 可真实修改真机系统日历
- 可稳定返回 `event_id`、冲突列表、删除结果

限制：
- 需要 `READ_CALENDAR` / `WRITE_CALENDAR` 权限
- 受设备 ROM 与日历账户可写权限影响

## 7.2 模式 B：Intent（回退路径）

执行方式：
- 构造 `Intent.ACTION_INSERT`
- `data = CalendarContract.Events.CONTENT_URI`
- 填充 `TITLE/BEGIN/END/LOCATION/DESCRIPTION`
- `startActivity(intent)`

优点：
- 权限成本低，设备兼容性好

限制：
- 无法稳定获取 `event_id`
- 无法保证用户最终点击“保存”
- 幂等只能做到“请求级避免重复拉起”，不能保证“系统事件不重复”

## 8. 审批与风险协议

1. 默认风险等级：`medium`
2. `requires_approval=true`
3. 在 `safety_check` 阶段：
- 若 `approve_sensitive=false`，进入 `pending_approval`，本次不执行
- 若 `approve_sensitive=true`，可进入 `approved` 并执行
4. 未审批执行应返回 `APPROVAL_REQUIRED`

状态流转：
- `planned -> pending_approval -> approved -> executed|failed`

## 9. 幂等与重试协议

## 9.1 幂等键

最小幂等键：
- `request_id`

建议增强：
- `request_id + dedupe_key`

## 9.2 重试策略

1. 可重试：
- `SKILL_EXECUTION_FAILED`（可恢复异常）

2. 不可重试：
- `INVALID_PARAM`
- `ACTION_NOT_ALLOWED`

3. 建议重试节奏：
- `1s / 2s / 4s`，最多 3 次

## 10. 错误码与映射

Skill 层返回码遵循 `docs/API.md` 通用码：
- `OK`
- `INVALID_PARAM`
- `APPROVAL_REQUIRED`
- `ACTION_NOT_ALLOWED`
- `SKILL_EXECUTION_FAILED`

建议消息规范（`data.message`）：
- 参数错误：`invalid time range` / `missing required field`
- 审批缺失：`approval required for medium risk calendar write`
- Intent 失败：`calendar intent launch failed: <reason>`

## 11. 安全与隐私

1. 日历写入前必须经过 PolicyEngine 风险门禁
2. 审计日志必须记录 `task_id/request_id/skill_name/result`
3. 不在日志中持久化敏感上下文正文（最小化原则）
4. Provider 模式必须显式申请日历读写权限；无权限时应拒绝执行

## 12. 与当前代码的一致性检查

## 12.1 已对齐项

1. Skill 名称：`create_calendar_event`
2. 风险分级：`medium`
3. 执行链路：`plan_skills -> safety_check -> execute_skills`
4. Android 默认执行方式：`CalendarContract` Provider 直连系统日历

## 12.2 待修复差异（建议）

1. 旧的 Python 本地模拟路径已移除  
说明：当前以 Android 真机 Provider 路径为准。

2. Android 原型 `ActionPlanner` 中 `create_calendar_event` 当前标记为 low/无需审批  
说明：与 `docs/API.md`、LangGraph `SkillSpec` 不一致，应统一为 medium/需审批。

3. Intent 模式缺少“最终是否创建成功”的回执闭环  
说明：建议在 UI 层补充用户确认回传或迁移 Provider 模式。

## 13. 测试与验收

## 13.1 最小验收（v1.1）

1. 规划包含 `create_calendar_event` 时可正确进入安全审查
2. 未审批时动作处于 `pending_approval`
3. 审批通过后可成功拉起日历创建界面
4. `SkillResult` 返回结构完整，`request_id` 可追踪
5. 失败时返回明确错误码和错误信息

## 13.2 边界用例

1. `end_time <= start_time`
2. 缺少 `title`
3. 无可处理日历应用
4. 设备时区与输入时区不一致
5. 重复 `request_id` 提交

## 14. 版本演进计划

1. v1.1（当前）
- 单事件 + Intent 模式
- 审批门禁 + 审计闭环

2. v1.2（建议）
- Provider 模式落地
- 可返回稳定 `event_id`
- 支持 `dedupe_key`

3. v1.3（建议）
- 批量 `events[]`
- 提醒项、冲突检测、增强幂等

## 15. 示例

## 15.1 SkillInvocation 示例

```python
{
    "skill_name": "create_calendar_event",
    "request_id": "req_cal_001",
    "task_id": "task_abc123",
    "args": {
        "request_id": "req_cal_001",
        "title": "机器学习作业截止提醒",
        "start_time": "2026-04-30T22:00:00Z",
        "end_time": "2026-04-30T22:30:00Z",
        "location": "线上提交",
        "description": "DDL 23:59"
    },
    "risk_level": "medium",
    "requires_approval": True,
    "description": "将作业 DDL 写入系统日历",
    "status": "planned"
}
```

## 15.2 SkillResult 示例（Intent 模式）

```python
{
    "skill_name": "create_calendar_event",
    "request_id": "req_cal_001",
    "code": "OK",
    "data": {
        "status": "pending_user_confirmation",
        "event_id": None,
        "write_mode": "intent",
        "message": "Calendar insert page launched"
    },
    "from_cache": False,
    "fetched_at": "2026-04-25T12:10:00Z",
    "source": "android_calendar_intent"
}
```
