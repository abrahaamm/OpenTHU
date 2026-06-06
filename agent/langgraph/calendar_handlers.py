from __future__ import annotations

import json
import os
import re
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Protocol


class CalendarBridgeError(RuntimeError):
    pass


class CalendarTimeResolutionError(ValueError):
    def __init__(self, reason: str, message: str) -> None:
        super().__init__(message)
        self.reason = reason
        self.message = message


class KotlinSkillBridge(Protocol):
    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        ...


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


CALENDAR_TIME_SKILLS = {"create_calendar_event", "detect_calendar_conflicts"}
_ISO_OFFSET_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})"
)
_CALENDAR_TIME_META_KEYS = {
    "current_time",
    "time_source",
    "time_text",
    "source_skill",
    "source_field",
    # TODO(calendar-timezone): normalize timezone to IANA ids before dispatch
    # instead of dropping it. Android rejects display names such as 中国标准时间.
    "timezone",
}
_CALENDAR_TIME_SOURCES = {
    "user_text",
    "planner_inferred",
    "upstream_skill",
    "explicit_absolute",
}


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
    if _ISO_OFFSET_RE.fullmatch(text) is None:
        raise ValueError(
            "datetime must be ISO-8601 with explicit UTC offset, "
            "e.g. 2026-06-03T14:00:00+08:00"
        )
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    return int(parsed.timestamp() * 1000)


def _parse_iso_datetime(raw: str) -> datetime | None:
    text = str(raw or "").strip()
    if _ISO_OFFSET_RE.fullmatch(text) is None:
        return None
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed


def _isoformat_seconds(value: datetime) -> str:
    return value.replace(microsecond=0).isoformat()


