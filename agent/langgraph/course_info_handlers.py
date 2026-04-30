from __future__ import annotations

"""
course_info_handlers.py
-----------------------
Server-side Skill handlers for course information retrieval.

These handlers run on the Python Agent-Core server and call the Tsinghua
learn.tsinghua.edu.cn HTTP APIs directly (via an HTTP session injected in
AgentState["session"]).  They do NOT require the Kotlin bridge because all
operations are pure HTTP data fetches with no Android-side side-effects.

Registered skills:
  - get_user_info         : Fetch student profile from learn
  - get_semesters         : Fetch semester list + current semester
  - get_courses           : Fetch course list for a semester
  - get_notices           : Fetch course notices for a set of course IDs
  - get_files             : Fetch course files for a set of course IDs
  - get_assignments       : Fetch assignments / DDLs for a set of course IDs
  - get_academic_calendar : Fetch academic calendar events for a date range
  - get_campus_activities : Fetch campus activity information

Session model
-------------
AgentState["session"] is expected to carry:
  {
    "cookies": {"JSESSIONID": "<value>"},   # set by login skill
    "csrf_token": "<value>",                 # optional
  }

When cookies are absent the handler returns code="SESSION_REQUIRED".

All HTTP calls use the `requests` library (already a transitive dependency).
If `requests` is unavailable the handler returns code="DEPENDENCY_MISSING".
"""

import logging
import os
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urljoin

logger = logging.getLogger("course_info_handlers")

# ---------------------------------------------------------------------------
# Constants – base URLs as defined in docs/API_http.md
# ---------------------------------------------------------------------------
_LEARN_BASE = "https://learn.tsinghua.edu.cn"
_ID_BASE = "https://id.tsinghua.edu.cn"
_ZHJW_BASE = "https://zhjw.cic.tsinghua.edu.cn"

_DEFAULT_TIMEOUT = int(os.getenv("OPENTHU_HTTP_TIMEOUT_SEC", "15"))


# ---------------------------------------------------------------------------
# Helper utilities
# ---------------------------------------------------------------------------

def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _make_result(
    *,
    skill_name: str,
    request_id: str,
    code: str,
    data: dict[str, Any],
    source: str = "learn.tsinghua.edu.cn",
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


def _get_requests():
    """Import requests lazily and raise a descriptive error when absent."""
    try:
        import requests  # type: ignore[import]
        return requests
    except ImportError:
        return None


def _build_session_headers(session: dict[str, Any]) -> dict[str, str]:
    return {
        "User-Agent": "OpenTHU-Agent/1.0",
        "Accept": "application/json, text/plain, */*",
    }


def _build_requests_session(session: dict[str, Any]):
    """Create a requests.Session pre-loaded with JSESSIONID cookie."""
    requests = _get_requests()
    if requests is None:
        return None
    s = requests.Session()
    cookies = session.get("cookies", {})
    if isinstance(cookies, dict):
        for name, value in cookies.items():
            s.cookies.set(name, value, domain="learn.tsinghua.edu.cn")
    s.headers.update(_build_session_headers(session))
    return s


def _add_csrf(url: str, csrf_token: str) -> str:
    if not csrf_token:
        return url
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}_csrf={csrf_token}"


def _check_deps_and_session(skill_name: str, request_id: str, session: dict[str, Any]):
    """
    Returns (requests_module, session_obj, error_result).
    error_result is None when everything is OK.
    """
    requests = _get_requests()
    if requests is None:
        return None, None, _make_result(
            skill_name=skill_name,
            request_id=request_id,
            code="DEPENDENCY_MISSING",
            data={"status": "dependency_missing", "message": "requests library is not installed"},
        )
    cookies = session.get("cookies", {})
    if not cookies or not cookies.get("JSESSIONID"):
        return None, None, _make_result(
            skill_name=skill_name,
            request_id=request_id,
            code="SESSION_REQUIRED",
            data={
                "status": "session_required",
                "message": "JSESSIONID cookie is missing. Run the login skill first.",
            },
        )
    s = _build_requests_session(session)
    return requests, s, None


def _safe_json(response) -> dict[str, Any] | list | None:
    try:
        return response.json()
    except Exception:
        return None


