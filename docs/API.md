﻿# OpenTHU Skill 接口文档（API）

版本：v1.1-draft  
日期：2026-04-24  
状态：Draft

## 1. 文档范围

本文档定义 OpenTHU 中 **Agent Skills 的接口契约**，以及 LangGraph Workflow 内部的任务/执行协议。

架构说明：
- OpenTHU **不再依赖独立的 Backend HTTP 服务**
- 学习数据获取、本地动作执行均通过 **Skill**（Agent 内置工具）完成
- Skill 直接调用上游清华系统（URL 与关键字段参考 `docs/API_http.md`）
- LangGraph Workflow 负责 plan → safety_check → approve → execute → audit 闭环

## 2. Skill 分类与调用约定

### 2.1 Skill 分类

| 分类 | 说明 | 风险等级 |
|------|------|----------|
| 认证类 | 登录、会话刷新、注销 | low / medium |
| 数据类 | 读取上游清华数据 | low |
| 动作类 | 执行本地系统动作 | low / medium / high |

### 2.2 调用约定

- **调用方**：LangGraph Workflow 节点（`plan` / `execute` 节点）
- **执行方**：Android Agent 内置 Skill 实现层
- **绑定方式**：Workflow 仅依赖 `SkillRegistry` 提供的 `SkillSpec` / `SkillHandler`，不直接耦合具体 Skill 实现
- **会话传递**：数据类 Skill 共享 Agent 持有的 `Session` 对象（含 `JSESSIONID` / `CSRF token`）
- **幂等**：所有写操作 Skill（动作类）支持 `request_id` 幂等
- **时间**：统一 ISO8601（UTC）；`set_alarm.time` 为本地时区语义（`HH:mm` 或本地 ISO8601）
- **错误码**：见 2.3

### 2.3 通用错误码

| 错误码 | 含义 |
|--------|------|
| `OK` | 成功 |
| `INVALID_PARAM` | 参数错误 |
| `SESSION_EXPIRED` | 会话已过期，需重新登录 |
| `UPSTREAM_AUTH_FAILED` | 上游认证失败 |
| `UPSTREAM_TIMEOUT` | 上游接口超时 |
| `UPSTREAM_UNAVAILABLE` | 上游接口不可达，已返回缓存数据 |
| `APPROVAL_REQUIRED` | 该 Skill 调用需要用户审批 |
| `ACTION_NOT_ALLOWED` | PolicyEngine 拒绝执行 |
| `SKILL_EXECUTION_FAILED` | Skill 执行失败 |

### 2.4 Skill 调用结构（Python 类型定义）

```python
@dataclass
class SkillInvocation:
    skill_name: str          # Skill 唯一名称
    request_id: str          # 幂等 ID
    task_id: str             # 所属 Workflow 任务
    args: dict[str, Any]     # Skill 入参（允许部分参数在 execute 阶段结合 AgentState 补全）
    risk_level: str          # "low" | "medium" | "high"
    requires_approval: bool  # 是否需要用户审批

@dataclass
class SkillResult:
    skill_name: str
    request_id: str
    code: str                # 错误码
    data: dict[str, Any]     # 返回数据
    from_cache: bool         # 是否来自本地缓存
    fetched_at: str          # ISO8601 时间戳
    source: str              # 数据来源描述
```

---

## 3. 认证类 Skills

## 3.1 `login`

**用途**：使用清华账号完成统一认证，建立学习系统会话。

**入参**：

```python
{
    "username": "2020xxxx",
    "password": "******",
    "fingerPrint": "...",
    "fingerGenPrint": "...",
    "fingerGenPrint3": "..."
}
```

**返回**：

```python
{
    "session": {
        "jsessionid": "...",
        "csrf_token": "...",
        "expires_at": "2026-04-24T14:30:00Z",
        "learn_bound": True,
        "zhjw_bound": False
    }
}
```

**上游调用链**（参考 `API_http.md` 三、认证接口）：
1. `POST https://id.tsinghua.edu.cn/do/off/ui/auth/login/form/.../0`
2. `POST https://id.tsinghua.edu.cn/do/off/ui/auth/login/check`
3. `GET https://learn.tsinghua.edu.cn/b/j_spring_security_thauth_roaming_entry?ticket={ticket}`

**规则**：密码不持久化；会话加密存储于 Android Keystore；会话过期时 Skill 自动重试（provider 回调模式）。

## 3.2 `refresh_session`

**用途**：检查并刷新现有会话状态。

**入参**：`session`（当前会话对象）  
**返回**：新的 `session` 对象（或错误码 `SESSION_EXPIRED`）

