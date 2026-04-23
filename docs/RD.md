# OpenTHU 需求文档（RD）

版本：v1.0-draft  
日期：2026-04-22  
状态：Draft

## 1. 文档目标

本文档定义 OpenTHU 在“清华学生学习数据接入 + Android Agent 执行闭环”场景下的产品需求、系统边界、功能拆分、验收标准与阶段计划。

本文档与以下资料协同：
- `docs/API_http.md`：上游站点接口参考（URL 与关键字段来源）
- `docs/API.md`：OpenTHU Agent 与 Backend 的正式接口契约

## 2. 项目背景

OpenTHU 当前已有 Android 端原型（Context / Actions / Safety 三层），但仍缺少：
- 可用于真实学生数据的后端接入链路
- Agent 与 Backend 的稳定协议
- 可审计、可审批的执行闭环

目标是把“演示型 Agent”升级为“可接入真实校园数据、可控执行动作的系统代理”。

## 3. 范围定义

## 3.1 In Scope

1. 接入清华上游系统的核心学习数据：
- 用户信息
- 学期与课程
- 课程通知
- 课程文件
- 作业与 DDL
- 教务日历

2. 建立 Agent 与 Backend 的 HTTP 协议：
- 会话建立与状态管理
- 任务计划与动作下发
- 动作结果回传
- 风险审批与审计记录

3. 在 Android 端实现可控动作执行：
- 展示建议与摘要
- 打开链接
- 创建提醒
- 创建日历事件
- 受控跨应用跳转

## 3.2 Out of Scope（当前阶段）

1. 自动提交作业到上游系统（保留能力设计，不做默认自动执行）
2. 无人工确认的高风险跨应用自动化（支付、账号敏感操作）
3. 多学校适配（当前仅清华）
4. 完整多端产品（iOS/Web 仅预留协议兼容）

## 4. 角色与职责

## 4.1 Agent（Android 端）

需要：
- 用户输入、设备上下文、可执行能力列表
- Backend 下发的任务与动作

给出：
- 实时上下文快照
- 执行动作结果（成功/失败/错误码/耗时）
- 风险审批请求触发与本地确认结果

## 4.2 Backend（OpenTHU 服务）

需要：
- Agent 上报的上下文、能力、动作回执
- 上游认证参数与会话态

给出：
- 数据聚合结果
- 任务计划与动作清单
- 风险决策（自动通过/待审批/拒绝）
- 审计日志与可追踪任务状态

## 4.3 Upstream（清华系统）

- `https://id.tsinghua.edu.cn`（身份认证）
- `https://learn.tsinghua.edu.cn`（学习数据）
- `https://zhjw.cic.tsinghua.edu.cn`（教务日历）

## 5. 业务流程需求

## 5.1 登录与会话建立

1. Agent 提交账号与设备指纹参数至 Backend
2. Backend 按参考流程请求 `id.tsinghua.edu.cn` 登录接口
3. Backend 使用 `ticket` 进入 `learn.tsinghua.edu.cn`，建立 `JSESSIONID`
4. Backend 保存会话并向 Agent 返回 `session_id` 与状态

验收标准：
- Agent 在 10 秒内可获取“已连接/失败原因”
- Backend 可识别会话过期并返回明确错误码

## 5.2 学习数据同步

1. Backend 获取当前学期与全部学期
2. Backend 获取课程列表
3. 以课程 ID（`wlkcid`）批量拉取通知/文件/作业
4. 按统一结构返回给 Agent

验收标准：
- 一次同步至少返回：课程、作业、通知三类信息
- 单次同步返回数据带 `fetched_at` 与 `source` 字段

## 5.3 Agent 任务执行与审批闭环

1. Agent 上报上下文并请求计划
2. Backend 输出 Action Plan（含 riskLevel 与 requiresApproval）
3. Agent 执行动作并回传结果
4. 对高风险动作，必须先审批后执行
5. Backend 记录审计轨迹（plan/approve/execute/replan）

验收标准：
- 高风险动作未审批不得执行
- 每个动作都可追溯到 task_id、action_id、request_id

## 6. 功能需求明细

## FR-01 账号连接

输入：`username`、`password`、`fingerPrint`、`fingerGenPrint`、`fingerGenPrint3`  
输出：`session_id`、`status`、`expires_at`  
规则：不在 Agent 持久化明文密码；会话异常可刷新。

