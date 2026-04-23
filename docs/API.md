# OpenTHU 接口文档（API）

版本：v1.0-draft  
日期：2026-04-22  
状态：Draft

## 1. 文档范围

本文档定义 OpenTHU 中 Agent（Android）与 Backend 的 HTTP 接口契约，并给出 Backend 到清华上游系统（id/learn/zhjw）的映射关系。

说明：
- 上游 URL 与关键字段参考 `docs/API_http.md`
- 本文档定义的是 OpenTHU 自有 API，不等同于上游原始 API

## 2. 交互角色与职责

## 2.1 Agent 侧（需要什么，给出什么）

Agent 需要：
- 登录会话状态（是否连接、是否过期）
- 学习数据聚合结果（课程/通知/作业/文件/日历）
- 可执行动作清单（含风险级别与审批要求）

Agent 给出：
- 用户输入（goal）
- 上下文快照（context signals）
- 能力列表（capabilities）
- 动作执行结果（success/failure/error）
- 审批结果（approved/rejected）

## 2.2 Backend 侧（需要什么，给出什么）

Backend 需要：
- Agent 上报的任务请求与执行回执
- 登录凭证参数（username/password/fingerprint）
- 上游系统会话态（JSESSIONID、CSRF）

Backend 给出：
- 稳定统一的数据模型
- 任务计划与动作编排
- 风险决策与审批门禁
- 可追溯审计日志

## 3. 协议约定

- Base URL：`/api/v1`
- Content-Type：`application/json`（文件上传除外）
- 认证：
  - Agent -> Backend：`Authorization: Bearer <agent_token>`
  - 用户会话：`session_id`（由 Backend 下发）
- 幂等：写接口必须支持 `request_id`
- 时间：统一 ISO8601（UTC）

通用响应：

```json
{
  "request_id": "req_20260422_xxx",
  "code": "OK",
  "message": "success",
  "data": {}
}
```

通用错误码：
- `OK`
- `INVALID_PARAM`
- `UNAUTHORIZED`
- `SESSION_EXPIRED`
- `UPSTREAM_AUTH_FAILED`
- `UPSTREAM_TIMEOUT`
- `APPROVAL_REQUIRED`
- `ACTION_NOT_ALLOWED`
- `INTERNAL_ERROR`

## 4. 会话与认证接口

## 4.1 创建学习会话

`POST /sessions/login`

用途：
- Backend 使用清华账号参数完成统一认证
- 换取可用于学习数据读取的会话

请求：

```json
{
  "request_id": "req_login_001",
  "username": "2020xxxx",
  "password": "******",
  "fingerprint": {
    "fingerPrint": "...",
    "fingerGenPrint": "...",
    "fingerGenPrint3": "..."
  }
}
```

响应：

```json
{
  "request_id": "req_login_001",
  "code": "OK",
  "data": {
    "session_id": "sess_xxx",
    "status": "active",
    "expires_at": "2026-04-22T14:30:00Z",
    "upstream": {
      "learn_bound": true,
      "zhjw_bound": false
    }
  }
}
```

后端上游映射：
1. `POST https://id.tsinghua.edu.cn/do/off/ui/auth/login/form/.../0`
2. `POST https://id.tsinghua.edu.cn/do/off/ui/auth/login/check`
3. `GET https://learn.tsinghua.edu.cn/b/j_spring_security_thauth_roaming_entry?ticket={ticket}`

## 4.2 刷新会话

`POST /sessions/{session_id}/refresh`

- Backend 检查上游会话有效性
- 必要时重新换票

## 4.3 注销会话

`DELETE /sessions/{session_id}`

- Backend 调用：`GET https://learn.tsinghua.edu.cn/f/j_spring_security_logout`
- 清理本地会话状态

## 5. 学习数据接口（Backend 对 Agent）

## 5.1 用户信息

`GET /students/me/profile?session_id={session_id}`

返回字段：
- `name`
- `student_id`
- `department`

## 5.2 学期列表

`GET /students/me/semesters?session_id={session_id}`

返回字段：
- `semester_id`
- `name`
- `is_current`

上游映射：
- `GET /b/wlxt/kc/v_wlkc_xs_xktjb_coassb/queryxnxq`
- `GET /b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester`

## 5.3 课程列表

`GET /students/me/courses?session_id={session_id}&semester_id={semester_id}`

返回字段：
- `course_id`（映射 `wlkcid`）
- `course_number`
- `name`
- `teacher_name`
- `time_and_location[]`
- `semester_id`

上游映射：
- `GET /b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/{semesterId}/{lang}`

## 5.4 课程通知

`GET /students/me/notices?session_id={session_id}&course_ids=...&cursor=...`

返回字段：
- `notice_id`
- `title`
- `publish_time`
- `expire_time`
- `content`
- `course_id`
- `attachment`（可空）

