# HOMEWORK_SKILL

更新时间：2026-05-02  
状态：Active

## 1. 目标与边界

Homework Skill 的目标是：
1. 拉取课程作业列表（全部）
2. 拉取课程作业列表（未提交）
3. 预览作业附件信息
4. 上传作业附件
5. 提交作业

当前架构中，Python 负责规划与参数校验，Kotlin 负责设备侧真实执行（网络请求、打开页面、文件选择器、结果回传）。

## 2. 端到端调用链

1. Agent-Core/LangGraph 在 `plan_skills` 产出 homework skill 调用。
2. `SkillManager` 基于 `args_json_schema` 做参数校验与归一化。
3. Python handler（`homework_handlers.py`）做语义校验后，把 invocation 转发给 Kotlin bridge。
4. Kotlin `PythonSkillBridgeExecutor` 将 JSON 映射为 `SystemAction`。
5. Kotlin `ActionExecutor` 执行真实动作（Learn 接口、页面打开、文件上传/提交）。
6. Kotlin 将执行结果标准化为 `{code, data}` 返回 Python，再由上层进入审计/回调。

## 3. Skill 定义（Python）

定义文件：`agent/langgraph/skill_core.py`

已注册 skill：
1. `crawl_course_homeworks`
2. `crawl_unsubmitted_homeworks`
3. `preview_homework_attachments`
4. `upload_homework_attachment`
5. `submit_homework`

这些 skill 的 `args_json_schema` 已接入 `SkillManager`，用于 planner 阶段和执行阶段双重校验。

## 4. Kotlin Section（实现与改动说明）

本节是 Homework Skill 在 Android 端可执行的核心。

### 4.1 改动文件总览

1. `app/src/main/java/ai/opencray/app/execution/ActionExecutor.kt`
2. `app/src/main/java/ai/opencray/app/bridge/PythonSkillBridgeExecutor.kt`
3. `app/src/main/java/ai/opencray/app/runtime/OpenCrayRuntime.kt`
4. `app/src/test/java/ai/opencray/app/bridge/PythonSkillBridgeExecutorTest.kt`

### 4.2 `ActionExecutor.kt`：执行层

新增/补齐的 action 路由：
1. `crawl_course_homeworks`
2. `crawl_unsubmitted_homeworks`
3. `preview_homework_attachments`
4. `upload_homework_attachment`
5. `submit_homework`

主要实现逻辑：
1. 认证解析：从 action 参数或 payload 中读取 `session_cookie`（必须），可选 `csrf_token`、`learn_base_url`。
2. 作业抓取：按课程调用 Learn 接口（未交/已交/已批改列表），抽取并结构化返回作业记录。
3. 附件预览：请求作业详情接口解析附件，随后打开作业详情页。
4. 附件上传：multipart 上传到作业提交接口；成功后返回 `attachment_token` 并打开作业页。
5. 作业提交：要求 `confirm_submit=true`；支持文本、文件路径/URI、`local_file_paths`、`attachment_tokens`；提交后打开作业页。

新增的重要约束与错误码触发点：
1. 缺少 `session_cookie` -> `reason=missing_auth`
2. 缺少 `homework_id` -> `reason=missing_homework_id`
3. 缺少内容（文本/附件均无）-> `reason=missing_submission_content`
4. 未确认提交 -> `reason=confirm_submit_required`

“上传、预览时给出窗口或页面”的实现：
1. 预览/上传/提交结束后统一 `ACTION_VIEW` 打开作业详情 URL。
2. 上传若未提供文件，主动拉起 `ACTION_OPEN_DOCUMENT` 文件选择器，并同时打开作业页面。

### 4.3 `PythonSkillBridgeExecutor.kt`：桥接层

改动目的：确保 Python<->Kotlin 返回契约覆盖 homework skill，不再只偏向 calendar。

关键改动：
1. `mapCode(...)` 增加 homework 原因映射：
- `confirm_submit_required` -> `APPROVAL_REQUIRED`
- `missing_auth/missing_homework_id/missing_course_ids/missing_submission_content` -> `INVALID_PARAM`
2. `buildData(...)` 新增 homework 分支：
- `crawl_course_homeworks`
- `crawl_unsubmitted_homeworks`
- `preview_homework_attachments`
- `upload_homework_attachment`
- `submit_homework`
3. 新增 `putReportData(...)`：将 `ActionExecutionReport.data` 透传为 JSON，避免信息丢失。

作用：
1. Python 侧拿到稳定 `code`（OK/APPROVAL_REQUIRED/INVALID_PARAM/...）。
2. Python 侧拿到完整结构化 `data`（count/homeworks/attachments/homework_id/opened_url 等）。

### 4.4 `OpenCrayRuntime.kt`：网关与回调层

改动 1：扩展设备能力上报（`supportedGatewayCapabilities`）
- 新增 homework 五个能力，保证网关能向设备下发这些 skill。

改动 2：扩展结果码映射（`mapGatewayResultCode`）
- 将 homework 的 `reason` 映射到网关侧统一 code，避免回调误判。

作用：
1. 服务器规划出的 homework skill 能被 Android 设备声明支持并被分发。
2. 执行结果可以按统一协议回传 Agent-Core。

### 4.5 `PythonSkillBridgeExecutorTest.kt`：单元测试覆盖

新增 homework 方向测试：
1. `submit_homework` 未确认 -> `APPROVAL_REQUIRED`
2. `crawl_course_homeworks` 结构化数据透传
3. `upload_homework_attachment` 缺失认证 -> `INVALID_PARAM`

作用：
1. 保证 JSON -> `SystemAction` -> `SkillResult` 的映射在 homework 场景稳定。
2. 防止 bridge 回归只支持 calendar 的问题。

## 5. 真机可用性要求

要在真机可用，必须满足：
1. App 已有 `INTERNET` 权限（已在 Manifest 中声明）。
2. 设备网络可访问 `learn.tsinghua.edu.cn`。
3. 调用参数必须携带有效 `session_cookie`（可选 `csrf_token`）。
4. 上传本地文件时，使用可读路径或 `content://` URI。

## 6. 建议测试方法（真机）

### 6.1 Kotlin 单元测试（桥接映射）

在项目根目录执行：

```bash
./gradlew :app:testDebugUnitTest --tests ai.opencray.app.bridge.PythonSkillBridgeExecutorTest
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests ai.opencray.app.bridge.PythonSkillBridgeExecutorTest
```

### 6.2 真机手工链路测试（不依赖在线 agent）

使用预置 invocation（或本地网关）按顺序验证：
1. `crawl_course_homeworks`：检查返回 `count/homeworks`。
2. `preview_homework_attachments`：检查是否打开作业页面且返回附件列表。
3. `upload_homework_attachment`：检查上传成功并返回 `attachment_token`。
4. `submit_homework`：传 `confirm_submit=true`，检查提交成功并打开页面。

若 `submit_homework` 不带 `confirm_submit=true`，应返回 `APPROVAL_REQUIRED`，这是预期的高风险保护行为。

## 7. 已知限制

1. `attachment_tokens` 的后端字段依赖 Learn 实际接口行为，当前已透传 `fjids`，如后端字段变更需同步。
2. 若 session 过期，Homework 相关 skill 会统一失败为认证类错误，需要上层先刷新登录态。
