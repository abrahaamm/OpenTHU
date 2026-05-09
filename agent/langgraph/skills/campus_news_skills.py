from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    from ..skill_core import SkillHandler, SkillInvocation, SkillResult
    from .campus_activity_processing import (
        build_activity,
        coerce_activity,
        dedupe_activities,
        filter_activities,
        looks_activity_related,
        summarize_activities,
    )
    from .campus_info_client import (
        ACTIVITY_CHANNELS,
        DEFAULT_ACTIVITY_KEYWORDS,
        CampusInfoClient,
        CampusInfoError,
    )
except ImportError:
    import sys

    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from skill_core import SkillHandler, SkillInvocation, SkillResult
    from skills.campus_activity_processing import (
        build_activity,
        coerce_activity,
        dedupe_activities,
        filter_activities,
        looks_activity_related,
        summarize_activities,
    )
    from skills.campus_info_client import (
        ACTIVITY_CHANNELS,
        DEFAULT_ACTIVITY_KEYWORDS,
        CampusInfoClient,
        CampusInfoError,
    )


DEFAULT_ACTIVITY_SOURCES = [
    {
        "activity_id": "src_tsinghua_news",
        "title": "清华大学新闻网",
        "organizer": "清华大学",
        "start_time": "",
        "location": "online",
        "url": "https://news.tsinghua.edu.cn/",
    },
    {
        "activity_id": "src_tsinghua_events",
        "title": "清华大学校园活动与通知入口",
        "organizer": "清华大学",
        "start_time": "",
        "location": "online",
        "url": "https://www.tsinghua.edu.cn/",
    },
]


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _load_configured_activities() -> tuple[list[dict[str, Any]], str]:
    raw_path = os.getenv("OPENTHU_CAMPUS_ACTIVITIES_FILE", "").strip()
    if not raw_path:
        return [], ""

    path = Path(raw_path)
    loaded = json.loads(path.read_text(encoding="utf-8"))
    records = loaded.get("activities", loaded) if isinstance(loaded, dict) else loaded
    if not isinstance(records, list):
        raise ValueError("campus activities file must contain a list or an object with `activities`")

    activities = [
        activity
        for idx, item in enumerate(records, start=1)
        if (activity := coerce_activity(item, idx)) is not None
    ]
    return activities, str(path)


def _coerce_keywords(args: dict[str, Any]) -> list[str]:
    raw = args.get("keywords") or args.get("keyword") or args.get("query") or ""
    if isinstance(raw, list):
        values = [str(item).strip() for item in raw]
    else:
        values = [item.strip() for item in str(raw).replace("，", ",").split(",")]
    values = [item for item in values if item]
    return values or list(DEFAULT_ACTIVITY_KEYWORDS)


def _coerce_limit(args: dict[str, Any]) -> int:
    try:
        value = int(args.get("limit", 10))
    except (TypeError, ValueError):
        value = 10
    return max(1, min(value, 30))


def _fetch_info_activities(
    session: dict[str, Any],
    keywords: list[str],
    limit: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[str]]:
    client = CampusInfoClient(session)
    sources: list[dict[str, Any]] = []
    warnings: list[str] = []
    if not client.available():
        warnings.append("INFO/WebVPN session cookie not available; skipped authenticated campus news API.")
        return [], sources, warnings

    slices = []
    seen_urls: set[str] = set()
    for channel in ACTIVITY_CHANNELS:
        try:
            channel_items = client.get_news_list(page=1, length=20, channel=channel)
            sources.append({"type": "info_channel", "channel": channel, "status": "ok", "count": len(channel_items)})
            for item in channel_items:
                if item.url not in seen_urls and looks_activity_related(item, keywords):
                    slices.append(item)
                    seen_urls.add(item.url)
        except CampusInfoError as exc:
            sources.append({"type": "info_channel", "channel": channel, "status": "failed", "message": str(exc)})

    for keyword in keywords[:5]:
        try:
            keyword_items = client.search_news_list(page=1, keyword=keyword, exact_match=False)
            sources.append({"type": "info_search", "keyword": keyword, "status": "ok", "count": len(keyword_items)})
            for item in keyword_items:
                if item.url not in seen_urls:
                    slices.append(item)
                    seen_urls.add(item.url)
        except CampusInfoError as exc:
            sources.append({"type": "info_search", "keyword": keyword, "status": "failed", "message": str(exc)})

    activities: list[dict[str, Any]] = []
    for item in slices[: max(limit * 2, 10)]:
        detail = None
        try:
            detail = client.get_news_detail(item.url)
        except CampusInfoError as exc:
            warnings.append(f"Detail fetch failed for {item.title[:24]}: {exc}")
        activities.append(build_activity(item, detail))
    return activities, sources, warnings


class CampusActivitiesSkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        args = dict(invocation.args or {})
        keywords = _coerce_keywords(args)
        limit = _coerce_limit(args)
        start_date = str(args.get("start_date", "")).strip()
        end_date = str(args.get("end_date", "")).strip()
        sources: list[dict[str, Any]] = []
        warnings: list[str] = []

        info_activities, info_sources, info_warnings = _fetch_info_activities(session, keywords, limit)
        sources.extend(info_sources)
        warnings.extend(info_warnings)

        try:
            configured_activities, configured_source = _load_configured_activities()
            if configured_source:
                sources.append({"type": "configured_file", "path": configured_source, "status": "ok", "count": len(configured_activities)})
        except Exception as exc:
            return SkillResult(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="SKILL_EXECUTION_FAILED",
                data={
                    "status": "source_error",
                    "message": f"Unable to load campus activities source: {exc}",
                    "activities": [],
                },
                from_cache=False,
                fetched_at=_utc_now(),
                source="campus_activities_file",
            )

        activities = dedupe_activities(info_activities + configured_activities)
        activities = filter_activities(activities, keywords=[], start_date=start_date, end_date=end_date, limit=limit)
        summary_payload = summarize_activities(activities, warnings)
        source = "info_webvpn_api" if info_activities else "campus_activities_file" if configured_activities else "official_entrypoints"
        from_cache = not bool(info_activities)

        if not activities:
            activities = list(DEFAULT_ACTIVITY_SOURCES)
            from_cache = True
            warnings.append("No parsed activity records available; returned official entry points.")
            summary_payload = summarize_activities(activities, warnings)

        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "ok",
                "summary": summary_payload["summary"],
                "activities": activities,
                "highlights": summary_payload["highlights"],
                "count": len(activities),
                "sources": sources,
                "warnings": summary_payload["warnings"],
                "missing_fields": summary_payload["missing_fields"],
                "keywords": keywords,
                "note": "Uses INFO/WebVPN news APIs when session cookies are available; falls back to OPENTHU_CAMPUS_ACTIVITIES_FILE or official entry points.",
            },
            from_cache=from_cache,
            fetched_at=_utc_now(),
            source=source,
        )
