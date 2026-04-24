# OpenTHU Calendar Skill 规范文档

版本：v1.0-draft  
日期：2026-04-25  
状态：Draft

## 1. 文档目标

本文档定义 OpenTHU `calendar skill` 的完整技术规范，用于指导以下能力的一致实现：
- Agent 基于 `create_calendar_event` 动作写入 Android 系统日历
- 执行过程可审批、可审计、可回放
- Skill 与 MCP 组件解耦，支持后续扩展（更新、删除、批量、冲突策略）

本文档与以下文档协同：
- `docs/API.md`：Agent <-> Backend 正式接口契约
- `docs/RD.md`：需求、验收与非功能约束

## 2. 范围定义

## 2.1 In Scope（本阶段）

1. 创建日历事件（单条/批量）
2. 写入目标日历（默认日历或指定日历）
3. 设置提醒（分钟级偏移）
4. 写入结果结构化回传（含每条事件执行结果）
5. 与审批、审计链路打通
6. 幂等与重试协议

## 2.2 Out of Scope（后续阶段）

1. 删除已有系统日历事件（高风险，默认关闭）
2. 修改已有系统日历事件（仅预留接口）
3. 云端日历同步保障（Google/Exchange 账户同步时延不在本系统 SLA）
4. 参会人邀请与回执（仅在上层业务明确要求后引入）

## 3. 术语与约定

- `Skill`：面向 Agent 的能力编排层，负责把动作参数组织为可执行工具调用。
- `MCP Calendar Server`：提供标准化工具接口，屏蔽 Android 平台细节。
- `Calendar Executor`：Android 侧最终执行器，调用 `CalendarContract` 写入系统日历。
- `Action`：Backend 下发的标准动作，`type=create_calendar_event`。
- `request_id`：写接口幂等键（跨重试保持不变）。
- `dedupe_key`：事件级幂等键（同一 action 内每个事件唯一）。

规范关键字：
- `MUST`：必须遵守
- `SHOULD`：建议遵守，需有明确理由才可偏离
- `MAY`：可选

## 4. 架构总览

采用“Skill 编排 + MCP 执行 + Android 适配”的三层架构：

```text
User Goal
  -> Backend Planner (/agent/tasks/plan)
    -> Agent Action Runner
      -> Calendar Skill (参数校验/策略决策/调用编排)
        -> MCP Calendar Server (tool contract)
          -> Android Calendar Executor (CalendarContract Provider)
            -> System Calendar
      -> Result Reporter (/agent/tasks/{task_id}/actions/{action_id}/result)
```

## 4.1 分层职责

1. Backend Planner
- 生成 `create_calendar_event` 动作与风险标记
- 提供 `action_id/risk_level/requires_approval/args`

2. Agent Action Runner
- 串联审批门禁、Skill 执行、结果回传
- 维护 action 生命周期状态

3. Calendar Skill
- 校验 `args` 结构与业务规则
- 规范化时间、时区、提醒参数
- 计算事件级 `dedupe_key`
- 组织 MCP 工具调用

4. MCP Calendar Server
- 对外暴露工具：权限检查、日历查询、事件写入
- 提供稳定错误码与结构化结果

5. Android Calendar Executor
- 请求/检查 `WRITE_CALENDAR` 权限
- 调用 `CalendarContract.Events` 与 `CalendarContract.Reminders`
- 返回系统 `event_id` 与写入状态

## 4.2 核心设计原则

1. 非破坏优先：本期仅新增事件，不自动删除或覆盖
2. 安全优先：审批优先于执行，高风险动作默认阻断
3. 幂等优先：同一 `request_id + action_id + dedupe_key` 不重复写入
4. 可观测优先：每条事件必须有可追踪执行结果
5. 向后兼容：对现有 `docs/API.md` 为增量扩展，不破坏已有字段

## 5. 能力模型

## 5.1 动作类型

固定使用：
- `type = create_calendar_event`

## 5.2 操作模式

