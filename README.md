# OpenTHU

OpenTHU is a mobile agent project for Tsinghua student scenarios. It combines:

- an Android prototype app in `app/`
- a LangGraph-based agent core in `agent/langgraph/`
- a skill-first architecture defined in `docs/`

The current direction is "PC Agent-Core Server + Android Device Executor":

- server-side LangGraph handles normalize / plan / safety / audit / memory
- Android app pulls approved tasks and executes system actions locally
- execution results are reported back to the server for task state updates
- FCM can be used as wake-up signal, while task payload stays on HTTPS pull

## Current Architecture

```mermaid
flowchart LR
    A["User Goal"] --> B["PC Agent-Core Server"]
    B --> C["normalize -> plan -> safety"]
    C --> D["Approved Skill Queue"]
    D --> E["Android App Pulls /tasks/next"]
    E --> F["Local Action Execution"]
    F --> G["POST task result to server"]
    G --> H["audit + memory + task status"]
```

## Project Layout

- `/app`
  - existing Android prototype
  - UI, runtime state, safety layer, and local system integration experiments
- `/agent/langgraph`
  - current agent core framework
  - skill registry, workflow orchestration, safety review, audit, memory
- `/docs`
  - `RD.md`: product scope and system boundary
  - `API.md`: skill contracts and workflow state model
  - `AGENT_CORE_SERVER.md`: server-dispatch architecture and HTTP API
  - `API_http.md`: upstream Tsinghua interface references for future skill implementers
- `/scripts`
  - prototype Android testing helpers
  - `run_calendar_preset_gateway_server.py`: preset-plan gateway stub for calendar emulator e2e tests

## What Changed

The architecture has been shifted to a skill-first model:

1. Agent-core can now run as a standalone server on a personal computer.
2. Reminders, calendar, alarms, assignments, courses, notices, activities, search, and related capabilities are all modeled as skills.
3. The workflow keeps the original core loop:
   - requirement normalization
   - planning
   - safety check
   - approval
   - execution
   - replan
   - audit
   - memory update
4. Concrete skill implementations are intentionally decoupled from the planning server.

## Current Status

The LangGraph core now provides:

- a docs-aligned `AgentState`
- a `SkillRegistry` boundary for injecting skills
- LLM-first skill planning with deterministic fallback
- hybrid safety review
  - rule-based risk assessment
  - optional LLM risk assessment
  - final risk uses the stricter result
- execution through registered skill handlers (local mode) and device dispatch mode (server mode)
- failure replanning
- audit log generation
- lightweight memory persistence

What is not implemented here:

- most data/auth skill bodies
- upstream Tsinghua HTTP adapters
- complete device bridge coverage for all action skills (in progress)

Those are meant to be added later by separate skill implementers behind the same registry interface.

## LangGraph Core

The current agent entrypoint is:

- [openthu_agent.py](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/openthu_agent.py)
- [agent_core_server.py](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/agent_core_server.py)

The skill abstraction lives in:

- [skill_core.py](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/skill_core.py)
- [skill_manager.py](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/skill_manager.py)

Skill developer docs:

- [SKILL_MANAGER_SCHEMA_GUIDE.md](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/SKILL_MANAGER_SCHEMA_GUIDE.md)
- [skill_json_schema.template.json](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/skills/skill_json_schema.template.json)
- [skill_test_template.py](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/skills/skill_test_template.py)

## Build

Android build:

```bash
./gradlew -Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort= :app:assembleDebug
```

LangGraph local run:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r agent/langgraph/requirements.txt

python3 agent/langgraph/openthu_agent.py \
  --input "帮我整理本周作业并加到提醒和日历"
```

Agent-Core server run (PC host):

```bash
python3 -m agent.langgraph.agent_core_server \
  --host 0.0.0.0 \
  --port 18789 \
  --store-file agent/langgraph/agent_core_store.json \
  --memory-file agent/langgraph/memory_store.json
```
