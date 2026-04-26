# OpenTHU 需求文档（RD）

版本：v1.1-draft  
日期：2026-04-24  
状态：Draft

## 1. 文档目标

本文档定义 OpenTHU 在"清华学生学习数据接入 + Android Agent 执行闭环"场景下的产品需求、系统边界、功能拆分、验收标准与阶段计划。

本文档与以下资料协同：
- `docs/API_http.md`：上游站点接口参考（URL 与关键字段来源，供 Skill 实现时参考）
- `docs/API.md`：OpenTHU Agent Skill 接口定义与 Workflow 协议

## 2. 项目背景

OpenTHU 当前已有 Android 端原型（Context / Actions / Safety 三层）和 LangGraph 工作流引擎，但仍缺少：
- 可用于真实学生数据的接入能力
- 结构化的 Skill（工具）体系，供 Agent 按需调用
- 可审计、可审批的执行闭环

目标是把"演示型 Agent"升级为"以 Skill 体系驱动、可接入真实校园数据、可控执行动作的系统代理"。Agent 通过内置 Skills 直接与上游清华系统通信，**不再依赖独立的 Backend 服务**。数据聚合、任务规划、执行编排全部在 Agent 侧的 LangGraph Workflow 中完成。

## 3. 范围定义

## 3.1 In Scope

1. **数据类 Skills**：Agent 通过 Skill 直接拉取清华上游数据：
   - 用户信息（`get_user_info`）
   - 学期与课程（`get_semesters`、`get_courses`）
   - 课程通知（`get_notices`）
   - 课程文件（`get_files`）
   - 作业与 DDL（`get_assignments`）
   - 教务日历（`get_academic_calendar`）
   - 校园活动信息（`get_campus_activities`）
   - 全局搜索（`search`）

2. **动作类 Skills**：Agent 通过 Skill 执行本地系统动作：
   - 查询当前时间（`get_current_time`）
   - 创建提醒事项（`create_reminder`）
   - 创建日历事件（`create_calendar_event`）
   - 设置闹钟（`set_alarm`）
   - 展示摘要（`show_summary`）
   - 打开链接（`open_url`）
   - 发送通知（`send_notification`）

3. **Workflow（保留并增强）**：
   - 任务规划（Plan）
   - 安全审查（Safety Check）
   - 风险审批（Approval）
   - 动作执行（Execute via Skills）
   - 结果回馈与重规划（Replan）
   - 审计记录（Audit）

4. **认证与会话管理**（Agent 侧内置 Skill）：
   - 清华统一身份认证（`login`）
   - 会话保持与刷新（`refresh_session`）
   - 注销（`logout`）

## 3.2 Out of Scope（当前阶段）

1. 独立 Backend HTTP 服务（数据聚合职责移入 Agent Skills）
2. 自动提交作业到上游系统（保留 Skill 设计，不做默认自动执行）
3. 第三方应用自动化与跨应用跳转（如微信、支付宝等）
4. 多学校适配（当前仅清华）
5. 完整多端产品（iOS/Web 仅预留协议兼容）

## 4. 角色与职责

## 4.1 Agent（Android 端 + LangGraph Workflow）

职责：
- 接受用户输入，驱动 LangGraph Workflow 运行
- 通过 Skill 调用上游清华系统，获取学习数据
- 通过 Skill 执行本地系统动作（提醒、日历、闹钟等）
- 对高风险动作请求用户审批，执行前必须确认
- 维护审计日志与执行记录（本地存储）
- 维护会话态（JSESSIONID / CSRF），供数据类 Skill 复用

提供给用户的能力：
- 自然语言对话界面
- 任务执行摘要与结果展示
- 高风险动作审批界面

## 4.2 Skills（Agent 内置工具层）

Skills 是 Agent 可调用的原子能力单元，分为两类：

**数据类 Skill**（读取上游数据）：
- 直接持有并复用 Agent 的会话态（Cookie / JSESSIONID）
- 调用 `API_http.md` 中定义的清华上游接口
- 返回标准化数据结构

