from __future__ import annotations

import os
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

try:
    from ..skill_core import SkillHandler, SkillInvocation, SkillResult
except ImportError:
    import sys

    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from skill_core import SkillHandler, SkillInvocation, SkillResult


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class ShowSummarySkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        args = dict(invocation.args or {})
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "shown",
                "title": str(args.get("title", "OpenTHU 摘要")).strip(),
                "content": str(args.get("content", "")).strip(),
                "format": str(args.get("format", "plain")).strip() or "plain",
            },
            from_cache=False,
            fetched_at=_utc_now(),
            source="agent_summary",
        )


class SendNotificationSkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        args = dict(invocation.args or {})
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "notification_id": f"ntf_{uuid4().hex[:10]}",
                "status": "queued",
                "title": str(args.get("title", "OpenTHU 通知")).strip(),
                "body": str(args.get("body", "")).strip(),
            },
            from_cache=False,
            fetched_at=_utc_now(),
            source="local_notification",
        )


class OpenUrlSkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        args = dict(invocation.args or {})
        url = str(args.get("url", "")).strip()
        if not url:
            return SkillResult(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": "`url` is required"},
                from_cache=False,
                fetched_at=_utc_now(),
                source="url_launcher",
            )
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "ready_to_open",
                "url": url,
                "in_app": bool(args.get("in_app", True)),
            },
            from_cache=False,
            fetched_at=_utc_now(),
            source="url_launcher",
        )