- `operation = create`（本期唯一必选值）
- `operation = upsert`（预留）
- `operation = delete`（预留，默认禁用）

## 5.3 风险分级建议

1. `low`
- 单条写入，未来 30 天内，默认日历，提醒 <= 2 个

2. `medium`
- 批量写入（2-20 条）
- 写入非默认日历
- 全天事件跨多天

3. `high`
- 批量 > 20 条
- 跨 365 天范围写入
- 删除或覆盖（未来版本）

高风险动作 `MUST` 经过审批后执行。

## 6. 接口与协议定义

## 6.1 Backend -> Agent（Action Args）

在 `docs/API.md` 的 `create_calendar_event.args` 基础上，定义如下结构：

```json
{
  "skill_version": "1.0.0",
  "operation": "create",
  "default_timezone": "Asia/Shanghai",
  "target_calendar": {
    "strategy": "default",
    "calendar_id": null,
    "calendar_name_hint": null
  },
  "idempotency": {
    "scope": "action",
    "request_id": "req_plan_001",
    "action_id": "act_2"
  },
  "events": [
    {
      "dedupe_key": "ddl-cs101-2026-05-01",
      "title": "CS101 作业截止提醒",
      "description": "提交实验报告",
      "location": "线上提交",
      "start_at": "2026-05-01T20:00:00+08:00",
      "end_at": "2026-05-01T20:30:00+08:00",
      "all_day": false,
      "timezone": "Asia/Shanghai",
      "reminders": [
        { "minutes_before": 1440, "method": "alert" },
        { "minutes_before": 30, "method": "alert" }
      ],
      "metadata": {
        "course_id": "wlkcid_xxx",
        "source": "assignment_deadline",
        "external_ref": "assignment_123"
      }
    }
  ]
}
```

### 6.1.1 字段约束

1. 顶层字段
- `skill_version`：`MUST` 为语义化版本号
- `operation`：本期 `MUST` 为 `create`
- `default_timezone`：IANA 时区，缺省 `Asia/Shanghai`
- `events`：`MUST` 非空，长度 `1..50`

2. 事件字段
- `dedupe_key`：`MUST` 非空，长度 `1..128`，在同一 action 内唯一
- `title`：`MUST` 非空，长度 `1..200`
- `start_at/end_at`：`MUST` 为 ISO8601，且 `start_at < end_at`
- `all_day=true` 时：`start_at/end_at` `SHOULD` 对齐到本地日界
- `reminders`：`MAY` 为空；若不为空，单事件最多 5 条
- `minutes_before`：取值 `0..10080`（最多提前 7 天）

## 6.2 Agent -> MCP（Tool Contract）

MCP Server 至少提供以下工具：

1. `calendar.check_permission`
- 输入：`{}`
- 输出：
```json
{
  "granted": true,
  "can_request_runtime_permission": true
}
```

2. `calendar.list_calendars`
- 输入：`{}`
- 输出：
```json
{
  "items": [
    {
      "calendar_id": "12",
      "display_name": "My Calendar",
      "account_name": "user@gmail.com",
      "is_primary": true,
      "writable": true
    }
  ]
}
```

3. `calendar.create_events`
- 输入：与 `6.1` 中 `target_calendar + events + idempotency` 对齐
- 输出：
```json
{
  "summary": {
    "total": 2,
    "created": 2,
    "skipped": 0,
    "failed": 0
  },
  "results": [
    {
      "dedupe_key": "ddl-cs101-2026-05-01",
      "status": "created",
      "calendar_event_id": "8342",
      "calendar_id": "12"
    }
  ]
}
```

4. `calendar.upsert_events`（预留）
- 本期可返回 `NOT_IMPLEMENTED`

## 6.3 Agent -> Backend（结果回传扩展）

使用既有接口：
- `POST /agent/tasks/{task_id}/actions/{action_id}/result`

推荐 `artifacts` 结构：

