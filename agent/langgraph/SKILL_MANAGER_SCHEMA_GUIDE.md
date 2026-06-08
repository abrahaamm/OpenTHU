# SkillManager and Skill JSON Schema Guide

This guide is for skill implementers working with the OpenTHU LangGraph core.

It explains:

- where `SkillManager` sits in the workflow
- how `args_json_schema` is used for planning and runtime validation
- what a skill developer must implement to be production-ready

## Runtime Position

Current execution path:

1. `plan_skills` generates candidate `{skill_name, args, description}`.
2. `_sanitize_skill_plan` validates and normalizes `args` by schema.
3. `execute_skills` dispatches approved invocations to `SkillManager`.
4. `SkillManager.execute()` validates args again before calling the handler.
5. If schema validation fails, the invocation returns `INVALID_PARAM` directly.

This gives both:

- early filtering in planning
- hard safety boundary before real handler execution

## Schema Sources

Each `SkillSpec` supports two fields:

- `args_json_schema`: preferred, machine-readable contract
- `args_schema`: legacy human-readable hints

Resolution order in `SkillManager`:

1. Use `args_json_schema` if provided and non-empty.
2. Else derive a best-effort schema from `args_schema`.

Notes:

- Derived schema from legacy hints currently uses `additionalProperties: true` for compatibility.
- Explicit `args_json_schema` can set `additionalProperties: false` for strict mode.

## Recommended SkillSpec Pattern

```python
SkillSpec(
    skill_name="example_skill",
    description="Example skill",
    category="action",
    risk_level="low",
    requires_approval=False,
    args_json_schema={
        "type": "object",
        "properties": {
            "text": {"type": "string"},
            "enabled": {"type": "boolean"},
            "mode": {"type": "string", "enum": ["fast", "safe"]},
        },
        "required": ["text"],
        "additionalProperties": False,
    },
)
```

## Validation Behavior in SkillManager

Validation includes:

- required-field checks
- type coercion (`string`, `boolean`, `integer`, `number`, `array`)
- enum constraint checks
- unknown-field behavior (`additionalProperties`)

If invalid:

- `code = INVALID_PARAM`
- `data.status = invalid_param`
- `data.errors` lists schema errors

## Handler Contract

Even with schema validation, handlers should still validate business semantics:

- temporal logic (`end_time > start_time`)
- permission checks
- resource existence
- cross-field domain constraints

Alarm-specific note:

- `set_alarm.time` uses local-time semantics (`HH:mm` preferred). Avoid UTC-offset conversions at execution time.
- `get_current_time` can be planned before `set_alarm` when user intent contains relative time phrases.

Use deterministic, structured errors:

- `INVALID_PARAM` for invalid user input
- `APPROVAL_REQUIRED` when user confirmation is needed
- `SKILL_EXECUTION_FAILED` for runtime/bridge/external failures

## Developer Checklist

1. Define `args_json_schema` in `SkillSpec` first.
2. Keep schema and handler behavior aligned.
3. Keep `additionalProperties` explicit.
4. Ensure handler returns stable `code` + `data.message`.
5. Add at least one schema validation test and one execute-path test.

## Templates

- Schema template:
  - [skill_json_schema.template.json](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/skills/skill_json_schema.template.json)
- Minimal test template:
  - [skill_test_template.py](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/skills/skill_test_template.py)