def _extract_current_time(args: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
    current_time = args.get("current_time")
    if isinstance(current_time, dict):
        return current_time
    if isinstance(current_time, str) and current_time.strip():
        return {"local_datetime": current_time.strip()}
    current_time = state.get("current_time")
    if isinstance(current_time, dict):
        return current_time
    if isinstance(current_time, str) and current_time.strip():
        return {"local_datetime": current_time.strip()}
    for key in ("server_results", "skill_results", "device_results"):
        results = state.get(key, [])
        if not isinstance(results, list):
            continue
        for item in reversed(results):
            if not isinstance(item, dict):
                continue
            if str(item.get("skill_name", "")) != "get_current_time" or str(item.get("code", "")) != "OK":
                continue
            data = item.get("data", {})
            if isinstance(data, dict):
                return data
    return {}


def _timezone_from_context(args: dict[str, Any], session: dict[str, Any], current_time: dict[str, Any]) -> str:
    for source in (args, current_time, session):
        for key in ("timezone", "timezone_id", "local_timezone", "tz"):
            value = str(source.get(key, "") if isinstance(source, dict) else "").strip()
            if value:
                return value
    return ""


def _llm_config_from_session(session: dict[str, Any], llm_config: tuple[str, str, str] | None) -> tuple[str, str, str]:
    if llm_config is not None:
        return llm_config
    api_key = str(
        session.get("openai_api_key")
        or session.get("OPENAI_API_KEY")
        or os.getenv("OPENAI_API_KEY", "")
    ).strip()
    model = str(session.get("llm_model") or os.getenv("OPENAI_MODEL", "gpt-4.1-mini")).strip()
    base_url = str(session.get("llm_base_url") or os.getenv("OPENAI_BASE_URL", "")).strip()
    return api_key, model, base_url


def _extract_json_object_text(raw_text: str) -> str:
    text = raw_text.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    if text.startswith("{") and text.endswith("}"):
        return text
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return text[start : end + 1]
    return text


def _calendar_time_source(args: dict[str, Any]) -> str:
    source = str(args.get("time_source", "")).strip().lower()
    if source in _CALENDAR_TIME_SOURCES:
        return source
    return ""


def _strip_calendar_time_meta(args: dict[str, Any]) -> dict[str, Any]:
    for key in _CALENDAR_TIME_META_KEYS:
        args.pop(key, None)
    return args


def _normalize_iso_calendar_args(
    *,
    args: dict[str, Any],
    session: dict[str, Any],
    current_time: dict[str, Any],
    raise_on_invalid_window: bool,
) -> dict[str, Any] | None:
    start_raw = str(args.get("start_time", "")).strip()
    end_raw = str(args.get("end_time", "")).strip()
    start_dt = _parse_iso_datetime(start_raw)
    end_dt = _parse_iso_datetime(end_raw) if end_raw else None
    if start_dt is None or (end_raw and end_dt is None):
        return None
    if end_dt is None:
        end_dt = start_dt + timedelta(hours=1)
    if end_dt <= start_dt:
        if not raise_on_invalid_window:
            return None
        raise CalendarTimeResolutionError(
            "invalid_resolved_time",
            "无法创建或检测日历事项：结束时间必须晚于开始时间。",
        )
    normalized = dict(args)
    normalized["start_time"] = _isoformat_seconds(start_dt)
    normalized["end_time"] = _isoformat_seconds(end_dt)
    timezone_text = _timezone_from_context(normalized, session, current_time)
    if timezone_text and not str(normalized.get("timezone", "")).strip():
        normalized["timezone"] = timezone_text
    return _strip_calendar_time_meta(normalized)


def resolve_calendar_time_args(
    *,
    skill_name: str,
    args: dict[str, Any],
    session: dict[str, Any],
    state: dict[str, Any],
    llm_config: tuple[str, str, str] | None = None,
) -> dict[str, Any]:
    copied = dict(args)
    start_raw = str(copied.get("start_time", "")).strip()
    if not start_raw:
        raise CalendarTimeResolutionError(
            "missing_start_time",
            "无法创建或检测日历事项：缺少开始时间。请补充具体日期或时间。",
        )

    time_source = _calendar_time_source(copied)
    current_time = _extract_current_time(copied, state)
    normalized = _normalize_iso_calendar_args(
        args=copied,
        session=session,
        current_time=current_time,
        raise_on_invalid_window=time_source in {"upstream_skill", "explicit_absolute"}
        or (not time_source and not current_time),
    )

    if time_source == "upstream_skill":
        if normalized is None:
            raise CalendarTimeResolutionError(
                "invalid_upstream_time",
                "无法创建或检测日历事项：上游 skill 提供的时间不是带时区偏移的 ISO 时间。",
            )
        return normalized

    if time_source == "explicit_absolute" and normalized is not None:
        return normalized

    if time_source == "user_text" and not current_time:
        raise CalendarTimeResolutionError(
            "missing_current_time",
            "无法根据用户原文时间解析日历事项。请先获取当前时间后重试。",
        )

    if time_source == "planner_inferred" and not current_time:
        raise CalendarTimeResolutionError(
            "missing_current_time",
            "无法复核 planner 推断的日历时间。请先获取当前时间后重试。",
        )

    if not time_source and not current_time:
        if normalized is not None:
            return normalized
        raise CalendarTimeResolutionError(
            "missing_current_time",
            "无法根据不完整时间解析日历事项。请补充具体日期时间，或先获取当前时间后重试。",
        )

    api_key, model, base_url = _llm_config_from_session(session, llm_config)
    if not api_key:
        raise CalendarTimeResolutionError(
            "llm_not_configured",
            "无法解析相对日历时间：当前没有可用的 LLM 配置。请补充具体日期时间后重试。",
        )

    payload = {
        "skill_name": skill_name,
        "user_input": state.get("user_input", "") or state.get("goal", ""),
        "time_source": time_source or "unspecified",
        "time_text": str(copied.get("time_text", "") or start_raw).strip(),
        "source_skill": str(copied.get("source_skill", "")).strip(),
        "source_field": str(copied.get("source_field", "")).strip(),
        "calendar_args": {
            key: value
            for key, value in copied.items()
            if key not in {"current_time"}
        },
        "current_time": current_time,
        "timezone": _timezone_from_context(copied, session, current_time),
        "output_contract": {
            "start_time": "ISO-8601 datetime with explicit UTC offset",
            "end_time": "ISO-8601 datetime with explicit UTC offset; default to start_time + 1 hour if user did not specify duration/end",
            "timezone": "IANA timezone id when known",
        },
    }
    system_prompt = (
        "You resolve calendar time arguments for a mobile calendar executor. "
        "calendar_args are candidate arguments and may contain planner mistakes. "
        "Return strict JSON only with keys start_time, end_time, timezone; if resolution is impossible, "
        "return strict JSON with key error. "
        "Respect time_source. If time_source is upstream_skill, do not replace an upstream deadline or event time "
        "with the user's general request text. If time_source is user_text or planner_inferred, user_input/time_text "
        "are authoritative and candidate ISO years are not trustworthy unless the user explicitly gave that year. "
        "When user_text lacks a year, infer the year from current_time: use the same year when the date-time is still "
        "upcoming in that timezone, otherwise use the next year. If time_source is explicit_absolute, preserve the "
        "year explicitly stated by the user even when it is earlier than current_time. "
        "If time_source is unspecified and candidate ISO conflicts with user_input/time_text, return an error. "
        "Do not invent an end time except the default one-hour duration when the user did not specify duration or end. "
        "Both start_time and end_time must be ISO-8601 datetimes with explicit UTC offset."
    )
    try:
        from openai import OpenAI

        client_kwargs: dict[str, Any] = {"api_key": api_key}
        if base_url:
            client_kwargs["base_url"] = base_url
        client = OpenAI(**client_kwargs)
        completion = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            max_tokens=400,
            temperature=0.0,
        )
        raw_text = (completion.choices[0].message.content or "").strip()
        parsed = json.loads(_extract_json_object_text(raw_text))
    except Exception as exc:
        raise CalendarTimeResolutionError(
            "resolver_failed",
            f"无法解析相对日历时间：{type(exc).__name__}。请补充具体日期时间后重试。",
        ) from exc

    if not isinstance(parsed, dict):
        raise CalendarTimeResolutionError(
            "resolver_failed",
            "无法解析相对日历时间：解析器没有返回有效结果。请补充具体日期时间后重试。",
        )
    resolver_error = str(parsed.get("error", "")).strip()
    if resolver_error:
        raise CalendarTimeResolutionError(
            "resolver_failed",
            f"无法解析日历时间：{resolver_error}",
        )

    resolved_start = _parse_iso_datetime(str(parsed.get("start_time", "")).strip())
    resolved_end = _parse_iso_datetime(str(parsed.get("end_time", "")).strip())
    if resolved_start is None or resolved_end is None:
        raise CalendarTimeResolutionError(
            "invalid_resolved_time",
            "无法解析相对日历时间：解析结果不是带时区偏移的 ISO 时间。请补充具体日期时间后重试。",
        )
    if resolved_end <= resolved_start:
        raise CalendarTimeResolutionError(
            "invalid_resolved_time",
            "无法解析相对日历时间：结束时间必须晚于开始时间。请补充具体日期时间后重试。",
        )

    copied["start_time"] = _isoformat_seconds(resolved_start)
    copied["end_time"] = _isoformat_seconds(resolved_end)
    timezone_text = str(parsed.get("timezone", "")).strip() or _timezone_from_context(copied, session, current_time)
    if timezone_text:
        copied["timezone"] = timezone_text
    return _strip_calendar_time_meta(copied)


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
            if not title:
                return self._result(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="INVALID_PARAM",
                    data={"status": "invalid_param", "message": "title is required"},
                )
            invocation.args = resolve_calendar_time_args(
                skill_name=invocation.skill_name,
                args=args,
                session=session,
                state=state,
            )
            start_raw = str(invocation.args.get("start_time", "")).strip()
            end_raw = str(invocation.args.get("end_time", "")).strip()
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
        except CalendarTimeResolutionError as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={
                    "status": "calendar_time_resolution_failed",
                    "reason": exc.reason,
                    "message": exc.message,
                },
            )
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
            invocation.args = resolve_calendar_time_args(
                skill_name=invocation.skill_name,
                args=args,
                session=session,
                state=state,
            )
            start_raw = str(invocation.args.get("start_time", "")).strip()
            end_raw = str(invocation.args.get("end_time", "")).strip()
            start_ms = _parse_iso_to_epoch_ms(start_raw)
            end_ms = _parse_iso_to_epoch_ms(end_raw)
            if end_ms <= start_ms:
                return self._result(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="INVALID_PARAM",
                    data={"status": "invalid_param", "message": "end_time must be later than start_time"},
                )
        except CalendarTimeResolutionError as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={
                    "status": "calendar_time_resolution_failed",
                    "reason": exc.reason,
                    "message": exc.message,
                },
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
