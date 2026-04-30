# Course Info Skill 实现说明

日期：2026-04-30  
状态：Active

---

## 1. 概述

`course_info` 是一组**服务端执行**的数据类 Skill，负责从清华大学网络学堂（learn.tsinghua.edu.cn）及教务系统（zhjw.cic.tsinghua.edu.cn）拉取学生本学期全部课程信息，包括：

- 学生个人信息
- 学期列表与当前学期
- 课程列表（含教师、时间地点等）
- 课程通知 / 公告
- 课程文件 / 课件
- 作业与 DDL
- 教务日历事件
- 校园活动（尽力而为）

这些 Skill 与 Calendar Skill 的最大区别是：**全部在 Python Agent-Core Server 侧执行 HTTP 请求，不经过 Kotlin 桥接。**

---

## 2. 架构位置

```
用户意图 (app goalInput)
  └─► Agent-Core Server (openthu_agent.py)
        ├─ normalize_requirement
        ├─ plan_skills            ← LLM / fallback 生成包含 course_info skill 的计划
        ├─ safety_check
        └─ execute_skills         ← SkillManager → course_info_handlers.py
              └─► HTTP → learn.tsinghua.edu.cn / zhjw.cic.tsinghua.edu.cn
                    └─ SkillResult → AgentState["skill_results"]
                          └─► 回传给 Android app
```

**注意**：这些 Skill 均在 Server 侧同步执行，Android 端只需展示结果，无需本地执行任何 Calendar/Sensor 操作。

---

## 3. 注册的 Skill 列表

| Skill 名称 | 分类 | 风险等级 | 需审批 | 需 Session | 描述 |
|---|---|---|---|---|---|
| `get_user_info` | data | low | No | Yes | 获取学生个人信息（姓名、学号、院系） |
| `get_semesters` | data | low | No | Yes | 获取全部学期列表 + 当前学期 |
| `get_courses` | data | low | No | Yes | 获取指定学期的课程列表 |
| `get_notices` | data | low | No | Yes | 获取指定课程 ID 列表的通知公告 |
| `get_files` | data | low | No | Yes | 获取指定课程 ID 列表的课程文件 |
| `get_assignments` | data | low | No | Yes | 获取指定课程 ID 列表的作业与 DDL |
| `get_academic_calendar` | data | low | No | Yes | 获取指定日期范围内的教务日历事件 |
| `get_campus_activities` | data | low | No | Yes | 获取校园活动信息（尽力而为） |

以上 Skill 在 `skill_core.py` 的 `build_default_registry()` 中均已声明 `SkillSpec`，并由 `course_info_handlers.py` 中的 `register_course_info_handlers()` 完成 handler 注册。

---

## 4. 实现文件

| 文件 | 说明 |
|---|---|
| `agent/langgraph/course_info_handlers.py` | 所有 Skill handler 实现（HTTP 请求 + 数据映射） |
| `agent/langgraph/skill_core.py` | `SkillSpec` 声明 + `register_course_info_handlers()` 调用 |
| `agent/langgraph/openthu_agent.py` | fallback normalizer 关键词识别 + fallback planner 规则 |

---

## 5. Skill Handler 实现细节

### 5.1 Session 模型

所有 Course Info Handler 均从 `AgentState["session"]` 读取认证信息：

```python
session = {
    "cookies": {
        "JSESSIONID": "<value>"   # 由 login skill 写入
    },
    "csrf_token": "<value>"       # 可选，写操作时需要
}
```

当 `JSESSIONID` 缺失时，handler 返回 `code="SESSION_REQUIRED"`，提示先执行 `login` skill。

### 5.2 依赖

所有 Handler 使用 `requests` 库发起 HTTP 请求。若 `requests` 未安装，返回 `code="DEPENDENCY_MISSING"`。

```bash
pip install requests
```

`requests` 已包含在 `agent/langgraph/requirements.txt` 中（如未包含请添加）。

### 5.3 超时控制

默认超时：15 秒，可通过环境变量覆盖：

```bash
export OPENTHU_HTTP_TIMEOUT_SEC=20
```

---

## 6. Skill 参数说明

### `get_courses`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `semester_id` | string | No（有回退） | 学期 ID，如 `2024-2025-2`；缺省时使用 `AgentState["semester_id"]` |
| `lang` | string | No | 语言，`zh_CN`（默认）或 `en_US` |

### `get_notices` / `get_files` / `get_assignments`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `course_ids` | list\[string\] | Yes | 课程 ID 列表，由 `get_courses` 结果提供 |

### `get_academic_calendar`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `start_date` | string | Yes | 格式 `YYYYMMDD` |
| `end_date` | string | Yes | 格式 `YYYYMMDD`，范围不超过 29 天 |
| `graduate` | boolean | No | `true` 使用研究生接口，默认本科生 |

---

## 7. 返回数据格式

所有 Skill 返回标准 `SkillResult`，成功时 `code="OK"`，`data` 结构如下：

### `get_user_info`

```json
{
  "user_info": {
    "id": "2024012345",
    "name": "张三",
    "department": "计算机系"
  }
}
```

### `get_semesters`

```json
{
  "semesters": ["2024-2025-2", "2024-2025-1", "..."],
  "current_semester": "2024-2025-2"
}
```

### `get_courses`