**动作类 Skill**（执行本地动作）：
- 调用 Android 系统 API 或 Intent
- 风险等级由 PolicyEngine 评估
- 高风险动作需要用户审批后才可执行

**框架边界**：
- Agent 核心通过 `SkillRegistry` 发现和调度 Skills
- Workflow 不直接依赖 Skill 业务代码，只依赖统一的 `SkillSpec` / `SkillInvocation` / `SkillResult`
- 因此 Skills 可以由不同成员并行实现，而不需要改动 Workflow 主链路

## 4.3 Upstream（清华系统，只读）

- `https://id.tsinghua.edu.cn`（身份认证）
- `https://learn.tsinghua.edu.cn`（学习数据）
- `https://zhjw.cic.tsinghua.edu.cn`（教务日历）

上游系统由数据类 Skill 直接访问，Agent 统一持有会话态。

## 5. 业务流程需求

## 5.1 登录与会话建立

1. 用户在 Android 端输入账号与密码
2. `login` Skill 按参考流程请求 `id.tsinghua.edu.cn` 登录接口
3. `login` Skill 使用 `ticket` 进入 `learn.tsinghua.edu.cn`，建立 `JSESSIONID`
4. Agent 在本地保存会话（加密存储），后续数据类 Skill 复用此会话

验收标准：
- 用户在 10 秒内可获取"已连接/失败原因"
- 会话过期时数据类 Skill 自动触发重登（利用 `provider` 回调模式）

## 5.2 学习数据获取（via Skills）

Workflow 按需调用对应 Skill，无需独立后端服务：
1. `get_semesters` / `get_courses` 获取学期与课程列表
2. `get_assignments` 拉取作业与 DDL
3. `get_notices` 拉取课程通知
4. `get_files` 拉取课程文件
5. `get_academic_calendar` 拉取教务日历

验收标准：
- Agent 可在单次 Workflow 运行中同时调用多个数据类 Skill
- Skill 返回数据带 `fetched_at` 与 `source` 字段

## 5.3 用户意图处理与 Workflow 执行

1. 用户输入自然语言目标（如"帮我把本周 DDL 加到日历"）
2. Workflow 的 `normalize` 节点将输入结构化
3. `plan` 节点规划所需 Skills 调用序列（数据读取 + 动作执行）
4. `safety_check` 节点评估各动作的风险等级
5. 高风险动作（如 `set_alarm`、`create_calendar_event`）进入 `pending_approval`
6. 用户审批后，`execute` 节点依次调用对应 Skills
7. 执行结果写入 Audit Log，失败时触发 Replan

验收标准：
- 高风险 Skill 未审批不得执行
- 每次 Skill 调用均可追溯到 task_id 与 skill_name

## 6. 功能需求明细（Skills）

## SK-01 login（认证登录）

输入：`username`、`password`、`fingerPrint`、`fingerGenPrint`、`fingerGenPrint3`  
输出：`session`（含 JSESSIONID、CSRF token、expires_at）  
规则：密码不持久化明文；会话加密存储在本地；会话过期自动刷新。

## SK-02 get_user_info（学生基础信息）

输入：当前有效 `session`  
输出：`name`、`student_id`、`department`  
规则：字段统一命名，不暴露上游原始脏字段。

## SK-03 get_semesters（学期列表）

输入：`session`  
输出：学期列表（`semester_id`、`name`、`is_current`）  
规则：自动标记当前学期。

## SK-04 get_courses（课程列表）

输入：`session`、`semester_id`（可选，默认当前学期）  
输出：课程列表（`course_id`、`name`、`teacher_name`、`time_and_location[]`、`semester_id`）  
规则：支持按学期过滤。

## SK-05 get_notices（课程通知）