```json
{
  "calendar_write": {
    "operation": "create",
    "total": 2,
    "created": 1,
    "skipped": 1,
    "failed": 0,
    "results": [
      {
        "dedupe_key": "ddl-cs101-2026-05-01",
        "status": "created",
        "calendar_event_id": "8342",
        "calendar_id": "12"
      },
      {
        "dedupe_key": "ddl-ma101-2026-05-02",
        "status": "skipped_idempotent",
        "calendar_event_id": "8201",
        "calendar_id": "12"
      }
    ]
  }
}
```

## 7. 状态机定义

## 7.1 Action 状态

- `planned` -> `pending_approval` -> `approved` -> `executed`
- 失败分支：任意状态 -> `failed`

约束：
1. `requires_approval=true` 时，`MUST` 先进入 `pending_approval`
2. 未审批执行 `MUST` 返回 `APPROVAL_REQUIRED`
3. 部分成功写入时，action 状态 `SHOULD` 仍为 `executed`，并在 `artifacts` 标注失败明细

## 7.2 事件级状态

- `created`
- `skipped_idempotent`
- `failed_validation`
- `failed_permission`
- `failed_provider_io`
- `failed_unknown`

## 8. 幂等、重试与一致性

## 8.1 幂等键

事件级唯一键定义：
- `idempotency_key = hash(request_id + action_id + dedupe_key)`

规则：
1. 同一 `idempotency_key` 在 TTL（建议 7 天）内重复请求，`MUST` 返回 `skipped_idempotent`
2. 重试时 `request_id` 与 `action_id` `MUST` 保持不变

## 8.2 重试策略

1. 可重试错误：`PROVIDER_TIMEOUT`、`TEMPORARY_IO_ERROR`
2. 不可重试错误：`INVALID_PARAM`、`PERMISSION_DENIED`
3. 重试建议：指数退避，`1s/2s/4s`，最大 3 次

## 8.3 部分成功策略

批量写入允许部分成功：
- 回传层面 `success=true` 仅在 `failed=0` 时使用
- 若存在失败，`success=false`，同时附带 `results` 明细，避免丢失已创建的 `event_id`

## 9. 时间与时区协议

1. 输入时间 `MUST` 使用 ISO8601（带偏移或 `Z`）
2. 若事件未显式提供 `timezone`，`MUST` 回退到 `default_timezone`
3. 写入系统日历前，执行器 `SHOULD` 转换为设备时区语义
4. Backend 审计与存储时间 `MUST` 使用 UTC
5. 跨天/夏令时边界场景 `MUST` 以事件原始时区解释，禁止按 UTC 直接切割全天事件

## 10. 权限与安全

## 10.1 Android 权限

`MUST` 声明并在运行时申请：
- `android.permission.READ_CALENDAR`
- `android.permission.WRITE_CALENDAR`

## 10.2 数据最小化

1. Skill 不保存用户完整日程正文历史，仅保存执行必要字段
2. 本地缓存的幂等记录 `SHOULD` 仅保留 `idempotency_key + event_id + timestamp`
3. 敏感文本（如作业内容）`SHOULD` 仅用于本次执行，不落长期日志

## 10.3 审批与门禁

1. `risk_level=high`：强制审批
2. `risk_level=medium`：默认无需审批，可由策略开关提升为审批
3. 用户拒绝审批后，action `MUST` 进入 `failed` 且错误码为 `ACTION_NOT_ALLOWED`

## 11. 错误码与映射

## 11.1 Skill/MCP 错误码

- `CAL_INVALID_PARAM`
- `CAL_PERMISSION_DENIED`
- `CAL_CALENDAR_NOT_FOUND`
- `CAL_NOT_WRITABLE`
- `CAL_INVALID_TIME_RANGE`
- `CAL_TOO_MANY_EVENTS`
- `CAL_PROVIDER_TIMEOUT`
- `CAL_PROVIDER_IO`
- `CAL_NOT_IMPLEMENTED`

## 11.2 与 API 通用码映射

