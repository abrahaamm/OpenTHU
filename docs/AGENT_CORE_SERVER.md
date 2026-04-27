# OpenTHU Server-App 架构使用指南

版本：v0.2-draft  
日期：2026-04-27  
状态：Draft

## 1. 这份文档解决什么问题

本文面向“本机运行 Agent-Core 服务 + Android App 执行系统动作”的联调场景，重点回答：

1. 服务端怎么启动
2. App 怎么配置连接
3. 一条目标请求如何走完整链路
4. 怎么定位常见故障

## 2. 架构总览

```mermaid
flowchart LR
  U["用户输入目标"] --> S["PC: Agent-Core Server (Python/LangGraph)"]
  S --> P["规划与审查: normalize/plan/safety/audit/memory"]
  P --> Q["已审批技能队列"]
  Q --> A["Android App 轮询 /tasks/next"]
  A --> E["设备执行: ActionExecutor"]
  E --> R["回传结果: POST /tasks/{id}/result"]
  R --> S
```

职责分工：

- Server（Python）：
  - 目标标准化、任务规划、安全审查、审计、记忆
  - 维护任务状态与待分发 Skill 队列
- App（Android）：
  - 拉取待执行 Skill
  - 调用系统能力执行（闹钟/日历/通知等）
  - 回传结构化执行结果

## 3. 前置条件

1. 本机已安装 Python 3.10+，并能创建虚拟环境
2. Android Studio 可启动 Emulator（或连接真机）
3. 项目目录：`/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray`

可选：

- `OPENAI_API_KEY`（使用 LLM 分支时）
- `--llm-base-url`、`--llm-model`（接兼容 OpenAI 的第三方模型）

## 4. 启动 Agent-Core 服务（PC）

### 4.1 安装依赖

```bash
cd /Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray
python3 -m venv .venv
.venv/bin/pip install -r agent/langgraph/requirements.txt
```

### 4.2 启动服务

默认端口 `18789`：

```bash
.venv/bin/python -m agent.langgraph.agent_core_server \
  --host 0.0.0.0 \
  --port 18789 \
  --store-file /tmp/openthu_agent_core_store.json \
  --memory-file /tmp/openthu_agent_memory.json
```

如果你本地习惯用 `28789`，保持前后端一致即可：

```bash
.venv/bin/python -m agent.langgraph.agent_core_server \
  --host 0.0.0.0 \
  --port 28789 \
  --store-file /tmp/openthu_agent_core_store.json \
  --memory-file /tmp/openthu_agent_memory.json
```

LLM 兼容模式示例：

```bash
OPENAI_API_KEY="<YOUR_KEY>" \
.venv/bin/python -m agent.langgraph.agent_core_server \
  --host 0.0.0.0 \
  --port 28789 \
  --llm-base-url "https://api.moonshot.cn/v1" \
  --llm-model "moonshot-v1-8k" \
  --store-file /tmp/openthu_agent_core_store.json \
  --memory-file /tmp/openthu_agent_memory.json
```

### 4.3 健康检查

```bash
curl -s http://127.0.0.1:28789/healthz
```

期望：

```json
{"status":"ok","ts":"..."}
```

## 5. App 端连接配置（Android Studio/Emulator）

1. 运行 App 到模拟器
2. 在 App 界面配置连接参数：
   - Host：`10.0.2.2`（模拟器访问宿主机固定地址）
   - Port：与你服务端一致（如 `28789`）
   - TLS：关闭（本地联调用 HTTP）
3. 点击 `Connect`
4. 观察状态区：
   - 成功时应出现类似 `Connected to ...`
   - 事件区应看到 `Gateway registered device_id=...`

说明：`Connect` 会触发 `POST /api/v1/devices/register`，注册当前设备能力。

## 6. 端到端测试流程（推荐手工联调）

以“帮我设置一个明天早上7点的闹钟”为例：

