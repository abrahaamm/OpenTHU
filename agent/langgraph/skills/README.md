# Skill Templates

This folder contains reusable templates for skill developers.

## Files

- [skill_json_schema.template.json](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/skills/skill_json_schema.template.json)
  - baseline `args_json_schema` template for `SkillSpec`
- [skill_test_template.py](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/skills/skill_test_template.py)
  - minimal tests for schema validation and manager execution

## How to Use

1. Copy `skill_json_schema.template.json` and rename it for your skill.
2. Fill `properties`, `required`, and `additionalProperties`.
3. Attach it to `SkillSpec(args_json_schema=...)`.
4. Copy `skill_test_template.py`, rename class and fields for your skill.
5. Add execute-path tests with your real handler.

For full conventions, see:

- [SKILL_MANAGER_SCHEMA_GUIDE.md](/Users/jasonlau/Documents/homeworks/mobile/openthu/OpenCray/agent/langgraph/SKILL_MANAGER_SCHEMA_GUIDE.md)
