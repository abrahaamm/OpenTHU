from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any, Callable
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen

try:
    from ..skill_core import SkillHandler, SkillInvocation, SkillRegistry, SkillResult
except ImportError:
    import os
    import sys

    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from skill_core import SkillHandler, SkillInvocation, SkillRegistry, SkillResult


LEARN_BASE_URL = "https://learn.tsinghua.edu.cn"
WEBVPN_JXRL_BKS_PREFIX = (
    "https://webvpn.tsinghua.edu.cn/http/"
    "77726476706e69737468656265737421eaff4b8b69336153301c9aa596522b20bc86e6e559a9b290/"
    "jxmh_out.do?m=bks_jxrl_all&p_start_date="
)
WEBVPN_JXRL_YJS_PREFIX = (
    "https://webvpn.tsinghua.edu.cn/http/"
    "77726476706e69737468656265737421eaff4b8b69336153301c9aa596522b20bc86e6e559a9b290/"
    "jxmh_out.do?m=yjs_jxrl_all&p_start_date="
)
WEBVPN_JXRL_MIDDLE = "&p_end_date="
WEBVPN_JXRL_SUFFIX = "&jsoncallback=m"
WEBVPN_SECONDARY_URL = (
    "https://webvpn.tsinghua.edu.cn/http/"
    "77726476706e69737468656265737421eaff4b8b69336153301c9aa596522b20bc86e6e559a9b290/"
    "portal3rd.do?m=bks_ejkbSearch"
)

PERIOD_STARTS = {
    "08:00": 1,
    "08:50": 2,
    "09:50": 3,
    "10:40": 4,
    "11:30": 5,
    "13:30": 6,
    "14:20": 7,
    "15:20": 8,
    "16:10": 9,
    "17:05": 10,
    "17:55": 11,
    "19:20": 12,
    "20:10": 13,
    "21:00": 14,
}
PERIOD_ENDS = {
    "08:45": 1,
    "09:35": 2,
    "10:35": 3,
    "11:25": 4,
    "12:15": 5,
    "14:15": 6,
    "15:05": 7,
    "16:05": 8,
    "16:55": 9,
    "17:50": 10,
    "18:40": 11,
    "20:05": 12,
    "20:55": 13,
    "21:45": 14,
}
SECONDARY_STARTS = ["08:00", "09:50", "13:30", "15:20", "17:05", "19:20"]
SECONDARY_ENDS = ["09:35", "12:15", "15:05", "16:55", "18:40", "21:45"]


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _result(
    invocation: SkillInvocation,
    code: str,
    data: dict[str, Any],
    source: str,
) -> SkillResult:
    return SkillResult(
        skill_name=invocation.skill_name,
        request_id=invocation.request_id,
        code=code,
        data=data,
        from_cache=False,
        fetched_at=_utc_now(),
        source=source,
    )


def _first_nonblank(*values: Any) -> str:
    for value in values:
        text = str(value or "").strip()
        if text:
            return text
    return ""


def _normalize_base_url(value: Any) -> str:
    text = str(value or "").strip().rstrip("/")
    return text or LEARN_BASE_URL


def _cookie_header_from_value(value: Any) -> str:
    if isinstance(value, dict):
        parts = []
        for key, raw in value.items():
            name = str(key or "").strip()
            content = str(raw or "").strip()
            if name and content:
                parts.append(f"{name}={content}")
        return "; ".join(parts)
    if isinstance(value, str):
        return _normalize_cookie_header(value)
    return ""


def _normalize_cookie_header(raw: str) -> str:
    return "; ".join(token.strip() for token in raw.split(";") if "=" in token)


def _learn_cookie_from_session(session: dict[str, Any]) -> str:
    cookie = _first_nonblank(
        session.get("homework_cookie"),
        session.get("learn_cookie"),
        session.get("session_cookie"),
        _cookie_header_from_value(session.get("cookies")),
    )
    if cookie:
        return _normalize_cookie_header(cookie)
    fallback = _cookie_header_from_value(session.get("cookie"))
    if "JSESSIONID=" in fallback or "XSRF-TOKEN=" in fallback:
        return fallback
    return ""


