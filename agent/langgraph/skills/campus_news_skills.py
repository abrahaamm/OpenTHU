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
        answer_activity_query,
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
    from .search_skills import SearchProviderError, _build_provider, search_with_scene
except ImportError:
    import sys

    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from skill_core import SkillHandler, SkillInvocation, SkillResult
    from skills.campus_activity_processing import (
        build_activity,
        answer_activity_query,
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
    from skills.search_skills import SearchProviderError, _build_provider, search_with_scene


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _load_configured_activities(session: dict[str, Any]) -> tuple[list[dict[str, Any]], str]:
    raw_path = ""
    for key in ("campus_file", "campus_activities_file"):
        value = session.get(key)
        if value is not None and str(value).strip():
            raw_path = str(value).strip()
            break
    if not raw_path:
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


def _coerce_query(args: dict[str, Any]) -> str:
    for key in ("query", "question", "search_query", "user_query"):
        value = args.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    raw_keywords = args.get("keywords") or args.get("keyword")
    if isinstance(raw_keywords, list):
        return " ".join(str(item).strip() for item in raw_keywords if str(item).strip())
    if isinstance(raw_keywords, str):
        return raw_keywords.strip()
    return ""


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


def _fetch_public_search_activities(
    session: dict[str, Any],
    *,
    keywords: list[str],
    query: str,
    limit: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[str]]:
    search_query = query.strip() or " ".join(keywords[:4]).strip() or "校园活动 讲座"
    if not any(token in search_query for token in ("清华", "校园", "活动", "讲座", "论坛")):
        search_query = f"清华 校园活动 {search_query}"
    try:
        provider = _build_provider(session=session)
        results, supplemental, from_cache = search_with_scene(
            provider=provider,
            query=search_query,
            scene="campus",
            max_results=max(1, min(limit, 10)),
            supplemental_results=0,
            domains=[],
            freshness_days=90,
            language="zh-CN",
        )
    except SearchProviderError as exc:
        return [], [{"type": "public_search", "status": "failed", "message": str(exc)}], [
            f"Public campus search unavailable: {exc}"
        ]

    activities: list[dict[str, Any]] = []
    for index, item in enumerate(results, start=1):
        activity = coerce_activity(
            {
                "activity_id": f"public_search_{index:03d}",
                "title": item.title,
                "url": item.url,
                "abstract": item.snippet,
                "source": item.source,
                "category": "campus",
                "confidence": 0.45,
            },
            index,
        )
        if activity is not None:
            activities.append(activity)

    sources = [
        {
            "type": "public_search",
            "provider": provider.name,
            "status": "ok",
            "count": len(activities),
            "from_cache": from_cache,
            "query": search_query,
            "supplemental_count": len(supplemental),
        }
    ]
    warnings: list[str] = []
    if activities:
        warnings.append("INFO/WebVPN 未返回可用数据，已使用公开校内网页搜索结果补充。")
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
        query = _coerce_query(args) or str(state.get("user_input", "")).strip()
        limit = _coerce_limit(args)
        start_date = str(args.get("start_date", "")).strip()
        end_date = str(args.get("end_date", "")).strip()
        sources: list[dict[str, Any]] = []
        warnings: list[str] = []

        info_activities, info_sources, info_warnings = _fetch_info_activities(session, keywords, limit)
        sources.extend(info_sources)
        warnings.extend(info_warnings)

        try:
            configured_activities, configured_source = _load_configured_activities(session)
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

        public_activities: list[dict[str, Any]] = []
        if not info_activities and not configured_activities:
            public_activities, public_sources, public_warnings = _fetch_public_search_activities(
                session,
                keywords=keywords,
                query=query,
                limit=limit,
            )
            sources.extend(public_sources)
            warnings.extend(public_warnings)

        activities = dedupe_activities(info_activities + configured_activities + public_activities)
        activities = filter_activities(activities, keywords=[], start_date=start_date, end_date=end_date, limit=limit)
        summary_payload = summarize_activities(activities, warnings)
        source = (
            "info_webvpn_api"
            if info_activities
            else "campus_activities_file"
            if configured_activities
            else "public_campus_search"
            if public_activities
            else "not_configured"
        )
        from_cache = not bool(info_activities)

        if not activities:
            if not sources and not configured_activities:
                return SkillResult(
                    skill_name=invocation.skill_name,
                    request_id=invocation.request_id,
                    code="NOT_CONFIGURED",
                    data={
                        "status": "not_configured",
                        "message": "Campus activities source is not configured. Provide INFO/WebVPN cookies or OPENTHU_CAMPUS_ACTIVITIES_FILE.",
                        "activities": [],
                        "sources": sources,
                        "warnings": warnings,
                        "keywords": keywords,
                        "query": query,
                    },
                    from_cache=False,
                    fetched_at=_utc_now(),
                    source="not_configured",
                )
            summary_payload = summarize_activities([], warnings)

        rag_payload = answer_activity_query(query, activities, limit=min(limit, 5)) if query else {
            "answer": "",
            "evidence": [],
            "matched_activities": [],
        }

        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "ok",
                "summary": summary_payload["summary"],
                "answer": rag_payload["answer"],
                "activities": activities,
                "highlights": summary_payload["highlights"],
                "matched_activities": rag_payload["matched_activities"],
                "evidence": rag_payload["evidence"],
                "count": len(activities),
                "sources": sources,
                "warnings": summary_payload["warnings"],
                "missing_fields": summary_payload["missing_fields"],
                "keywords": keywords,
                "query": query,
                "note": "Uses INFO/WebVPN news APIs when session cookies are available, or OPENTHU_CAMPUS_ACTIVITIES_FILE when explicitly configured.",
            },
            from_cache=from_cache,
            fetched_at=_utc_now(),
            source=source,
        )
