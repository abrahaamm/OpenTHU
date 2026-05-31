from __future__ import annotations

import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen

try:
    import requests
except ImportError:  # pragma: no cover - urllib fallback keeps minimal installs usable.
    requests = None


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


def _first_nonblank(*values: Any) -> str:
    for value in values:
        text = str(value or "").strip()
        if text:
            return text
    return ""


def _normalize_cookie_header(raw: Any) -> str:
    if isinstance(raw, dict):
        return "; ".join(
            f"{str(key).strip()}={str(value).strip()}"
            for key, value in raw.items()
            if str(key).strip() and str(value).strip()
        )
    return "; ".join(token.strip() for token in str(raw or "").split(";") if "=" in token)


def _extract_cookie_value(cookie_header: str, cookie_name: str) -> str:
    for part in cookie_header.split(";"):
        token = part.strip()
        name, _, value = token.partition("=")
        if name.strip().lower() == cookie_name.lower():
            return value.strip()
    return ""


def _looks_like_learn_cookie(cookie_header: str) -> bool:
    return bool(
        _extract_cookie_value(cookie_header, "JSESSIONID")
        or _extract_cookie_value(cookie_header, "XSRF-TOKEN")
        or _extract_cookie_value(cookie_header, "XSRFToken")
    )


def _learn_cookie_from_session(session: dict[str, Any], args: dict[str, Any]) -> str:
    for value in (
        args.get("session_cookie"),
        args.get("cookies"),
        args.get("homework_cookie"),
        args.get("learn_cookie"),
        session.get("homework_cookie"),
        session.get("learn_cookie"),
        session.get("session_cookie"),
        session.get("cookies"),
    ):
        cookie = _normalize_cookie_header(value)
        if cookie:
            return cookie

    for value in (args.get("cookie"), session.get("cookie")):
        cookie = _normalize_cookie_header(value)
        if cookie and _looks_like_learn_cookie(cookie):
            return cookie
    return ""


def _csrf_from_session(session: dict[str, Any], args: dict[str, Any], cookie: str) -> str:
    return _first_nonblank(
        args.get("csrf_token"),
        args.get("homework_csrf"),
        args.get("learn_csrf"),
        session.get("csrf_token"),
        session.get("homework_csrf"),
        session.get("learn_csrf"),
        _extract_cookie_value(cookie, "XSRF-TOKEN"),
        _extract_cookie_value(cookie, "XSRFToken"),
        _extract_cookie_value(cookie, "_csrf"),
        args.get("csrf"),
        session.get("csrf"),
    )


def _decode_response(raw: bytes, content_type: str = "") -> str:
    charset = ""
    if "charset=" in content_type:
        charset = content_type.rsplit("charset=", 1)[-1].split(";", 1)[0].strip()
    for candidate in [charset, "utf-8", "gb18030"]:
        if not candidate:
            continue
        try:
            return raw.decode(candidate)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


class LearnHomeworkHttpError(RuntimeError):
    pass


