from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from typing import Any, Protocol

try:
    from .calendar_handlers import register_calendar_handlers
    from .homework_handlers import register_homework_handlers
except ImportError:
    from calendar_handlers import register_calendar_handlers
    from homework_handlers import register_homework_handlers


@dataclass
class SkillSpec:
    skill_name: str
    description: str
    category: str
    risk_level: str
    requires_approval: bool
    skill_version: str = "v1"
    session_required: bool = False
    args_schema: dict[str, str] = field(default_factory=dict)
    args_json_schema: dict[str, Any] = field(default_factory=dict)
    when_to_use: str = ""
    avoid_when: str = ""
    example_utterances: list[str] = field(default_factory=list)

    def to_planner_dict(self) -> dict[str, Any]:
        return {
            "skill_name": self.skill_name,
            "description": self.description,
            "category": self.category,
            "risk_level": self.risk_level,
            "requires_approval": self.requires_approval,
            "session_required": self.session_required,
            "skill_version": self.skill_version,
            "args_schema": self.args_schema,
            "args_json_schema": self.args_json_schema,
            "when_to_use": self.when_to_use,
            "avoid_when": self.avoid_when,
            "example_utterances": self.example_utterances,
        }


@dataclass
class SkillInvocation:
    skill_name: str
    request_id: str
    task_id: str
    args: dict[str, Any]
    risk_level: str
    requires_approval: bool
    description: str
    status: str = "planned"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class SkillResult:
    skill_name: str
    request_id: str
    code: str
    data: dict[str, Any]
    from_cache: bool
    fetched_at: str
    source: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class SkillHandler(Protocol):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult: ...


class MissingSkillHandler:
    def __init__(self, skill_name: str) -> None:
        self.skill_name = skill_name

    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        return SkillResult(
            skill_name=self.skill_name,
            request_id=invocation.request_id,
            code="SKILL_EXECUTION_FAILED",
            data={
                "status": "not_implemented",
                "message": f"Skill '{self.skill_name}' is not registered yet",
            },
            from_cache=False,
            fetched_at=datetime.now(timezone.utc).isoformat(),
            source="skill_registry",
        )


class SkillRegistry:
    def __init__(self) -> None:
        self._specs: dict[str, SkillSpec] = {}
        self._handlers: dict[str, SkillHandler] = {}

    def register_spec(self, spec: SkillSpec) -> None:
        self._specs[spec.skill_name] = spec

    def register_handler(self, skill_name: str, handler: SkillHandler) -> None:
        self._handlers[skill_name] = handler

    def get_spec(self, skill_name: str) -> SkillSpec | None:
        return self._specs.get(skill_name)

    def get_handler(self, skill_name: str) -> SkillHandler:
        return self._handlers.get(skill_name, MissingSkillHandler(skill_name))

    def list_specs(self) -> list[SkillSpec]:
        return list(self._specs.values())

    def describe_for_planner(self) -> list[dict[str, Any]]:
        return [spec.to_planner_dict() for spec in self.list_specs()]