输入：`session`、`course_ids[]`、时间窗口（可选）  
输出：通知列表（`notice_id`、`title`、`publish_time`、`expire_time`、`content`、`course_id`、`attachment`）  
规则：并发拉取多门课程通知，合并后按时间排序；支持"未过期/已过期"聚合。

## SK-06 get_files（课程文件）

输入：`session`、`course_ids[]`  
输出：文件列表（`file_id`、`title`、`category`、`size`、`file_type`、`upload_time`、`course_id`、`download_url`、`preview_url`）  
规则：下载 URL 需自动补齐 CSRF 参数。

## SK-07 get_assignments（作业与 DDL）

输入：`session`、`course_ids[]`  
输出：作业列表（`assignment_id`、`student_homework_id`、`title`、`deadline`、`submitted`、`grade`、`course_id`）  
规则：未提交优先排序；自动并发请求未交/已交未批改/已批改三种状态。

## SK-08 get_academic_calendar（教务日历）

输入：`session`、`start_date`（YYYYMMDD）、`end_date`（YYYYMMDD）、`graduate`（可选）  
输出：日历事件列表（`event_id`、`title`、`start_time`、`end_time`）  
规则：Skill 内部完成教务系统 ticket 换取与认证跳转。

## SK-09 get_campus_activities（校园活动信息）

输入：`session`、`keywords`（可选）、时间范围（可选）  
输出：活动列表（`activity_id`、`title`、`organizer`、`start_time`、`location`、`url`）  
规则：聚合清华校内活动数据源。

## SK-10 search（全局搜索）

输入：`session`、`query`、`scope`（`assignments|notices|files|activities|all`）  
输出：搜索结果列表（含来源类型与相关度）  
规则：在 Agent 侧对已缓存数据执行全文检索；超出缓存范围时触发对应数据类 Skill 刷新。

## SK-11 create_reminder（创建提醒事项）

输入：`title`、`due_time`、`notes`（可选）  
输出：`reminder_id`、`status`  
风险等级：medium  
规则：调用 Android 系统提醒 API；需用户确认后执行。

## SK-12 create_calendar_event（创建日历事件）

输入：`title`、`start_time`、`end_time`、`location`（可选）、`description`（可选）  
输出：`event_id`、`status`  
风险等级：medium  
规则：调用 Android 日历 Intent；需用户确认后执行。

## SK-13 set_alarm（设置闹钟）

输入：`time`（本地时区语义，推荐 `HH:mm`）、`label`（可选）、`repeat`（可选）  
输出：`alarm_id`、`status`  
风险等级：low  
规则：调用 Android AlarmManager API。

## SK-14 show_summary（展示摘要）

输入：`title`、`content`（Markdown）  
输出：展示状态  
风险等级：low  
规则：在 Android 端 UI 中渲染结构化摘要卡片。

## SK-15 send_notification（发送通知）

输入：`title`、`body`、`action_url`（可选）  
输出：通知状态  
风险等级：low  
规则：调用 Android 通知系统；支持 Deep Link 跳转。

## SK-16 open_url（打开链接）

输入：`url`、`in_app`（是否在内置浏览器中打开）  
输出：跳转状态  
风险等级：low  
规则：优先内置 WebView；外部链接需用户确认。

## 7. Workflow 节点设计

Agent 使用 LangGraph 构建以下节点（与现有实现对齐）：

```
用户输入
  └─► normalize（结构化用户意图）
        └─► plan（生成 Skill 调用计划）
              └─► safety_check（风险分级）
                    ├─► [低风险] execute（直接调用 Skill）
                    └─► [高/中风险] pending_approval
                              └─► [用户审批] execute（调用 Skill）
                                    └─► [失败] replan
                                    └─► [成功] audit → final_response
```

动作状态机：
- `planned` → `pending_approval` → `approved` → `executed` / `failed`
- `planned` → `executed` / `failed`（低风险直接执行）

任务状态机：
- `planned` → `in_progress` → `completed` / `failed`

## 8. 数据模型要求