上游映射：
- `GET /b/wlxt/kcgg/wlkc_ggb/student/pageListXsbyWgq?wlkcid={courseId}`
- `GET /b/wlxt/kcgg/wlkc_ggb/student/pageListXsbyYgq?wlkcid={courseId}`

## 5.5 课程文件

`GET /students/me/files?session_id={session_id}&course_ids=...&cursor=...`

返回字段：
- `file_id`（映射 `wjid`）
- `title`
- `category`
- `size`
- `file_type`
- `upload_time`
- `course_id`
- `download_url`
- `preview_url`

上游映射：
- `GET /b/wlxt/kj/wlkc_kjxxb/student/kjxxbByWlkcidAndSizeForStudent?wlkcid={courseID}&size=200`
- `GET /b/wlxt/kj/wlkc_kjflb/{courseType}/pageList?wlkcid={courseID}`
- 下载：`GET /b/wlxt/kj/wlkc_kjxxb/{courseType}/downloadFile?sfgk=0&wjid={fileID}`
- 预览：`GET /f/wlxt/kc/wj_wjb/{courseType}/beforePlay?...`

说明：
- Backend 负责在下载 URL 上补齐 CSRF 参数

## 5.6 作业与 DDL

`GET /students/me/assignments?session_id={session_id}&course_ids=...&cursor=...`

返回字段：
- `assignment_id`
- `student_homework_id`（映射 `xszyid`）
- `title`
- `deadline`
- `submitted`
- `grade`
- `course_id`

上游映射：
- `POST /b/wlxt/kczy/zy/student/zyListWj`
- `POST /b/wlxt/kczy/zy/student/zyListYjwg`
- `POST /b/wlxt/kczy/zy/student/zyListYpg`
- `POST /b/wlxt/kczy/zy/student/detail`

## 5.7 教务日历

`GET /students/me/calendar?session_id={session_id}&start_date=YYYYMMDD&end_date=YYYYMMDD`

返回字段：
- `event_id`
- `title`
- `start_time`
- `end_time`
- `location`

上游映射：
1. `POST /b/wlxt/common/auth/gnt` 获取 ticket
2. `GET https://zhjw.cic.tsinghua.edu.cn/j_acegi_login.do?url=/&ticket={ticket}`
3. `GET https://zhjw.cic.tsinghua.edu.cn/jxmh_out.do?...`

## 6. Agent 控制面接口（Agent <-> Backend）

## 6.1 Agent 心跳与同步

`POST /agents/{agent_id}/sync`

用途：
- Agent 定期上报状态
- Backend 返回待执行任务与配置

请求：

```json
{
  "request_id": "req_sync_001",
  "session_id": "sess_xxx",
  "device": {
    "platform": "android",
    "app_version": "0.1.0"
  },
  "capabilities": [
    "notification_context",
    "cross_app_actions",
    "safety_guard"
  ],
  "context_signals": [
    {
      "id": "notif_1",
      "title": "ddl reminder",
      "detail": "course A due tomorrow",
      "source": "notification_listener"
    }
  ],
  "last_cursor": "cmd_120"
}
```

响应：

```json
{
  "request_id": "req_sync_001",
  "code": "OK",
  "data": {
    "commands": [
      {
        "command_id": "cmd_121",
        "type": "plan_task",
        "payload": {
          "task_id": "task_xxx",
          "goal": "整理本周DDL并提醒"
        }
      }
    ],
    "server_time": "2026-04-22T12:10:00Z"
  }
}
```

## 6.2 请求任务计划

`POST /agent/tasks/plan`

请求：

```json
{
  "request_id": "req_plan_001",
  "session_id": "sess_xxx",
  "goal": "整理本周作业和DDL并提醒",
  "context": {
    "semester_id": "2025-2026-2"
  }
}
```

响应：

```json
{
  "request_id": "req_plan_001",
  "code": "OK",
  "data": {
    "task_id": "task_xxx",
    "status": "planned",
    "actions": [
      {
        "action_id": "act_1",
        "type": "show_summary",
        "title": "展示DDL摘要",
        "risk_level": "low",
        "requires_approval": false,
        "args": {}
      },
      {
        "action_id": "act_2",
        "type": "create_calendar_event",
        "title": "创建提醒日历",
        "risk_level": "medium",
        "requires_approval": false,
        "args": {
          "events": []
        }
      }
    ]
  }
}
```

## 6.3 回传动作执行结果

`POST /agent/tasks/{task_id}/actions/{action_id}/result`

请求：

```json
{
  "request_id": "req_result_001",
  "agent_id": "agent_android_xxx",
  "status": "executed",
  "success": true,
  "message": "calendar intent launched",
  "started_at": "2026-04-22T12:11:00Z",
  "finished_at": "2026-04-22T12:11:02Z",
  "artifacts": {
    "opened_url": "https://learn.tsinghua.edu.cn/..."
  }
}
```