def build_default_registry() -> SkillRegistry:
    registry = SkillRegistry()
    calendar_time_text_schema = {
        "type": "string",
        "description": (
            "Calendar time text. May be a concrete ISO-8601 datetime with explicit UTC offset "
            "or natural-language/relative/year-underspecified time text copied from the user. A user "
            "time is complete only if it explicitly includes year, date, and time; 6月21日下午4点 "
            "is incomplete because it lacks a year. If relative or missing a year, plan get_current_time "
            "before this calendar skill and copy the original phrase instead of filling a year."
        ),
    }
    timezone_schema = {
        "type": "string",
        "description": (
            "Optional IANA timezone id used for calendar display/context, preferably copied "
            "from session.timezone or settings, e.g. Asia/Shanghai. Time values must still "
            "include an explicit offset."
        ),
    }
    calendar_time_source_schema = {
        "type": "string",
        "enum": ["user_text", "planner_inferred", "upstream_skill", "explicit_absolute"],
        "description": (
            "Where the calendar time came from. Use user_text for relative/year-underspecified "
            "time copied from the user, planner_inferred only if the planner filled missing parts, "
            "upstream_skill for authoritative times from earlier skill results such as DDLs, and "
            "explicit_absolute only when the user gave a complete date with explicit year and time."
        ),
    }
    calendar_time_metadata_schema = {
        "time_source": calendar_time_source_schema,
        "time_text": {
            "type": "string",
            "description": "Original user time phrase, e.g. 6月21日下午4点. Required when time_source=user_text.",
        },
        "source_skill": {
            "type": "string",
            "description": "Upstream skill name when time_source=upstream_skill, e.g. get_assignments.",
        },
        "source_field": {
            "type": "string",
            "description": "Upstream result field when time_source=upstream_skill, e.g. deadline.",
        },
    }

    for spec in [
        SkillSpec("login", "Authenticate with Tsinghua identity system", "auth", "low", False),
        SkillSpec("refresh_session", "Refresh an existing learn session", "auth", "low", False, session_required=True),
        SkillSpec("logout", "Clear local session and upstream learn session", "auth", "low", False, session_required=True),
        SkillSpec("get_user_info", "Fetch student profile", "data", "low", False, session_required=True),
        SkillSpec("get_semesters", "Fetch semester list and current semester", "data", "low", False, session_required=True),
        SkillSpec(
            "get_courses",
            "Fetch course list and class time/location blocks for a semester",
            "data",
            "low",
            False,
            session_required=True,
            args_schema={"semester_id": "string"},
            args_json_schema={
                "type": "object",
                "properties": {
                    "semester_id": {"type": "string"},
                    "lang": {"type": "string"},
                    "include_schedule_detail": {"type": "boolean"},
                },
                "required": ["homework_id"],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "get_course_schedule",
            "Fetch class schedule entries from Tsinghua WebVPN teaching-calendar or Learn course list",
            "data",
            "low",
            False,
            session_required=True,
            when_to_use="Use when the user asks to view, fetch, check, import, or summarize their class timetable/course schedule/课表/课程表. Always run get_semesters before this skill in the same plan unless the plan already contains a resolved semester_id.",
            avoid_when="Do not use for generic course catalog questions; use get_courses for course lists.",
            example_utterances=[
                "拉取我的课表",
                "帮我看看今天有什么课",
                "fetch my course schedule",
                "show my timetable this week",
            ],
            args_json_schema={
                "type": "object",
                "properties": {
                    "semester_id": {"type": "string"},
                    "first_day": {"type": "string"},
                    "week_count": {"type": "integer"},
                    "graduate": {"type": "boolean"},
                    "include_secondary": {"type": "boolean"},
                    "lang": {"type": "string"},
                },
                "required": [],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "get_notices",
            "Fetch course notices",
            "data",
            "low",
            False,
            session_required=True,
            args_schema={"course_ids": "list[string]"},
        ),
        SkillSpec(
            "get_files",
            "Fetch course files",
            "data",
            "low",
            False,
            session_required=True,
            args_schema={"course_ids": "list[string]"},
        ),
        SkillSpec(
            "get_assignments",
            "Fetch assignments and DDLs",
            "data",
            "low",
            False,
            session_required=True,
            args_schema={"course_ids": "list[string]"},
            args_json_schema={
                "type": "object",
                "properties": {
                    "course_ids": {"type": "array", "items": {"type": "string"}},
                    "semester_id": {"type": "string"},
                    "include_submitted": {"type": "boolean"},
                    "learn_base_url": {"type": "string"},
                    "session_cookie": {"type": "string"},
                    "cookies": {"type": "string"},
                    "homework_cookie": {"type": "string"},
                    "learn_cookie": {"type": "string"},
                    "csrf_token": {"type": "string"},
                    "locale": {"type": "string"},
                },
                "required": [],
                "additionalProperties": False,
            },
            when_to_use="Use for assignment and deadline lists exposed by the normal course-info API, not the Android Tsinghua Learn homework crawler.",
            example_utterances=[
                "帮我获取当前作业和DDL",
                "show assignment deadlines",
            ],
        ),
        SkillSpec(
            "get_homework_cookie",
            "Load a provided Tsinghua Learn cookie for homework skills",
            "auth",
            "high",
            True,
            session_required=False,
            when_to_use="Use only when the user explicitly provides a Learn cookie, CSRF token, or pasted authentication header to load.",
            avoid_when="Do not use just because the user wants to check homework; homework skills should return login-required if no login state is available.",
            example_utterances=[
                "这是网络学堂 cookie：JSESSIONID=...",
                "load this Learn cookie for homework",
            ],
            args_json_schema={
                "type": "object",
                "properties": {
                    "cookies": {"type": "string"},
                    "session_cookie": {"type": "string"},
                    "homework_cookie": {"type": "string"},
                    "learn_cookie": {"type": "string"},
                    "csrf_token": {"type": "string"},
                    "homework_csrf": {"type": "string"},
                    "learn_csrf": {"type": "string"},
                    "learn_base_url": {"type": "string"},
                },
                "required": [],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "crawl_course_homeworks",
            "Crawl homework records from Tsinghua Learn on the Android device",
            "data",
            "low",
            False,
            session_required=False,
            when_to_use="Use when the user asks to check/list/fetch all Tsinghua Learn homework, assignments, due dates, homework status, or course homework records.",
            avoid_when="If the user specifically asks for unsubmitted/not submitted/missing homework, prefer crawl_unsubmitted_homeworks.",
            example_utterances=[
                "帮我看看网络学堂作业",
                "check my homework",
                "list my homework assignments",
                "看看最近有哪些作业",
            ],
            args_json_schema={
                "type": "object",
                "properties": {
                    "semester_id": {"type": "string"},
                    "course_ids": {"type": "array", "items": {"type": "string"}},
                    "include_submitted": {"type": "boolean"},
                    "learn_base_url": {"type": "string"},
                    "session_cookie": {"type": "string"},
                    "cookies": {"type": "string"},
                    "homework_cookie": {"type": "string"},
                    "learn_cookie": {"type": "string"},
                    "csrf_token": {"type": "string"},
                    "locale": {"type": "string"},
                },
                "required": [],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "crawl_unsubmitted_homeworks",
            "Crawl unsubmitted homework records from Tsinghua Learn on the Android device",
            "data",
            "low",
            False,
            session_required=False,
            when_to_use="Use when the user asks to find/check/list homework that is unsubmitted, not submitted, missing, unfinished, overdue, 未提交, 未交, 没交, or 待提交.",
            avoid_when="Do not answer with a generic AI limitation. This skill is allowed to inspect authorized homework state; if login is missing it will report that the user should log in from settings.",
            example_utterances=[
                "check my homework that is not submitted",
                "find unsubmitted assignments",
                "帮我看看还没交的作业",
                "网络学堂有没有未提交作业",
            ],
            args_json_schema={
                "type": "object",
                "properties": {
                    "semester_id": {"type": "string"},
                    "course_ids": {"type": "array", "items": {"type": "string"}},
                    "include_overdue": {"type": "boolean"},
                    "learn_base_url": {"type": "string"},
                    "session_cookie": {"type": "string"},
                    "cookies": {"type": "string"},
                    "homework_cookie": {"type": "string"},
                    "learn_cookie": {"type": "string"},
                    "csrf_token": {"type": "string"},
                    "locale": {"type": "string"},
                },
                "required": [],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "preview_homework_attachments",
            "Open and parse attachment entries for a homework item",
            "action",
            "low",
            False,
            session_required=False,
            when_to_use="Use after a homework item is known and the user asks to view, open, parse, or check files/attachments for that homework.",
            example_utterances=[
                "看看这个作业有哪些附件",
                "preview attachments for this homework",
            ],
            args_json_schema={
                "type": "object",
                "properties": {
                    "homework_id": {"type": "string"},
                    "homework_title": {"type": "string"},
                    "lookup_hint": {"type": "string"},
                    "homework_detail_url": {"type": "string"},
                    "learn_base_url": {"type": "string"},
                    "session_cookie": {"type": "string"},
                    "cookies": {"type": "string"},
                    "homework_cookie": {"type": "string"},
                    "learn_cookie": {"type": "string"},
                    "csrf_token": {"type": "string"},
                    "include_feedback_attachments": {"type": "boolean"},
                },
                "required": [],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "upload_homework_attachment",
            "Upload one attachment into a homework submission form",
            "action",
            "medium",
            True,
            session_required=False,
            when_to_use="Use when the user asks to upload an attached local file into a specific homework submission. Preserve attached_file.file_uri and file_name from the user message.",
            avoid_when="Do not use for final submission without explicit submit intent; upload only stages the attachment.",
            example_utterances=[
                "把这个文件上传到作业",
                "upload the attached PDF to my homework",
            ],
            args_json_schema={
                "type": "object",
                "properties": {
                    "homework_id": {"type": "string"},
                    "homework_title": {"type": "string"},
                    "lookup_hint": {"type": "string"},
                    "xszyid": {"type": "string"},
                    "student_homework_id": {"type": "string"},
                    "course_id": {"type": "string"},
                    "wlkcid": {"type": "string"},
                    "file_path": {"type": "string"},
                    "file_uri": {"type": "string"},
                    "file_name": {"type": "string"},
                    "submission_text": {"type": "string"},
                    "homework_detail_url": {"type": "string"},
                    "learn_base_url": {"type": "string"},
                    "session_cookie": {"type": "string"},
                    "cookies": {"type": "string"},
                    "homework_cookie": {"type": "string"},
                    "learn_cookie": {"type": "string"},
                    "csrf_token": {"type": "string"},
                },
                "required": [],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "submit_homework",
            "Submit homework content and/or uploaded files",
            "action",
            "high",
            True,
            session_required=False,
            when_to_use="Use when the user explicitly asks to submit/turn in homework content or uploaded files.",
            avoid_when="Do not use for merely checking homework status or uploading a draft file.",
            example_utterances=[
                "提交这份作业",
                "turn in this homework with the attached file",
            ],
            args_json_schema={
                "type": "object",
                "properties": {
                    "homework_id": {"type": "string"},
                    "submission_session_id": {"type": "string"},
                    "homework_title": {"type": "string"},
                    "lookup_hint": {"type": "string"},
                    "zyid": {"type": "string"},
                    "homework_zyid": {"type": "string"},
                    "xszyid": {"type": "string"},
                    "student_homework_id": {"type": "string"},
                    "course_id": {"type": "string"},
                    "wlkcid": {"type": "string"},
                    "submission_text": {"type": "string"},
                    "attachment_tokens": {"type": "array", "items": {"type": "string"}},
                    "local_file_paths": {"type": "array", "items": {"type": "string"}},
                    "file_path": {"type": "string"},
                    "file_uri": {"type": "string"},
                    "file_name": {"type": "string"},
                    "homework_detail_url": {"type": "string"},
                    "learn_base_url": {"type": "string"},
                    "session_cookie": {"type": "string"},
                    "cookies": {"type": "string"},
                    "homework_cookie": {"type": "string"},
                    "learn_cookie": {"type": "string"},
                    "csrf_token": {"type": "string"},
                },
                "required": [],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "get_academic_calendar",
            "Fetch academic calendar events",
            "data",
            "low",
            False,
            session_required=True,
            args_schema={"start_date": "YYYYMMDD", "end_date": "YYYYMMDD"},
        ),
        SkillSpec(
            "get_campus_activities",
            "Fetch campus activity information on the Android device",
            "data",
            "low",
            False,
            session_required=False,
            when_to_use="Use when the user asks about campus events, lectures, activities, notices, registration opportunities, or 校园活动/讲座/资讯.",
            avoid_when="Do not prepend get_semesters unless the user explicitly asks about semesters or course terms.",
            example_utterances=[
                "帮我看看最近校内有什么与AI有关的活动",
                "近期有什么讲座",
                "campus events about AI",
            ],
            args_schema={
                "query": "string (optional; detailed question for activity RAG answer)",
                "keywords": "list[string] (optional)",
                "start_date": "YYYY-MM-DD or YYYYMMDD (optional)",
                "end_date": "YYYY-MM-DD or YYYYMMDD (optional)",
                "limit": "integer (optional)",
            },
        ),
        SkillSpec(
            "search",
            "Search the web and summarize retrieved evidence",
            "data",
            "low",
            False,
            session_required=True,
            when_to_use="Use when the user asks to search, look up, find current information, compare sources, or search 校内/校外 materials.",
            avoid_when="Do not use for simple casual chat or questions that can be answered directly without retrieval.",
            example_utterances=[
                "帮我搜索这个主题",
                "查一下校内关于AI的通知",
                "search the web for recent information",
            ],
            args_schema={
                "query": "string (required)",
                "scope": "web|all (optional)",
                "scene": "campus|general|hybrid (optional, default hybrid)",
                "max_results": "integer (optional)",
                "supplemental_results": "integer (optional, only for hybrid)",
                "domains": "list[string] (optional)",
                "freshness_days": "integer (optional)",
                "use_rag": "bool (optional)",
                "language": "string (optional)",
            },
        ),
        SkillSpec(
            "get_current_time",
            "Get current local time and timezone context for planning",
            "data",
            "low",
            False,
            session_required=False,
            when_to_use=(
                "Must be used before alarm/reminder/calendar planning when the user uses relative "
                "time such as 明天, 后天, 今晚, 下周, tomorrow, next Monday, or a calendar date "
                "without a year such as 6月21日下午4点."
            ),
            example_utterances=[
                "明早八点叫我",
                "remind me tomorrow",
            ],
            args_json_schema={
                "type": "object",
                "properties": {},
                "required": [],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "create_reminder",
            "Create a reminder item",
            "action",
            "medium",
            True,
            when_to_use="Use when the user asks to create a reminder, todo, task, 待办, 提醒事项, or wants to be reminded about something.",
            example_utterances=[
                "明天提醒我交作业",
                "create a todo for the meeting",
            ],
        ),
        SkillSpec(
            "create_calendar_event",
            "Create a calendar event on device for schedules, classes, deadlines, and task reminders",
            "action",
            "medium",
            True,
            when_to_use="Use when the user asks to add/create/schedule something on the system calendar or 日历.",
            avoid_when="Use create_reminder for lightweight todo/reminder requests that are not calendar events.",
            example_utterances=[
                "把这周的作业DDL加到日历",
                "schedule this meeting on my calendar",
            ],
            args_json_schema={
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "start_time": calendar_time_text_schema,
                    "end_time": {
                        **calendar_time_text_schema,
                        "description": (
                            calendar_time_text_schema["description"]
                            + " Optional; defaults to one hour after start_time when omitted."
                        ),
                    },
                    "current_time": {
                        "type": "string",
                        "description": "Optional current-time context injected by the server/runtime; planners should not invent it.",
                    },
                    **calendar_time_metadata_schema,
                    "timezone": timezone_schema,
                    "location": {"type": "string"},
                    "description": {"type": "string"},
                    "conflict_decision": {
                        "type": "string",
                        "enum": ["prompt_user", "skip_write", "coexist", "delete_conflicts"],
                    },
                    "allow_conflict_delete": {"type": "boolean"},
                },
                "required": ["title", "start_time"],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "detect_calendar_conflicts",
            "Detect conflicts between a candidate event and existing calendar events",
            "action",
            "low",
            False,
            args_json_schema={
                "type": "object",
                "properties": {
                    "start_time": calendar_time_text_schema,
                    "end_time": {
                        **calendar_time_text_schema,
                        "description": (
                            calendar_time_text_schema["description"]
                            + " Optional; defaults to one hour after start_time when omitted."
                        ),
                    },
                    "current_time": {
                        "type": "string",
                        "description": "Optional current-time context injected by the server/runtime; planners should not invent it.",
                    },
                    **calendar_time_metadata_schema,
                    "timezone": timezone_schema,
                },
                "required": ["start_time"],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "delete_calendar_event",
            "Delete calendar events by id (destructive)",
            "action",
            "high",
            True,
            args_json_schema={
                "type": "object",
                "properties": {
                    "event_id": {"type": "string"},
                    "event_ids": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                    "confirm_delete": {"type": "boolean"},
                },
                "required": ["confirm_delete"],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "set_alarm",
            "Set a one-off system alarm for a precise clock time; use for wake-up/timekeeping",
            "action",
            "low",
            False,
            when_to_use="Use when the user asks to set an alarm/闹钟 at a time.",
            example_utterances=[
                "设置明早八点的闹钟",
                "wake me up at 7:30",
            ],
            args_schema={
                "time": "string",
                "label": "string",
                "repeat": "string",
                "vibrate": "bool",
            },
            args_json_schema={
                "type": "object",
                "properties": {
                    "time": {
                        "type": "string",
                        "description": "Alarm time in local-time semantics. Accepts `HH:mm` (preferred) or local ISO8601 datetime, e.g. `07:30` / `2026-04-28T07:30:00`."
                    },
                    "label": {
                        "type": "string",
                        "description": "Optional label or message for the alarm"
                    },
                    "repeat": {
                        "type": "string",
                        "description": "Optional repeat rule"
                    },
                    "vibrate": {
                        "type": "boolean",
                        "description": "Optional flag to enable vibration"
                    }
                },
                "required": ["time"],
                "additionalProperties": False,
            },
        ),
        SkillSpec(
            "read_notifications",
            (
                "Read active/unread status bar notifications from the Android device. "
                "Use this to check incoming messages (WeChat, SMS, QQ, email) "
            ),
            "action",
            "low",
            False,
            when_to_use="Use when the user asks to read, check, summarize, or inspect unread system notifications/通知栏/未读通知.",
            example_utterances=[
                "读取一下未读通知",
                "summarize my unread notifications",
            ],
            args_schema={},
            args_json_schema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
        SkillSpec("show_summary", "Display a structured summary to the user", "action", "low", False),
        SkillSpec("send_notification", "Send a local system notification", "action", "low", False),
        SkillSpec("open_url", "Open a URL in-app or externally", "action", "low", False),
    ]:
        registry.register_spec(spec)

    try:
        try:
            from .skills.alarm_skills import GetCurrentTimeSkill, SetAlarmSkill
        except ImportError:
            from skills.alarm_skills import GetCurrentTimeSkill, SetAlarmSkill
        registry.register_handler("get_current_time", GetCurrentTimeSkill())
        registry.register_handler("set_alarm", SetAlarmSkill())
        from .skills.notification_skills import ReadNotificationsSkill
        registry.register_handler("read_notifications", ReadNotificationsSkill())
    except ImportError:
        pass
    search_skill_cls = None
    try:
        try:
            from .skills.search_skills import SearchSkill
        except ImportError:
            from skills.search_skills import SearchSkill
        search_skill_cls = SearchSkill
    except ImportError:
        try:
            from .skills.campus_data_skills import SearchSkill
        except ImportError:
            from skills.campus_data_skills import SearchSkill
        search_skill_cls = SearchSkill
    if search_skill_cls is not None:
        registry.register_handler("search", search_skill_cls())

    campus_activities_skill_cls = None
    try:
        try:
            from .skills.campus_news_skills import CampusActivitiesSkill
        except ImportError:
            from skills.campus_news_skills import CampusActivitiesSkill
        campus_activities_skill_cls = CampusActivitiesSkill
    except ImportError:
        try:
            from .skills.campus_data_skills import CampusActivitiesSkill
        except ImportError:
            from skills.campus_data_skills import CampusActivitiesSkill
        campus_activities_skill_cls = CampusActivitiesSkill
    if campus_activities_skill_cls is not None:
        registry.register_handler("get_campus_activities", campus_activities_skill_cls())

    try:
        try:
            from .skills.summary_skills import CreateReminderSkill, OpenUrlSkill, SendNotificationSkill, ShowSummarySkill
        except ImportError:
            from skills.summary_skills import CreateReminderSkill, OpenUrlSkill, SendNotificationSkill, ShowSummarySkill
        registry.register_handler("create_reminder", CreateReminderSkill())
        registry.register_handler("show_summary", ShowSummarySkill())
        registry.register_handler("send_notification", SendNotificationSkill())
        registry.register_handler("open_url", OpenUrlSkill())
    except ImportError:
        pass
    try:
        try:
            from .skills.course_info_skills import register_course_info_handlers
        except ImportError:
            from skills.course_info_skills import register_course_info_handlers
        register_course_info_handlers(registry)
    except ImportError:
        pass

    try:
        try:
            from .skills.campus_data_skills import build_static_campus_data_handlers
        except ImportError:
            from skills.campus_data_skills import build_static_campus_data_handlers
        for skill_name, handler in build_static_campus_data_handlers().items():
            if isinstance(registry.get_handler(skill_name), MissingSkillHandler):
                registry.register_handler(skill_name, handler)
    except ImportError:
        pass
    register_calendar_handlers(registry)
    register_homework_handlers(registry)
    return registry