class LearnHomeworkHttpClient:
    def __init__(self, session: dict[str, Any], args: dict[str, Any]) -> None:
        self.base_url = str(
            _first_nonblank(args.get("learn_base_url"), session.get("learn_base_url"), "https://learn.tsinghua.edu.cn")
        ).rstrip("/")
        self.cookie = _learn_cookie_from_session(session, args)
        self.csrf = _csrf_from_session(session, args, self.cookie)

    def validate_auth(self) -> str | None:
        if not self.cookie:
            return "网络学堂登录态未配置。请去设置页的「清华统一登录」完成登录后再重试。"
        if not self.csrf:
            return "网络学堂登录态缺少 XSRF-TOKEN。请去设置页的「清华统一登录」重新登录后再重试。"
        return None

    def get_text(self, path: str, *, referer: str = "", ajax: bool = False, html: bool = False) -> str:
        url = path if path.startswith("http") else urljoin(f"{self.base_url}/", path.lstrip("/"))
        return self._request_text("GET", url, referer=referer, ajax=ajax, html=html)

    def post_form(self, path: str, form: dict[str, Any], *, referer: str = "") -> str:
        url = path if path.startswith("http") else urljoin(f"{self.base_url}/", path.lstrip("/"))
        body = urlencode({key: str(value) for key, value in form.items()}).encode("utf-8")
        return self._request_text("POST", url, body=body, referer=referer, ajax=True)

    def _request_text(
        self,
        method: str,
        url: str,
        *,
        body: bytes | None = None,
        referer: str = "",
        ajax: bool = False,
        html: bool = False,
    ) -> str:
        headers = {
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            if html
            else "application/json, text/javascript, */*; q=0.01",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
            "Cache-Control": "no-cache",
            "Connection": "close",
            "Pragma": "no-cache",
            "Cookie": self.cookie,
            "User-Agent": "Mozilla/5.0 OpenTHU-AgentCore/1.0",
        }
        if referer:
            headers["Referer"] = referer
        if ajax:
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Origin"] = self.base_url
        if self.csrf:
            headers["X-XSRF-TOKEN"] = self.csrf
            headers["X-CSRF-TOKEN"] = self.csrf
            headers["X-XSRFToken"] = self.csrf
        if body is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        if requests is not None:
            last_error: Exception | None = None
            attempts = 5 if method.upper() == "POST" else 3
            for attempt in range(attempts):
                try:
                    response = requests.request(
                        method,
                        url,
                        data=body,
                        headers=headers,
                        timeout=12,
                        allow_redirects=False,
                    )
                    break
                except requests.RequestException as exc:
                    last_error = exc
                    if attempt < attempts - 1:
                        time.sleep(0.4 * (attempt + 1))
                        continue
                    raise LearnHomeworkHttpError(f"network_error for {url}: {exc}") from exc
            else:
                raise LearnHomeworkHttpError(f"network_error for {url}: {last_error}")

            text = _decode_response(response.content, response.headers.get("Content-Type", ""))
            if 200 <= response.status_code <= 299:
                return text
            location = response.headers.get("Location", "")
            raise LearnHomeworkHttpError(
                f"HTTP {response.status_code} for {url}{f' location={location}' if location else ''}: {text[:512]}"
            )

        request = Request(url, data=body, headers=headers, method=method)
        try:
            with urlopen(request, timeout=24) as response:
                raw = response.read()
                return _decode_response(raw, response.headers.get("Content-Type", ""))
        except HTTPError as exc:
            location = exc.headers.get("Location", "")
            raw = exc.read()
            text = _decode_response(raw, exc.headers.get("Content-Type", ""))
            raise LearnHomeworkHttpError(
                f"HTTP {exc.code} for {url}{f' location={location}' if location else ''}: {text[:512]}"
            ) from exc
        except URLError as exc:
            raise LearnHomeworkHttpError(f"network_error for {url}: {exc}") from exc


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


def _datatable_payload(course_id: str) -> dict[str, Any]:
    return {
        "aoData": json.dumps(
            [
                {"name": "sEcho", "value": 1},
                {"name": "iDisplayStart", "value": 0},
                {"name": "iDisplayLength", "value": 100},
                {"name": "wlkcid", "value": course_id},
            ],
            ensure_ascii=False,
        )
    }


def _normalize_text(value: Any) -> str:
    return " ".join(str(value or "").split())