## 3.3 `logout`

**用途**：注销清华会话并清除本地所有认证数据。

**入参**：`session`  
**返回**：`{ "status": "logged_out" }`  

**上游调用**：`GET https://learn.tsinghua.edu.cn/f/j_spring_security_logout`

---

## 4. 数据类 Skills

所有数据类 Skill 共享以下公共返回字段：

```python
{
    "fetched_at": "2026-04-24T12:00:00Z",
    "source": "learn.tsinghua.edu.cn",
    "from_cache": False,
    # ... 业务数据
}
```

## 4.1 `get_user_info`

**用途**：获取当前登录学生的基本信息。

**入参**：`session`  
**返回**：

```python
{
    "name": "张三",
    "student_id": "2020xxxxxx",
    "department": "计算机科学与技术系"
}
```

**上游映射**：参考 `API_http.md` 十、用户信息接口

## 4.2 `get_semesters`

**用途**：获取全部学期列表与当前学期。

**入参**：`session`  
**返回**：

```python
{
    "current_semester_id": "2025-2026-2",
    "semesters": [
        {
            "semester_id": "2025-2026-2",
            "name": "2025-2026学年第二学期",
            "is_current": True
        }
    ]
}
```

**上游映射**：参考 `API_http.md` 四、学期接口

## 4.3 `get_courses`

**用途**：获取指定学期的课程列表。

**入参**：

```python
{
    "session": ...,
    "semester_id": "2025-2026-2"   # 可选，默认当前学期
}
```

**返回**：

```python
{
    "courses": [
        {
            "course_id": "wlkcid_xxx",      # 映射上游 wlkcid
            "course_number": "30240243",
            "name": "人工智能导论",
            "teacher_name": "某老师",
            "time_and_location": [
                { "weekday": 2, "period": [3, 4], "location": "六教6A009" }
            ],
            "semester_id": "2025-2026-2"
        }
    ]
}
```

**上游映射**：参考 `API_http.md` 五、课程接口

## 4.4 `get_notices`

**用途**：并发获取多门课程的通知公告。

**入参**：

```python
{
    "session": ...,
    "course_ids": ["wlkcid_1", "wlkcid_2"],
    "include_expired": False    # 可选，是否包含已过期通知
}
```

**返回**：

```python
{
    "notices": [
        {
            "notice_id": "...",
            "title": "关于第5周作业的说明",
            "publisher": "某老师",
            "publish_time": "2026-04-20T10:00:00Z",
            "expire_time": "2026-05-20T10:00:00Z",
            "content": "<p>...</p>",
            "has_read": False,
            "marked_important": True,
            "course_id": "wlkcid_1",
            "course_name": "人工智能导论",
            "attachment": {
                "name": "说明.pdf",
                "size": 102400,
                "download_url": "https://learn.tsinghua.edu.cn/..."
            }
        }
    ]
}
```

**上游映射**：参考 `API_http.md` 六、通知接口

## 4.5 `get_files`

**用途**：并发获取多门课程的课程文件。

**入参**：

```python
{
    "session": ...,
    "course_ids": ["wlkcid_1"]
}
```

**返回**：

```python
{
    "files": [
        {
            "file_id": "wjid_xxx",
            "title": "第3讲课件",
            "description": "",
            "category": "课件",
            "size": 2048000,
            "file_type": "pdf",
            "is_new": True,
            "upload_time": "2026-04-18T08:00:00Z",
            "download_url": "https://learn.tsinghua.edu.cn/...",
            "preview_url": "https://learn.tsinghua.edu.cn/...",
            "course_id": "wlkcid_1",
            "course_name": "人工智能导论"
        }
    ]
}
```

**规则**：`download_url` 由 Skill 内部自动注入 CSRF Token。  
**上游映射**：参考 `API_http.md` 七、课程文件接口

## 4.6 `get_assignments`

**用途**：并发获取多门课程的作业与 DDL。

**入参**：

```python
{
    "session": ...,
    "course_ids": ["wlkcid_1", "wlkcid_2"]
}
```

**返回**：

```python
{
    "assignments": [
        {
            "assignment_id": "...",
            "student_homework_id": "xszyid_xxx",
            "title": "第2次作业",
            "description": "<p>...</p>",
            "deadline": "2026-04-28T23:59:59Z",
            "late_submission_deadline": null,
            "submitted": False,
            "is_late_submission": False,
            "grade": null,
            "graded": False,
            "course_id": "wlkcid_1",
            "course_name": "人工智能导论",
            "url": "https://learn.tsinghua.edu.cn/..."
        }
    ]
}
```

