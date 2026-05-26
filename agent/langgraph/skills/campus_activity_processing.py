from __future__ import annotations

import hashlib
import re
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from typing import Any

try:
    from .campus_info_client import CampusNewsDetail, CampusNewsSlice
except ImportError:
    from campus_info_client import CampusNewsDetail, CampusNewsSlice


CN_TZ = timezone(timedelta(hours=8))


def coerce_activity(raw: Any, index: int) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        return None
    title = str(raw.get("title", "")).strip()
    url = str(raw.get("url", "")).strip()
    if not title and not url:
        return None
    abstract = str(raw.get("abstract", "")).strip()
    organizer = str(raw.get("organizer", "")).strip()
    start_time = str(raw.get("start_time", "")).strip()
    location = str(raw.get("location", "")).strip()
    category = str(raw.get("category", "")).strip()
    text = f"{title}\n{abstract}"
    if not start_time:
        start_time = extract_datetime(text, str(raw.get("publish_time", "")).strip())
    if not location:
        location = extract_labeled_value(text, ("活动地点", "讲座地点", "地点", "会场", "会议地点"))
    if not organizer:
        organizer = extract_labeled_value(text, ("主办单位", "主办方", "主办", "承办单位", "承办"))
    if not category:
        category = classify_activity(title, abstract, str(raw.get("channel", "")).strip())
    confidence = float(raw.get("confidence", 0.5) or 0.5)
    if start_time and "confidence" not in raw:
        confidence += 0.15
    if location and "confidence" not in raw:
        confidence += 0.1
    if organizer and "confidence" not in raw:
        confidence += 0.1

    activity = {
        "activity_id": str(raw.get("activity_id", f"activity_{index:03d}")).strip(),
        "title": title or url,
        "organizer": organizer,
        "start_time": start_time,
        "end_time": str(raw.get("end_time", "")).strip(),
        "location": location,
        "url": url,
        "category": category or "other",
        "source": str(raw.get("source", "")).strip(),
        "publish_time": str(raw.get("publish_time", "")).strip(),
        "abstract": abstract,
        "confidence": round(min(confidence, 0.95), 2),
    }
    return activity


def build_activity(slice_item: CampusNewsSlice, detail: CampusNewsDetail | None = None) -> dict[str, Any]:
    detail_title = detail.title.strip() if detail and detail.title else ""
    title = detail_title if detail_title and detail_title.lower() not in {"title", "untitled"} else slice_item.title.strip()
    abstract = detail.abstract if detail else ""
    text = f"{title}\n{abstract}"
    start_time = extract_datetime(text, slice_item.publish_time)
    organizer = extract_labeled_value(text, ("主办单位", "主办方", "主办", "承办单位", "承办")) or slice_item.source
    location = extract_labeled_value(text, ("活动地点", "讲座地点", "地点", "会场", "会议地点"))
    category = classify_activity(title, abstract, slice_item.channel)
    confidence = 0.45
    if start_time:
        confidence += 0.2
    if location:
        confidence += 0.15
    if organizer:
        confidence += 0.1
    if detail and abstract:
        confidence += 0.1
    url = (detail.url if detail else "") or slice_item.url
    return {
        "activity_id": stable_activity_id(title, start_time, url),
        "title": title,
        "organizer": organizer,
        "start_time": start_time,
        "end_time": "",
        "location": location,
        "url": url,
        "category": category,
        "source": slice_item.source,
        "channel": slice_item.channel,
        "publish_time": slice_item.publish_time,
        "abstract": abstract[:500],
        "confidence": round(min(confidence, 0.95), 2),
        "topped": slice_item.topped,
        "in_fav": slice_item.in_fav,
    }


def stable_activity_id(title: str, start_time: str, url: str) -> str:
    digest = hashlib.sha1(f"{title}|{start_time}|{url}".encode("utf-8")).hexdigest()[:12]
    return f"act_{digest}"


