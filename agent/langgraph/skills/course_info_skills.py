from __future__ import annotations

import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol

try:
    from ..skill_core import SkillHandler, SkillInvocation, SkillRegistry, SkillResult
except ImportError:
    import sys

    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from skill_core import SkillHandler, SkillInvocation, SkillRegistry, SkillResult


class CourseBridgeError(RuntimeError):
    pass


class KotlinSkillBridge(Protocol):
    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        ...


class UnconfiguredKotlinBridge:
    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        raise CourseBridgeError(
            "Kotlin bridge is unavailable. Configure OPENTHU_KOTLIN_BRIDGE_MODE=json_file "
            "and provide request/response files, or inject a KotlinSkillBridge implementation."
        )


class JsonFileKotlinBridge:
    def __init__(
        self,
        *,
        request_file: str | None = None,
        response_file: str | None = None,
        timeout_sec: float | None = None,
        poll_interval_sec: float | None = None,
    ) -> None:
        self.request_file = Path(
            request_file
            or os.getenv("OPENTHU_KOTLIN_BRIDGE_REQUEST_FILE", "").strip()
        )
        self.response_file = Path(
            response_file
            or os.getenv("OPENTHU_KOTLIN_BRIDGE_RESPONSE_FILE", "").strip()
        )
        self.timeout_sec = timeout_sec or float(os.getenv("OPENTHU_KOTLIN_BRIDGE_TIMEOUT_SEC", "12"))
        self.poll_interval_sec = poll_interval_sec or 0.2

        if not str(self.request_file):
            raise CourseBridgeError("Missing OPENTHU_KOTLIN_BRIDGE_REQUEST_FILE")
        if not str(self.response_file):
            raise CourseBridgeError("Missing OPENTHU_KOTLIN_BRIDGE_RESPONSE_FILE")

    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        self.request_file.parent.mkdir(parents=True, exist_ok=True)
        self.response_file.parent.mkdir(parents=True, exist_ok=True)
        envelope = {
            "type": "skill_invocation",
            "sent_at": _utc_now(),
            "invocation": invocation,
        }
        self.request_file.write_text(json.dumps(envelope, ensure_ascii=False, indent=2), encoding="utf-8")

        start = time.time()
        while time.time() - start <= self.timeout_sec:
            if self.response_file.exists():
                raw = self.response_file.read_text(encoding="utf-8").strip()
                if raw:
                    try:
                        parsed = json.loads(raw)
                    except json.JSONDecodeError:
                        time.sleep(self.poll_interval_sec)
                        continue
                    if parsed.get("request_id") == invocation.get("request_id"):
                        return parsed
            time.sleep(self.poll_interval_sec)

        raise CourseBridgeError(
            f"Kotlin bridge timed out after {self.timeout_sec:.1f}s (request_id={invocation.get('request_id', '')})"
        )


def _resolve_default_bridge() -> KotlinSkillBridge:
    mode = os.getenv("OPENTHU_KOTLIN_BRIDGE_MODE", "").strip().lower()
    if mode == "json_file":
        return JsonFileKotlinBridge()
    return UnconfiguredKotlinBridge()


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _result(
    invocation: SkillInvocation,
    code: str,
    data: dict[str, Any],
    source: str,
) -> SkillResult:
    return SkillResult(
        skill_name=invocation.skill_name,
        request_id=invocation.request_id,
        code=code,
        data=data,
        from_cache=False,
        fetched_at=_utc_now(),
        source=source,
    )


class _BaseCourseInfoSkill(SkillHandler):
    def __init__(self, bridge: KotlinSkillBridge | None = None) -> None:
        self.bridge = bridge or _resolve_default_bridge()

    def _result(
        self,
        invocation: SkillInvocation,
        code: str,
        data: dict[str, Any],
        source: str,
    ) -> SkillResult:
        return _result(invocation, code, data, source)

    def _dispatch_to_kotlin(self, invocation: SkillInvocation, state: dict[str, Any]) -> SkillResult:
        payload = {
            "skill_name": invocation.skill_name,
            "request_id": invocation.request_id,
            "task_id": invocation.task_id,
            "args": invocation.args,
            "risk_level": invocation.risk_level,
            "requires_approval": invocation.requires_approval,
            "description": invocation.description,
        }
        try:
            response = self.bridge.execute(payload, state)
        except CourseBridgeError as exc:
            return self._result(
                invocation,
                "SKILL_EXECUTION_FAILED",
                {"status": "bridge_error", "message": str(exc)},
                "course_info_bridge",
            )
        except Exception as exc:
            return self._result(
                invocation,
                "SKILL_EXECUTION_FAILED",
                {"status": "bridge_error", "message": f"{type(exc).__name__}: {exc}"},
                "course_info_bridge",
            )

        code = str(response.get("code", "")).strip().upper() or "SKILL_EXECUTION_FAILED"
        data = response.get("data")
        if not isinstance(data, dict):
            message = str(response.get("message", "")).strip() or "kotlin bridge returned invalid payload"
            data = {"status": "invalid_bridge_payload", "message": message}
            code = "SKILL_EXECUTION_FAILED"
        source = str(response.get("source", "android_kotlin_bridge")).strip() or "android_kotlin_bridge"
        return self._result(invocation, code, data, source)


class GetSemestersSkill(_BaseCourseInfoSkill):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        return self._dispatch_to_kotlin(invocation, state)


class GetCoursesSkill(_BaseCourseInfoSkill):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        return self._dispatch_to_kotlin(invocation, state)


class GetCourseScheduleSkill(_BaseCourseInfoSkill):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        return self._dispatch_to_kotlin(invocation, state)


def register_course_info_handlers(registry: SkillRegistry) -> None:
    registry.register_handler("get_semesters", GetSemestersSkill())
    registry.register_handler("get_courses", GetCoursesSkill())
    registry.register_handler("get_course_schedule", GetCourseScheduleSkill())