## FR-02 学生基础信息

输入：有效 `session_id`  
输出：`name`、`student_id`、`department`  
规则：字段名统一，不直接暴露上游脏字段。

## FR-03 学期与课程

输入：`semester_id`（可选）  
输出：学期列表、当前学期、课程列表（含 `course_id/wlkcid`、教师、时间地点）  
规则：课程列表至少支持按学期过滤。

## FR-04 通知聚合

输入：`course_ids[]`、时间窗口（可选）  
输出：通知列表（标题、发布时间、课程归属、附件）  
规则：支持“未过期/已过期”聚合。

## FR-05 文件聚合与下载指引

输入：`course_ids[]`  
输出：文件列表（`file_id/wjid`、类别、大小、下载 URL）  
规则：下载类 URL 返回前必须完成 CSRF 参数补全。

## FR-06 作业与 DDL

输入：`course_ids[]`  
输出：作业列表（`assignment_id`、`student_homework_id/xszyid`、`deadline`、`submitted`、`grade`）  
规则：按未提交优先排序。

## FR-07 教务日历

输入：`start_date`、`end_date`、`graduate`（可选）  
输出：时间段日历事件  
规则：后端负责 ticket 换取与教务认证跳转。

## FR-08 Action Plan 与执行

输入：`goal/context/capabilities`  
输出：`actions[]`（含 `type/riskLevel/requiresApproval/args`）  
规则：Agent 严格按 `action_id` 回传结果。

## FR-09 审批与安全

输入：待审批动作  
输出：`approved/rejected/timeout`  
规则：高风险动作默认 `pending_approval`。

## FR-10 审计日志

输入：全链路操作事件  
输出：可检索审计记录（按 task/action/session 查询）  
规则：审计事件不可篡改，至少保留 180 天。

## 7. 数据模型要求

核心实体：
- UserProfile
- Semester
- Course
- Notice
- CourseFile
- Assignment
- AgentTask
- AgentAction
- ApprovalRecord
- AuditRecord

关键字段要求：
- 所有实体必须有 `id`
- 所有时间字段使用 ISO8601（UTC）
- 所有列表接口支持 `cursor` 分页
- 所有写接口支持 `request_id` 幂等

## 8. 非功能需求

## 8.1 安全

1. 凭证与会话分离：账号凭证仅用于换取会话
2. 传输全程 HTTPS
3. 关键动作（高风险）必须审批
4. 审计日志包含操作者、来源、时间、结果

## 8.2 性能

1. Agent 同步接口 P95 < 1.5s（不含首次登录）
2. 数据聚合接口 P95 < 3s
3. 批量接口默认支持 200 条分页

## 8.3 可用性与容错

1. 上游失败时返回明确错误码与重试建议
2. Backend 对上游接口支持指数退避重试
3. Agent 离线时可展示最近一次成功快照

## 8.4 可观测性

1. 每个请求均含 `request_id`
2. 关键链路有埋点：login/sync/plan/approve/execute
3. 可按 `session_id/task_id/action_id` 回溯

## 9. 风险与对策

1. 上游接口变更风险
- 对策：建立 adapter 层，不在业务层硬编码上游字段

2. 会话失效风险
- 对策：统一 session manager + 自动刷新 + 明确过期码

3. 自动化误触发风险
- 对策：风险分级 + 人工审批 + 白名单动作

4. 法律与合规风险
- 对策：最小化采集、脱敏日志、可撤销授权

## 10. 里程碑

1. M1（协议冻结）
- 完成 RD/API 文档评审
- 确认 Agent/Backend 字段契约

2. M2（数据接入）
- 打通登录、学期、课程、作业、通知
- 完成基础聚合接口

3. M3（执行闭环）
- 打通 plan/approval/execute/audit 主链路
- Android 端接入动作执行与结果回传

4. M4（稳定化）
- 完成压测、异常演练、审计报表
- 进入灰度验证

## 11. 验收清单

1. 可登录并稳定获取会话
2. 可拉取并展示用户/课程/作业/通知/文件/日历
3. Agent 与 Backend 可完成计划->执行->回传闭环
4. 高风险动作需审批且可追踪
5. 所有接口具备错误码、幂等、审计字段