**规则**：并发请求未交/已交未批改/已批改三个状态，合并后按 deadline 升序排列（未提交优先）。  
**上游映射**：参考 `API_http.md` 八、作业接口

## 4.7 `get_academic_calendar`

**用途**：获取教务日历中的教学活动事件。

**入参**：

```python
{
    "session": ...,
    "start_date": "20260421",
    "end_date": "20260520",
    "graduate": False    # 可选，是否使用研究生接口
}
```

**返回**：

```python
{
    "events": [
        {
            "event_id": "...",
            "title": "期中考试周",
            "start_time": "2026-04-28T00:00:00Z",
            "end_time": "2026-05-04T23:59:59Z",
            "location": null
        }
    ]
}
```

**规则**：Skill 内部完成教务系统 ticket 换取（3 步认证跳转）。  
**上游映射**：参考 `API_http.md` 九、课程日历接口

## 4.8 `get_campus_activities`

**用途**：获取校园活动信息。

**入参**：

```python
{
    "session": ...,
    "keywords": "学术讲座",   # 可选
    "start_date": "20260421", # 可选
    "end_date": "20260520"    # 可选
}
```

**返回**：

```python
{
    "activities": [
        {
            "activity_id": "...",
            "title": "AI 前沿讲座",
            "organizer": "计算机系",
            "start_time": "2026-04-25T14:00:00Z",
            "location": "FIT 报告厅",
            "url": "https://..."
        }
    ]
}
```

## 4.9 `search`

**用途**：在 Agent 本地缓存中全文检索；缓存不命中时触发对应数据类 Skill 刷新。

**入参**：

```python
{
    "session": ...,
    "query": "机器学习作业",
    "scope": "all"    # "assignments" | "notices" | "files" | "activities" | "all"
}
```

**返回**：

```python
{
    "results": [
        {
            "type": "assignment",
            "id": "...",
            "title": "机器学习第3次作业",
            "snippet": "...",
            "relevance": 0.92,
            "source_skill": "get_assignments"
        }
    ]
}
```

---

## 5. 动作类 Skills

动作类 Skill 由 LangGraph `execute` 节点调用。执行前，PolicyEngine 会评估风险等级，`medium` / `high` 级别需要经过用户审批。

## 5.1 `show_summary`

**风险等级**：low  
**用途**：在 Android 端 UI 展示结构化摘要卡片。

**入参**：

```python
{
    "request_id": "req_xxx",
    "title": "本周 DDL 一览",
    "content": "## 待完成作业\n- 人工智能导论第2次作业（4月28日）\n- ...",
    "format": "markdown"    # "markdown" | "plain"
}
```

**返回**：`{ "status": "displayed" }`

## 5.2 `send_notification`

**风险等级**：low  
**用途**：向用户推送 Android 系统通知。

**入参**：

```python
{
    "request_id": "req_xxx",
    "title": "DDL 提醒",
    "body": "人工智能导论第2次作业将于明日截止",
    "action_url": "https://learn.tsinghua.edu.cn/..."    # 可选
}
```

**返回**：`{ "notification_id": "...", "status": "sent" }`

## 5.3 `create_reminder`

**风险等级**：medium（需用户确认）  
**用途**：在系统提醒事项 App 中创建提醒。

**入参**：

```python
{
    "request_id": "req_xxx",
    "title": "提交机器学习作业",
    "due_time": "2026-04-28T22:00:00Z",
    "notes": "DDL 为 23:59，提前提醒"    # 可选
}
```

**返回**：`{ "reminder_id": "...", "status": "created" }`  
**实现**：调用 Android Intent 创建提醒（`android.provider.CalendarContract.Reminders` 或第三方提醒应用）

## 5.4 `create_calendar_event`

**风险等级**：medium（需用户确认）  
**用途**：在系统日历中创建事件。

**入参**：

```python
{
    "request_id": "req_xxx",
    "title": "期中考试：人工智能导论",
    "start_time": "2026-05-02T09:00:00Z",
    "end_time": "2026-05-02T11:00:00Z",
    "location": "六教6A001",    # 可选
    "description": "闭卷考试",   # 可选
    "conflict_decision": "prompt_user"  # 可选: prompt_user|skip_write|coexist|delete_conflicts
}
```

**返回**：`{ "event_id": "...", "status": "created|skipped_conflict|conflict_detected" }`  
**实现**：通过 Android `CalendarContract` + `ContentResolver` 直接写入系统日历（Provider 模式）