1. 在 App 输入目标，点击 `Plan Goal`
2. App 调用 `POST /api/v1/agent/tasks/plan`，Server 返回 `task_id` 和 `approved_skills`
3. 点击 `Run Agent`
4. App 循环调用 `GET /api/v1/agent/tasks/next?device_id=...` 拉取待执行 Skill
5. App 本地执行后，调用 `POST /api/v1/agent/tasks/{task_id}/result` 回传
6. 全部 request_id 回传后，任务状态转为 `completed` 或 `failed`

## 7. API 调试清单（脱离 App 也可复现）

Base Path：`/api/v1`

### 7.1 注册设备

```bash
curl -s -X POST "http://127.0.0.1:28789/api/v1/devices/register" \
  -H "Content-Type: application/json" \
  -d '{
    "device_id":"emulator-5554",
    "user_id":"debug_user",
    "platform":"android",
    "app_version":"0.1.0",
    "capabilities":["get_current_time","set_alarm","create_calendar_event","detect_calendar_conflicts","delete_calendar_event","open_url"]
  }' | jq .
```

### 7.2 创建规划任务

```bash
curl -s -X POST "http://127.0.0.1:28789/api/v1/agent/tasks/plan" \
  -H "Content-Type: application/json" \
  -d '{
    "device_id":"emulator-5554",
    "user_id":"debug_user",
    "goal":"帮我设置一个明天早上7点的闹钟",
    "approve_sensitive":true,
    "session":{}
  }' | jq .
```

### 7.3 拉取待执行 Skill

```bash
curl -s "http://127.0.0.1:28789/api/v1/agent/tasks/next?device_id=emulator-5554" | jq .
```

### 7.4 查询任务总状态

```bash
TASK_ID="<task_id>"
curl -s "http://127.0.0.1:28789/api/v1/agent/tasks/${TASK_ID}" | jq .
```

重点字段：

- `data.status`
- `data.approved_skills`
- `data.device_results`
- `data.in_flight_request_ids`
- `data.completed_request_ids`

## 8. 常见问题排障

### 8.1 端口占用：`address already in use`

```bash
lsof -nP -iTCP:28789 -sTCP:LISTEN
kill <PID>
```

### 8.2 `device_not_registered`

原因：未先调用 `/devices/register`。  
处理：先点击 App `Connect`，或手工调用注册接口。

### 8.3 `task_not_found`

原因：`task_id` 过期、拼写错误，或不是当前服务实例里的任务。  
处理：用同一服务进程返回的 `task_id` 查询；避免重启后换了 store 文件。

### 8.4 `/tasks/next` 返回 `NO_TASK`

常见原因：

1. 规划结果没有 `approved_skills`
2. 任务已执行完（全部 request_id 已进入 `completed_request_ids`）
3. 使用了错误的 `device_id`

### 8.5 App 显示连接成功，但动作未落地

先查 `GET /api/v1/agent/tasks/{task_id}` 的 `device_results`：

- 如果 `code=SKILL_EXECUTION_FAILED`，查看 `message`
- 常见为系统权限、参数格式或端上能力缺失问题，优先在 App 事件日志和系统日志定位

## 9. 本地与生产建议

本地联调：

- HTTP + 轮询即可跑通主链路
- FCM 可不接入

生产建议：

1. 使用 HTTPS（反向代理或网关层 TLS）
2. 引入鉴权（设备 token / 用户 token）
3. FCM 只做唤醒，任务详情仍通过 `/tasks/next` 拉取
4. 持久化 `store-file` 与 `memory-file`，并做备份策略

## 10. 对应实现位置

- Server 入口：
  - `/agent/langgraph/agent_core_server.py`
- App HTTP 客户端：
  - `/app/src/main/java/ai/opencray/app/gateway/AgentCoreHttpClient.kt`
- App Runtime 调度：
  - `/app/src/main/java/ai/opencray/app/runtime/OpenCrayRuntime.kt`
