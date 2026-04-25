from __future__ import annotations

import os
import re
import subprocess
import time
from datetime import datetime, timezone
from typing import Any


CALENDAR_EVENTS_URI = "content://com.android.calendar/events"
CALENDAR_CALENDARS_URI = "content://com.android.calendar/calendars"


class CalendarBridgeError(RuntimeError):
    pass


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


def _epoch_ms_to_iso(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).isoformat()


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


class AdbCalendarBridge:
    def __init__(
        self,
        adb_bin: str | None = None,
        device_serial: str | None = None,
    ) -> None:
        self.adb_bin = adb_bin or os.getenv("OPENTHU_ADB_BIN", "adb")
        self.device_serial = device_serial or os.getenv("OPENTHU_ADB_SERIAL", "").strip()

    def _adb_prefix(self) -> list[str]:
        prefix = [self.adb_bin]
        if self.device_serial:
            prefix.extend(["-s", self.device_serial])
        return prefix

    def _run_content(self, *args: str) -> str:
        cmd = self._adb_prefix() + ["shell", "content", *args]
        try:
            completed = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                check=False,
            )
        except FileNotFoundError as exc:
            raise CalendarBridgeError(
                f"adb not found (configured binary: {self.adb_bin})"
            ) from exc

        stdout = completed.stdout.strip()
        stderr = completed.stderr.strip()
        if completed.returncode != 0:
            detail = stderr or stdout or f"exit={completed.returncode}"
            raise CalendarBridgeError(f"adb content command failed: {detail}")

        # Some Android builds print command failures while still returning code 0.
        lowered_stdout = stdout.lower()
        lowered_stderr = stderr.lower()
        if (
            "[error]" in lowered_stdout
            or "usage: adb shell content" in lowered_stdout
            or "error while accessing provider" in lowered_stdout
            or "error while accessing provider" in lowered_stderr
        ):
            detail = stderr or stdout
            raise CalendarBridgeError(f"adb content command failed: {detail}")

        if stderr:
            raise CalendarBridgeError(f"adb content command stderr: {stderr}")
        return stdout

    def _quote_shell_arg(self, value: str) -> str:
        # content runs through /system/bin/sh on device side, so quote SQL snippets.
        escaped = value.replace("'", "'\\''")
        return f"'{escaped}'"

    def _escape_sql_string(self, value: str) -> str:
        return value.replace("'", "''")

    def resolve_writable_calendar_id(self) -> int:
        output = self._run_content(
            "query",
            "--uri",
            CALENDAR_CALENDARS_URI,
            "--projection",
            "_id:calendar_access_level:visible:isPrimary",
            "--where",
            self._quote_shell_arg("visible=1"),
            "--sort",
            "_id",
        )
        rows = self._parse_rows(output)
        for row in rows:
            row_id = self._parse_int(row.get("_id"))
            access = self._parse_int(row.get("calendar_access_level"))
            if row_id is None or access is None:
                continue
            if access >= 500:
                return row_id
        # Fallback: some devices may have writable calendars marked not-visible.
        output_all = self._run_content(
            "query",
            "--uri",
            CALENDAR_CALENDARS_URI,
            "--projection",
            "_id:calendar_access_level:visible:isPrimary",
            "--sort",
            "_id",
        )
        for row in self._parse_rows(output_all):
            row_id = self._parse_int(row.get("_id"))
            access = self._parse_int(row.get("calendar_access_level"))
            if row_id is None or access is None:
                continue
            if access >= 500:
                return row_id
        raise CalendarBridgeError("No writable calendar found on connected device")

    def query_conflicts(self, start_ms: int, end_ms: int) -> list[dict[str, Any]]:
        where = (
            f"dtstart < {end_ms} AND "
            f"(dtend IS NULL OR dtend = 0 OR dtend > {start_ms}) AND "
            "(deleted IS NULL OR deleted=0)"
        )
        output = self._run_content(
            "query",
            "--uri",
            CALENDAR_EVENTS_URI,
            "--projection",
            "_id:title:dtstart:dtend",
            "--where",
            self._quote_shell_arg(where),
            "--sort",
            "dtstart",
        )
        parsed: list[dict[str, Any]] = []
        for row in self._parse_rows(output):
            event_id = self._parse_int(row.get("_id"))
            start = self._parse_int(row.get("dtstart"))
            if event_id is None or start is None:
                continue
            end_raw = self._parse_int(row.get("dtend"))
            end = end_raw if end_raw and end_raw > start else start + 60_000
            if start < end_ms and start_ms < end:
                parsed.append(
                    {
                        "event_id": str(event_id),
                        "title": row.get("title", ""),
                        "start_time": _epoch_ms_to_iso(start),
                        "end_time": _epoch_ms_to_iso(end),
                        "start_ms": start,
                        "end_ms": end,
                    }
                )
        return parsed

    def query_events_by_ids(self, event_ids: list[int]) -> list[dict[str, Any]]:
        if not event_ids:
            return []
        where = "_id IN (" + ",".join(str(item) for item in event_ids) + ")"
        output = self._run_content(
            "query",
            "--uri",
            CALENDAR_EVENTS_URI,
            "--projection",
            "_id:title:dtstart:dtend",
            "--where",
            self._quote_shell_arg(where),
        )
        parsed: list[dict[str, Any]] = []
        for row in self._parse_rows(output):
            event_id = self._parse_int(row.get("_id"))
            if event_id is None:
                continue
            parsed.append(
                {
                    "event_id": str(event_id),
                    "title": row.get("title", ""),
                    "start_time": _epoch_ms_to_iso(self._parse_int(row.get("dtstart")) or 0),
                    "end_time": _epoch_ms_to_iso(self._parse_int(row.get("dtend")) or 0),
                }
            )
        return parsed

    def insert_event(
        self,
        *,
        calendar_id: int,
        title: str,
        description: str,
        start_ms: int,
        end_ms: int,
    ) -> str:
        normalized_title = title.replace("\n", " ").strip()
        normalized_description = description.replace("\n", " ").strip()
        timezone_id = os.getenv("OPENTHU_CALENDAR_TIMEZONE", "UTC").strip() or "UTC"
        before_ids = set(self._query_event_ids_by_signature(normalized_title, start_ms, end_ms))
        output = self._run_content(
            "insert",
            "--uri",
            CALENDAR_EVENTS_URI,
            "--bind",
            self._quote_shell_arg(f"calendar_id:i:{calendar_id}"),
            "--bind",
            self._quote_shell_arg(f"title:s:{normalized_title}"),
            "--bind",
            self._quote_shell_arg(f"description:s:{normalized_description}"),
            "--bind",
            self._quote_shell_arg(f"dtstart:l:{start_ms}"),
            "--bind",
            self._quote_shell_arg(f"dtend:l:{end_ms}"),
            "--bind",
            self._quote_shell_arg(f"eventTimezone:s:{timezone_id}"),
        )
        matched = re.search(r"/events/(\d+)", output)
        if not matched:
            # Some Android builds return empty stdout for successful insert.
            for _ in range(3):
                after_ids = set(self._query_event_ids_by_signature(normalized_title, start_ms, end_ms))
                new_ids = sorted(after_ids - before_ids)
                if new_ids:
                    return str(new_ids[-1])
                if len(after_ids) == 1:
                    return str(next(iter(after_ids)))
                time.sleep(0.2)
            raise CalendarBridgeError(f"Unable to parse inserted event id: {output}")
        return matched.group(1)

    def _query_event_ids_by_signature(
        self,
        title: str,
        start_ms: int,
        end_ms: int,
    ) -> list[int]:
        escaped_title = self._escape_sql_string(title)
        where = (
            f"title='{escaped_title}' AND "
            f"dtstart={start_ms} AND "
            f"dtend={end_ms} AND "
            "(deleted IS NULL OR deleted=0)"
        )
        output = self._run_content(
            "query",
            "--uri",
            CALENDAR_EVENTS_URI,
            "--projection",
            "_id",
            "--where",
            self._quote_shell_arg(where),
            "--sort",
            "_id",
        )
        ids: list[int] = []
        for row in self._parse_rows(output):
            event_id = self._parse_int(row.get("_id"))
            if event_id is not None:
                ids.append(event_id)
        return ids

    def delete_events(self, event_ids: list[int]) -> int:
        if not event_ids:
            return 0
        existing_before = {
            int(item["event_id"])
            for item in self.query_events_by_ids(event_ids)
            if item.get("event_id")
        }
        where = "_id IN (" + ",".join(str(item) for item in event_ids) + ")"
        output = self._run_content(
            "delete",
            "--uri",
            CALENDAR_EVENTS_URI,
            "--where",
            self._quote_shell_arg(where),
        )
        matched = re.search(r"(\d+)\s+(?:rows?|records?)", output, flags=re.IGNORECASE)
        if matched:
            return int(matched.group(1))
        # Some builds return empty output even when deletion succeeds.
        for _ in range(3):
            existing_after = {
                int(item["event_id"])
                for item in self.query_events_by_ids(event_ids)
                if item.get("event_id")
            }
            deleted = len(existing_before - existing_after)
            if deleted > 0:
                return deleted
            if not existing_after and existing_before:
                return len(existing_before)
            time.sleep(0.2)
        return 0

    def _parse_rows(self, output: str) -> list[dict[str, str]]:
        rows: list[dict[str, str]] = []
        for line in output.splitlines():
            stripped = line.strip()
            if not stripped.startswith("Row:"):
                continue
            payload = re.sub(r"^Row:\s*\d+\s*", "", stripped)
            rows.append(self._parse_payload(payload))
        return rows

    def _parse_payload(self, payload: str) -> dict[str, str]:
        result: dict[str, str] = {}
        key = ""
        value_chars: list[str] = []
        reading_key = True
        i = 0
        while i < len(payload):
            ch = payload[i]
            if reading_key:
                if ch == "=":
                    reading_key = False
                else:
                    key += ch
                i += 1
                continue
            if ch == "," and i + 1 < len(payload) and payload[i + 1] == " ":
                next_idx = i + 2
                key_buf: list[str] = []
                while next_idx < len(payload) and payload[next_idx] not in {"=", ","}:
                    key_buf.append(payload[next_idx])
                    next_idx += 1
                if next_idx < len(payload) and payload[next_idx] == "=":
                    result[key.strip()] = "".join(value_chars).strip()
                    key = "".join(key_buf)
                    value_chars = []
                    reading_key = False
                    i = next_idx + 1
                    continue
            value_chars.append(ch)
            i += 1
        if key:
            result[key.strip()] = "".join(value_chars).strip()
        return result

    def _parse_int(self, raw: str | None) -> int | None:
        if raw is None:
            return None
        text = raw.strip()
        if not text:
            return None
        try:
            return int(text)
        except ValueError:
            return None


