from __future__ import annotations

import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol


class HomeworkBridgeError(RuntimeError):
    pass


class HomeworkSkillBridge(Protocol):
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


def _coerce_string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return []
        if text.startswith("["):
            try:
                parsed = json.loads(text)
            except json.JSONDecodeError:
                parsed = []
            if isinstance(parsed, list):
                return [str(item).strip() for item in parsed if str(item).strip()]
        return [part.strip() for part in text.split(",") if part.strip()]
    return [str(value).strip()] if str(value).strip() else []


class UnconfiguredHomeworkBridge:
    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        raise HomeworkBridgeError(
            "Homework Kotlin bridge is not configured. In Agent-Core gateway mode, "
            "homework skills should be dispatched to the Android device."
        )


class JsonFileHomeworkBridge:
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
            or os.getenv("OPENTHU_HOMEWORK_BRIDGE_REQUEST_FILE", "").strip()
            or os.getenv("OPENTHU_KOTLIN_BRIDGE_REQUEST_FILE", "").strip()
        )
        self.response_file = Path(
            response_file
            or os.getenv("OPENTHU_HOMEWORK_BRIDGE_RESPONSE_FILE", "").strip()
            or os.getenv("OPENTHU_KOTLIN_BRIDGE_RESPONSE_FILE", "").strip()
        )
        self.timeout_sec = timeout_sec or float(os.getenv("OPENTHU_HOMEWORK_BRIDGE_TIMEOUT_SEC", "12"))
        self.poll_interval_sec = poll_interval_sec or 0.2

        if not str(self.request_file):
            raise HomeworkBridgeError("Missing OPENTHU_HOMEWORK_BRIDGE_REQUEST_FILE")
        if not str(self.response_file):
            raise HomeworkBridgeError("Missing OPENTHU_HOMEWORK_BRIDGE_RESPONSE_FILE")

    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        self.request_file.parent.mkdir(parents=True, exist_ok=True)
        self.response_file.parent.mkdir(parents=True, exist_ok=True)
        self.request_file.write_text(
            json.dumps(
                {
                    "type": "skill_invocation",
                    "sent_at": _utc_now_iso(),
                    "invocation": invocation,
                },
                ensure_ascii=False,
                indent=2,
            ),
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
        raise HomeworkBridgeError(
            f"Homework bridge timed out after {self.timeout_sec:.1f}s "
            f"(request_id={invocation.get('request_id', '')})"
        )


def _resolve_default_bridge() -> HomeworkSkillBridge:
    mode = os.getenv("OPENTHU_HOMEWORK_BRIDGE_MODE", "").strip().lower()
    if mode == "json_file":
        return JsonFileHomeworkBridge()
    return UnconfiguredHomeworkBridge()


class _BaseHomeworkHandler:
    def __init__(self, bridge: HomeworkSkillBridge | None = None) -> None:
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

    def _dispatch_to_bridge(self, invocation: Any, state: dict[str, Any]) -> Any:
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
        except HomeworkBridgeError as exc:
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
            message = str(response.get("message", "")).strip() or "homework bridge returned invalid payload"
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


class GetHomeworkCookieHandler(_BaseHomeworkHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        args = dict(invocation.args)
        for key in ("cookies", "session_cookie", "homework_cookie", "learn_cookie", "csrf_token", "learn_base_url"):
            if key in args and args[key] is not None:
                args[key] = str(args[key]).strip()
        invocation.args.clear()
        invocation.args.update(args)
        return self._dispatch_to_bridge(invocation, state)


class CrawlCourseHomeworksHandler(_BaseHomeworkHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        invocation.args["course_ids"] = _coerce_string_list(invocation.args.get("course_ids"))
        if "include_submitted" in invocation.args:
            invocation.args["include_submitted"] = _coerce_bool(invocation.args.get("include_submitted"), default=True)
        return self._dispatch_to_bridge(invocation, state)


class CrawlUnsubmittedHomeworksHandler(_BaseHomeworkHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        invocation.args["course_ids"] = _coerce_string_list(invocation.args.get("course_ids"))
        if "include_overdue" in invocation.args:
            invocation.args["include_overdue"] = _coerce_bool(invocation.args.get("include_overdue"), default=False)
        return self._dispatch_to_bridge(invocation, state)


class PreviewHomeworkAttachmentsHandler(_BaseHomeworkHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        homework_id = str(invocation.args.get("homework_id", "")).strip()
        if not homework_id:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": "homework_id is required"},
            )
        invocation.args["homework_id"] = homework_id
        return self._dispatch_to_bridge(invocation, state)


class UploadHomeworkAttachmentHandler(_BaseHomeworkHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        homework_id = str(invocation.args.get("homework_id", "")).strip()
        file_path = str(invocation.args.get("file_path", "")).strip()
        file_uri = str(invocation.args.get("file_uri", "")).strip()
        if not homework_id:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": "homework_id is required"},
            )
        if not file_path and not file_uri:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": "file_path or file_uri is required"},
            )
        invocation.args["homework_id"] = homework_id
        return self._dispatch_to_bridge(invocation, state)


class SubmitHomeworkHandler(_BaseHomeworkHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        args = invocation.args
        homework_id = str(args.get("homework_id", "")).strip()
        confirmed = _coerce_bool(args.get("confirm_submit"), default=False)
        if not confirmed:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="APPROVAL_REQUIRED",
                data={
                    "status": "awaiting_confirmation",
                    "high_risk": True,
                    "message": "confirm_submit=true is required for submit_homework",
                },
            )
        if not homework_id:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": "homework_id is required"},
            )
        attachment_tokens = _coerce_string_list(args.get("attachment_tokens"))
        local_file_paths = _coerce_string_list(args.get("local_file_paths"))
        has_content = any(
            [
                str(args.get("submission_text", "")).strip(),
                str(args.get("file_path", "")).strip(),
                str(args.get("file_uri", "")).strip(),
                attachment_tokens,
                local_file_paths,
            ]
        )
        if not has_content:
            return self._result(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": "submission_text, file, or attachment_tokens are required"},
            )
        args["homework_id"] = homework_id
        args["confirm_submit"] = True
        args["attachment_tokens"] = attachment_tokens
        args["local_file_paths"] = local_file_paths
        return self._dispatch_to_bridge(invocation, state)


def register_homework_handlers(registry: Any, bridge: HomeworkSkillBridge | None = None) -> None:
    resolved_bridge = bridge or _resolve_default_bridge()
    registry.register_handler("get_homework_cookie", GetHomeworkCookieHandler(resolved_bridge))
    registry.register_handler("crawl_course_homeworks", CrawlCourseHomeworksHandler(resolved_bridge))
    registry.register_handler("crawl_unsubmitted_homeworks", CrawlUnsubmittedHomeworksHandler(resolved_bridge))
    registry.register_handler("preview_homework_attachments", PreviewHomeworkAttachmentsHandler(resolved_bridge))
    registry.register_handler("upload_homework_attachment", UploadHomeworkAttachmentHandler(resolved_bridge))
    registry.register_handler("submit_homework", SubmitHomeworkHandler(resolved_bridge))
