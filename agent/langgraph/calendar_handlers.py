from __future__ import annotations

import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol


class CalendarBridgeError(RuntimeError):
    pass


class KotlinSkillBridge(Protocol):
    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        ...


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _coerce_bool(value: Any, *, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in {"1", "true", "yes", "y", "on"}:
            return True
        if lowered in {"0", "false", "no", "n", "off"}:
            return False
    if isinstance(value, (int, float)):
        return bool(value)
    return default


def _parse_iso_to_epoch_ms(raw: str) -> int:
    text = raw.strip()
    if not text:
        raise ValueError("empty datetime")
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1000)


def _coerce_event_ids(raw_ids: Any, raw_id: Any) -> list[int]:
    collected: list[int] = []

    def append_value(value: Any) -> None:
        if value is None:
            return
        if isinstance(value, int):
            collected.append(value)
            return
        if isinstance(value, str):
            parsed = value.strip()
            if not parsed:
                return
            if "," in parsed:
                for part in parsed.split(","):
                    append_value(part)
                return
            as_int = int(parsed)
            collected.append(as_int)
            return
        if isinstance(value, list):
            for item in value:
                append_value(item)
            return
        raise ValueError(f"Unsupported event id type: {type(value).__name__}")

    append_value(raw_ids)
    append_value(raw_id)
    deduped = sorted(set(collected))
    return [item for item in deduped if item > 0]


class UnconfiguredKotlinBridge:
    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        raise CalendarBridgeError(
            "Kotlin bridge not configured. "
            "Set OPENTHU_CALENDAR_BRIDGE_MODE=json_file with request/response files, "
            "or inject a KotlinSkillBridge implementation when registering handlers."
        )


class JsonFileKotlinBridge:
    """Simple file bridge for Python<->Kotlin handoff in local/on-device integration.

    Python writes one invocation JSON to request file and waits for Kotlin runtime
    to write the corresponding SkillResult-compatible payload to response file.
    """

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
            raise CalendarBridgeError("Missing OPENTHU_KOTLIN_BRIDGE_REQUEST_FILE")
        if not str(self.response_file):
            raise CalendarBridgeError("Missing OPENTHU_KOTLIN_BRIDGE_RESPONSE_FILE")

    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        self.request_file.parent.mkdir(parents=True, exist_ok=True)
        self.response_file.parent.mkdir(parents=True, exist_ok=True)
        envelope = {
            "type": "skill_invocation",
            "sent_at": _utc_now_iso(),
            "invocation": invocation,
        }
        self.request_file.write_text(
            json.dumps(envelope, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

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
        raise CalendarBridgeError(
            f"Kotlin bridge timed out after {self.timeout_sec:.1f}s "
            f"(request_id={invocation.get('request_id', '')})"
        )


def _resolve_default_bridge() -> KotlinSkillBridge:
    mode = os.getenv("OPENTHU_CALENDAR_BRIDGE_MODE", "").strip().lower()
    if mode == "json_file":
        return JsonFileKotlinBridge()
    return UnconfiguredKotlinBridge()


class _BaseCalendarHandler:
    def __init__(self, bridge: KotlinSkillBridge | None = None) -> None:
        self.bridge = bridge or _resolve_default_bridge()

    def _result(
        self,
        *,
        skill_name: str,
        request_id: str,
        code: str,
        data: dict[str, Any],
        source: str = "android_kotlin_bridge",
    ) -> Any:
        try:
            from .skill_core import SkillResult
        except ImportError:
            from skill_core import SkillResult

        return SkillResult(
            skill_name=skill_name,
            request_id=request_id,
            code=code,
            data=data,
            from_cache=False,
            fetched_at=_utc_now_iso(),
            source=source,
        )

    def _dispatch_to_kotlin(self, invocation: Any, state: dict[str, Any]) -> Any:
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
        except CalendarBridgeError as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="SKILL_EXECUTION_FAILED",
                data={"status": "bridge_error", "message": str(exc)},
            )
        except Exception as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="SKILL_EXECUTION_FAILED",
                data={"status": "bridge_error", "message": f"{type(exc).__name__}: {exc}"},
            )

        code = str(response.get("code", "")).strip().upper() or "SKILL_EXECUTION_FAILED"
        data = response.get("data")
        if not isinstance(data, dict):
            message = str(response.get("message", "")).strip() or "kotlin bridge returned invalid payload"
            data = {"status": "invalid_bridge_payload", "message": message}
            code = "SKILL_EXECUTION_FAILED"
        source = str(response.get("source", "android_kotlin_bridge")).strip() or "android_kotlin_bridge"
        return self._result(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code=code,
            data=data,
            source=source,
        )