冲突策略说明：
- Android 日历支持时间重叠事件共存
- 当存在冲突时，可由用户选择：
  - `skip_write`：不写入
  - `coexist`：与原事项共存并写入
  - `delete_conflicts`：删除冲突事项后写入（高风险）

## 5.4.1 `detect_calendar_conflicts`

**风险等级**：low  
**用途**：检测待写入事项是否与已有日历事件冲突，并返回用户可选决策。

**入参**：

```python
{
    "request_id": "req_xxx",
    "start_time": "2026-05-02T09:00:00Z",
    "end_time": "2026-05-02T11:00:00Z"
}
```

**返回**：

```python
{
    "status": "detected",
    "supports_overlap": True,
    "conflict_count": 1,
    "conflicts": [
        {
            "event_id": "evt_xxx",
            "title": "已有事项",
            "start_time": "2026-05-02T08:30:00Z",
            "end_time": "2026-05-02T10:00:00Z"
        }
    ],
    "decision_options": ["skip_write", "coexist", "delete_conflicts"]
}
```

## 5.4.2 `delete_calendar_event`

**风险等级**：high（需用户确认）  
**用途**：删除一个或多个已存在的日历事项（破坏性操作）。

**入参**：

```python
{
    "request_id": "req_xxx",
    "event_id": "evt_xxx",      # 与 event_ids 二选一
    "event_ids": ["evt_a"],     # 可选
    "confirm_delete": True      # 必选，未确认时返回 APPROVAL_REQUIRED
}
```

**返回**：

```python
{
    "status": "deleted",
    "deleted_count": 1,
    "deleted": [{ "event_id": "evt_xxx", "title": "..." }],
    "missing_event_ids": [],
    "high_risk": True
}
```

## 5.5 `get_current_time`

**风险等级**：low  
**用途**：获取设备当前本地时间与时区上下文，用于相对时间任务的规划校验。

**入参**：

```python
{
    "request_id": "req_xxx"
}
```

**返回**：

```python
{
    "status": "ok",
    "local_datetime": "2026-04-27T01:30:00+08:00",
    "local_date": "2026-04-27",
    "local_time": "01:30",
    "timezone": "Asia/Shanghai",
    "timezone_name": "CST",
    "utc_offset": "+08:00",
    "epoch_ms": 1777224600000
}
```

## 5.6 `set_alarm`

**风险等级**：low  
**用途**：设置系统闹钟。

**入参**：

```python
{
    "request_id": "req_xxx",
    "time": "07:30",              # 推荐
    # 或 "time": "2026-04-28T07:30:00"（本地 ISO8601，按本地时区语义解释）
    "label": "记得提交作业",    # 可选
    "vibrate": True            # 可选
}
```

**返回**：`{ "alarm_id": "...", "status": "set" }`  
**实现**：发送 `android.intent.action.SET_ALARM` Intent

## 5.7 `open_url`

**风险等级**：low  
**用途**：打开指定 URL。

**入参**：

```python
{
    "request_id": "req_xxx",
    "url": "https://learn.tsinghua.edu.cn/...",
    "in_app": True    # 是否在内置 WebView 打开
}
```

**返回**：`{ "status": "opened" }`

## 6. Workflow 内部协议

## 6.1 AgentState（LangGraph State 定义）

```python
class AgentState(TypedDict, total=False):
    request_id: str
    session: dict[str, Any]          # 当前会话态（由 login Skill 提供）
    task_id: str
    task_status: str
    semester_id: str
    user_input: str
    user_id: str
    approve_sensitive: bool
    standardized_prompt: dict[str, Any]
    skill_plan: list[SkillInvocation]      # 计划调用的 Skill 列表
    safety_report: dict[str, Any]
    approved_skills: list[SkillInvocation]
    blocked_skills: list[SkillInvocation]
    skill_results: list[SkillResult]
    failed_skills: list[SkillInvocation]
    needs_replan: bool
    replanned_skills: list[SkillInvocation]
    audit_log: list[dict[str, Any]]
    final_response: dict[str, Any]
    approval_records: list[dict[str, Any]]
```

说明：
- `skill_plan`、`approved_skills`、`blocked_skills` 中的元素均来自 `SkillRegistry` 中注册的 Skill 元数据
- Workflow 核心只负责规划、审查、调度、审计，不负责 Skill 的业务实现细节

## 6.2 Skill 调用计划格式（plan 节点输出）

