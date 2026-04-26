# OpenTHU LangGraph Agent

This module is the current agent core framework for OpenTHU.

It is now designed around a skill-first architecture:

- the workflow does not depend on a standalone backend planner
- both data access and local actions are represented as skills
- the workflow only depends on skill metadata and skill handlers
- concrete skill implementations can be injected later without rewriting the core graph

## Workflow

```mermaid
flowchart TD
    A["normalize_requirement"] --> B["plan_skills"]
    B --> C["safety_check"]
    C --> D["execute_skills"]
    D --> E{"failed skills?"}
    E -- "yes" --> F["replan_failed"]
    E -- "no" --> G["audit_record"]
    F --> G
    G --> H["memory_update"]
    H --> I["finalize"]
```

## Key Files

- [openthu_agent.py](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/openthu_agent.py)
  - LangGraph workflow
  - state transitions
  - planning / safety / execution / replan / audit / memory
- [skill_core.py](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/skill_core.py)
  - `SkillSpec`
  - `SkillInvocation`
  - `SkillResult`
  - `SkillRegistry`
  - default skill catalog

## Core Design

1. `plan_skills`
   - LLM-first skill planning
   - deterministic fallback if no model is available
   - outputs `skill_plan`

2. `safety_check`
   - rule-based risk review
   - optional LLM risk review
   - stricter result wins
   - medium/high risk skills are blocked unless approval is granted for this run

3. `execute_skills`
   - dispatches each approved `SkillInvocation` to the registered handler
   - the workflow does not know concrete skill internals

4. `replan_failed`
   - creates follow-up `show_summary` skill invocations for failed skills

5. `audit_record`
   - records `plan / safety_check / approve / execute / replan`

6. `memory_update`
   - persists a small execution memory snapshot to JSON

## Running Locally

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r agent/langgraph/requirements.txt

python3 agent/langgraph/openthu_agent.py \
  --input "帮我整理本周作业并加到提醒和日历" \
  --user-id "thu_demo"
```

Grant approval-required skills for one run:

```bash
python3 agent/langgraph/openthu_agent.py \
  --input "帮我把课程DDL加入提醒和日历" \
  --approve-sensitive
```

Pass a local session placeholder when debugging:

```bash
python3 agent/langgraph/openthu_agent.py \
  --input "帮我读取本学期课程通知" \
  --session-id "sess_demo" \
  --semester-id "2025-2026-2"
```

## Important Boundary

This module is only the orchestration core.

It still does not implement most data/auth skills:

- login adapters
- course / assignment / notice fetchers
- notification concrete handlers

Calendar actions are now wired with concrete handlers:

- `create_calendar_event`
- `detect_calendar_conflicts`
- `delete_calendar_event`

These handlers validate arguments and then dispatch invocation payloads through a Kotlin bridge.
Android-side execution is handled by Kotlin runtime (`ActionExecutor`) under app permissions.

Environment variables:

- `OPENTHU_CALENDAR_BRIDGE_MODE` (`json_file` to enable file bridge)
- `OPENTHU_KOTLIN_BRIDGE_REQUEST_FILE` (required for `json_file` mode)
- `OPENTHU_KOTLIN_BRIDGE_RESPONSE_FILE` (required for `json_file` mode)
- `OPENTHU_KOTLIN_BRIDGE_TIMEOUT_SEC` (optional, default 12s)

## Calendar Skill Tests

Run logic validation with a mock Kotlin bridge:

```bash
python agent/langgraph/run_calendar_skill_tests.py --mode mock
```