def _load_homework_courses(client: LearnHomeworkHttpClient, invocation: Any) -> list[dict[str, Any]]:
    semester_id = str(invocation.args.get("semester_id") or "2025-2026-2").strip()
    locale = str(invocation.args.get("locale") or "zh").strip()
    ts = int(time.time() * 1000)
    path = f"/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/{semester_id}/{locale}?timestamp={ts}"
    raw = client.get_text(path, referer=f"{client.base_url}/f/wlxt/index/course/student/", ajax=True)
    root = json.loads(raw)
    items = root.get("resultList")
    if not isinstance(items, list):
        items = []
    courses: list[dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        course_id = _normalize_text(item.get("wlkcid"))
        if not course_id:
            continue
        courses.append(
            {
                "wlkcid": course_id,
                "course_id": course_id,
                "course_name": _normalize_text(item.get("kcm")),
                "course_english_name": _normalize_text(item.get("ywkcm")),
                "course_no": _normalize_text(item.get("kch")),
                "class_no": _normalize_text(item.get("kxhnumber")),
                "teacher_name": _normalize_text(item.get("jsm")),
                "time_location": _normalize_text(item.get("sjddb")),
                "homework_total": _normalize_text(item.get("zyzs")),
                "homework_unsubmitted": _normalize_text(item.get("wjzys")),
                "course_url": f"{client.base_url}/f/wlxt/index/course/student/course?wlkcid={course_id}",
                "homework_page_url": f"{client.base_url}/f/wlxt/kczy/zy/student/beforePageList?wlkcid={course_id}",
            }
        )
    return courses


def _extract_homework_records(
    raw: Any,
    course: dict[str, Any],
    endpoint_type: str,
    endpoint: str,
    base_url: str,
) -> list[dict[str, Any]]:
    if not isinstance(raw, dict):
        return []
    rows = raw.get("aaData") or raw.get("data") or raw.get("resultList") or raw.get("rows")
    if not isinstance(rows, list):
        nested = raw.get("object")
        if isinstance(nested, dict):
            rows = nested.get("aaData") or nested.get("data") or nested.get("resultList") or nested.get("rows")
    if not isinstance(rows, list):
        rows = []
    records: list[dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        homework_id = _normalize_text(
            _first_nonblank(row.get("zyid"), row.get("id"), row.get("zyId"), row.get("homework_id"))
        )
        student_homework_id = _normalize_text(
            _first_nonblank(row.get("xszyid"), row.get("xsZyId"), row.get("student_homework_id"), homework_id)
        )
        title = _normalize_text(_first_nonblank(row.get("bt"), row.get("zybt"), row.get("title"), row.get("zymc")))
        if not any([homework_id, student_homework_id, title]):
            continue
        deadline = _normalize_text(
            _first_nonblank(row.get("jzsj"), row.get("deadline"), row.get("jssj"), row.get("endTime"))
        )
        detail_url = f"{base_url}/f/wlxt/kczy/zy/student/viewZy?zyid={homework_id}" if homework_id else ""
        records.append(
            {
                "homework_id": homework_id,
                "zyid": homework_id,
                "student_homework_id": student_homework_id,
                "xszyid": student_homework_id,
                "title": title or "未命名作业",
                "homework_title": title or "未命名作业",
                "deadline": deadline,
                "submitted": endpoint_type != "unsubmitted",
                "status_group": endpoint_type,
                "course_id": course.get("course_id", ""),
                "course_wlkcid": course.get("wlkcid", ""),
                "course_name": course.get("course_name", ""),
                "course_no": course.get("course_no", ""),
                "class_no": course.get("class_no", ""),
                "teacher_name": course.get("teacher_name", ""),
                "detail_url": detail_url,
                "submit_url": f"{base_url}/f/wlxt/kczy/zy/student/tijiao?wlkcid={course.get('wlkcid', '')}&xszyid={student_homework_id}"
                if student_homework_id
                else "",
                "source_endpoint": endpoint,
            }
        )
    return records


def _homework_auth_failure_message(message: str) -> bool:
    lowered = message.lower()
    return (
        "http 401" in lowered
        or "http 403" in lowered
        or "http 30" in lowered
        or "authserver" in lowered
        or "login" in lowered
    )


def _positive_int(value: Any) -> bool:
    try:
        return int(str(value or "0").strip()) > 0
    except ValueError:
        return False


def _crawl_homeworks_on_server(invocation: Any, session: dict[str, Any], *, unsubmitted_only: bool) -> Any:
    try:
        from .skill_core import SkillResult
    except ImportError:
        from skill_core import SkillResult

    client = LearnHomeworkHttpClient(session, invocation.args)
    auth_error = client.validate_auth()
    if auth_error:
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="NOT_CONFIGURED",
            data={"status": "login_required", "reason": "login_required", "message": auth_error},
            from_cache=False,
            fetched_at=_utc_now_iso(),
            source="agent_core_homework_http",
        )

    requested_course_ids = set(_coerce_string_list(invocation.args.get("course_ids")))
    endpoint_types = ["unsubmitted"] if unsubmitted_only else ["unsubmitted", "submitted_ungraded", "graded", "excellent"]
    endpoint_map = {
        "unsubmitted": "/b/wlxt/kczy/zy/student/zyListWj",
        "submitted_ungraded": "/b/wlxt/kczy/zy/student/zyListYjwg",
        "graded": "/b/wlxt/kczy/zy/student/zyListYpg",
        "excellent": "/b/wlxt/kczy/zy/student/yxzylist",
    }
    try:
        courses = _load_homework_courses(client, invocation)
        if requested_course_ids:
            courses = [course for course in courses if str(course.get("wlkcid", "")) in requested_course_ids]
        elif unsubmitted_only:
            courses = [course for course in courses if _positive_int(course.get("homework_unsubmitted"))]
        records: list[dict[str, Any]] = []
        warnings: list[str] = []
        for course in courses:
            try:
                client.get_text(str(course["homework_page_url"]), referer=str(course["course_url"]), html=True)
            except Exception as exc:
                if _homework_auth_failure_message(str(exc)):
                    raise
                warnings.append(
                    f"Skipped homework page prefetch for {course.get('course_name', course.get('wlkcid', 'unknown'))}: {exc}"
                )
            for endpoint_type in endpoint_types:
                endpoint = endpoint_map[endpoint_type]
                raw = client.post_form(endpoint, _datatable_payload(str(course["wlkcid"])), referer=str(course["homework_page_url"]))
                parsed = json.loads(raw)
                records.extend(_extract_homework_records(parsed, course, endpoint_type, endpoint, client.base_url))
        deduped: dict[str, dict[str, Any]] = {}
        for record in records:
            key = f"{record.get('homework_id', '')}::{record.get('student_homework_id', '')}::{record.get('course_id', '')}"
            deduped.setdefault(key, record)
        homeworks = sorted(deduped.values(), key=lambda item: str(item.get("deadline", "")))
        status = "unsubmitted_crawled" if unsubmitted_only else "crawled"
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": status,
                "message": f"Homework crawl completed: {len(homeworks)} item(s).",
                "count": len(homeworks),
                "homeworks": homeworks,
                "course_count": len(courses),
                "course_ids": sorted(requested_course_ids),
                "learn_base_url": client.base_url,
                "warnings": warnings,
            },
            from_cache=False,
            fetched_at=_utc_now_iso(),
            source="agent_core_homework_http",
        )
    except Exception as exc:
        message = str(exc)
        code = "NOT_CONFIGURED" if _homework_auth_failure_message(message) else "SKILL_EXECUTION_FAILED"
        status = "login_required" if code == "NOT_CONFIGURED" else "failed"
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code=code,
            data={
                "status": status,
                "reason": "login_required" if code == "NOT_CONFIGURED" else "crawl_failed",
                "message": "网络学堂登录态已失效或不完整。请去设置页的「清华统一登录」重新登录后再重试。"
                if code == "NOT_CONFIGURED"
                else f"Homework crawl failed: {message}",
                "exception": message,
            },
            from_cache=False,
            fetched_at=_utc_now_iso(),
            source="agent_core_homework_http",
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
        if not isinstance(self.bridge, UnconfiguredHomeworkBridge) and not _learn_cookie_from_session(session, invocation.args):
            return self._dispatch_to_bridge(invocation, state)
        return _crawl_homeworks_on_server(invocation, session, unsubmitted_only=False)


class CrawlUnsubmittedHomeworksHandler(_BaseHomeworkHandler):
    def invoke(self, invocation: Any, session: dict[str, Any], state: dict[str, Any]) -> Any:
        invocation.args["course_ids"] = _coerce_string_list(invocation.args.get("course_ids"))
        if "include_overdue" in invocation.args:
            invocation.args["include_overdue"] = _coerce_bool(invocation.args.get("include_overdue"), default=False)
        if not isinstance(self.bridge, UnconfiguredHomeworkBridge) and not _learn_cookie_from_session(session, invocation.args):
            return self._dispatch_to_bridge(invocation, state)
        return _crawl_homeworks_on_server(invocation, session, unsubmitted_only=True)


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