```python
[
    {
        "skill_name": "get_assignments",
        "request_id": "req_xxx_1",
        "task_id": "task_yyy",
        "args": {
            "course_ids": ["wlkcid_1", "wlkcid_2"]
        },
        "risk_level": "low",
        "requires_approval": False,
        "description": "获取本学期所有课程作业与 DDL"
    },
    {
        "skill_name": "create_calendar_event",
        "request_id": "req_xxx_2",
        "task_id": "task_yyy",
        "args": {
            "title": "提交人工智能作业",
            "start_time": "2026-04-28T22:00:00Z",
            "end_time": "2026-04-28T23:00:00Z"
        },
        "risk_level": "medium",
        "requires_approval": True,
        "description": "将作业 DDL 添加到日历"
    }
]
```

## 6.3 审批记录格式

```python
{
    "approval_id": "apv_xxx",
    "task_id": "task_yyy",
    "skill_name": "create_calendar_event",
    "request_id": "req_xxx_2",
    "risk_level": "medium",
    "reason": "将写入系统日历",
    "decision": "approved",    # "approved" | "rejected" | "timeout"
    "operator": "user",
    "decided_at": "2026-04-24T12:15:00Z"
}
```

规则：
- `risk_level=high` 的 Skill 调用必须存在审批记录
- 未审批的 Skill 调用必须返回 `APPROVAL_REQUIRED` 错误
- 若某些入参依赖上游 Skill 的结果（例如 `course_ids`），允许在 `plan` 阶段先留空，由 `execute` 节点结合当前 `AgentState` 补全

## 6.4 审计日志格式

```python
{
    "audit_id": "aud_xxx",
    "task_id": "task_yyy",
    "skill_name": "create_calendar_event",
    "request_id": "req_xxx_2",
    "stage": "execute",    # "plan" | "safety_check" | "approve" | "execute" | "replan"
    "result": "success",   # "success" | "failure" | "skipped"
    "message": "日历事件创建成功",
    "timestamp": "2026-04-24T12:15:05Z"
}
```

---

## 7. Skill 状态机

```
Skill 调用状态：
  planned → pending_approval → approved → executed
                            → rejected → skipped
  planned → executed   （低风险，直接执行）
  planned → failed     （执行失败，触发 replan）
```

```
任务状态：
  planned → in_progress → completed
                        → failed
```

---

## 8. Skill 到上游接口映射表（关键）

| Skill 名称 | 上游系统 | 关键上游 URL（参考） | 方法 |
|-----------|---------|-------------------|------|
| `login` | id.tsinghua.edu.cn | `/do/off/ui/auth/login/form/.../0`、`/do/off/ui/auth/login/check` | POST |
| `login`（绑定 learn） | learn.tsinghua.edu.cn | `/b/j_spring_security_thauth_roaming_entry?ticket={ticket}` | GET |
| `logout` | learn.tsinghua.edu.cn | `/f/j_spring_security_logout` | GET |
| `get_user_info` | learn.tsinghua.edu.cn | （see API_http.md §10） | GET |
| `get_semesters` | learn.tsinghua.edu.cn | `/b/wlxt/kc/v_wlkc_xs_xktjb_coassb/queryxnxq`、`/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester` | GET |
| `get_courses` | learn.tsinghua.edu.cn | `/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/{semesterId}/{lang}` | GET |
| `get_notices` | learn.tsinghua.edu.cn | `/b/wlxt/kcgg/wlkc_ggb/student/pageListXsbyWgq`、`pageListXsbyYgq` | GET |
| `get_files` | learn.tsinghua.edu.cn | `/b/wlxt/kj/wlkc_kjxxb/student/kjxxbByWlkcidAndSizeForStudent` | GET |
| `get_assignments` | learn.tsinghua.edu.cn | `/b/wlxt/kczy/zy/student/zyListWj` 等三个状态接口 | POST |
| `get_academic_calendar` | zhjw.cic.tsinghua.edu.cn | `/b/wlxt/common/auth/gnt` → `j_acegi_login.do` → `jxmh_out.do` | POST/GET |
| `create_reminder` | Android System | `android.provider.CalendarContract.Reminders` | Intent |
| `create_calendar_event` | Android System | `content://com.android.calendar/events`（默认 Provider 写入，必要时回退 Intent） | Provider Write (+ Intent Fallback) |
| `detect_calendar_conflicts` | Android System | `content://com.android.calendar/events`（本地查询） | Provider Query |
| `delete_calendar_event` | Android System | `content://com.android.calendar/events/{event_id}`（本地删除） | Provider Delete |
| `set_alarm` | Android System | `android.intent.action.SET_ALARM` | Intent |

---

## 9. 版本策略

- Skill 接口版本通过 `skill_version` 字段追踪
- 字段新增向后兼容
- 字段删除或语义变更必须在 RD 中标注，并同步更新 Workflow 调用侧
