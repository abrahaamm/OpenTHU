from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

try:
    from ..skill_core import SkillHandler, SkillInvocation, SkillResult
except ImportError:
    import os
    import sys

    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from skill_core import SkillHandler, SkillInvocation, SkillResult


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _parse_local_alarm_time(raw: str) -> tuple[int, int] | None:
    text = (raw or "").strip()
    if not text:
        return None

    # Preferred local wall-clock format.
    hhmm = re.match(r"^([01]?\d|2[0-3]):([0-5]\d)$", text)
    if hhmm:
        return int(hhmm.group(1)), int(hhmm.group(2))

    # Local-time semantics for ISO-like strings: keep wall-clock hour/minute
    # and ignore timezone offset to avoid UTC-shift surprises.
    iso_like = re.match(
        r"^\d{4}-\d{2}-\d{2}T([01]\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?(?:\.\d+)?(?:Z|[+-][01]\d:[0-5]\d)?$",
        text,
    )
    if iso_like:
        return int(iso_like.group(1)), int(iso_like.group(2))

    return None


class GetCurrentTimeSkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        now_local = datetime.now().astimezone()
        tzinfo = now_local.tzinfo
        tz_name = now_local.tzname() or "local"
        tz_id = getattr(tzinfo, "key", None) or tz_name
        offset = now_local.strftime("%z")
        offset_fmt = f"{offset[:3]}:{offset[3:]}" if len(offset) == 5 else offset
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "ok",
                "local_datetime": now_local.replace(microsecond=0).isoformat(),
                "local_date": now_local.strftime("%Y-%m-%d"),
                "local_time": now_local.strftime("%H:%M"),
                "timezone": tz_id,
                "timezone_name": tz_name,
                "utc_offset": offset_fmt,
                "epoch_ms": int(now_local.timestamp() * 1000),
            },
            from_cache=False,
            fetched_at=_utc_now(),
            source="system_clock",
        )


class SetAlarmSkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        args = dict(invocation.args or {})
        parsed = _parse_local_alarm_time(str(args.get("time", "")))
        if parsed is None:
            return SkillResult(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={
                    "status": "invalid_param",
                    "message": "Invalid `time`. Use local `HH:mm` or local ISO8601 datetime.",
                },
                from_cache=False,
                fetched_at=_utc_now(),
                source="system_alarm",
            )

        hour, minute = parsed
        normalized_time = f"{hour:02d}:{minute:02d}"
        args["time"] = normalized_time

        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "alarm_id": f"alm_{uuid4().hex[:10]}",
                "status": "set",
                "payload": args,
                "normalized": {
                    "local_time": normalized_time,
                    "timezone_semantics": "local",
                },
            },
            from_cache=False,
            fetched_at=_utc_now(),
            source="system_alarm",
        )
