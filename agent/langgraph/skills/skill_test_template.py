from __future__ import annotations

import unittest
from datetime import datetime, timezone
import os
import sys
from typing import Any

try:
    from ..skill_core import SkillInvocation, SkillRegistry, SkillResult, SkillSpec
    from ..skill_manager import SkillManager
except ImportError:
    current_dir = os.path.dirname(os.path.abspath(__file__))
    parent_dir = os.path.dirname(current_dir)
    if parent_dir not in sys.path:
        sys.path.append(parent_dir)
    from skill_core import SkillInvocation, SkillRegistry, SkillResult, SkillSpec
    from skill_manager import SkillManager


class ExampleSkillHandler:
    """Minimal handler used for skill manager schema tests."""

    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "ok",
                "received_args": invocation.args,
            },
            from_cache=False,
            fetched_at=datetime.now(timezone.utc).isoformat(),
            source="example_skill",
        )


def build_example_manager() -> SkillManager:
    registry = SkillRegistry()
    registry.register_spec(
        SkillSpec(
            skill_name="example_skill",
            description="Example skill for schema tests",
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
    )
    registry.register_handler("example_skill", ExampleSkillHandler())
    return SkillManager(registry=registry)


class SkillTemplateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.manager = build_example_manager()

    def test_schema_missing_required(self) -> None:
        normalized, errors, _ = self.manager.validate_and_normalize_args(
            "example_skill",
            {"enabled": True},
        )
        self.assertEqual(normalized, {"enabled": True})
        self.assertIn("missing required field `text`", errors)

    def test_schema_coercion_and_execute(self) -> None:
        invocation = SkillInvocation(
            skill_name="example_skill",
            request_id="req_example_1",
            task_id="task_example",
            args={"text": 123, "enabled": "true", "mode": "fast"},
            risk_level="low",
            requires_approval=False,
            description="template test",
        )
        result = self.manager.execute(invocation, {}, {})
        self.assertEqual(result["code"], "OK")
        self.assertEqual(
            result["data"]["received_args"],
            {"text": "123", "enabled": True, "mode": "fast"},
        )

    def test_schema_reject_unknown_field(self) -> None:
        invocation = SkillInvocation(
            skill_name="example_skill",
            request_id="req_example_2",
            task_id="task_example",
            args={"text": "ok", "extra_field": "not-allowed"},
            risk_level="low",
            requires_approval=False,
            description="template test",
        )
        result = self.manager.execute(invocation, {}, {})
        self.assertEqual(result["code"], "INVALID_PARAM")
        self.assertIn("unknown field `extra_field`", result["data"]["errors"])


if __name__ == "__main__":
    unittest.main()
