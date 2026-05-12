# HOMEWORK_SKILL

更新时间：2026-05-12  
状态：Active

## 1. 目标

Homework Skill 的职责：
1. 拉取所有课程作业（`crawl_course_homeworks`）
2. 拉取未提交作业（`crawl_unsubmitted_homeworks`）
3. 预览作业附件（`preview_homework_attachments`）
4. 上传作业附件（`upload_homework_attachment`）
5. 提交作业（`submit_homework`）
6. 获取/注入 Learn Cookie（`get_homework_cookie`）

## 2. 新增 Cookie Tool 协议

新增工具：`get_homework_cookie`  
参数严格为 3 个：
1. `student_id`
2. `password`
3. `cookies`

执行规则：
1. 当 `cookies` 非空时：Kotlin 直接使用该 cookie，跳过账号密码登录流程。
2. 当 `cookies` 为空时：Kotlin 使用 `student_id/password` 走登录流程获取 cookie。
3. 获取成功后：Kotlin 在本地缓存 cookie，会被后续 homework 工具自动复用。

## 3. 端到端调用链

1. Server 规划 skill 链路（plan-only 或实时下发）。
2. Python `SkillManager` 按 schema 校验参数。
3. Python handler 将 invocation 透传给 Kotlin bridge。
4. Kotlin `PythonSkillBridgeExecutor` 映射为 `SystemAction`。
5. Kotlin `ActionExecutor` 执行真实 HTTP/页面操作。
6. Kotlin 返回结构化结果（`code + data`）到 server。

## 4. Kotlin Section（关键实现）

### 4.1 `ActionExecutor.kt`

新增动作路由：
1. `get_homework_cookie`

新增逻辑：
1. `cachedHomeworkAuth`：缓存最近一次可用认证（cookie/csrf/baseUrl）。
2. `executeGetHomeworkCookie(...)`：
   - 若 `cookies` 非空，直接标准化并缓存。
   - 若 `cookies` 为空，使用 `student_id/password` 登录并抓取 cookie 后缓存。
3. `resolveHomeworkAuth(...)`：
   - 优先级：`session_cookie` > `cookies` > `cachedHomeworkAuth.cookie`
   - `csrf_token` 缺省时，自动从 cookie 中提取 `XSRF-TOKEN`。
4. 其它 homework 动作（crawl/preview/upload/submit）不再要求每次都显式传 `session_cookie`，可复用缓存。

新增失败原因：
1. `missing_credentials`
2. `invalid_cookies`
3. `auth_failed`

### 4.2 `PythonSkillBridgeExecutor.kt`

新增映射：
1. `get_homework_cookie` 的 `data.status` 输出为 `cookie_ready/failed`。
2. 新增错误码映射到 `INVALID_PARAM`：
   - `missing_credentials`
   - `invalid_cookies`
   - `auth_failed`

### 4.3 `OpenCrayRuntime.kt`

新增 capability：
1. `get_homework_cookie`

新增网关错误码映射（`mapGatewayResultCode`）：
1. `missing_credentials/invalid_cookies/auth_failed` -> `INVALID_PARAM`

## 5. Python 侧改动

### 5.1 `skill_core.py`

新增 `SkillSpec`：
1. `get_homework_cookie`
2. category=`auth`
3. risk=`high`
4. args schema 仅包含：
   - `student_id: string`
   - `password: string`
   - `cookies: string`

### 5.2 `homework_handlers.py`

新增 `GetHomeworkCookieHandler`：
1. 仅做参数归一化（trim）
2. 透传给 Kotlin bridge 执行

并在 `register_homework_handlers(...)` 注册该 handler。

## 6. 推荐执行顺序

针对需要鉴权的作业链路，推荐：
1. `get_homework_cookie`
2. `crawl_unsubmitted_homeworks` 或 `crawl_course_homeworks`
3. `preview_homework_attachments`（可选）
4. `upload_homework_attachment`（可选）
5. `submit_homework`（高风险，需要显式确认）

## 7. 测试建议

### 7.1 Kotlin 单测

执行：
```powershell
gradlew.bat :app:testDebugUnitTest --tests ai.opencray.app.bridge.PythonSkillBridgeExecutorTest
```

已覆盖：
1. `get_homework_cookie` -> `cookie_ready`
2. homework 各核心工具的 code/data 映射

### 7.2 设备联调

1. 先下发 `get_homework_cookie`：
   - 场景 A：`cookies` 直接传值（推荐稳定联调）
   - 场景 B：`cookies=""`，传 `student_id/password`
2. 再下发 crawl/upload/submit 等工具。
3. 通过 server `device_results` 验证返回：
   - `code`
   - `data.status`
   - `data.reason`（失败场景）

## 8. 注意事项

1. 账号密码登录流程受上游认证策略影响（可能新增隐藏字段、验证码、风控参数）。
2. 生产环境建议优先使用受控渠道下发 `cookies`，避免在模型上下文长期暴露账号密码。
3. `submit_homework` 仍要求 `confirm_submit=true`。