```json
{
  "semester_id": "2024-2025-2",
  "courses": [
    {
      "id": "abc123",
      "name": "机器学习",
      "chinese_name": "机器学习",
      "english_name": "Machine Learning",
      "teacher_name": "李四",
      "course_number": "80250143",
      "course_index": 1,
      "semester_id": "2024-2025-2"
    }
  ],
  "course_count": 6
}
```

### `get_notices`

```json
{
  "course_ids": ["abc123"],
  "notices": [
    {
      "id": "ntc001",
      "title": "第一次作业发布",
      "publisher": "李四",
      "publish_time": "2026-03-01 10:00:00",
      "expire_time": "2026-06-30 00:00:00",
      "marked_important": false,
      "has_read": false,
      "course_id": "abc123",
      "url": ""
    }
  ],
  "notice_count": 1
}
```

### `get_files`

```json
{
  "course_ids": ["abc123"],
  "files": [
    {
      "id": "wj001",
      "title": "第一章课件.pdf",
      "category": "课件",
      "size": 2048000,
      "file_type": "pdf",
      "upload_time": "2026-02-28 09:00:00",
      "download_url": "https://learn.tsinghua.edu.cn/b/wlxt/kj/wlkc_kjxxb/student/downloadFile?sfgk=0&wjid=wj001",
      "course_id": "abc123"
    }
  ],
  "file_count": 3
}
```

### `get_assignments`

```json
{
  "course_ids": ["abc123"],
  "assignments": [
    {
      "id": "zy001",
      "title": "作业一：线性回归",
      "deadline": "2026-04-10 23:59:59",
      "submitted": false,
      "graded": false,
      "completion_state": "pending",
      "course_id": "abc123"
    }
  ],
  "assignment_count": 2,
  "pending_count": 1
}
```

---

## 8. 典型执行链（plan 示例）

**用户目标**：`"获取我本学期所有课程信息"`

LLM/fallback planner 生成的 Skill 链：

```
1. login              → 建立 JSESSIONID 会话
2. get_semesters      → 获取当前学期 ID
3. get_user_info      → 获取学生基础信息
4. get_courses        → 获取课程列表（含 course_ids）
5. get_notices        → 获取所有课程通知
6. get_files          → 获取所有课程文件
7. get_assignments    → 获取作业与 DDL
8. show_summary       → 汇总并展示给用户
```

**触发关键词**（normalizer fallback 识别）：
- `课程信息` / `所有课程` / `全部课程` / `本学期课程` / `课程详情` / `course info` / `course_info`
- 对应 entity：`course_info`

---

## 9. 错误码参考

| code | 含义 | 常见原因 |
|---|---|---|
| `OK` | 成功 | — |
| `SESSION_REQUIRED` | 缺少 JSESSIONID | 需先执行 `login` skill |
| `INVALID_PARAM` | 参数校验失败 | `course_ids` 为空，或日期格式错误 |
| `DEPENDENCY_MISSING` | `requests` 未安装 | `pip install requests` |
| `SKILL_EXECUTION_FAILED` | HTTP 请求异常 | 网络错误、服务端异常、会话过期 |

---

## 10. 本地测试

```bash
cd d:/Shared/2026\ Spring/Android/project/OpenTHU

# 启动 Agent-Core Server（需设置 OPENAI_API_KEY 和真实会话 cookie）
python -m agent.langgraph.agent_core_server \
  --host 0.0.0.0 --port 28789 \
  --llm-base-url "https://api.moonshot.cn/v1" \
  --llm-model "moonshot-v1-8k" \
  --store-file agent/langgraph/agent_core_store.json \
  --memory-file agent/langgraph/agent_memory.json

# 在 app 中：
# 1. 连接到 Agent-Core（输入 host/port）
# 2. 在 goal 输入框中输入："获取我本学期所有课程信息"
# 3. 点击 Plan Goal
# 4. 观察 Server 日志中的执行链
```

### 无需 Android 的单元测试

```python
from agent.langgraph.course_info_handlers import (
    GetCoursesHandler, GetAssignmentsHandler
)
from agent.langgraph.skill_core import SkillInvocation

session = {"cookies": {"JSESSIONID": "<your_jsessionid>"}}

invocation = SkillInvocation(
    skill_name="get_courses",
    request_id="req_test_001",
    task_id="task_test",
    args={"semester_id": "2024-2025-2"},
    risk_level="low",
    requires_approval=False,
    description="test",
)
result = GetCoursesHandler().invoke(invocation, session, {})
print(result.code, result.data.get("course_count"))
```

---

## 11. 与 Calendar Skill 的对比

| 维度 | Calendar Skill | Course Info Skill |
|---|---|---|
| 执行位置 | Android Kotlin (`ActionExecutor`) | Python Server (`course_info_handlers.py`) |
| 通信机制 | Kotlin Bridge / HTTP 轮询 | 直接 HTTP 请求（在 Server 进程内） |
| 依赖 | Android Calendar Provider | `requests` + 清华学号会话 |
| 数据来源 | Android 设备本地日历 | learn.tsinghua.edu.cn / zhjw 教务系统 |
| 副作用 | 写入/删除手机日历 | 只读数据拉取（无副作用） |
| 风险等级 | medium ~ high | low（只读） |

---

*参考：[API_http.md](API_http.md) · 实现文件：[course_info_handlers.py](../agent/langgraph/course_info_handlers.py)*
