from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from typing import Any, Protocol

try:
    from .calendar_handlers import register_calendar_handlers
except ImportError:
    from calendar_handlers import register_calendar_handlers


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

    for spec in [
        SkillSpec("login", "Authenticate with Tsinghua identity system", "auth", "low", False),
        SkillSpec("refresh_session", "Refresh an existing learn session", "auth", "low", False, session_required=True),
        SkillSpec("logout", "Clear local session and upstream learn session", "auth", "low", False, session_required=True),
        SkillSpec("get_user_info", "Fetch student profile", "data", "low", False, session_required=True),
        SkillSpec("get_semesters", "Fetch semester list and current semester", "data", "low", False, session_required=True),
        SkillSpec(
            "get_courses",
            "Fetch course list for a semester",
            "data",
            "low",
            False,
            session_required=True,
            args_schema={"semester_id": "string"},
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
            "Fetch campus activity information",
            "data",
            "low",
            False,
            session_required=True,
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
            args_json_schema={
                "type": "object",
                "properties": {},
                "required": [],
                "additionalProperties": False,
            },
        ),
        SkillSpec("create_reminder", "Create a reminder item", "action", "medium", True),
        SkillSpec(
            "create_calendar_event",
            "Create a system calendar event",
            "action",
            "medium",
            True,
            args_json_schema={
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "start_time": {"type": "string"},
                    "end_time": {"type": "string"},
                    "location": {"type": "string"},
                    "description": {"type": "string"},
                    "conflict_decision": {
                        "type": "string",
                        "enum": ["prompt_user", "skip_write", "coexist", "delete_conflicts"],
                    },
                    "allow_conflict_delete": {"type": "boolean"},
                },
                "required": ["title", "start_time", "end_time"],
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
                    "start_time": {"type": "string"},
                    "end_time": {"type": "string"},
                },
                "required": ["start_time", "end_time"],
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
            "Set a system alarm",
            "action",
            "low",
            False,
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
            from .skills.campus_data_skills import build_static_campus_data_handlers
        except ImportError:
            from skills.campus_data_skills import build_static_campus_data_handlers
        for skill_name, handler in build_static_campus_data_handlers().items():
            if isinstance(registry.get_handler(skill_name), MissingSkillHandler):
                registry.register_handler(skill_name, handler)
    except ImportError:
        pass
    register_calendar_handlers(registry)
    return registry