class _BaseCalendarHandler:
    def __init__(self, bridge: AdbCalendarBridge | None = None) -> None:
        self.bridge = bridge or AdbCalendarBridge()

    def _result(
        self,
        *,
        skill_name: str,
        request_id: str,
        code: str,
        data: dict[str, Any],
        source: str = "android_calendar_adb",
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

            description = str(args.get("description", "")).strip()
            conflict_decision = str(args.get("conflict_decision", "prompt_user")).strip().lower() or "prompt_user"
            conflicts = self.bridge.query_conflicts(start_ms, end_ms)

            if conflicts:
                if conflict_decision == "prompt_user":
                    return self._result(
                        skill_name=invocation.skill_name,
                        request_id=invocation.request_id,
                        code="APPROVAL_REQUIRED",
                        data={
                            "status": "conflict_detected",
                            "supports_overlap": True,
                            "conflict_count": len(conflicts),
                            "conflicts": conflicts,
                            "decision_options": ["skip_write", "coexist", "delete_conflicts"],
                        },
                    )
                if conflict_decision == "skip_write":
                    return self._result(
                        skill_name=invocation.skill_name,
                        request_id=invocation.request_id,
                        code="OK",
                        data={
                            "status": "skipped_conflict",
                            "conflict_count": len(conflicts),
                            "conflicts": conflicts,
                        },
                    )
                if conflict_decision == "delete_conflicts":
                    allow_delete = _coerce_bool(args.get("allow_conflict_delete"), default=False)
                    if not allow_delete:
                        return self._result(
                            skill_name=invocation.skill_name,
                            request_id=invocation.request_id,
                            code="ACTION_NOT_ALLOWED",
                            data={
                                "status": "delete_blocked",
                                "message": "allow_conflict_delete=true is required for delete_conflicts",
                            },
                        )
                    to_delete = [int(item["event_id"]) for item in conflicts]
                    self.bridge.delete_events(to_delete)
                elif conflict_decision != "coexist":
                    return self._result(
                        skill_name=invocation.skill_name,
                        request_id=invocation.request_id,
                        code="INVALID_PARAM",
                        data={
                            "status": "invalid_param",
                            "message": "conflict_decision must be prompt_user|skip_write|coexist|delete_conflicts",
                        },
                    )

            calendar_id = self.bridge.resolve_writable_calendar_id()
            event_id = self.bridge.insert_event(
                calendar_id=calendar_id,
                title=title,
                description=description,
                start_ms=start_ms,
                end_ms=end_ms,
            )
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="OK",
                data={
                    "status": "created",
                    "event_id": event_id,
                    "conflict_count": len(conflicts),
                },
            )
        except ValueError as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": str(exc)},
            )
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
                data={"status": "handler_error", "message": f"{type(exc).__name__}: {exc}"},
            )


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
            conflicts = self.bridge.query_conflicts(start_ms, end_ms)
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="OK",
                data={
                    "status": "detected",
                    "supports_overlap": True,
                    "conflict_count": len(conflicts),
                    "conflicts": conflicts,
                    "decision_options": ["skip_write", "coexist", "delete_conflicts"],
                },
            )
        except ValueError as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": str(exc)},
            )
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
                data={"status": "handler_error", "message": f"{type(exc).__name__}: {exc}"},
            )


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

            existing = self.bridge.query_events_by_ids(requested)
            existing_ids = sorted({int(item["event_id"]) for item in existing})
            deleted_count = self.bridge.delete_events(existing_ids)
            missing = [str(item) for item in requested if item not in existing_ids]
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="OK",
                data={
                    "status": "deleted",
                    "deleted_count": deleted_count,
                    "deleted": existing,
                    "missing_event_ids": missing,
                    "high_risk": True,
                },
            )
        except ValueError as exc:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": str(exc)},
            )
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
                data={"status": "handler_error", "message": f"{type(exc).__name__}: {exc}"},
            )


def register_calendar_handlers(registry: Any) -> None:
    bridge = AdbCalendarBridge()
    registry.register_handler("create_calendar_event", CreateCalendarEventHandler(bridge))
    registry.register_handler("detect_calendar_conflicts", DetectCalendarConflictsHandler(bridge))
    registry.register_handler("delete_calendar_event", DeleteCalendarEventHandler(bridge))
