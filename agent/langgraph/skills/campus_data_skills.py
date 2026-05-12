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
        query = str(invocation.args.get("query", "")).strip()
        activities = [
            {
                "activity_id": "src_tsinghua_news",
                "title": "清华大学新闻网",
                "url": "https://news.tsinghua.edu.cn/",
                "source": "official",
            },
            {
                "activity_id": "src_tsinghua_events",
                "title": "清华大学校园活动入口",
                "url": "https://www.tsinghua.edu.cn/",
                "source": "official",
            },
        ]
        message = "Returned official activity entry points."
        if query:
            message = f"Returned activity sources for query: {query}"
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "ok",
                "message": message,
                "query": query,
                "activities": activities,
            },
            from_cache=True,
            fetched_at=_utc_now(),
            source="campus_data_fallback",
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
                source="campus_data_fallback",
            )
        results = [
            {
                "title": "清华大学官网",
                "url": "https://www.tsinghua.edu.cn/",
                "snippet": "校园通知、活动与综合信息入口",
                "source": "official",
            },
            {
                "title": "清华大学新闻网",
                "url": "https://news.tsinghua.edu.cn/",
                "snippet": "校园新闻与活动报道",
                "source": "official",
            },
        ]
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "ok",
                "query": query,
                "results": results,
                "summary": f"Found {len(results)} official entries for '{query}'.",
            },
            from_cache=True,
            fetched_at=_utc_now(),
            source="campus_data_fallback",
        )


class StaticCampusDataSkill(SkillHandler):
    """Graceful fallback for campus data skills that do not have upstream adapters yet."""

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
        has_session = bool(
            session.get("cookie")
            or session.get("webvpn_cookie")
            or session.get("info_cookie")
        )
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "empty",
                "message": self.message,
                self.collection_key: [],
                "args": invocation.args,
                "warnings": [
                    "Authenticated upstream campus adapter is not configured yet."
                    if has_session
                    else "No WebVPN/INFO session cookie was provided."
                ],
            },
            from_cache=True,
            fetched_at=_utc_now(),
            source="campus_data_fallback",
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