核心实体（Agent 本地存储）：
- `UserProfile`（用户信息缓存）
- `Session`（会话态，加密存储）
- `Semester` / `Course`（学期课程缓存）
- `Notice` / `CourseFile` / `Assignment`（学习数据缓存）
- `AgentTask`（任务记录）
- `SkillInvocation`（Skill 调用记录，替代原 AgentAction）
- `ApprovalRecord`（审批记录）
- `AuditRecord`（审计记录）

关键字段要求：
- 所有实体必须有 `id`
- 所有时间字段使用 ISO8601（UTC），`set_alarm.time` 例外（本地时区语义）
- 所有缓存数据带 `fetched_at` 与 `source`
- 所有写操作支持 `request_id` 幂等

## 9. 非功能需求

## 9.1 安全

1. 凭证不明文持久化：密码仅用于换取会话，会话加密存储（Android Keystore）
2. 传输全程 HTTPS
3. 高风险 Skill 调用必须有审批记录
4. 审计日志包含操作者、来源、时间、结果

## 9.2 性能

1. 数据类 Skill 调用 P95 < 2s（不含首次登录）
2. 多 Skill 并发调用时总耗时 P95 < 4s（利用 asyncio / coroutines）
3. 缓存命中时返回 P95 < 200ms

## 9.3 可用性与容错

1. 上游失败时 Skill 返回明确错误码与重试建议
2. Skill 对上游接口支持指数退避重试
3. 数据类 Skill 在上游不可达时可返回本地缓存快照（带 `from_cache: true` 标记）

## 9.4 可观测性

1. 每次 Skill 调用均含 `request_id` 与 `skill_name`
2. 关键链路埋点：`login` / `skill_invoke` / `plan` / `approve` / `execute`
3. 可按 `task_id` / `skill_name` / `session_id` 回溯

## 10. 风险与对策

1. **上游接口变更风险**
   - 对策：数据类 Skill 内部封装上游适配层（参考 thu-learn-lib 模式），不在 Workflow 层硬编码上游字段

2. **会话失效风险**
   - 对策：Skill 统一持有 Session，会话失效时自动触发 `login` Skill 重登（provider 回调模式）

3. **自动化误触发风险**
   - 对策：PolicyEngine 风险分级 + 用户审批 + 白名单动作 Skill

4. **本地数据安全风险**
   - 对策：会话态使用 Android Keystore 加密；缓存数据在 SharedPreferences / Room DB 加密存储

5. **法律与合规风险**
   - 对策：最小化数据采集、脱敏 Audit Log、可撤销授权（logout Skill 清除所有本地数据）

## 11. 里程碑

1. **M1（Skill 体系设计冻结）**
   - 完成 RD / API 文档评审
   - 确认全部 Skill 接口定义与数据结构

2. **M2（数据类 Skill 接入）**
   - 实现 `login` / `get_user_info` / `get_semesters` / `get_courses` / `get_assignments` / `get_notices`
   - Agent 可在 Workflow 中调用上述 Skills 并展示数据

3. **M3（动作类 Skill 与执行闭环）**
   - 实现 `create_reminder` / `create_calendar_event` / `set_alarm` / `show_summary`
   - 打通 plan → approval → execute（via Skill）→ audit 主链路

4. **M4（搜索、通知与活动 Skill）**
   - 实现 `search` / `send_notification` / `get_campus_activities`
   - 完成全量 Skill 覆盖

5. **M5（稳定化）**
   - 压测、异常演练、Audit 报表
   - 进入灰度验证

## 12. 验收清单

1. 可登录并稳定建立清华会话（via `login` Skill）
2. 数据类 Skills 可正确拉取用户/课程/作业/通知/文件/日历
3. Workflow 可完成 plan → execute（via Skills）→ audit 完整闭环
4. 高风险动作类 Skill 必须经审批后执行，且可追踪
5. 所有 Skill 调用具备错误码、幂等性、缓存与审计字段
6. 用户可通过 `logout` Skill 一键清除本地所有会话与缓存数据