def extract_labeled_value(text: str, labels: tuple[str, ...]) -> str:
    for label in labels:
        pattern = rf"{re.escape(label)}\s*[:：]\s*([^\n。；;]+)"
        match = re.search(pattern, text)
        if match:
            value = re.sub(r"\s+", " ", match.group(1)).strip()
            value = _trim_labeled_value(value)
            if value:
                return value
    return ""


def _trim_labeled_value(value: str) -> str:
    value = re.split(
        r"\s*(?:活动时间|讲座时间|报名时间|时间|活动对象|活动规则|参与方式|展览简介|主办单位|主办方|承办单位|承办|联系人|联系方式)\s*[:：]",
        value,
        maxsplit=1,
    )[0].strip()
    value = re.split(r"\s+(?:活动时间|讲座时间|报名时间|活动对象|活动规则|参与方式|展览简介)[:：]?", value, maxsplit=1)[0].strip()
    if re.search(r"(线上线下相结合|视频直播|回放观看|点击|扫描|登录|报名方式)", value):
        return ""
    return value[:80].strip(" ，,；;")


def extract_datetime(text: str, publish_time_hint: str = "") -> str:
    now = datetime.now(CN_TZ)
    explicit = re.search(
        r"((20\d{2})[年./-]\s*(\d{1,2})[月./-]\s*(\d{1,2})日?)"
        r"(?:[^\d]{0,8}([01]?\d|2[0-3])[:：点]([0-5]\d)?分?)?",
        text,
    )
    if explicit:
        year = int(explicit.group(2))
        month = int(explicit.group(3))
        day = int(explicit.group(4))
        hour = int(explicit.group(5) or 0)
        minute = int(explicit.group(6) or 0)
        return datetime(year, month, day, hour, minute, tzinfo=CN_TZ).isoformat()

    cn_date = re.search(
        r"(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]"
        r"(?:[^\d]{0,8}([01]?\d|2[0-3])[:：点]([0-5]\d)?分?)?",
        text,
    )
    if cn_date:
        year = infer_year(int(cn_date.group(1)), int(cn_date.group(2)), publish_time_hint, now.date())
        hour = int(cn_date.group(3) or 0)
        minute = int(cn_date.group(4) or 0)
        return datetime(year, int(cn_date.group(1)), int(cn_date.group(2)), hour, minute, tzinfo=CN_TZ).isoformat()

    iso = re.search(r"20\d{2}-\d{1,2}-\d{1,2}(?:[ T]\d{1,2}:\d{2})?", text)
    if iso:
        normalized = iso.group(0).replace(" ", "T")
        if "T" not in normalized:
            normalized += "T00:00"
        try:
            parsed = datetime.fromisoformat(normalized)
            return parsed.replace(tzinfo=CN_TZ).isoformat()
        except ValueError:
            return ""
    return ""


def infer_year(month: int, day: int, publish_time: str, today: date) -> int:
    year = today.year
    match = re.search(r"(20\d{2})[./-](\d{1,2})[./-](\d{1,2})", publish_time)
    if match:
        year = int(match.group(1))
    candidate = date(year, month, day)
    if candidate < today - timedelta(days=60):
        year += 1
    return year


def classify_activity(title: str, abstract: str, channel: str) -> str:
    text = f"{title} {abstract}"
    if "招聘" in text or "就业" in text or channel in {"LM_BYJYXX", "LM_JYZPXX"}:
        return "career"
    if "讲座" in text or "报告" in text or "论坛" in text or "seminar" in text.lower():
        return "lecture"
    if "报名" in text or "招募" in text or "大赛" in text:
        return "registration"
    if "演出" in text or "展览" in text or "艺术" in text:
        return "culture"
    if channel == "LM_HB":
        return "poster"
    return "campus"


def looks_activity_related(item: CampusNewsSlice, keywords: list[str]) -> bool:
    text = f"{item.title} {item.source} {item.channel}"
    if item.channel in {"LM_HB", "LM_XJ_XSSQDT", "LM_JYGG", "LM_KYTZ", "LM_XJ_XTWBGTZ"}:
        return True
    return any(keyword and keyword in text for keyword in keywords)