class CreateCalendarEventHandler(_BaseCalendarHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        args = invocation.args
        try:
            title = str(args.get("title", "")).strip()
            start_raw = str(args.get("start_time", "")).strip()
            end_raw = str(args.get("end_time", "")).strip()
            if not title or not start_raw or not end_raw:
                return self._result(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="INVALID_PARAM",
                    data={"status": "invalid_param", "message": "title/start_time/end_time are required"},
                )
            start_ms = _parse_iso_to_epoch_ms(start_raw)
            end_ms = _parse_iso_to_epoch_ms(end_raw)
            if end_ms <= start_ms:
                return self._result(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="INVALID_PARAM",
                    data={"status": "invalid_param", "message": "end_time must be later than start_time"},
                )
            conflict_decision = str(args.get("conflict_decision", "prompt_user")).strip().lower() or "prompt_user"
            if conflict_decision not in {"prompt_user", "skip_write", "coexist", "delete_conflicts"}:
                return self._result(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="INVALID_PARAM",
                    data={
                        "status": "invalid_param",
                        "message": "conflict_decision must be prompt_user|skip_write|coexist|delete_conflicts",
                    },
                )
            if conflict_decision == "delete_conflicts":
                allow_delete = _coerce_bool(args.get("allow_conflict_delete"), default=False)
                invocation.args["allow_conflict_delete"] = allow_delete
        except ValueError as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": str(exc)},
            )
        return self._dispatch_to_kotlin(invocation, state)


class DetectCalendarConflictsHandler(_BaseCalendarHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        args = invocation.args
        try:
            start_raw = str(args.get("start_time", "")).strip()
            end_raw = str(args.get("end_time", "")).strip()
            if not start_raw or not end_raw:
                return self._result(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="INVALID_PARAM",
                    data={"status": "invalid_param", "message": "start_time/end_time are required"},
                )
            start_ms = _parse_iso_to_epoch_ms(start_raw)
            end_ms = _parse_iso_to_epoch_ms(end_raw)
            if end_ms <= start_ms:
                return self._result(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="INVALID_PARAM",
                    data={"status": "invalid_param", "message": "end_time must be later than start_time"},
                )
        except ValueError as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": str(exc)},
            )
        return self._dispatch_to_kotlin(invocation, state)


class DeleteCalendarEventHandler(_BaseCalendarHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        args = invocation.args
        try:
            confirmed = _coerce_bool(args.get("confirm_delete"), default=False)
            if not confirmed:
                return self._result(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="APPROVAL_REQUIRED",
                    data={
                        "status": "awaiting_confirmation",
                        "high_risk": True,
                        "message": "confirm_delete=true is required for delete_calendar_event",
                    },
                )
            requested = _coerce_event_ids(args.get("event_ids"), args.get("event_id"))
            if not requested:
                return self._result(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="INVALID_PARAM",
                    data={"status": "invalid_param", "message": "event_id/event_ids are required"},
                )
            invocation.args["event_ids"] = [str(item) for item in requested]
            invocation.args["event_id"] = str(requested[0])
            invocation.args["confirm_delete"] = True
        except ValueError as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": str(exc)},
            )
        return self._dispatch_to_kotlin(invocation, state)


def register_calendar_handlers(registry: Any, bridge: KotlinSkillBridge | None = None) -> None:
    resolved_bridge = bridge or _resolve_default_bridge()
    registry.register_handler("create_calendar_event", CreateCalendarEventHandler(resolved_bridge))
    registry.register_handler("detect_calendar_conflicts", DetectCalendarConflictsHandler(resolved_bridge))
    registry.register_handler("delete_calendar_event", DeleteCalendarEventHandler(resolved_bridge))