响应：

```json
{
  "request_id": "req_result_001",
  "code": "OK",
  "data": {
    "task_status": "in_progress"
  }
}
```

## 7. 审批与安全接口

## 7.1 拉取待审批动作

`GET /approvals/pending?session_id={session_id}&agent_id={agent_id}`

返回：
- `approval_id`
- `task_id`
- `action_id`
- `risk_level`
- `reason`
- `expires_at`

## 7.2 提交审批决策

`POST /approvals/{approval_id}/decision`

请求：

```json
{
  "request_id": "req_approve_001",
  "decision": "approved",
  "operator": "user",
  "comment": "允许执行"
}
```

响应：

```json
{
  "request_id": "req_approve_001",
  "code": "OK",
  "data": {
    "approval_status": "approved"
  }
}
```

规则：
- `risk_level=high` 的动作必须存在审批记录
- 未审批动作执行必须返回 `APPROVAL_REQUIRED`

## 8. 审计接口

## 8.1 查询任务审计轨迹

`GET /audit/tasks/{task_id}`

返回字段：
- `audit_id`
- `task_id`
- `action_id`
- `stage`（`plan|approve|execute|replan`）
- `message`
- `timestamp`

## 8.2 查询会话审计轨迹

`GET /audit/sessions/{session_id}`

## 9. 数据结构定义

## 9.1 AgentAction

```json
{
  "action_id": "act_1",
  "type": "show_summary|open_url|set_alarm|create_calendar_event|launch_app",
  "title": "string",
  "risk_level": "low|medium|high",
  "requires_approval": true,
  "status": "planned|pending_approval|approved|executed|failed",
  "args": {}
}
```

## 9.2 Assignment

```json
{
  "assignment_id": "string",
  "student_homework_id": "string",
  "title": "string",
  "deadline": "2026-04-23T15:59:59Z",
  "submitted": false,
  "grade": null,
  "course_id": "wlkcid_xxx"
}
```

## 10. Backend 到上游接口映射表（关键）

| OpenTHU Backend 能力 | 上游 URL（参考） | 方法 | 关键参数/字段 |
|---|---|---|---|
| 登录认证 | `https://id.tsinghua.edu.cn/do/off/ui/auth/login/form/.../0` | POST | `username,password,fingerPrint,fingerGenPrint,fingerGenPrint3` |
| 登录校验 | `https://id.tsinghua.edu.cn/do/off/ui/auth/login/check` | POST | 同上 |
| learn 绑定 | `https://learn.tsinghua.edu.cn/b/j_spring_security_thauth_roaming_entry?ticket={ticket}` | GET | `ticket` |
| 注销 | `https://learn.tsinghua.edu.cn/f/j_spring_security_logout` | GET | `JSESSIONID` |
| 学期列表 | `/b/wlxt/kc/v_wlkc_xs_xktjb_coassb/queryxnxq` | GET | 会话 |
| 当前学期 | `/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester` | GET | 会话 |
| 课程列表 | `/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/{semesterId}/{lang}` | GET | `semesterId` |
| 通知列表 | `/b/wlxt/kcgg/wlkc_ggb/student/pageListXsbyWgq` | GET | `wlkcid` |
| 文件列表 | `/b/wlxt/kj/wlkc_kjxxb/student/kjxxbByWlkcidAndSizeForStudent` | GET | `wlkcid,size` |
| 文件下载 | `/b/wlxt/kj/wlkc_kjxxb/{courseType}/downloadFile?sfgk=0&wjid={fileID}` | GET | `wjid` + CSRF |
| 作业列表 | `/b/wlxt/kczy/zy/student/zyListWj` 等 | POST | `wlkcid` |
| 作业详情 | `/b/wlxt/kczy/zy/student/detail` | POST | `id` |
| 作业提交（预留） | `/b/wlxt/kczy/zy/student/tjzy` | POST | `xszyid,zynr,fileupload,isDeleted` |
| 日历 ticket | `/b/wlxt/common/auth/gnt` | POST | 会话 |
| 教务登录 | `https://zhjw.cic.tsinghua.edu.cn/j_acegi_login.do?url=/&ticket={ticket}` | GET | `ticket` |
| 教务日历 | `https://zhjw.cic.tsinghua.edu.cn/jxmh_out.do?...` | GET | `startDate,endDate` |

## 11. 状态机约定

动作状态：
- `planned`
- `pending_approval`
- `approved`
- `executed`
- `failed`

任务状态：
- `planned`
- `in_progress`
- `completed`
- `failed`

## 12. 版本策略

- 接口前缀含版本号：`/api/v1`
- 字段新增向后兼容
- 字段删除或语义变更必须升级大版本