def _extract_list(raw: Any, *keys: str) -> list:
    """Navigate nested dicts by a sequence of keys to find a list."""
    node = raw
    for key in keys:
        if not isinstance(node, dict):
            return []
        node = node.get(key)
    return node if isinstance(node, list) else []


# ---------------------------------------------------------------------------
# Base handler class
# ---------------------------------------------------------------------------

class _BaseCourseInfoHandler:
    """Shared interface for all course-info skill handlers."""

    def invoke(
        self,
        invocation: Any,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> Any:
        raise NotImplementedError

    def _ok(self, invocation: Any, data: dict[str, Any], source: str = "learn.tsinghua.edu.cn") -> Any:
        return _make_result(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data=data,
            source=source,
        )

    def _err(self, invocation: Any, code: str, message: str, extra: dict | None = None) -> Any:
        data: dict[str, Any] = {"status": code.lower(), "message": message}
        if extra:
            data.update(extra)
        return _make_result(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code=code,
            data=data,
        )

    def _http_err(self, invocation: Any, exc: Exception) -> Any:
        return self._err(
            invocation,
            "SKILL_EXECUTION_FAILED",
            f"HTTP request failed: {type(exc).__name__}: {exc}",
        )


# ---------------------------------------------------------------------------
# Individual skill handlers
# ---------------------------------------------------------------------------

class GetUserInfoHandler(_BaseCourseInfoHandler):
    """
    Fetch the authenticated student's basic profile.

    Returns: id, name, department
    Ref: docs/API_http.md §10
    """

    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        _, s, err = _check_deps_and_session(invocation.skill_name, invocation.request_id, session)
        if err:
            return err
        try:
            url = f"{_LEARN_BASE}/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/getUserInfo"
            resp = s.get(url, timeout=_DEFAULT_TIMEOUT)
            resp.raise_for_status()
            raw = _safe_json(resp)
            if not isinstance(raw, dict):
                return self._err(invocation, "SKILL_EXECUTION_FAILED", "Unexpected response format from getUserInfo")
            result_data = raw.get("resultList") or raw.get("result") or raw
            user: dict[str, Any] = {}
            if isinstance(result_data, dict):
                user = {
                    "id": str(result_data.get("xh", result_data.get("id", ""))),
                    "name": str(result_data.get("xm", result_data.get("name", ""))),
                    "department": str(result_data.get("dw", result_data.get("department", ""))).replace("(未译)", "").strip(),
                    "raw": result_data,
                }
            logger.info(
                "[get_user_info] request_id=%s user_id=%s name=%s",
                invocation.request_id,
                user.get("id", ""),
                user.get("name", ""),
            )
            return self._ok(invocation, {"user_info": user})
        except Exception as exc:
            logger.warning("[get_user_info] request_id=%s error=%s", invocation.request_id, exc)
            return self._http_err(invocation, exc)


class GetSemestersHandler(_BaseCourseInfoHandler):
    """
    Fetch the full semester list and identify the current semester.

    Returns: semesters (list of semester IDs), current_semester
    Ref: docs/API_http.md §4
    """

    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        _, s, err = _check_deps_and_session(invocation.skill_name, invocation.request_id, session)
        if err:
            return err
        try:
            # 4.1 All semesters
            all_url = f"{_LEARN_BASE}/b/wlxt/kc/v_wlkc_xs_xktjb_coassb/queryxnxq"
            all_resp = s.get(all_url, timeout=_DEFAULT_TIMEOUT)
            all_resp.raise_for_status()
            semesters: list[str] = _safe_json(all_resp) or []
            if not isinstance(semesters, list):
                semesters = []

            # 4.2 Current semester
            curr_url = f"{_LEARN_BASE}/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester"
            curr_resp = s.get(curr_url, timeout=_DEFAULT_TIMEOUT)
            curr_resp.raise_for_status()
            curr_raw = _safe_json(curr_resp)
            current_semester = ""
            if isinstance(curr_raw, dict):
                current_semester = str(
                    curr_raw.get("id")
                    or curr_raw.get("xnxq")
                    or curr_raw.get("currentSemester", {}).get("id", "")
                    or ""
                )

            logger.info(
                "[get_semesters] request_id=%s total=%d current=%s",
                invocation.request_id,
                len(semesters),
                current_semester,
            )
            return self._ok(invocation, {
                "semesters": semesters,
                "current_semester": current_semester,
            })
        except Exception as exc:
            logger.warning("[get_semesters] request_id=%s error=%s", invocation.request_id, exc)
            return self._http_err(invocation, exc)


class GetCoursesHandler(_BaseCourseInfoHandler):
    """
    Fetch the course list for a given semester.

    Args: semester_id (string) – e.g. "2024-2025-2"
    Returns: courses (list of Course dicts)
    Ref: docs/API_http.md §5
    """

    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        _, s, err = _check_deps_and_session(invocation.skill_name, invocation.request_id, session)
        if err:
            return err

        semester_id: str = str(invocation.args.get("semester_id", "")).strip()
        lang: str = str(invocation.args.get("lang", "zh_CN")).strip() or "zh_CN"

        if not semester_id:
            # Fall back to current semester from state when not supplied
            semester_id = state.get("semester_id", "")
        if not semester_id:
            return self._err(
                invocation,
                "INVALID_PARAM",
                "semester_id is required. Run get_semesters first to obtain the current semester ID.",
            )

        try:
            url = (
                f"{_LEARN_BASE}/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student"
                f"/loadCourseBySemesterId/{semester_id}/{lang}"
            )
            resp = s.get(url, timeout=_DEFAULT_TIMEOUT)
            resp.raise_for_status()
            raw = _safe_json(resp)

            courses: list[dict[str, Any]] = []
            raw_list = _extract_list(raw, "resultList") or _extract_list(raw, "result")
            if not raw_list and isinstance(raw, list):
                raw_list = raw
            for item in raw_list:
                if not isinstance(item, dict):
                    continue
                courses.append({
                    "id": str(item.get("wlkcid", item.get("id", ""))),
                    "name": str(item.get("kcm", item.get("name", ""))),
                    "chinese_name": str(item.get("kcm", "")),
                    "english_name": str(item.get("ywkcm", "")),
                    "teacher_number": str(item.get("jsgh", "")),
                    "teacher_name": str(item.get("jsm", item.get("teacherName", ""))),
                    "course_number": str(item.get("kch", "")),
                    "course_index": item.get("kxh", 0),
                    "semester_id": semester_id,
                    "raw": item,
                })

            logger.info(
                "[get_courses] request_id=%s semester=%s count=%d",
                invocation.request_id,
                semester_id,
                len(courses),
            )
            return self._ok(invocation, {
                "semester_id": semester_id,
                "courses": courses,
                "course_count": len(courses),
            })
        except Exception as exc:
            logger.warning("[get_courses] request_id=%s error=%s", invocation.request_id, exc)
            return self._http_err(invocation, exc)


class GetNoticesHandler(_BaseCourseInfoHandler):
    """
    Fetch notices (announcements) for a list of courses.

    Args: course_ids (list[string])
    Returns: notices (list of Notice dicts)
    Ref: docs/API_http.md §6
    """

    _NOTICE_URLS = [
        f"{_LEARN_BASE}/b/wlxt/kcgg/wlkc_ggb/student/pageListXsbyWgq",  # active
        f"{_LEARN_BASE}/b/wlxt/kcgg/wlkc_ggb/student/pageListXsbyYgq",  # expired
    ]

    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        _, s, err = _check_deps_and_session(invocation.skill_name, invocation.request_id, session)
        if err:
            return err

        course_ids: list[str] = [
            str(cid) for cid in invocation.args.get("course_ids", []) if cid
        ]
        if not course_ids:
            return self._err(invocation, "INVALID_PARAM", "course_ids must be a non-empty list")

        all_notices: list[dict[str, Any]] = []
        for course_id in course_ids:
            for url in self._NOTICE_URLS:
                try:
                    resp = s.get(url, params={"wlkcid": course_id}, timeout=_DEFAULT_TIMEOUT)
                    resp.raise_for_status()
                    raw = _safe_json(resp)
                    items = (
                        _extract_list(raw, "object", "aaData")
                        or _extract_list(raw, "resultList")
                        or _extract_list(raw, "data")
                        or (raw if isinstance(raw, list) else [])
                    )
                    for item in items:
                        if not isinstance(item, dict):
                            continue
                        all_notices.append({
                            "id": str(item.get("ggid", item.get("id", ""))),
                            "title": str(item.get("bt", item.get("title", ""))),
                            "publisher": str(item.get("fbrm", item.get("publisher", ""))),
                            "publish_time": str(item.get("fbsj", "")),
                            "expire_time": str(item.get("gqsj", "")),
                            "marked_important": bool(item.get("sfqd", False)),
                            "has_read": bool(item.get("sfyd", False)),
                            "course_id": course_id,
                            "url": str(item.get("url", "")),
                        })
                except Exception as exc:
                    logger.warning(
                        "[get_notices] course_id=%s url=%s error=%s",
                        course_id,
                        url,
                        exc,
                    )

        # Sort by publish_time descending (lexicographic ISO works)
        all_notices.sort(key=lambda n: n.get("publish_time", ""), reverse=True)

        logger.info(
            "[get_notices] request_id=%s courses=%d total_notices=%d",
            invocation.request_id,
            len(course_ids),
            len(all_notices),
        )
        return self._ok(invocation, {
            "course_ids": course_ids,
            "notices": all_notices,
            "notice_count": len(all_notices),
        })


class GetFilesHandler(_BaseCourseInfoHandler):
    """
    Fetch course files for a list of courses.

    Args: course_ids (list[string])
    Returns: files (list of File dicts)
    Ref: docs/API_http.md §7
    """

    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        _, s, err = _check_deps_and_session(invocation.skill_name, invocation.request_id, session)
        if err:
            return err

        course_ids: list[str] = [
            str(cid) for cid in invocation.args.get("course_ids", []) if cid
        ]
        if not course_ids:
            return self._err(invocation, "INVALID_PARAM", "course_ids must be a non-empty list")

        all_files: list[dict[str, Any]] = []
        for course_id in course_ids:
            try:
                url = (
                    f"{_LEARN_BASE}/b/wlxt/kj/wlkc_kjxxb/student"
                    f"/kjxxbByWlkcidAndSizeForStudent"
                )
                resp = s.get(url, params={"wlkcid": course_id, "size": 200}, timeout=_DEFAULT_TIMEOUT)
                resp.raise_for_status()
                raw = _safe_json(resp)
                items = (
                    _extract_list(raw, "object", "aaData")
                    or _extract_list(raw, "resultList")
                    or _extract_list(raw, "data")
                    or (raw if isinstance(raw, list) else [])
                )
                for item in items:
                    if not isinstance(item, dict):
                        continue
                    file_id = str(item.get("wjid", item.get("id", "")))
                    all_files.append({
                        "id": file_id,
                        "title": str(item.get("bt", item.get("title", ""))),
                        "description": str(item.get("ms", "")),
                        "category": str(item.get("flmc", "")),
                        "size": int(item.get("wjdx", item.get("size", 0)) or 0),
                        "file_type": str(item.get("wjlx", "")),
                        "marked_important": bool(item.get("sfqd", False)),
                        "is_new": bool(item.get("isNew", False)),
                        "upload_time": str(item.get("scsj", "")),
                        "download_url": (
                            f"{_LEARN_BASE}/b/wlxt/kj/wlkc_kjxxb/student"
                            f"/downloadFile?sfgk=0&wjid={file_id}"
                        ),
                        "course_id": course_id,
                    })
            except Exception as exc:
                logger.warning("[get_files] course_id=%s error=%s", course_id, exc)

        all_files.sort(key=lambda f: f.get("upload_time", ""), reverse=True)

        logger.info(
            "[get_files] request_id=%s courses=%d total_files=%d",
            invocation.request_id,
            len(course_ids),
            len(all_files),
        )
        return self._ok(invocation, {
            "course_ids": course_ids,
            "files": all_files,
            "file_count": len(all_files),
        })


class GetAssignmentsHandler(_BaseCourseInfoHandler):
    """
    Fetch assignments (DDLs) for a list of courses.

    Calls three endpoints (pending / submitted / graded) concurrently and merges.
    Args: course_ids (list[string])
    Returns: assignments (list of Assignment dicts)
    Ref: docs/API_http.md §8
    """

    _ASSIGNMENT_URLS = [
        (f"{_LEARN_BASE}/b/wlxt/kczy/zy/student/zyListWj", "pending"),
        (f"{_LEARN_BASE}/b/wlxt/kczy/zy/student/zyListYjwg", "submitted"),
        (f"{_LEARN_BASE}/b/wlxt/kczy/zy/student/zyListYpg", "graded"),
    ]

    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        _, s, err = _check_deps_and_session(invocation.skill_name, invocation.request_id, session)
        if err:
            return err

        course_ids: list[str] = [
            str(cid) for cid in invocation.args.get("course_ids", []) if cid
        ]
        if not course_ids:
            return self._err(invocation, "INVALID_PARAM", "course_ids must be a non-empty list")

        all_assignments: list[dict[str, Any]] = []
        for course_id in course_ids:
            for url, completion_state in self._ASSIGNMENT_URLS:
                try:
                    resp = s.post(
                        url,
                        data={"wlkcid": course_id},
                        timeout=_DEFAULT_TIMEOUT,
                    )
                    resp.raise_for_status()
                    raw = _safe_json(resp)
                    items = (
                        _extract_list(raw, "object", "aaData")
                        or _extract_list(raw, "resultList")
                        or _extract_list(raw, "data")
                        or (raw if isinstance(raw, list) else [])
                    )
                    for item in items:
                        if not isinstance(item, dict):
                            continue
                        all_assignments.append({
                            "id": str(item.get("zyid", item.get("id", ""))),
                            "student_homework_id": str(item.get("xszyid", "")),
                            "title": str(item.get("bt", item.get("title", ""))),
                            "description": str(item.get("nr", "")),
                            "deadline": str(item.get("jzsj", "")),
                            "late_submission_deadline": str(item.get("wjjzsj", "")),
                            "submitted": completion_state in {"submitted", "graded"},
                            "graded": completion_state == "graded",
                            "grade": item.get("cj"),
                            "grade_level": str(item.get("cjdj", "")),
                            "completion_state": completion_state,
                            "course_id": course_id,
                        })
                except Exception as exc:
                    logger.warning(
                        "[get_assignments] course_id=%s state=%s error=%s",
                        course_id,
                        completion_state,
                        exc,
                    )

        # Sort: pending first by deadline asc, then graded/submitted by deadline desc
        pending = sorted(
            [a for a in all_assignments if a["completion_state"] == "pending"],
            key=lambda a: a.get("deadline", ""),
        )
        done = sorted(
            [a for a in all_assignments if a["completion_state"] != "pending"],
            key=lambda a: a.get("deadline", ""),
            reverse=True,
        )
        sorted_assignments = pending + done

        logger.info(
            "[get_assignments] request_id=%s courses=%d pending=%d done=%d",
            invocation.request_id,
            len(course_ids),
            len(pending),
            len(done),
        )
        return self._ok(invocation, {
            "course_ids": course_ids,
            "assignments": sorted_assignments,
            "assignment_count": len(sorted_assignments),
            "pending_count": len(pending),
        })


class GetAcademicCalendarHandler(_BaseCourseInfoHandler):
    """
    Fetch academic calendar events (lecture schedule) from the academic affairs system.

    Args: start_date (YYYYMMDD), end_date (YYYYMMDD)
    Returns: calendar_events (list)
    Ref: docs/API_http.md §9
    """

    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        _, s, err = _check_deps_and_session(invocation.skill_name, invocation.request_id, session)
        if err:
            return err

        start_date: str = str(invocation.args.get("start_date", "")).strip()
        end_date: str = str(invocation.args.get("end_date", "")).strip()

        if not start_date or not end_date:
            return self._err(invocation, "INVALID_PARAM", "start_date and end_date (YYYYMMDD) are required")

        try:
            # Step 1: obtain ticket from learn
            ticket_resp = s.post(
                f"{_LEARN_BASE}/b/wlxt/common/auth/gnt",
                timeout=_DEFAULT_TIMEOUT,
            )
            ticket_resp.raise_for_status()
            ticket_raw = _safe_json(ticket_resp)
            ticket: str = ""
            if isinstance(ticket_raw, dict):
                ticket = str(ticket_raw.get("result", ticket_raw.get("ticket", ""))).strip()

            if ticket:
                # Step 2: authenticate with academic affairs system
                zhjw_s = _build_requests_session(session)
                zhjw_s.get(
                    f"{_ZHJW_BASE}/j_acegi_login.do",
                    params={"url": "/", "ticket": ticket},
                    timeout=_DEFAULT_TIMEOUT,
                )

            # Step 3: fetch calendar
            graduate = bool(invocation.args.get("graduate", False))
            method = "yjs_jxrl_all" if graduate else "bks_jxrl_all"
            import random
            callback = f"jQuery_{random.randint(10**12, 10**13)}"
            cal_url = (
                f"{_ZHJW_BASE}/jxmh_out.do"
                f"?m={method}"
                f"&p_start_date={start_date}"
                f"&p_end_date={end_date}"
                f"&jsoncallback={callback}"
            )
            # Use the authenticated zhjw session if ticket was obtained
            fetch_s = zhjw_s if ticket else s
            cal_resp = fetch_s.get(cal_url, timeout=_DEFAULT_TIMEOUT)
            cal_resp.raise_for_status()

            # Response is JSONP: "jQuery_xxx([...])"
            raw_text = cal_resp.text.strip()
            start_idx = raw_text.find("(")
            end_idx = raw_text.rfind(")")
            events_raw: list = []
            if start_idx != -1 and end_idx != -1:
                import json
                try:
                    events_raw = json.loads(raw_text[start_idx + 1: end_idx])
                except Exception:
                    pass

            events = events_raw if isinstance(events_raw, list) else []
            logger.info(
                "[get_academic_calendar] request_id=%s start=%s end=%s events=%d",
                invocation.request_id,
                start_date,
                end_date,
                len(events),
            )
            return self._ok(invocation, {
                "start_date": start_date,
                "end_date": end_date,
                "calendar_events": events,
                "event_count": len(events),
            }, source="zhjw.cic.tsinghua.edu.cn")
        except Exception as exc:
            logger.warning("[get_academic_calendar] request_id=%s error=%s", invocation.request_id, exc)
            return self._http_err(invocation, exc)


class GetCampusActivitiesHandler(_BaseCourseInfoHandler):
    """
    Fetch campus activity information.

    Returns: activities (list)
    Note: Tsinghua does not expose a stable public activities API.
          This implementation fetches the public campus homepage RSS-like endpoint
          when available and falls back to a stub with a guidance message.
    Ref: docs/API_http.md §(campus activities)
    """

    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        _, s, err = _check_deps_and_session(invocation.skill_name, invocation.request_id, session)
        if err:
            return err

        try:
            url = f"{_LEARN_BASE}/b/wlxt/kcgg/wlkc_ggb/student/pageList"
            resp = s.get(url, timeout=_DEFAULT_TIMEOUT)
            resp.raise_for_status()
            raw = _safe_json(resp)
            activities = (
                _extract_list(raw, "object", "aaData")
                or _extract_list(raw, "resultList")
                or (raw if isinstance(raw, list) else [])
            )
            logger.info(
                "[get_campus_activities] request_id=%s activities=%d",
                invocation.request_id,
                len(activities),
            )
            return self._ok(invocation, {
                "activities": activities,
                "activity_count": len(activities),
            })
        except Exception as exc:
            logger.warning("[get_campus_activities] request_id=%s error=%s", invocation.request_id, exc)
            # Non-fatal – return stub result so the plan can continue
            return self._ok(invocation, {
                "activities": [],
                "activity_count": 0,
                "note": "Campus activities endpoint unavailable; data may require manual retrieval.",
            })


# ---------------------------------------------------------------------------
# Registration entry point
# ---------------------------------------------------------------------------

def register_course_info_handlers(registry: Any) -> None:
    """Register all course-info skill handlers into the given SkillRegistry."""
    registry.register_handler("get_user_info", GetUserInfoHandler())
    registry.register_handler("get_semesters", GetSemestersHandler())
    registry.register_handler("get_courses", GetCoursesHandler())
    registry.register_handler("get_notices", GetNoticesHandler())
    registry.register_handler("get_files", GetFilesHandler())
    registry.register_handler("get_assignments", GetAssignmentsHandler())
    registry.register_handler("get_academic_calendar", GetAcademicCalendarHandler())
    registry.register_handler("get_campus_activities", GetCampusActivitiesHandler())
    logger.debug("[course_info] registered 8 course-info skill handlers")
