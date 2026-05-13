from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

try:
    from ..skill_core import SkillHandler, SkillInvocation, SkillResult
except ImportError:
    import os
    import sys

    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from skill_core import SkillHandler, SkillInvocation, SkillResult


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class CampusActivitiesSkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="NOT_CONFIGURED",
            data={
                "status": "not_configured",
                "message": "Campus activities adapter is not configured.",
                "query": str(invocation.args.get("query", "")).strip(),
                "activities": [],
            },
            from_cache=False,
            fetched_at=_utc_now(),
            source="campus_data_unavailable",
        )


class SearchSkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        query = str(invocation.args.get("query", "")).strip()
        if not query:
            return SkillResult(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": "`query` is required"},
                from_cache=False,
                fetched_at=_utc_now(),
                source="campus_data_unavailable",
            )
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="NOT_CONFIGURED",
            data={
                "status": "not_configured",
                "message": "Search provider is not configured.",
                "query": query,
                "results": [],
                "summary": "",
            },
            from_cache=False,
            fetched_at=_utc_now(),
            source="campus_data_unavailable",
        )


class StaticCampusDataSkill(SkillHandler):
    """Report missing upstream adapters without returning synthetic data."""

    def __init__(
        self,
        *,
        skill_name: str,
        collection_key: str,
        message: str,
    ) -> None:
        self.skill_name = skill_name
        self.collection_key = collection_key
        self.message = message

    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="NOT_CONFIGURED",
            data={
                "status": "not_configured",
                "message": self.message,
                self.collection_key: [],
                "args": invocation.args,
                "warnings": ["Authenticated upstream campus adapter is not configured."],
            },
            from_cache=False,
            fetched_at=_utc_now(),
            source="campus_data_unavailable",
        )


def build_static_campus_data_handlers() -> dict[str, StaticCampusDataSkill]:
    return {
        "get_user_info": StaticCampusDataSkill(
            skill_name="get_user_info",
            collection_key="user",
            message="Student profile adapter is not configured yet.",
        ),
        "get_semesters": StaticCampusDataSkill(
            skill_name="get_semesters",
            collection_key="semesters",
            message="Semester list adapter is not configured yet.",
        ),
        "get_courses": StaticCampusDataSkill(
            skill_name="get_courses",
            collection_key="courses",
            message="Course list adapter is not configured yet.",
        ),
        "get_notices": StaticCampusDataSkill(
            skill_name="get_notices",
            collection_key="notices",
            message="Course notice adapter is not configured yet.",
        ),
        "get_files": StaticCampusDataSkill(
            skill_name="get_files",
            collection_key="files",
            message="Course file adapter is not configured yet.",
        ),
        "get_assignments": StaticCampusDataSkill(
            skill_name="get_assignments",
            collection_key="assignments",
            message="Assignment adapter is not configured yet.",
        ),
        "get_academic_calendar": StaticCampusDataSkill(
            skill_name="get_academic_calendar",
            collection_key="events",
            message="Academic calendar adapter is not configured yet.",
        ),
    }
