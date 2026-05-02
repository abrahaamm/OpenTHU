# Homework Skill 技术文档

更新时间：2026-05-02  
状态：Active

## 1. 目标

Homework Skill 为 Agent 提供作业场景的可执行工具链，覆盖：

1. 抓取所选课程全部作业  
2. 抓取所选课程未提交作业  
3. 上传作业附件  
4. 提交作业  
5. 预览作业提交窗口附件

当前架构与 Calendar Skill 一致：  
Python 负责规划与参数校验，具体执行通过桥接交给 Kotlin 运行时。

## 2. 技能清单

在 `agent/langgraph/skill_core.py` 注册了以下 SkillSpec（均带 `args_json_schema`）：

1. `crawl_course_homeworks`（low）
2. `crawl_unsubmitted_homeworks`（low）
3. `upload_homework_attachment`（medium, requires_approval）
4. `submit_homework`（high, requires_approval）
5. `preview_homework_attachments`（low）

## 3. Python 侧执行逻辑

实现文件：`agent/langgraph/homework_handlers.py`

核心结构：

1. `HomeworkSkillBridge`：跨语言桥接协议
2. `JsonFileHomeworkBridge`：文件桥接实现（Python 写请求，等待 Kotlin 回包）
3. `*_Handler`：每个工具的语义校验 + 桥接分发

语义校验要点：

1. `upload_homework_attachment` 要求 `homework_id` 且 `file_path`/`file_uri` 至少一个
2. `submit_homework` 要求 `confirm_submit=true`，否则返回 `APPROVAL_REQUIRED`
3. `submit_homework` 要求提交内容至少有一种：`submission_text` / `attachment_tokens` / `local_file_paths`
4. `preview_homework_attachments` 要求 `homework_id`

## 4. Planner 适配

`agent/langgraph/openthu_agent.py` 已补充 fallback 规划：

1. 新增实体识别：`homework_unsubmitted` / `homework_submit` / `homework_attachment`
2. 在作业相关意图下自动规划 homework 工具链
3. 风险规则中新增：
   - `submit_homework` => high
   - `upload_homework_attachment` => medium

## 5. 桥接配置

环境变量：

1. `OPENTHU_HOMEWORK_BRIDGE_MODE`：`json_file` 启用文件桥接
2. `OPENTHU_HOMEWORK_BRIDGE_REQUEST_FILE`：请求文件路径
3. `OPENTHU_HOMEWORK_BRIDGE_RESPONSE_FILE`：响应文件路径
4. `OPENTHU_HOMEWORK_BRIDGE_TIMEOUT_SEC`：超时（默认 12 秒）

桥接请求包（Python -> Kotlin）与 Calendar 保持一致：

```json
{
  "type": "skill_invocation",
  "sent_at": "2026-05-02T08:00:00Z",
  "invocation": {
    "skill_name": "submit_homework",
    "request_id": "req_xxx",
    "task_id": "task_xxx",
    "args": {
      "homework_id": "hw_123",
      "submission_text": "answer",
      "confirm_submit": true
    }
  }
}
```

## 6. 测试

新增测试脚本：`agent/langgraph/run_homework_skill_tests.py`

运行：

```bash
python agent/langgraph/run_homework_skill_tests.py --mode mock
```

覆盖点：

1. skill 注册绑定
2. schema 严格校验（未知字段拒绝）
3. 抓取全部/未提交作业
4. 上传参数校验
5. 提交确认门禁
6. 上传后提交
7. 附件预览