def dedupe_activities(activities: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_url: dict[str, dict[str, Any]] = {}
    by_key: dict[tuple[str, str], dict[str, Any]] = {}
    for activity in activities:
        url = str(activity.get("url", "")).strip()
        key = (str(activity.get("title", "")).strip(), str(activity.get("start_time", "")).strip()[:10])
        existing = by_url.get(url) if url else by_key.get(key)
        if existing is None or float(activity.get("confidence", 0)) > float(existing.get("confidence", 0)):
            if url:
                by_url[url] = activity
            by_key[key] = activity
    deduped = list({item["activity_id"]: item for item in list(by_url.values()) + list(by_key.values())}.values())
    return sorted(deduped, key=activity_sort_key)


def activity_sort_key(activity: dict[str, Any]) -> tuple[int, str, str]:
    start_time = str(activity.get("start_time", ""))
    return (0 if start_time else 1, start_time or str(activity.get("publish_time", "")), str(activity.get("title", "")))


def filter_activities(
    activities: list[dict[str, Any]],
    keywords: list[str],
    start_date: str = "",
    end_date: str = "",
    limit: int = 10,
) -> list[dict[str, Any]]:
    start_bound = parse_date_bound(start_date)
    end_bound = parse_date_bound(end_date)
    filtered: list[dict[str, Any]] = []
    for activity in activities:
        haystack = " ".join(str(activity.get(key, "")) for key in ("title", "organizer", "location", "category", "abstract"))
        if keywords and not any(keyword in haystack for keyword in keywords):
            continue
        start_time = str(activity.get("start_time", ""))
        if start_time:
            item_date = datetime.fromisoformat(start_time).date()
            if start_bound and item_date < start_bound:
                continue
            if end_bound and item_date > end_bound:
                continue
        filtered.append(activity)
    return filtered[:limit]


def parse_date_bound(raw: str) -> date | None:
    if not raw:
        return None
    text = raw.strip()
    for fmt in ("%Y-%m-%d", "%Y%m%d"):
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            pass
    return None


def summarize_activities(activities: list[dict[str, Any]], warnings: list[str]) -> dict[str, Any]:
    if not activities:
        return {
            "summary": "暂未找到可整理的校园活动信息。",
            "highlights": [],
            "missing_fields": {},
            "warnings": warnings,
        }

    with_time = [item for item in activities if item.get("start_time")]
    categories: dict[str, int] = {}
    missing_fields = {"start_time": 0, "location": 0, "organizer": 0}
    for item in activities:
        categories[str(item.get("category", "campus"))] = categories.get(str(item.get("category", "campus")), 0) + 1
        for field in missing_fields:
            if not item.get(field):
                missing_fields[field] += 1
    main_categories = "、".join(name for name, _ in sorted(categories.items(), key=lambda pair: pair[1], reverse=True)[:3])
    first = with_time[0] if with_time else activities[0]
    first_desc = first["title"]
    if first.get("start_time"):
        first_desc = f"{first['start_time'][:16]} 的 {first_desc}"
    if first.get("location"):
        first_desc += f"，地点 {first['location']}"
    summary = f"共整理到 {len(activities)} 条校园动态/活动信息，其中 {len(with_time)} 条有明确活动时间。"
    if main_categories:
        summary += f"主题主要集中在 {main_categories}。"
    summary += f"优先关注：{first_desc}。"
    return {
        "summary": summary,
        "highlights": activities[:3],
        "missing_fields": missing_fields,
        "warnings": warnings,
    }


def answer_activity_query(query: str, activities: list[dict[str, Any]], limit: int = 5) -> dict[str, Any]:
    question = query.strip()
    if not question or not activities:
        return {"answer": "", "evidence": [], "matched_activities": []}

    terms = _query_terms(question)
    scored = []
    for activity in activities:
        score, evidence = _score_activity(activity, terms)
        if score > 0:
            scored.append((score, activity, evidence))
    scored.sort(key=lambda item: (-item[0], activity_sort_key(item[1])))
    selected = scored[: max(1, limit)]

    evidence_items = []
    matched = []
    for score, activity, snippets in selected:
        evidence_items.append(
            {
                "activity_id": activity.get("activity_id", ""),
                "title": activity.get("title", ""),
                "url": activity.get("url", ""),
                "score": round(score, 3),
                "snippets": snippets[:3],
            }
        )
        matched.append(activity)

    if not matched:
        return {
            "answer": f"没有在已抓取的校园动态中找到与“{question}”明显相关的活动。",
            "evidence": [],
            "matched_activities": [],
        }

    lead = matched[0]
    lines = [f"针对“{question}”，在已抓取的校园动态中找到 {len(matched)} 条较相关信息。"]
    focus = _activity_brief(lead)
    if focus:
        lines.append(f"最值得先看的是：{focus}。")
    if len(matched) > 1:
        more = "；".join(_activity_brief(item) for item in matched[1:3] if _activity_brief(item))
        if more:
            lines.append(f"另外可以关注：{more}。")

    return {
        "answer": "".join(lines),
        "evidence": evidence_items,
        "matched_activities": matched,
    }


def _query_terms(query: str) -> list[str]:
    normalized = query.lower()
    terms = [item for item in re.split(r"[\s,，。；;：:、？?！!（）()【】\[\]\"']+", normalized) if len(item) >= 2]
    phrase_bank = (
        "ai",
        "人工智能",
        "大模型",
        "机器学习",
        "讲座",
        "论坛",
        "沙龙",
        "报名",
        "比赛",
        "大赛",
        "竞赛",
        "就业",
        "招聘",
        "实习",
        "展览",
        "艺术",
        "音乐",
        "线上",
        "线下",
        "本科生",
        "研究生",
        "博士后",
    )
    terms.extend(phrase for phrase in phrase_bank if phrase in normalized)
    cjk = re.sub(r"[^\u4e00-\u9fffA-Za-z0-9]+", "", query)
    if len(cjk) <= 12:
        terms.extend(cjk[idx : idx + size] for size in (2, 3) for idx in range(0, max(len(cjk) - size + 1, 0)))
    counts = Counter(term for term in terms if term)
    return [term for term, _ in counts.most_common()]


def _score_activity(activity: dict[str, Any], terms: list[str]) -> tuple[float, list[str]]:
    weighted_fields = (
        ("title", 4.0),
        ("category", 2.0),
        ("organizer", 1.8),
        ("source", 1.5),
        ("location", 1.5),
        ("abstract", 1.0),
    )
    score = 0.0
    evidence: list[str] = []
    for field, weight in weighted_fields:
        text = str(activity.get(field, "") or "")
        lower = text.lower()
        hits = [term for term in terms if term and term in lower]
        if not hits:
            continue
        score += weight * len(set(hits))
        snippet = _field_snippet(field, text, hits[0])
        if snippet:
            evidence.append(snippet)
    if score > 0 and activity.get("start_time"):
        score += 0.2
    return score, evidence


def _field_snippet(field: str, text: str, term: str) -> str:
    clean = re.sub(r"\s+", " ", text).strip()
    if not clean:
        return ""
    if len(clean) <= 160:
        return f"{field}: {clean}"
    index = clean.lower().find(term.lower())
    if index < 0:
        return f"{field}: {clean[:160]}"
    start = max(0, index - 60)
    end = min(len(clean), index + 100)
    prefix = "..." if start > 0 else ""
    suffix = "..." if end < len(clean) else ""
    return f"{field}: {prefix}{clean[start:end]}{suffix}"


def _activity_brief(activity: dict[str, Any]) -> str:
    parts = []
    if activity.get("start_time"):
        parts.append(str(activity["start_time"])[:16])
    parts.append(str(activity.get("title", "")).strip())
    if activity.get("location"):
        parts.append(f"地点 {activity['location']}")
    if activity.get("organizer"):
        parts.append(f"主办 {activity['organizer']}")
    return "，".join(part for part in parts if part)