- 参数类错误 -> `INVALID_PARAM`
- 权限类错误 -> `ACTION_NOT_ALLOWED`
- 审批缺失 -> `APPROVAL_REQUIRED`
- 上游/系统暂时异常 -> `INTERNAL_ERROR`

## 12. 审计与可观测性

## 12.1 审计字段（最小集合）

- `session_id`
- `task_id`
- `action_id`
- `request_id`
- `risk_level`
- `approval_id`（如有）
- `operation`
- `events_total/created/skipped/failed`
- `timestamp`

## 12.2 指标（Metrics）

- `calendar_action_total{status}`
- `calendar_event_total{result}`
- `calendar_action_latency_ms`
- `calendar_permission_denied_total`
- `calendar_idempotent_skip_total`

## 13. 非功能要求

1. 性能：20 条事件批量创建 P95 < 3s（本地执行，不含人工审批）
2. 可用性：MCP 调用成功率月度 >= 99.5%
3. 可维护性：接口字段向后兼容，新增字段必须为可选
4. 可测试性：每个错误码至少 1 条自动化用例覆盖

## 14. 验收标准与测试矩阵

## 14.1 功能验收

1. 单条创建成功，返回 `calendar_event_id`
2. 批量创建部分失败，回传逐条结果
3. 重试同一请求不重复写入（命中幂等）
4. 未授权权限时正确失败并提示
5. 高风险动作未审批不可执行

## 14.2 边界用例

1. 全天事件跨 DST 边界
2. `end_at <= start_at`
3. 超过 50 条事件写入
4. 非可写日历 ID
5. 同一 action 内重复 `dedupe_key`

## 15. 版本治理

1. `skill_version` 使用 SemVer
2. 兼容规则：
- `major` 变化可包含不兼容变更
- `minor/patch` `MUST` 向后兼容
3. Agent 与 MCP `SHOULD` 在握手时上报：
- `supported_skill_versions`
- `supported_operations`

## 16. 实施里程碑建议

1. M1（MVP）
- 支持 `operation=create`
- 支持默认日历写入与提醒
- 打通审批、审计、幂等最小闭环

2. M2（增强）
- 支持指定日历写入
- 支持批量冲突检测与更细粒度错误码

3. M3（扩展）
- 引入 `upsert`
- 评估受控删除（强审批 + 双重确认）

## 17. 附录：最小可执行示例

## 17.1 Action 示例

```json
{
  "action_id": "act_2",
  "type": "create_calendar_event",
  "title": "创建作业提醒",
  "risk_level": "medium",
  "requires_approval": false,
  "args": {
    "skill_version": "1.0.0",
    "operation": "create",
    "default_timezone": "Asia/Shanghai",
    "target_calendar": { "strategy": "default" },
    "idempotency": {
      "scope": "action",
      "request_id": "req_plan_001",
      "action_id": "act_2"
    },
    "events": [
      {
        "dedupe_key": "ddl-os-2026-05-03",
        "title": "操作系统作业截止",
        "start_at": "2026-05-03T20:00:00+08:00",
        "end_at": "2026-05-03T20:20:00+08:00",
        "all_day": false,
        "reminders": [
          { "minutes_before": 60, "method": "alert" }
        ]
      }
    ]
  }
}
```

## 17.2 结果回传示例

```json
{
  "request_id": "req_result_001",
  "agent_id": "agent_android_xxx",
  "status": "executed",
  "success": true,
  "message": "calendar events created",
  "started_at": "2026-04-25T10:00:00Z",
  "finished_at": "2026-04-25T10:00:01Z",
  "artifacts": {
    "calendar_write": {
      "operation": "create",
      "total": 1,
      "created": 1,
      "skipped": 0,
      "failed": 0,
      "results": [
        {
          "dedupe_key": "ddl-os-2026-05-03",
          "status": "created",
          "calendar_event_id": "9527",
          "calendar_id": "12"
        }
      ]
    }
  }
}
```
