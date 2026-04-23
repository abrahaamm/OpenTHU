# OpenTHU LangGraph Agent (Single-Stack)

This module implements the OpenTHU agent flow with a strict LangGraph pipeline:

1. requirement normalization (LLM)
2. planning
3. safety review
4. execution
5. failed-action replanning
6. audit record
7. memory update

## Run

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r agent/langgraph/requirements.txt

python3 agent/langgraph/openthu_agent.py \
  --input "帮我关注清华活动并把课程DDL加到日历和提醒" \
  --user-id "thu_demo"
```

Allow sensitive actions explicitly:

```bash
python3 agent/langgraph/openthu_agent.py \
  --input "读取校园通知并自动处理微信验证码登录" \
  --approve-sensitive
```

## Notes

- If `OPENAI_API_KEY` is set and `openai` is installed, requirement normalization uses a real LLM call.
- Otherwise, it falls back to deterministic extraction so the full graph still runs offline.
- Memory is persisted into `agent/langgraph/memory_store.json` by default.