def _webvpn_cookie_from_session(session: dict[str, Any]) -> str:
    return _normalize_cookie_header(
        _first_nonblank(
            session.get("webvpn_cookie"),
            session.get("info_cookie"),
            session.get("cookie"),
        ),
    )


def _csrf_from_session(session: dict[str, Any]) -> str:
    cookie = _learn_cookie_from_session(session)
    return _first_nonblank(
        session.get("csrf_token"),
        session.get("csrf"),
        session.get("homework_csrf"),
        session.get("learn_csrf"),
        _extract_cookie_value(cookie, "XSRF-TOKEN"),
    )


def _extract_cookie_value(cookie_header: str, cookie_name: str) -> str:
    prefix = f"{cookie_name}="
    for part in cookie_header.split(";"):
        token = part.strip()
        if token.startswith(prefix):
            return token.split("=", 1)[1].strip()
    return ""


def _decode_response(raw: bytes, content_type: str = "") -> str:
    charset = ""
    match = re.search(r"charset=([\w.-]+)", content_type or "", re.I)
    if match:
        charset = match.group(1)
    for candidate in [charset, "utf-8", "gb18030"]:
        if not candidate:
            continue
        try:
            return raw.decode(candidate)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


class CourseInfoHttpClient:
    def __init__(self, session: dict[str, Any]) -> None:
        self.session = session
        self.learn_base_url = _normalize_base_url(session.get("learn_base_url"))
        self.learn_cookie = _learn_cookie_from_session(session)
        self.webvpn_cookie = _webvpn_cookie_from_session(session)
        self.csrf_token = _csrf_from_session(session)

    def get_learn_json(
        self,
        path: str,
        params: dict[str, Any] | None = None,
    ) -> Any:
        text = self.get_learn_text(path, params=params, accept="application/json, text/plain, */*")
        return json.loads(text)

    def get_learn_text(
        self,
        path: str,
        params: dict[str, Any] | None = None,
        accept: str = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    ) -> str:
        url = path if path.startswith("http") else urljoin(f"{self.learn_base_url}/", path.lstrip("/"))
        return self._request_text(url, cookie_header=self.learn_cookie, params=params, accept=accept)

    def get_webvpn_text(self, url: str) -> str:
        return self._request_text(url, cookie_header=self.webvpn_cookie, accept="text/plain,*/*")

    def fetch_learn_csrf(self) -> str:
        if self.csrf_token:
            return self.csrf_token
        html = self.get_learn_text("/f/wlxt/index/course/student/index")
        match = re.search(r"_csrf=([\w-]+)", html)
        if match:
            self.csrf_token = match.group(1)
        return self.csrf_token

    def _request_text(
        self,
        url: str,
        cookie_header: str,
        params: dict[str, Any] | None = None,
        accept: str = "*/*",
    ) -> str:
        if params:
            cleaned = {key: value for key, value in params.items() if value not in (None, "")}
            if cleaned:
                separator = "&" if "?" in url else "?"
                url = f"{url}{separator}{urlencode(cleaned)}"
        headers = {
            "Accept": accept,
            "User-Agent": "OpenTHU-Agent/1.0",
        }
        if cookie_header:
            headers["Cookie"] = cookie_header
        request = Request(url, headers=headers, method="GET")
        with urlopen(request, timeout=20) as response:
            raw = response.read()
            return _decode_response(raw, response.headers.get("Content-Type", ""))


ClientFactory = Callable[[dict[str, Any]], CourseInfoHttpClient]


@dataclass
class SemesterInfo:
    semester_id: str
    semester_name: str
    start_date: str
    end_date: str
    first_day: str
    week_count: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "semester_id": self.semester_id,
            "semester_name": self.semester_name,
            "start_date": self.start_date,
            "end_date": self.end_date,
            "first_day": self.first_day,
            "week_count": self.week_count,
        }


def _parse_date(value: Any) -> date | None:
    text = str(value or "").strip()
    if not text:
        return None
    for fmt in ("%Y-%m-%d", "%Y/%m/%d", "%Y%m%d"):
        try:
            return datetime.strptime(text[:10] if fmt != "%Y%m%d" else text[:8], fmt).date()
        except ValueError:
            continue
    return None


def _aligned_monday(start_date: date) -> date:
    weekday = start_date.weekday()
    if weekday == 5:
        return start_date + timedelta(days=2)
    if weekday == 6:
        return start_date + timedelta(days=1)
    return start_date - timedelta(days=weekday)


def _parse_semester(raw: Any) -> SemesterInfo | None:
    if not isinstance(raw, dict):
        text = str(raw or "").strip()
        if not text:
            return None
        return SemesterInfo(
            semester_id=text,
            semester_name=text,
            start_date="",
            end_date="",
            first_day="",
            week_count=0,
        )
    semester_id = _first_nonblank(raw.get("id"), raw.get("xnxq"), raw.get("semester_id"))
    if not semester_id:
        return None
    start = _parse_date(_first_nonblank(raw.get("kssj"), raw.get("start_date"), raw.get("startDate")))
    end = _parse_date(_first_nonblank(raw.get("jssj"), raw.get("end_date"), raw.get("endDate")))
    first_day = _aligned_monday(start) if start else None
    week_count = ((end - first_day).days // 7 + 1) if start and end and first_day else 0
    return SemesterInfo(
        semester_id=semester_id,
        semester_name=_first_nonblank(raw.get("xnxqmc"), raw.get("name"), raw.get("semester_name"), semester_id),
        start_date=start.isoformat() if start else "",
        end_date=end.isoformat() if end else "",
        first_day=first_day.isoformat() if first_day else "",
        week_count=max(week_count, 0),
    )


def _extract_list(raw: Any, *paths: tuple[str, ...] | str) -> list[Any]:
    if isinstance(raw, list):
        return raw
    for path in paths:
        keys = (path,) if isinstance(path, str) else path
        node = raw
        for key in keys:
            if not isinstance(node, dict):
                node = None
                break
            node = node.get(key)
        if isinstance(node, list):
            return node
    return []


def _parse_jsonp_list(text: str) -> list[Any]:
    stripped = text.strip()
    start = stripped.find("[")
    end = stripped.rfind("]")
    if start < 0 or end < start:
        return []
    try:
        parsed = json.loads(stripped[start : end + 1])
        return parsed if isinstance(parsed, list) else []
    except json.JSONDecodeError:
        return []


def _weekday(value: Any) -> int | None:
    text = str(value or "").strip()
    if not text:
        return None
    mapping = {
        "一": 1,
        "二": 2,
        "三": 3,
        "四": 4,
        "五": 5,
        "六": 6,
        "日": 7,
        "天": 7,
        "mon": 1,
        "tue": 2,
        "wed": 3,
        "thu": 4,
        "fri": 5,
        "sat": 6,
        "sun": 7,
    }
    if text.isdigit():
        number = int(text)
        return number if 1 <= number <= 7 else None
    lower = text.lower()
    for token, number in mapping.items():
        if token in lower:
            return number
    return None


def _period_pair(start: Any, end: Any, fallback: Any = "") -> list[int]:
    start_text = str(start or "").strip()
    end_text = str(end or "").strip()
    if start_text.isdigit() and end_text.isdigit():
        return [int(start_text), int(end_text)]
    fallback_text = str(fallback or "").strip()
    match = re.search(r"(\d{1,2})\s*[-~至,，]\s*(\d{1,2})\s*节?", fallback_text)
    if match:
        return [int(match.group(1)), int(match.group(2))]
    match = re.search(r"第\s*(\d{1,2})\s*节", fallback_text)
    if match:
        period = int(match.group(1))
        return [period, period]
    return []


def _time_period_pair(start_time: str, end_time: str) -> list[int]:
    start = PERIOD_STARTS.get(start_time[:5])
    end = PERIOD_ENDS.get(end_time[:5])
    if start and end:
        return [start, end]
    return []


def _clean_text(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


def _normalize_time_location_dict(raw: dict[str, Any]) -> dict[str, Any] | None:
    weekday = _weekday(_first_nonblank(raw.get("weekday"), raw.get("dayOfWeek"), raw.get("xqj"), raw.get("skxq")))
    period = _period_pair(
        _first_nonblank(raw.get("start_period"), raw.get("startPeriod"), raw.get("ksjc")),
        _first_nonblank(raw.get("end_period"), raw.get("endPeriod"), raw.get("jsjc")),
        _first_nonblank(raw.get("period"), raw.get("jc"), raw.get("jcdm"), raw.get("sksj")),
    )
    location = _clean_text(_first_nonblank(raw.get("location"), raw.get("jxdd"), raw.get("dd"), raw.get("skdd")))
    weeks = _clean_text(_first_nonblank(raw.get("weeks"), raw.get("zc"), raw.get("zcd"), raw.get("week")))
    if not any([weekday, period, location, weeks]):
        return None
    item: dict[str, Any] = {}
    if weekday:
        item["weekday"] = weekday
    if period:
        item["period"] = period
    if location:
        item["location"] = location
    if weeks:
        item["weeks"] = weeks
    return item


def _parse_time_location_text(text: str) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for segment in re.split(r"[;\n]+", text):
        raw = _clean_text(segment)
        if not raw:
            continue
        weekday = _weekday(raw)
        period = _period_pair("", "", raw)
        location = ""
        location_match = re.search(r"(?:地点|教室)[:：]\s*([^,，;；]+)", raw)
        if location_match:
            location = location_match.group(1).strip()
        elif "@" in raw:
            location = raw.rsplit("@", 1)[1].strip()
        else:
            tail = re.split(r"节|周", raw)[-1].strip(" ,，()（）")
            if tail and not re.fullmatch(r"[\d\-.~至,，单双前后八全]+", tail):
                location = tail
        item: dict[str, Any] = {"raw_text": raw}
        if weekday:
            item["weekday"] = weekday
        if period:
            item["period"] = period
        if location:
            item["location"] = location
        results.append(item)
    return results


def _dedupe_time_locations(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[str] = set()
    deduped: list[dict[str, Any]] = []
    for item in items:
        key = json.dumps(item, ensure_ascii=False, sort_keys=True)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(item)
    return deduped


def _extract_time_and_location(raw: dict[str, Any]) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    for key in ("timeAndLocation", "time_and_location", "sjddbList", "sjddList", "sjs", "sjdd"):
        value = raw.get(key)
        if isinstance(value, list):
            for child in value:
                if isinstance(child, dict):
                    parsed = _normalize_time_location_dict(child)
                    if parsed:
                        items.append(parsed)
                elif isinstance(child, str):
                    items.extend(_parse_time_location_text(child))
        elif isinstance(value, dict):
            parsed = _normalize_time_location_dict(value)
            if parsed:
                items.append(parsed)
        elif isinstance(value, str):
            items.extend(_parse_time_location_text(value))
    for key in ("sjddb", "sksjdd", "sksj", "timeLocation"):
        value = str(raw.get(key, "") or "").strip()
        if value:
            items.extend(_parse_time_location_text(value))
    return _dedupe_time_locations(items)


def _normalize_course(raw: dict[str, Any], semester_id: str) -> dict[str, Any] | None:
    course_id = _first_nonblank(raw.get("wlkcid"), raw.get("course_id"), raw.get("id"))
    name = _first_nonblank(raw.get("kcm"), raw.get("name"), raw.get("course_name"))
    if not course_id and not name:
        return None
    return {
        "course_id": course_id,
        "id": course_id,
        "course_number": _first_nonblank(raw.get("kch"), raw.get("course_number")),
        "course_index": _first_nonblank(raw.get("kxh"), raw.get("course_index")),
        "name": name,
        "chinese_name": _first_nonblank(raw.get("kcm"), raw.get("chinese_name"), name),
        "english_name": _first_nonblank(raw.get("ywkcm"), raw.get("english_name")),
        "teacher_number": _first_nonblank(raw.get("jsgh"), raw.get("teacher_number")),
        "teacher_name": _first_nonblank(raw.get("jsm"), raw.get("teacherName"), raw.get("teacher_name")),
        "time_and_location": _extract_time_and_location(raw),
        "semester_id": semester_id,
    }


def _entry_from_primary(raw: dict[str, Any], first_day: str = "") -> dict[str, Any] | None:
    name = _clean_text(raw.get("nr"))
    day = _clean_text(raw.get("nq"))
    start_time = _clean_text(raw.get("kssj")).replace("：", ":")
    end_time = _clean_text(raw.get("jssj")).replace("：", ":")
    if not name or not day or not start_time or not end_time:
        return None
    begin = f"{day} {start_time[:5]}"
    end = f"{day} {end_time[:5]}"
    day_value = _parse_date(day)
    first_value = _parse_date(first_day)
    week = ((day_value - first_value).days // 7 + 1) if day_value and first_value else 0
    weekday = day_value.isoweekday() if day_value else 0
    return {
        "name": name,
        "location": _clean_text(raw.get("dd")),
        "category": _clean_text(raw.get("fl")),
        "type": "primary",
        "date": day,
        "weekday": weekday,
        "week": week,
        "start_time": begin,
        "end_time": end,
        "period": _time_period_pair(start_time[:5], end_time[:5]),
        "source_id": _first_nonblank(raw.get("grrlID"), raw.get("id")),
    }


def _schedule_summary(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[str, dict[str, Any]] = {}
    for entry in entries:
        name = _clean_text(entry.get("name"))
        location = _clean_text(entry.get("location"))
        category = _clean_text(entry.get("category"))
        key = f"{name}\0{location}\0{category}"
        group = grouped.setdefault(
            key,
            {
                "name": name,
                "location": location,
                "category": category,
                "occurrence_count": 0,
                "weeks": [],
                "time_and_location": [],
            },
        )
        group["occurrence_count"] += 1
        week = entry.get("week")
        if isinstance(week, int) and week > 0 and week not in group["weeks"]:
            group["weeks"].append(week)
        block = {
            "weekday": entry.get("weekday"),
            "period": entry.get("period", []),
            "location": location,
        }
        if block not in group["time_and_location"]:
            group["time_and_location"].append(block)
    for group in grouped.values():
        group["weeks"] = sorted(group["weeks"])
    return list(grouped.values())


def _dedupe_schedule_entries(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[str] = set()
    deduped: list[dict[str, Any]] = []
    for entry in entries:
        key = json.dumps(entry, ensure_ascii=False, sort_keys=True)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(entry)
    return deduped


class _BaseCourseInfoSkill(SkillHandler):
    def __init__(self, client_factory: ClientFactory = CourseInfoHttpClient) -> None:
        self.client_factory = client_factory

    def _client_or_error(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        *,
        require_learn: bool = True,
        require_webvpn: bool = False,
    ) -> tuple[CourseInfoHttpClient | None, SkillResult | None]:
        client = self.client_factory(session)
        if require_learn and not client.learn_cookie:
            return None, _result(
                invocation,
                "NOT_CONFIGURED",
                {
                    "status": "not_configured",
                    "message": "Learn cookie is not configured. Use the WebView login flow or set homework_cookie/learn_cookie.",
                },
                "course_info_auth",
            )
        if require_webvpn and not client.webvpn_cookie:
            return None, _result(
                invocation,
                "NOT_CONFIGURED",
                {
                    "status": "not_configured",
                    "message": "WebVPN cookie is not configured, so the full teaching-calendar schedule cannot be fetched.",
                },
                "course_info_auth",
            )
        return client, None


class GetSemestersSkill(_BaseCourseInfoSkill):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        client, error = self._client_or_error(invocation, session)
        if error:
            return error
        assert client is not None
        try:
            csrf = client.csrf_token
            if not csrf:
                try:
                    csrf = client.fetch_learn_csrf()
                except Exception:
                    csrf = ""
            current_raw = client.get_learn_json(
                "/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester",
                params={"_csrf": csrf} if csrf else None,
            )
            all_raw: Any = []
            try:
                all_raw = client.get_learn_json("/b/wlxt/kc/v_wlkc_xs_xktjb_coassb/queryxnxq")
            except Exception:
                all_raw = []

            current = _parse_semester(current_raw.get("result") if isinstance(current_raw, dict) else None)
            next_semesters = []
            if isinstance(current_raw, dict):
                next_semesters = [_parse_semester(item) for item in current_raw.get("resultList", [])]
            all_semesters = [_parse_semester(item) for item in _extract_list(all_raw)]

            merged: list[SemesterInfo] = []
            seen: set[str] = set()
            for semester in [current, *next_semesters, *all_semesters]:
                if semester is None or semester.semester_id in seen:
                    continue
                seen.add(semester.semester_id)
                merged.append(semester)

            return _result(
                invocation,
                "OK",
                {
                    "status": "ok",
                    "current_semester": current.semester_id if current else "",
                    "semesters": [semester.to_dict() for semester in merged],
                    "semester_count": len(merged),
                },
                client.learn_base_url,
            )
        except Exception as exc:
            return _result(
                invocation,
                "SKILL_EXECUTION_FAILED",
                {"status": "failed", "message": f"Failed to fetch semesters: {exc}"},
                client.learn_base_url,
            )


def _current_semester_id(client: CourseInfoHttpClient, state: dict[str, Any]) -> str:
    state_semester = _first_nonblank(state.get("semester_id"))
    if state_semester:
        return state_semester
    csrf = client.csrf_token
    try:
        if not csrf:
            csrf = client.fetch_learn_csrf()
        raw = client.get_learn_json(
            "/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester",
            params={"_csrf": csrf} if csrf else None,
        )
        if isinstance(raw, dict):
            semester = _parse_semester(raw.get("result"))
            if semester:
                return semester.semester_id
    except Exception:
        return ""
    return ""


def _fetch_courses(
    client: CourseInfoHttpClient,
    semester_id: str,
    *,
    lang: str,
    include_detail: bool,
) -> list[dict[str, Any]]:
    raw = client.get_learn_json(
        f"/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/{semester_id}/{lang}",
        params={"timestamp": int(datetime.now().timestamp() * 1000)},
    )
    items = _extract_list(raw, "resultList", "result", "data", ("object", "aaData"))
    courses = []
    for item in items:
        if not isinstance(item, dict):
            continue
        course = _normalize_course(item, semester_id)
        if course is None:
            continue
        if include_detail and course["course_id"]:
            try:
                detail_raw = client.get_learn_json("/b/kc/v_wlkc_xk_sjddb/detail", params={"id": course["course_id"]})
                detail_items = _extract_list(detail_raw, "resultList", "result", "data", ("object", "aaData"))
                detail_times: list[dict[str, Any]] = []
                for detail in detail_items:
                    if isinstance(detail, dict):
                        parsed = _normalize_time_location_dict(detail)
                        if parsed:
                            detail_times.append(parsed)
                course["time_and_location"] = _dedupe_time_locations(course["time_and_location"] + detail_times)
            except Exception:
                course.setdefault("warnings", []).append("course_detail_unavailable")
        courses.append(course)
    return courses


class GetCoursesSkill(_BaseCourseInfoSkill):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        client, error = self._client_or_error(invocation, session)
        if error:
            return error
        assert client is not None
        semester_id = _first_nonblank(invocation.args.get("semester_id"), state.get("semester_id"))
        if not semester_id:
            semester_id = _current_semester_id(client, state)
        if not semester_id:
            return _result(
                invocation,
                "INVALID_PARAM",
                {
                    "status": "invalid_param",
                    "message": "semester_id is required and current semester could not be resolved.",
                },
                client.learn_base_url,
            )
        lang = _first_nonblank(invocation.args.get("lang"), "zh_CN")
        include_detail = bool(invocation.args.get("include_schedule_detail", True))
        try:
            courses = _fetch_courses(client, semester_id, lang=lang, include_detail=include_detail)
            return _result(
                invocation,
                "OK",
                {
                    "status": "ok",
                    "semester_id": semester_id,
                    "courses": courses,
                    "course_count": len(courses),
                },
                client.learn_base_url,
            )
        except Exception as exc:
            return _result(
                invocation,
                "SKILL_EXECUTION_FAILED",
                {"status": "failed", "message": f"Failed to fetch courses: {exc}"},
                client.learn_base_url,
            )


def _semester_for_schedule(
    client: CourseInfoHttpClient,
    args: dict[str, Any],
    state: dict[str, Any],
) -> SemesterInfo | None:
    first_day = _first_nonblank(args.get("first_day"))
    week_count_raw = _first_nonblank(args.get("week_count"))
    semester_id = _first_nonblank(args.get("semester_id"), state.get("semester_id"))
    if first_day and week_count_raw.isdigit():
        return SemesterInfo(
            semester_id=semester_id,
            semester_name=semester_id,
            start_date="",
            end_date="",
            first_day=first_day,
            week_count=int(week_count_raw),
        )
    csrf = client.csrf_token
    if not csrf:
        csrf = client.fetch_learn_csrf()
    raw = client.get_learn_json(
        "/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester",
        params={"_csrf": csrf} if csrf else None,
    )
    if not isinstance(raw, dict):
        return None
    current = _parse_semester(raw.get("result"))
    if semester_id and isinstance(raw.get("resultList"), list):
        for item in raw["resultList"]:
            parsed = _parse_semester(item)
            if parsed and parsed.semester_id == semester_id:
                return parsed
    return current


def _fetch_primary_schedule(
    client: CourseInfoHttpClient,
    semester: SemesterInfo,
    graduate: bool,
) -> list[dict[str, Any]]:
    prefix = WEBVPN_JXRL_YJS_PREFIX if graduate else WEBVPN_JXRL_BKS_PREFIX
    first = _parse_date(semester.first_day)
    if first is None:
        return []
    entries: list[dict[str, Any]] = []
    group_size = 3
    groups = max((semester.week_count + group_size - 1) // group_size, 1)
    for index in range(groups):
        start = first + timedelta(days=index * group_size * 7)
        end = first + timedelta(days=((index + 1) * group_size - 1) * 7 + 6)
        url = f"{prefix}{start.strftime('%Y%m%d')}{WEBVPN_JXRL_MIDDLE}{end.strftime('%Y%m%d')}{WEBVPN_JXRL_SUFFIX}"
        for raw in _parse_jsonp_list(client.get_webvpn_text(url)):
            if isinstance(raw, dict):
                entry = _entry_from_primary(raw, semester.first_day)
                if entry:
                    entries.append(entry)
    return entries


def _parse_week_segment(segment: str) -> list[int]:
    weeks: list[int] = []
    for part in re.split(r"[,，~]", segment):
        item = part.strip()
        if not item:
            continue
        if "-" in item:
            left, right = item.split("-", 1)
            if left.isdigit() and right.isdigit() and int(left) <= int(right):
                weeks.extend(range(int(left), int(right) + 1))
        elif item.isdigit():
            weeks.append(int(item))
    return weeks


def _secondary_weeks(detail: str) -> list[int]:
    if "单周" in detail:
        return [1, 3, 5, 7, 9, 11, 13, 15]
    if "双周" in detail:
        return [2, 4, 6, 8, 10, 12, 14, 16]
    if "全周" in detail:
        return list(range(1, 17))
    if "前八周" in detail or "前8周" in detail:
        return list(range(1, 9))
    if "后八周" in detail or "后8周" in detail:
        return list(range(9, 17))
    match = re.search(r"第([\d\-~,，]+)周", detail)
    if match:
        return _parse_week_segment(match.group(1))
    match = re.search(r"Week([\d\-~,，]+)", detail, re.I)
    if match:
        return _parse_week_segment(match.group(1))
    return []


def _fetch_secondary_schedule(client: CourseInfoHttpClient, semester: SemesterInfo) -> list[dict[str, Any]]:
    text = client.get_webvpn_text(WEBVPN_SECONDARY_URL)
    lower_bound = text.find("function setInitValue")
    upper_bound = text.find("}", lower_bound)
    script = text[lower_bound:upper_bound] if lower_bound >= 0 and upper_bound > lower_bound else text
    regex = re.compile(
        r'"<span onmouseover=\\"return overlib\(\'(.+?)\'\);\\" onmouseout=\'return nd\(\);\'>(.+?)</span>";'
        r"[ \n\t\r]+?document\.getElementById\('(.+?)'\)\.innerHTML \+= strHTML\+\"<br>\";",
        re.S,
    )
    first = _parse_date(semester.first_day)
    if first is None:
        return []
    entries: list[dict[str, Any]] = []
    for match in regex.finditer(script):
        detail = re.sub(r"\s+", "", match.group(1))
        title = _clean_text(match.group(2))
        basic = match.group(3)
        if len(basic) < 4:
            continue
        try:
            session_index = int(basic[1]) - 1
            weekday = int(basic[3])
            start = SECONDARY_STARTS[session_index]
            end = SECONDARY_ENDS[session_index]
        except (ValueError, IndexError):
            continue
        location_match = re.search(r"[^(]+?\(([^，]+?)，.+?", detail)
        location = location_match.group(1) if location_match else ""
        for week in _secondary_weeks(detail):
            day = first + timedelta(days=(week - 1) * 7 + weekday - 1)
            entries.append(
                {
                    "name": title,
                    "location": location,
                    "category": "secondary",
                    "type": "secondary",
                    "date": day.isoformat(),
                    "weekday": weekday,
                    "week": week,
                    "start_time": f"{day.isoformat()} {start}",
                    "end_time": f"{day.isoformat()} {end}",
                    "period": _time_period_pair(start, end),
                }
            )
    return entries


class GetCourseScheduleSkill(_BaseCourseInfoSkill):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        client, error = self._client_or_error(invocation, session, require_learn=False)
        if error:
            return error
        assert client is not None
        graduate = bool(invocation.args.get("graduate", False))
        include_secondary = bool(invocation.args.get("include_secondary", not graduate))
        warnings: list[str] = []

        if client.webvpn_cookie:
            try:
                semester = _semester_for_schedule(client, invocation.args, state)
                if semester is None or not semester.first_day or not semester.week_count:
                    return _result(
                        invocation,
                        "INVALID_PARAM",
                        {
                            "status": "invalid_param",
                            "message": "first_day/week_count are required when current semester cannot be resolved.",
                        },
                        "course_schedule",
                    )
                entries = _fetch_primary_schedule(client, semester, graduate)
                if include_secondary and not graduate:
                    try:
                        entries.extend(_fetch_secondary_schedule(client, semester))
                    except Exception:
                        warnings.append("secondary_schedule_unavailable")
                entries = _dedupe_schedule_entries(entries)
                return _result(
                    invocation,
                    "OK",
                    {
                        "status": "ok",
                        "source": "webvpn_teaching_calendar",
                        "semester": semester.to_dict(),
                        "schedule_entries": entries,
                        "schedule_count": len(entries),
                        "courses": _schedule_summary(entries),
                        "warnings": warnings,
                    },
                    "webvpn.tsinghua.edu.cn",
                )
            except Exception as exc:
                warnings.append(f"webvpn_schedule_failed: {exc}")

        if not client.learn_cookie:
            return _result(
                invocation,
                "NOT_CONFIGURED",
                {
                    "status": "not_configured",
                    "message": "Neither WebVPN cookie nor Learn cookie is configured for course schedule crawling.",
                    "warnings": warnings,
                },
                "course_info_auth",
            )

        semester_id = _first_nonblank(invocation.args.get("semester_id"), state.get("semester_id"))
        if not semester_id:
            semester_id = _current_semester_id(client, state)
        if not semester_id:
            return _result(
                invocation,
                "INVALID_PARAM",
                {
                    "status": "invalid_param",
                    "message": "semester_id is required for Learn course schedule fallback.",
                    "warnings": warnings,
                },
                client.learn_base_url,
            )
        courses = _fetch_courses(
            client,
            semester_id,
            lang=_first_nonblank(invocation.args.get("lang"), "zh_CN"),
            include_detail=True,
        )
        return _result(
            invocation,
            "OK",
            {
                "status": "ok",
                "source": "learn_course_list",
                "semester_id": semester_id,
                "courses": courses,
                "course_count": len(courses),
                "warnings": warnings + ["WebVPN cookie missing; returned Learn course time/location blocks."],
            },
            client.learn_base_url,
        )


def register_course_info_handlers(registry: SkillRegistry) -> None:
    registry.register_handler("get_semesters", GetSemestersSkill())
    registry.register_handler("get_courses", GetCoursesSkill())
    registry.register_handler("get_course_schedule", GetCourseScheduleSkill())
