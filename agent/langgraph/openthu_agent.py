from __future__ import annotations

import argparse
import json
import logging
import os
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, TypedDict
from uuid import uuid4

logger = logging.getLogger("openthu_agent")

from langgraph.graph import END, START, StateGraph

try:
    from .skill_core import SkillInvocation, SkillRegistry, build_default_registry
    from .skill_manager import SkillManager
except ImportError:
    from skill_core import SkillInvocation, SkillRegistry, build_default_registry
    from skill_manager import SkillManager


class AgentState(TypedDict, total=False):
    request_id: str
    session: dict[str, Any]
    task_id: str
    task_status: str
    semester_id: str
    user_input: str
    user_id: str
    approve_sensitive: bool
    standardized_prompt: dict[str, Any]
    normalization_warnings: list[str]
    skill_plan: list[dict[str, Any]]
    safety_report: dict[str, Any]
    approved_skills: list[dict[str, Any]]
    blocked_skills: list[dict[str, Any]]
    skill_results: list[dict[str, Any]]
    failed_skills: list[dict[str, Any]]
    needs_replan: bool
    replanned_skills: list[dict[str, Any]]
    audit_log: list[dict[str, Any]]
    approval_records: list[dict[str, Any]]
    memory_update: dict[str, Any]
    final_response: dict[str, Any]
    trace_log: list[dict[str, Any]]
    normalizer_source: str
    planner_source: str


class StructuredPrompt(TypedDict):
    objective: str
    entities: list[str]
    constraints: list[str]
    success_criteria: list[str]
    sensitivity: str


TASK_ENTITIES = {
    "assignments",
    "courses",
    "notices",
    "files",
    "activities",
    "search",
    "user_profile",
    "semesters",
    "academic_calendar",
    "reminder",
    "calendar",
    "calendar_conflict",
    "calendar_delete",
    "alarm",
    "current_time",
    "notification",
    "system_notifications",
    "session_refresh",
    "logout",
    "auth",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def risk_rank(risk_level: str) -> int:
    return {"low": 0, "medium": 1, "high": 2}.get(risk_level, 1)


def normalize_risk(risk_level: str) -> str:
    risk = str(risk_level).strip().lower()
    return risk if risk in {"low", "medium", "high"} else "medium"


class RequirementLLM:
    """Normalize user requirement into a stable prompt structure."""

    def __init__(self, model: str = "gpt-4.1-mini", base_url: str = "") -> None:
        self.model = model
        self.base_url = base_url.strip()
        self.last_mode = "fallback"
        self.last_error = ""

    def normalize(
        self,
        user_input: str,
        api_key: str = "",
        model: str = "",
        base_url: str = "",
    ) -> dict[str, Any]:
        openai_key = (api_key or os.getenv("OPENAI_API_KEY", "")).strip()
        if not openai_key:
            return self._fallback(user_input)
        resolved_model = (model or self.model).strip()
        resolved_base_url = (base_url or self.base_url).strip()

        try:
            from openai import OpenAI

            if resolved_base_url:
                client = OpenAI(api_key=openai_key, base_url=resolved_base_url)
            else:
                client = OpenAI(api_key=openai_key)
            system_prompt = (
                "Convert the user requirement into strict JSON with keys: "
                "objective, entities, constraints, success_criteria, sensitivity. "
                "Use concise, execution-oriented values. Return JSON only."
            )
            if not resolved_base_url:
                try:
                    response = client.responses.create(
                        model=resolved_model,
                        input=[
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": user_input},
                        ],
                        max_output_tokens=500,
                        temperature=0.1,
                    )
                    self.last_mode = "llm_responses"
                    self.last_error = ""
                    return json.loads(self._extract_json_text(response.output_text.strip()))
                except Exception:
                    pass
            completion = client.chat.completions.create(
                model=resolved_model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_input},
                ],
                max_tokens=500,
                temperature=0.1,
            )
            content = completion.choices[0].message.content or ""
            self.last_mode = "llm_chat_completions"
            self.last_error = ""
            return json.loads(self._extract_json_text(content.strip()))
        except Exception as exc:
            self.last_mode = "fallback"
            self.last_error = f"{type(exc).__name__}: {exc}"
            return self._fallback(user_input)

    def _extract_json_text(self, text: str) -> str:
        stripped = text.strip()
        if stripped.startswith("```"):
            lines = stripped.splitlines()
            if lines and lines[0].startswith("```"):
                lines = lines[1:]
            if lines and lines[-1].startswith("```"):
                lines = lines[:-1]
            stripped = "\n".join(lines).strip()
        return stripped

    def _fallback(self, user_input: str) -> dict[str, Any]:
        lower = user_input.lower()
        entities: list[str] = []
        sensitivity = "low"

        if any(token in lower for token in ["作业", "ddl", "deadline", "assignment", "homework"]):
            entities.append("assignments")
        if any(token in lower for token in ["课程", "课表", "上课", "course", "schedule"]):
            entities.append("courses")
        system_notification_intent = any(
            token in lower
            for token in [
                "未读通知",
                "系统通知",
                "手机通知",
                "通知栏",
                "读取通知",
                "读通知",
                "read notifications",
                "unread notification",
                "unread notifications",
            ]
        )
        if any(token in lower for token in ["通知", "公告", "notice", "消息", "门户"]) and not system_notification_intent:
            entities.append("notices")
        if system_notification_intent:
            entities.append("system_notifications")
        if any(token in lower for token in ["文件", "课件", "资料", "file"]):
            entities.append("files")
        if any(token in lower for token in ["活动", "讲座", "资讯", "校园", "activity", "news"]):
            entities.append("activities")
        if any(token in lower for token in ["搜索", "查找", "search"]):
            entities.append("search")
        if any(token in lower for token in ["个人信息", "学号", "院系", "profile", "user info"]):
            entities.append("user_profile")
        if any(token in lower for token in ["学期", "semester"]):
            entities.append("semesters")
        if any(token in lower for token in ["校历", "教务日历", "academic calendar"]):
            entities.append("academic_calendar")
        if any(token in lower for token in ["提醒", "待办", "reminder"]):
            entities.append("reminder")
        if any(token in lower for token in ["日历", "calendar"]):
            entities.append("calendar")
        if any(token in lower for token in ["冲突", "conflict", "重叠", "overlap"]):
            entities.append("calendar_conflict")
        if any(token in lower for token in ["删除日历", "删除日程", "删除事项", "delete calendar", "delete event", "remove event"]):
            entities.append("calendar_delete")
        if any(token in lower for token in ["闹钟", "alarm"]):
            entities.append("alarm")
        if any(token in lower for token in ["当前时间", "现在几点", "几点了", "time now", "current time"]):
            entities.append("current_time")
        if any(token in lower for token in ["通知我", "推送", "notification"]):
            entities.append("notification")
        if any(token in lower for token in ["刷新会话", "刷新登录", "refresh session"]):
            entities.append("session_refresh")
        if any(token in lower for token in ["退出登录", "注销", "logout"]):
            entities.append("logout")
        if any(token in lower for token in ["登录", "password", "验证码", "login", "account"]):
            entities.append("auth")
            sensitivity = "high"

        if not entities:
            entities.append("general_task")

        return {
            "objective": user_input.strip(),
            "entities": entities,
            "constraints": [
                "skill-first-execution",
                "approval-required-for-medium-and-high-risk",
            ],
            "success_criteria": [
                "skill plan generated",
                "safety check completed",
                "execution trace auditable",
            ],
            "sensitivity": sensitivity,
        }


class OpenTHULangGraphAgent:
    def __init__(
        self,
        memory_file: Path,
        skill_registry: SkillRegistry | None = None,
        skill_manager: SkillManager | None = None,
        llm_model: str = "gpt-4.1-mini",
        llm_base_url: str = "",
    ) -> None:
        self.memory_file = memory_file
        self.llm = RequirementLLM(model=llm_model, base_url=llm_base_url)
        if skill_manager is not None:
            self.skill_manager = skill_manager
        elif skill_registry is not None:
            self.skill_manager = SkillManager(registry=skill_registry)
        else:
            self.skill_manager = SkillManager(registry=build_default_registry())
        self.skill_registry = self.skill_manager.registry
        self.graph = self._build_graph()

    def _build_graph(self):
        workflow = StateGraph(AgentState)

        workflow.add_node("normalize_requirement", self._normalize_requirement)
        workflow.add_node("plan_skills", self._plan_skills)
        workflow.add_node("safety_check", self._safety_check)
        workflow.add_node("execute_skills", self._execute_skills)
        workflow.add_node("replan_failed", self._replan_failed)
        workflow.add_node("audit_record", self._audit_record)
        workflow.add_node("memory_update", self._memory_update)
        workflow.add_node("finalize", self._finalize)

        workflow.add_edge(START, "normalize_requirement")
        workflow.add_edge("normalize_requirement", "plan_skills")
        workflow.add_edge("plan_skills", "safety_check")
        workflow.add_edge("safety_check", "execute_skills")
        workflow.add_conditional_edges(
            "execute_skills",
            self._route_after_execution,
            {
                "replan_failed": "replan_failed",
                "audit_record": "audit_record",
            },
        )
        workflow.add_edge("replan_failed", "audit_record")
        workflow.add_edge("audit_record", "memory_update")
        workflow.add_edge("memory_update", "finalize")
        workflow.add_edge("finalize", END)

        return workflow.compile()

    def run(
        self,
        user_input: str,
        user_id: str = "demo_user",
        approve_sensitive: bool = False,
        session: dict[str, Any] | None = None,
        semester_id: str = "",
    ) -> dict[str, Any]:
        logger.info(
            "[agent.run] user_id=%s approve_sensitive=%s input=%r",
            user_id,
            approve_sensitive,
            user_input[:120],
        )
        initial = self._build_initial_state(
            user_input=user_input,
            user_id=user_id,
            approve_sensitive=approve_sensitive,
            session=session,
            semester_id=semester_id,
        )
        logger.debug("[agent.run] initial task_id=%s request_id=%s", initial["task_id"], initial["request_id"])
        result = self.graph.invoke(initial)
        logger.info(
            "[agent.run] done task_id=%s task_status=%s",
            result.get("task_id", ""),
            result.get("task_status", ""),
        )
        return result

    def run_plan_only(
        self,
        user_input: str,
        user_id: str = "demo_user",
        approve_sensitive: bool = False,
        session: dict[str, Any] | None = None,
        semester_id: str = "",
    ) -> dict[str, Any]:
        logger.info(
            "[agent.run_plan_only] user_id=%s approve_sensitive=%s input=%r",
            user_id,
            approve_sensitive,
            user_input[:120],
        )
        state = self._build_initial_state(
            user_input=user_input,
            user_id=user_id,
            approve_sensitive=approve_sensitive,
            session=session,
            semester_id=semester_id,
        )
        logger.debug(
            "[agent.run_plan_only] task_id=%s request_id=%s",
            state["task_id"],
            state["request_id"],
        )

        for node_fn in (
            self._normalize_requirement,
            self._plan_skills,
            self._safety_check,
            self._audit_record,
            self._memory_update,
        ):
            state.update(node_fn(state))

        approved_count = len(state.get("approved_skills", []))
        blocked_count = len(state.get("blocked_skills", []))
        if approved_count > 0:
            task_status = "ready_for_device_execution"
        elif blocked_count > 0:
            task_status = "approval_required"
        else:
            task_status = "planned"
        state["task_status"] = task_status

        code = "OK" if approved_count > 0 else "NO_ACTION_PLANNED"
        if task_status == "approval_required":
            message = "Plan generated but waiting for approval"
        elif code == "OK":
            message = "Plan generated and ready for device execution"
        else:
            message = "No executable skills generated by planner"

        logger.info(
            "[agent.run_plan_only] finished task_id=%s code=%s task_status=%s "
            "approved=%d blocked=%d normalizer=%s planner=%s",
            state["task_id"],
            code,
            task_status,
            approved_count,
            blocked_count,
            state.get("normalizer_source", "?"),
            state.get("planner_source", "?"),
        )
        if approved_count:
            approved_names = [
                item.get("skill_name", "?") for item in state.get("approved_skills", [])
                if isinstance(item, dict)
            ]
            logger.info("[agent.run_plan_only] approved skill chain: %s", approved_names)
        return {
            "request_id": state["request_id"],
            "code": code,
            "message": message,
            "data": {
                "mode": "plan_only",
                "task_id": state["task_id"],
                "task_status": state.get("task_status", "planned"),
                "session": state.get("session", {}),
                "standardized_prompt": state.get("standardized_prompt", {}),
                "normalization_warnings": state.get("normalization_warnings", []),
                "skill_plan": state.get("skill_plan", []),
                "approved_skills": state.get("approved_skills", []),
                "blocked_skills": state.get("blocked_skills", []),
                "safety_report": state.get("safety_report", {}),
                "approval_records": state.get("approval_records", []),
                "audit_log": state.get("audit_log", []),
                "memory_update": state.get("memory_update", {}),
                "trace_log": state.get("trace_log", []),
                "available_skills": self.skill_manager.list_for_planner(),
                "normalizer_source": state.get("normalizer_source", "fallback"),
                "planner_source": state.get("planner_source", "fallback"),
            },
        }

    def chat_turn(
        self,
        user_input: str,
        user_id: str = "demo_user",
        session: dict[str, Any] | None = None,
        history: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        text = user_input.strip()
        if not text:
            return {
                "request_id": f"chat_{uuid4().hex[:12]}",
                "code": "INVALID_PARAM",
                "message": "empty message",
                "data": {
                    "mode": "chat",
                    "should_plan": False,
                    "reply": "",
                    "confidence": 1.0,
                    "source": "rule",
                },
            }

        llm_result = self._chat_turn_via_llm(
            user_input=text,
            user_id=user_id,
            session=session or {},
            history=history or [],
        )
        if llm_result is not None:
            return llm_result

        fallback = self._classify_conversation_fallback(text)
        return {
            "request_id": f"chat_{uuid4().hex[:12]}",
            "code": "OK",
            "message": "chat turn generated",
            "data": fallback,
        }

    def _chat_turn_via_llm(
        self,
        *,
        user_input: str,
        user_id: str,
        session: dict[str, Any],
        history: list[dict[str, Any]],
    ) -> dict[str, Any] | None:
        api_key, model, base_url = self._llm_config_from_session(session)
        if not api_key:
            return None

        system_prompt = (
            "你是 OpenTHU 移动端里的对话式校园助手。"
            "用户可以闲聊、提问，也可以让你执行提醒、日历、校园信息、搜索、通知读取等任务。"
            "请先判断用户这句话是否需要进入工具/skill 规划。"
            "只有当用户明确要求你执行、查询、创建、设置、读取、打开、删除或安排某件事时，should_plan 才为 true。"
            "普通寒暄、身份问题、能力咨询、开放聊天、常识性问答都应直接自然回复，should_plan 为 false。"
            "返回严格 JSON：{\"should_plan\": boolean, \"reply\": \"自然语言回复\", \"confidence\": 0到1之间数字}。"
            "不要返回 Markdown 代码块，不要暴露内部 JSON、skill 名称或执行记录。"
            "当 should_plan 为 true 时，reply 用一句简短自然的话说明你会处理；具体结果之后由执行链路返回。"
        )
        compact_history = []
        for item in history[-8:]:
            if not isinstance(item, dict):
                continue
            role = str(item.get("role", "")).strip().lower()
            text = str(item.get("text", "")).strip()
            if role not in {"user", "assistant"} or not text:
                continue
            compact_history.append({"role": role, "content": text[:1000]})

        try:
            from openai import OpenAI

            client = self._create_openai_client(OpenAI, api_key, base_url=base_url)
            messages = [{"role": "system", "content": system_prompt}] + compact_history
            messages.append({"role": "user", "content": user_input})
            completion = client.chat.completions.create(
                model=model,
                messages=messages,
                max_tokens=600,
                temperature=0.4,
            )
            raw = (completion.choices[0].message.content or "").strip()
            parsed = json.loads(self.llm._extract_json_text(raw))
            if not isinstance(parsed, dict):
                return None
            should_plan = bool(parsed.get("should_plan", False))
            reply = str(parsed.get("reply", "")).strip()
            confidence = float(parsed.get("confidence", 0.7) or 0.7)
            if not reply:
                reply = "好的，我来处理。" if should_plan else self._fallback_chat_reply(user_input)
            return {
                "request_id": f"chat_{uuid4().hex[:12]}",
                "code": "OK",
                "message": "chat turn generated",
                "data": {
                    "mode": "task" if should_plan else "chat",
                    "should_plan": should_plan,
                    "reply": reply,
                    "confidence": max(0.0, min(confidence, 1.0)),
                    "source": "llm",
                    "user_id": user_id,
                },
            }
        except Exception as exc:
            logger.warning("[agent.chat] LLM chat turn failed: %s", exc)
            return None

    def _classify_conversation_fallback(self, user_input: str) -> dict[str, Any]:
        normalized = user_input.strip().lower()
        structured = self.llm._fallback(user_input)
        entities = set(structured.get("entities", []))
        should_plan = bool(entities & TASK_ENTITIES)
        if entities == {"general_task"}:
            should_plan = self._looks_like_task_request(normalized)

        return {
            "mode": "task" if should_plan else "chat",
            "should_plan": should_plan,
            "reply": "好的，我来处理。" if should_plan else self._fallback_chat_reply(user_input),
            "confidence": 0.72 if should_plan else 0.64,
            "source": "rule",
        }

    def _looks_like_task_request(self, normalized_text: str) -> bool:
        conversation_markers = [
            "你能帮我做什么",
            "可以帮我做什么",
            "你能做什么",
            "能干什么",
            "讲个笑话",
            "聊聊",
            "随便聊",
            "how are you",
            "你好吗",
            "最近怎么样",
        ]
        if any(marker in normalized_text for marker in conversation_markers):
            return False
        task_markers = [
            "帮我",
            "请帮",
            "请你",
            "麻烦",
            "安排",
            "创建",
            "设置",
            "提醒",
            "查询",
            "搜索",
            "查找",
            "读取",
            "打开",
            "加入",
            "写入",
            "删除",
            "同步",
            "总结",
            "处理",
            "plan",
            "schedule",
            "remind",
            "search",
            "open",
            "create",
            "set ",
        ]
        return any(marker in normalized_text for marker in task_markers)

    def _fallback_chat_reply(self, user_input: str) -> str:
        normalized = user_input.strip().lower()
        has_english = any("a" <= char <= "z" for char in normalized)
        greetings = {"hi", "hello", "hey", "你好", "嗨", "在吗", "早上好", "晚上好"}
        if any(token in normalized for token in ["how are you", "how's it going", "你好吗", "最近怎么样"]):
            return self._stable_chat_choice(
                normalized,
                [
                    "我状态不错，正在努力从“任务机器”进化成更会聊天的助手。你呢，今天怎么样？",
                    "我在，感觉还挺清醒的。更想问问你：今天过得顺吗？",
                    "Doing alright. 我这边在线，也愿意先闲聊一会儿。How about you?",
                ],
            )
        if normalized in greetings or (len(normalized) <= 12 and any(item in normalized for item in greetings)):
            return self._stable_chat_choice(
                normalized,
                [
                    "Hi，我在。今天想先聊两句，还是让我帮你处理点什么？",
                    "来了。你直接说就行，我会先按聊天接住，需要执行时再动工具。",
                    "Hello。今天我不急着进入任务模式，先听你说。",
                ],
            )
        if any(token in normalized for token in ["who are you", "what are you", "你是谁", "你是做什么的", "你是干嘛的"]):
            return self._stable_chat_choice(
                normalized,
                [
                    "我是 OpenTHU 助手，一个偏校园场景的对话式 Agent。你可以把我当聊天入口，也可以让我处理提醒、日历、校园活动、搜索和通知。",
                    "我是 OpenTHU。目标不是只给你执行记录，而是像正常助手一样先听懂你，再在需要时调用工具。",
                    "你可以把我理解成校园移动端里的 Agent：平时能聊天，遇到明确任务时才会规划和请求确认。",
                ],
            )
        if any(token in normalized for token in ["你能做什么", "能干什么", "help", "帮助", "怎么用"]):
            return self._stable_chat_choice(
                normalized,
                [
                    "你可以直接自然说，比如“明早八点提醒我交作业”“帮我看看近期校园活动”“搜索一下某个话题”。如果只是聊天，我也会按聊天回应。",
                    "我主要能做两类事：陪你正常对话，以及在你明确提出任务时处理提醒、日历、活动、搜索和通知。",
                    "你不用背命令。像和人说话一样描述目标就行；需要权限或确认的时候，我会在对话里问你。",
                ],
            )
        if "讲个笑话" in normalized:
            return self._stable_chat_choice(
                normalized,
                [
                    "可以。一个 Agent 最怕什么？用户说“随便聊聊”，它立刻生成了三步执行计划。",
                    "讲一个项目里的冷笑话：以前我听到 hello，也想进入任务规划。现在至少知道先打招呼了。",
                    "可以。我的成长路线大概是：先别把所有话都当需求，然后再学会不要把所有回应都写成工作总结。",
                ],
            )
        if any(token in normalized for token in ["谢谢", "thank"]):
            return self._stable_chat_choice(
                normalized,
                [
                    "不客气。你继续说，我跟着。",
                    "没事，我在。",
                    "当然。需要继续聊或者接着处理任务，都可以。",
                ],
            )
        if any(token in normalized for token in ["累", "烦", "焦虑", "难受", "不开心", "emo"]):
            return self._stable_chat_choice(
                normalized,
                [
                    "听起来今天有点消耗。要不先别急着解决问题，你可以把发生了什么慢慢说给我听。",
                    "我听见了。你不用马上整理成一个清楚的问题，先把感觉说出来也可以。",
                    "这听起来不太轻松。我可以陪你捋一下，也可以只安静接住你现在的状态。",
                ],
            )
        if has_english:
            return self._stable_chat_choice(
                normalized,
                [
                    "I’m here. We can chat normally; if you want me to do something, just say it naturally.",
                    "Got you. I’ll treat this as a regular conversation unless you ask me to take an action.",
                    "I’m listening. What’s on your mind?",
                ],
            )
        return self._stable_chat_choice(
            normalized,
            [
                "我在听。你可以继续往下说一点，我会按聊天接住。",
                "嗯，我明白你的意思。你想顺着这个话题聊，还是让我帮你整理一下？",
                "收到。这个我先不当成任务，只当作普通聊天。你可以接着说。",
                "可以，我们就按聊天来。你想从哪里开始？",
            ],
        )

    def _stable_chat_choice(self, seed: str, replies: list[str]) -> str:
        if not replies:
            return ""
        return replies[sum(ord(char) for char in seed) % len(replies)]

    def _build_initial_state(
        self,
        user_input: str,
        user_id: str,
        approve_sensitive: bool,
        session: dict[str, Any] | None,
        semester_id: str,
    ) -> AgentState:
        return {
            "request_id": f"req_{uuid4().hex[:12]}",
            "session": session or {},
            "task_id": f"task_{uuid4().hex[:10]}",
            "task_status": "planned",
            "semester_id": semester_id,
            "user_input": user_input,
            "user_id": user_id,
            "approve_sensitive": approve_sensitive,
        }

    def _normalize_requirement(self, state: AgentState) -> dict[str, Any]:
        logger.debug(
            "[node.normalize] task_id=%s input=%r",
            state.get("task_id", ""),
            state["user_input"][:100],
        )
        api_key, model, base_url = self._llm_config_from_state(state)
        raw_prompt = self.llm.normalize(
            state["user_input"],
            api_key=api_key,
            model=model,
            base_url=base_url,
        )
        standardized, warnings = self._coerce_structured_prompt(
            raw_prompt,
            fallback_text=state["user_input"],
        )
        logger.info(
            "[node.normalize] task_id=%s source=%s entities=%s sensitivity=%s warnings=%d",
            state.get("task_id", ""),
            self.llm.last_mode,
            standardized["entities"],
            standardized["sensitivity"],
            len(warnings),
        )
        if self.llm.last_error:
            logger.warning(
                "[node.normalize] llm error task_id=%s error=%s",
                state.get("task_id", ""),
                self.llm.last_error,
            )
        if warnings:
            logger.debug("[node.normalize] warnings task_id=%s: %s", state.get("task_id", ""), warnings)
        return {
            "standardized_prompt": standardized,
            "normalization_warnings": warnings,
            "normalizer_source": self.llm.last_mode,
            "trace_log": self._append_trace(
                state,
                node="normalize_requirement",
                detail={
                    "entities": standardized["entities"],
                    "sensitivity": standardized["sensitivity"],
                    "warnings_count": len(warnings),
                    "source": self.llm.last_mode,
                    "error": self.llm.last_error,
                },
            ),
        }

    def _plan_skills(self, state: AgentState) -> dict[str, Any]:
        logger.debug("[node.plan] task_id=%s", state.get("task_id", ""))
        structured_prompt = self._coerce_prompt_from_state(state)
        planned = self._plan_skills_via_llm(state, structured_prompt)
        planner_source = "llm"
        if not planned:
            logger.debug("[node.plan] task_id=%s llm returned empty, falling back to rule-based planner", state.get("task_id", ""))
            planned = self._fallback_skill_plan(state, structured_prompt)
            planner_source = "fallback"

        if not planned:
            logger.warning(
                "[node.plan] task_id=%s no skills generated, inserting show_summary placeholder",
                state.get("task_id", ""),
            )
            planned = [
                self._build_skill_invocation(
                    skill_name="show_summary",
                    task_id=state["task_id"],
                    args={
                        "title": "需求解析结果",
                        "content": "当前没有足够信息生成执行计划，请补充目标或上下文。",
                        "format": "plain",
                    },
                    description="在端侧展示规划失败原因，等待用户补充信息",
                )
            ]
            planner_source = "fallback"

        skill_names = [item.get("skill_name", "") for item in planned]
        logger.info(
            "[node.plan] task_id=%s source=%s planned=%d skills=%s",
            state.get("task_id", ""),
            planner_source,
            len(planned),
            skill_names,
        )
        return {
            "skill_plan": planned,
            "planner_source": planner_source,
            "task_status": "planned",
            "trace_log": self._append_trace(
                state,
                node="plan_skills",
                detail={
                    "planned_count": len(planned),
                    "planned_skills": [item.get("skill_name", "") for item in planned],
                    "source": planner_source,
                },
            ),
        }

    def _safety_check(self, state: AgentState) -> dict[str, Any]:
        logger.debug(
            "[node.safety] task_id=%s approve_sensitive=%s plan_size=%d",
            state.get("task_id", ""),
            state.get("approve_sensitive", False),
            len(state.get("skill_plan", [])),
        )
        approve_sensitive = state.get("approve_sensitive", False)
        structured_prompt = self._coerce_prompt_from_state(state)
        plan = state.get("skill_plan", [])

        approved: list[dict[str, Any]] = []
        blocked: list[dict[str, Any]] = []
        approval_records: list[dict[str, Any]] = []
        risk_details: list[dict[str, Any]] = []

        for planned_skill in plan:
            assessed_risk, risk_reason, risk_source = self._assess_skill_risk(
                planned_skill,
                structured_prompt,
            )
            invocation = dict(planned_skill)
            invocation["risk_level"] = assessed_risk
            invocation["requires_approval"] = assessed_risk in {"medium", "high"}
            invocation["risk_reason"] = risk_reason
            invocation["risk_source"] = risk_source

            record = {
                "skill_name": invocation["skill_name"],
                "request_id": invocation["request_id"],
                "risk_level": assessed_risk,
                "reason": risk_reason,
                "source": risk_source,
            }
            risk_details.append(record)

            if invocation["requires_approval"] and not approve_sensitive:
                invocation["status"] = "pending_approval"
                blocked.append(invocation)
                logger.warning(
                    "[node.safety] task_id=%s skill_name=%s BLOCKED risk=%s reason=%r",
                    state.get("task_id", ""),
                    invocation["skill_name"],
                    assessed_risk,
                    risk_reason,
                )
                approval_records.append(
                    {
                        "approval_id": f"apv_{uuid4().hex[:10]}",
                        "task_id": state["task_id"],
                        "skill_name": invocation["skill_name"],
                        "request_id": invocation["request_id"],
                        "risk_level": assessed_risk,
                        "reason": risk_reason,
                        "decision": "pending",
                        "operator": "user",
                        "decided_at": utc_now(),
                    }
                )
            else:
                invocation["status"] = "approved"
                approved.append(invocation)
                logger.debug(
                    "[node.safety] task_id=%s skill_name=%s APPROVED risk=%s",
                    state.get("task_id", ""),
                    invocation["skill_name"],
                    assessed_risk,
                )
                if invocation["requires_approval"]:
                    approval_records.append(
                        {
                            "approval_id": f"apv_{uuid4().hex[:10]}",
                            "task_id": state["task_id"],
                            "skill_name": invocation["skill_name"],
                            "request_id": invocation["request_id"],
                            "risk_level": assessed_risk,
                            "reason": risk_reason,
                            "decision": "approved",
                            "operator": "user",
                            "decided_at": utc_now(),
                        }
                    )

        updated_plan = self._merge_plan_statuses(
            plan=plan,
            approved=approved,
            blocked=blocked,
        )
        logger.info(
            "[node.safety] task_id=%s approved=%d blocked=%d",
            state.get("task_id", ""),
            len(approved),
            len(blocked),
        )
        return {
            "skill_plan": updated_plan,
            "approved_skills": approved,
            "blocked_skills": blocked,
            "approval_records": approval_records,
            "safety_report": {
                "approved_count": len(approved),
                "blocked_count": len(blocked),
                "risk_details": risk_details,
            },
            "task_status": "in_progress" if approved else "planned",
            "trace_log": self._append_trace(
                state,
                node="safety_check",
                detail={
                    "approved_count": len(approved),
                    "blocked_count": len(blocked),
                    "blocked_skills": [item.get("skill_name", "") for item in blocked],
                },
            ),
        }

    def _execute_skills(self, state: AgentState) -> dict[str, Any]:
        approved_skills = state.get("approved_skills", [])
        logger.info(
            "[node.execute] task_id=%s executing %d approved skill(s)",
            state.get("task_id", ""),
            len(approved_skills),
        )
        current_plan = state.get("skill_plan", [])
        current_session = dict(state.get("session", {}))
        skill_results: list[dict[str, Any]] = []
        failed_skills: list[dict[str, Any]] = []

        for invocation_dict in approved_skills:
            invocation = self._skill_invocation_from_dict(invocation_dict)
            logger.debug(
                "[node.execute] task_id=%s invoking skill_name=%s request_id=%s args=%s",
                state.get("task_id", ""),
                invocation.skill_name,
                invocation.request_id,
                json.dumps(invocation.args, ensure_ascii=False),
            )
            result = self.skill_manager.execute(invocation, current_session, state)

            success = result.get("code") == "OK"
            result["task_id"] = state["task_id"]
            result["status"] = "executed" if success else "failed"
            result["success"] = success
            result["skill_name"] = invocation.skill_name
            result["description"] = invocation.description
            skill_results.append(result)

            if success:
                logger.info(
                    "[node.execute] skill_name=%s request_id=%s OK",
                    invocation.skill_name,
                    invocation.request_id,
                )
                maybe_session = result.get("data", {}).get("session")
                if isinstance(maybe_session, dict):
                    current_session = maybe_session
            else:
                logger.warning(
                    "[node.execute] skill_name=%s request_id=%s FAILED code=%s message=%r",
                    invocation.skill_name,
                    invocation.request_id,
                    result.get("code", "SKILL_EXECUTION_FAILED"),
                    result.get("data", {}).get("message", ""),
                )
                failed = dict(invocation_dict)
                failed["status"] = "failed"
                failed["failure_code"] = result.get("code", "SKILL_EXECUTION_FAILED")
                failed["failure_message"] = result.get("data", {}).get("message", "")
                failed_skills.append(failed)

        updated_plan = self._apply_result_statuses(current_plan, skill_results)

        if not approved_skills and state.get("blocked_skills"):
            task_status = "planned"
        elif failed_skills:
            task_status = "in_progress"
        else:
            task_status = "completed"

        logger.info(
            "[node.execute] task_id=%s done executed=%d failed=%d task_status=%s",
            state.get("task_id", ""),
            len(skill_results),
            len(failed_skills),
            task_status,
        )
        return {
            "session": current_session,
            "skill_plan": updated_plan,
            "skill_results": skill_results,
            "failed_skills": failed_skills,
            "needs_replan": bool(failed_skills),
            "task_status": task_status,
            "trace_log": self._append_trace(
                state,
                node="execute_skills",
                detail={
                    "executed_count": len(skill_results),
                    "failed_count": len(failed_skills),
                    "result_codes": [item.get("code", "") for item in skill_results],
                },
            ),
        }

    def _route_after_execution(self, state: AgentState) -> str:
        return "replan_failed" if state.get("needs_replan") else "audit_record"

    def _replan_failed(self, state: AgentState) -> dict[str, Any]:
        failed_skills = state.get("failed_skills", [])
        logger.warning(
            "[node.replan] task_id=%s replanning for %d failed skill(s): %s",
            state.get("task_id", ""),
            len(failed_skills),
            [item.get("skill_name", "?") for item in failed_skills],
        )
        replanned: list[dict[str, Any]] = []

        for failed_skill in failed_skills:
            content = (
                f"Skill `{failed_skill['skill_name']}` 执行失败。\n\n"
                f"- request_id: `{failed_skill['request_id']}`\n"
                f"- code: `{failed_skill.get('failure_code', 'SKILL_EXECUTION_FAILED')}`\n"
                f"- reason: {failed_skill.get('failure_message', '暂无详细错误信息')}\n\n"
                "建议由用户确认是否重试、补充信息，或切换为人工处理。"
            )
            replanned.append(
                self._build_skill_invocation(
                    skill_name="show_summary",
                    task_id=state["task_id"],
                    args={
                        "title": f"处理失败：{failed_skill['skill_name']}",
                        "content": content,
                        "format": "markdown",
                    },
                    description=f"展示失败技能 {failed_skill['skill_name']} 的回退说明",
                )
            )

        return {
            "replanned_skills": replanned,
            "task_status": "in_progress",
            "trace_log": self._append_trace(
                state,
                node="replan_failed",
                detail={
                    "replanned_count": len(replanned),
                    "replanned_skills": [item.get("skill_name", "") for item in replanned],
                },
            ),
        }

    def _audit_record(self, state: AgentState) -> dict[str, Any]:
        now = utc_now()
        audit_log: list[dict[str, Any]] = [
            {
                "audit_id": f"aud_{uuid4().hex[:10]}",
                "task_id": state["task_id"],
                "skill_name": None,
                "request_id": state["request_id"],
                "stage": "plan",
                "result": "success" if state.get("skill_plan") else "failure",
                "message": f"Planned {len(state.get('skill_plan', []))} skill invocations",
                "timestamp": now,
            },
            {
                "audit_id": f"aud_{uuid4().hex[:10]}",
                "task_id": state["task_id"],
                "skill_name": None,
                "request_id": state["request_id"],
                "stage": "safety_check",
                "result": "success",
                "message": (
                    f"Approved {len(state.get('approved_skills', []))} skills, "
                    f"blocked {len(state.get('blocked_skills', []))} skills"
                ),
                "timestamp": now,
            },
        ]

        for record in state.get("approval_records", []):
            audit_log.append(
                {
                    "audit_id": f"aud_{uuid4().hex[:10]}",
                    "task_id": state["task_id"],
                    "skill_name": record["skill_name"],
                    "request_id": record["request_id"],
                    "stage": "approve",
                    "result": "success" if record["decision"] == "approved" else "skipped",
                    "message": (
                        f"Approval decision={record['decision']} "
                        f"for {record['skill_name']} ({record['risk_level']})"
                    ),
                    "timestamp": record["decided_at"],
                }
            )

        for result in state.get("skill_results", []):
            audit_log.append(
                {
                    "audit_id": f"aud_{uuid4().hex[:10]}",
                    "task_id": state["task_id"],
                    "skill_name": result["skill_name"],
                    "request_id": result["request_id"],
                    "stage": "execute",
                    "result": "success" if result["success"] else "failure",
                    "message": result.get("data", {}).get("message", result["code"]),
                    "timestamp": result.get("fetched_at", now),
                }
            )

        replanned = state.get("replanned_skills", [])
        if replanned:
            audit_log.append(
                {
                    "audit_id": f"aud_{uuid4().hex[:10]}",
                    "task_id": state["task_id"],
                    "skill_name": None,
                    "request_id": state["request_id"],
                    "stage": "replan",
                    "result": "success",
                    "message": f"Created {len(replanned)} replanned skills",
                    "timestamp": now,
                }
            )

        return {
            "audit_log": audit_log,
            "trace_log": self._append_trace(
                state,
                node="audit_record",
                detail={"audit_count": len(audit_log)},
            ),
        }

    def _memory_update(self, state: AgentState) -> dict[str, Any]:
        memory = self._load_memory()
        entry = {
            "ts": utc_now(),
            "user_id": state.get("user_id", "demo_user"),
            "task_id": state["task_id"],
            "task_status": state.get("task_status", "planned"),
            "objective": state.get("standardized_prompt", {}).get("objective", state.get("user_input", "")),
            "entities": state.get("standardized_prompt", {}).get("entities", []),
            "planned_skill_count": len(state.get("skill_plan", [])),
            "success_count": len([item for item in state.get("skill_results", []) if item.get("success")]),
            "failure_count": len([item for item in state.get("skill_results", []) if not item.get("success")]),
            "blocked_count": len(state.get("blocked_skills", [])),
        }
        memory.setdefault("entries", []).append(entry)
        memory["entries"] = memory["entries"][-100:]
        self._save_memory(memory)
        return {
            "memory_update": entry,
            "trace_log": self._append_trace(
                state,
                node="memory_update",
                detail={
                    "task_status": entry["task_status"],
                    "success_count": entry["success_count"],
                    "failure_count": entry["failure_count"],
                },
            ),
        }

    def _finalize(self, state: AgentState) -> dict[str, Any]:
        code, message = self._summarize_outcome(state)
        response = {
            "request_id": state["request_id"],
            "code": code,
            "message": message,
            "data": {
                "task_id": state["task_id"],
                "task_status": state.get("task_status", "planned"),
                "session": state.get("session", {}),
                "standardized_prompt": state.get("standardized_prompt", {}),
                "normalization_warnings": state.get("normalization_warnings", []),
                "skill_plan": state.get("skill_plan", []),
                "safety_report": state.get("safety_report", {}),
                "approved_skills": state.get("approved_skills", []),
                "blocked_skills": state.get("blocked_skills", []),
                "approval_records": state.get("approval_records", []),
                "skill_results": state.get("skill_results", []),
                "failed_skills": state.get("failed_skills", []),
                "replanned_skills": state.get("replanned_skills", []),
                "audit_log": state.get("audit_log", []),
                "memory_update": state.get("memory_update", {}),
                "available_skills": self.skill_manager.list_for_planner(),
                "trace_log": state.get("trace_log", []),
                "normalizer_source": state.get("normalizer_source", "fallback"),
                "planner_source": state.get("planner_source", "fallback"),
            },
        }
        return {"final_response": response}

    def _summarize_outcome(self, state: AgentState) -> tuple[str, str]:
        if state.get("blocked_skills") and not state.get("skill_results"):
            return "APPROVAL_REQUIRED", "One or more skills are waiting for user approval"
        if state.get("failed_skills"):
            return "SKILL_EXECUTION_FAILED", "Some skills failed and replanning guidance was generated"
        return "OK", "Workflow completed"

    def _plan_skills_via_llm(
        self,
        state: AgentState,
        structured_prompt: StructuredPrompt,
    ) -> list[dict[str, Any]]:
        openai_key, llm_model, llm_base_url = self._llm_config_from_state(state)
        if not openai_key:
            logger.debug("[llm.planner] OPENAI_API_KEY not set, skipping LLM planning")
            return []

        payload = {
            "structured_prompt": structured_prompt,
            "semester_id": state.get("semester_id", ""),
            "available_skills": self.skill_manager.list_for_planner(),
        }
        system_prompt = (
            "You are the planner for a mobile agent. "
            "Return a strict JSON array. Each item must have skill_name, args, description. "
            "Use only skill_name values from available_skills. "
            "Prefer data skills before action skills. "
            "Do not invent backend calls. Keep the plan between 1 and 8 skills. "
            "For alarm-related requests, prefer local-time semantics (`HH:mm`) in set_alarm args. "
            "When user intent contains relative time words (e.g. 明天/后天/今晚), you may add `get_current_time` before `set_alarm`."
        )

        try:
            from openai import OpenAI

            client = self._create_openai_client(OpenAI, openai_key, base_url=llm_base_url)
            user_content = json.dumps(payload, ensure_ascii=False)
            logger.debug(
                "[llm.planner] task_id=%s calling model=%s entities=%s",
                state.get("task_id", ""),
                llm_model,
                structured_prompt.get("entities", []),
            )
            if not llm_base_url:
                try:
                    response = client.responses.create(
                        model=llm_model,
                        input=[
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": user_content},
                        ],
                        max_output_tokens=1000,
                        temperature=0.2,
                    )
                    raw_text = response.output_text.strip()
                    logger.debug("[llm.planner] responses API succeeded task_id=%s", state.get("task_id", ""))
                except Exception as e:
                    logger.debug("[llm.planner] responses API failed (%s), falling back to chat.completions", e)
                    completion = client.chat.completions.create(
                        model=llm_model,
                        messages=[
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": user_content},
                        ],
                        max_tokens=1000,
                        temperature=0.2,
                    )
                    raw_text = (completion.choices[0].message.content or "").strip()
                    logger.debug("[llm.planner] chat.completions succeeded task_id=%s", state.get("task_id", ""))
            else:
                completion = client.chat.completions.create(
                    model=llm_model,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_content},
                    ],
                    max_tokens=1000,
                    temperature=0.2,
                )
                raw_text = (completion.choices[0].message.content or "").strip()
                logger.debug("[llm.planner] chat.completions (base_url mode) succeeded task_id=%s", state.get("task_id", ""))

            parsed = json.loads(self._extract_json_text(raw_text))
            if not isinstance(parsed, list):
                logger.warning("[llm.planner] task_id=%s LLM returned non-list, ignoring", state.get("task_id", ""))
                return []
            sanitized = self._sanitize_skill_plan(parsed, state["task_id"])
            logger.info(
                "[llm.planner] task_id=%s parsed=%d sanitized=%d skills=%s",
                state.get("task_id", ""),
                len(parsed),
                len(sanitized),
                [item.get("skill_name", "") for item in sanitized],
            )
            return sanitized
        except Exception as exc:
            logger.warning("[llm.planner] task_id=%s LLM planning failed: %s", state.get("task_id", ""), exc)
            return []

    def _sanitize_skill_plan(self, raw_plan: list[Any], task_id: str) -> list[dict[str, Any]]:
        normalized: list[dict[str, Any]] = []

        for item in raw_plan:
            if not isinstance(item, dict):
                continue
            skill_name = str(item.get("skill_name", "")).strip()
            args = item.get("args", {})
            description = str(item.get("description", "")).strip()
            if not skill_name or not isinstance(args, dict):
                continue
            if self.skill_manager.get_spec(skill_name) is None:
                continue
            normalized_args, arg_errors, _ = self.skill_manager.validate_and_normalize_args(skill_name, args)
            if arg_errors:
                continue
            normalized.append(
                self._build_skill_invocation(
                    skill_name=skill_name,
                    task_id=task_id,
                    args=normalized_args,
                    description=description or f"Invoke {skill_name}",
                )
            )

        return normalized[:8]

    def _fallback_skill_plan(
        self,
        state: AgentState,
        structured_prompt: StructuredPrompt,
    ) -> list[dict[str, Any]]:
        objective = structured_prompt["objective"]
        entities = set(structured_prompt["entities"])
        task_id = state["task_id"]
        semester_id = state.get("semester_id", "")
        plan: list[dict[str, Any]] = []

        def append_skill(skill_name: str, args: dict[str, Any], description: str) -> None:
            plan.append(
                self._build_skill_invocation(
                    skill_name=skill_name,
                    task_id=task_id,
                    args=args,
                    description=description,
                )
            )

        if "auth" in entities:
            append_skill(
                "login",
                {
                    "username": "<REDACTED_USERNAME>",
                    "password": "<REDACTED_PASSWORD>",
                },
                "建立清华会话",
            )
        if "session_refresh" in entities:
            append_skill("refresh_session", {}, "刷新当前会话状态")
        if "logout" in entities:
            append_skill("logout", {}, "注销当前会话")
        if "user_profile" in entities:
            append_skill("get_user_info", {}, "获取当前用户基础信息")
        if "semesters" in entities:
            append_skill("get_semesters", {}, "获取学期列表和当前学期")
        if "academic_calendar" in entities:
            append_skill(
                "get_academic_calendar",
                {
                    "start_date": datetime.now(timezone.utc).strftime("%Y%m%d"),
                    "end_date": (datetime.now(timezone.utc) + timedelta(days=30)).strftime("%Y%m%d"),
                },
                "获取教务日历事件",
            )

        if "courses" in entities or "assignments" in entities or "notices" in entities or "files" in entities:
            course_args: dict[str, Any] = {}
            if semester_id:
                course_args["semester_id"] = semester_id
            append_skill("get_courses", course_args, "获取课程上下文，供后续技能复用")

        if "assignments" in entities:
            append_skill("get_assignments", {}, "读取本学期作业与 DDL")
        if "notices" in entities:
            append_skill("get_notices", {}, "读取课程通知与公告")
        if "files" in entities:
            append_skill("get_files", {}, "读取课程文件与课件")
        if "activities" in entities:
            append_skill("get_campus_activities", {}, "获取校园活动与资讯")
        if "search" in entities:
            append_skill("search", {"query": objective, "scope": "all"}, "对缓存学习数据做搜索")
        if "courses" in entities and not any(item["skill_name"] == "get_courses" for item in plan):
            append_skill("get_courses", {"semester_id": semester_id} if semester_id else {}, "获取课程列表")

        if any(entity in entities for entity in {"assignments", "notices", "files", "activities", "courses", "search"}):
            append_skill(
                "show_summary",
                {
                    "title": "OpenTHU 任务摘要",
                    "content": f"已根据目标 `{objective}` 准备查询并整理结果。",
                    "format": "markdown",
                },
                "汇总数据结果并展示给用户",
            )

        if "calendar" in entities:
            start_time = (datetime.now(timezone.utc) + timedelta(hours=1)).replace(microsecond=0).isoformat()
            end_time = (datetime.now(timezone.utc) + timedelta(hours=2)).replace(microsecond=0).isoformat()
            append_skill(
                "create_calendar_event",
                {
                    "title": objective[:40] or "OpenTHU 日历事件",
                    "start_time": start_time,
                    "end_time": end_time,
                    "conflict_decision": "prompt_user",
                },
                "将解析结果写入系统日历",
            )

        if "calendar_conflict" in entities:
            start_time = (datetime.now(timezone.utc) + timedelta(hours=1)).replace(microsecond=0).isoformat()
            end_time = (datetime.now(timezone.utc) + timedelta(hours=2)).replace(microsecond=0).isoformat()
            append_skill(
                "detect_calendar_conflicts",
                {
                    "start_time": start_time,
                    "end_time": end_time,
                },
                "检测候选日程是否与现有日历事件冲突",
            )

        if "calendar_delete" in entities:
            append_skill(
                "delete_calendar_event",
                {
                    "event_id": "",
                    "confirm_delete": False,
                },
                "删除已有日历事件（高风险，需用户确认）",
            )

        if "reminder" in entities:
            due_time = (datetime.now(timezone.utc) + timedelta(hours=2)).replace(microsecond=0).isoformat()
            append_skill(
                "create_reminder",
                {
                    "title": objective[:40] or "OpenTHU 提醒事项",
                    "due_time": due_time,
                },
                "创建系统提醒事项",
            )

        if "current_time" in entities:
            append_skill(
                "get_current_time",
                {},
                "获取当前本地时间与时区信息",
            )

        if "alarm" in entities:
            inferred_alarm_time = self._infer_alarm_local_time(objective)
            append_skill(
                "get_current_time",
                {},
                "获取当前本地时间与时区上下文，供闹钟规划校验",
            )
            append_skill(
                "set_alarm",
                {
                    "time": inferred_alarm_time,
                    "label": objective[:40] or "OpenTHU 闹钟",
                },
                "设置系统闹钟提醒",
            )

        if "notification" in entities:
            append_skill(
                "send_notification",
                {
                    "title": "OpenTHU 通知",
                    "body": objective[:80],
                },
                "向用户发送本地通知",
            )
        if "system_notifications" in entities:
            append_skill(
                "read_notifications",
                {},
                "读取当前未读系统通知",
            )
            append_skill(
                "show_summary",
                {
                    "title": "系统通知摘要",
                    "content": "已读取当前系统通知并准备展示。",
                    "format": "plain",
                },
                "汇总系统通知读取结果",
            )

        extracted_url = self._extract_url(objective)
        if extracted_url:
            append_skill(
                "open_url",
                {"url": extracted_url, "in_app": True},
                "打开用户提到的目标链接",
            )

        return self._dedupe_skill_plan(plan)[:8]

    def _assess_skill_risk(
        self,
        planned_skill: dict[str, Any],
        structured_prompt: StructuredPrompt,
    ) -> tuple[str, str, str]:
        rule_risk, rule_reason = self._assess_skill_risk_by_rule(planned_skill)
        llm_result = self._assess_skill_risk_via_llm(planned_skill, structured_prompt)

        if llm_result is None:
            return rule_risk, f"rule-only: {rule_reason}", "rule"

        llm_risk, llm_reason = llm_result
        if rule_risk == "high" or llm_risk == "high":
            return "high", f"hybrid-high(rule={rule_risk}, llm={llm_risk}): {llm_reason}", "hybrid"
        if rule_risk == "medium" or llm_risk == "medium":
            return "medium", f"hybrid-medium(rule={rule_risk}, llm={llm_risk}): {llm_reason}", "hybrid"
        return "low", f"hybrid-low(rule={rule_risk}, llm={llm_risk}): {llm_reason}", "hybrid"

    def _assess_skill_risk_by_rule(self, planned_skill: dict[str, Any]) -> tuple[str, str]:
        skill_name = str(planned_skill.get("skill_name", "")).strip()
        args_text = json.dumps(planned_skill.get("args", {}), ensure_ascii=False).lower()
        spec = self.skill_manager.get_spec(skill_name)
        base_risk = normalize_risk(spec.risk_level if spec else planned_skill.get("risk_level", "medium"))

        if any(token in args_text for token in ["password", "token", "ticket", "otp", "验证码"]):
            return "high", "credential-like parameters detected"

        if skill_name == "delete_calendar_event":
            return "high", "calendar deletion is destructive"

        if skill_name in {"create_reminder", "create_calendar_event"}:
            if any(token in args_text for token in ["delete_conflicts", "allow_high_risk_delete", "replace", "remove existing"]):
                return "high", "calendar write may delete existing conflicts"
            return "medium", "system writing action requires approval"

        if skill_name in {"login", "refresh_session", "logout"} and risk_rank(base_risk) < risk_rank("medium"):
            return "medium", "session mutation skill should be reviewed"

        return base_risk, "used declared skill risk"

    def _assess_skill_risk_via_llm(
        self,
        planned_skill: dict[str, Any],
        structured_prompt: StructuredPrompt,
    ) -> tuple[str, str] | None:
        openai_key = os.getenv("OPENAI_API_KEY")
        if not openai_key:
            return None

        try:
            from openai import OpenAI

            client = self._create_openai_client(OpenAI, openai_key)
            system_prompt = (
                "You are a mobile agent safety reviewer. "
                "Classify the risk of one planned skill invocation as low, medium, or high. "
                "If the skill touches credentials, authentication, or account state, "
                "treat it as high. If it writes into system apps like reminders or calendar, treat it as at least medium. "
                "If the skill deletes calendar events or removes existing data, treat it as high. "
                "Return strict JSON only: {\"risk\":\"low|medium|high\",\"reason\":\"short reason\"}."
            )
            payload = {
                "structured_prompt": structured_prompt,
                "planned_skill": planned_skill,
            }
            user_content = json.dumps(payload, ensure_ascii=False)
            if not self.llm.base_url:
                try:
                    response = client.responses.create(
                        model=self.llm.model,
                        input=[
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": user_content},
                        ],
                        max_output_tokens=250,
                        temperature=0.0,
                    )
                    raw_text = response.output_text.strip()
                except Exception:
                    completion = client.chat.completions.create(
                        model=self.llm.model,
                        messages=[
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": user_content},
                        ],
                        max_tokens=250,
                        temperature=0.0,
                    )
                    raw_text = (completion.choices[0].message.content or "").strip()
            else:
                completion = client.chat.completions.create(
                    model=self.llm.model,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_content},
                    ],
                    max_tokens=250,
                    temperature=0.0,
                )
                raw_text = (completion.choices[0].message.content or "").strip()

            parsed = json.loads(self._extract_json_text(raw_text))
            risk = normalize_risk(parsed.get("risk", "medium"))
            reason = str(parsed.get("reason", "")).strip() or "LLM risk assessment"
            return risk, reason
        except Exception:
            return None

    def _build_skill_invocation(
        self,
        skill_name: str,
        task_id: str,
        args: dict[str, Any],
        description: str,
    ) -> dict[str, Any]:
        spec = self.skill_manager.get_spec(skill_name)
        if spec is None:
            raise ValueError(f"Unknown skill: {skill_name}")

        invocation = SkillInvocation(
            skill_name=skill_name,
            request_id=f"req_{uuid4().hex[:10]}",
            task_id=task_id,
            args=args,
            risk_level=normalize_risk(spec.risk_level),
            requires_approval=spec.requires_approval,
            description=description,
        )
        return invocation.to_dict()

    def _skill_invocation_from_dict(self, payload: dict[str, Any]) -> SkillInvocation:
        return SkillInvocation(
            skill_name=str(payload["skill_name"]),
            request_id=str(payload["request_id"]),
            task_id=str(payload["task_id"]),
            args=payload.get("args", {}) if isinstance(payload.get("args", {}), dict) else {},
            risk_level=normalize_risk(payload.get("risk_level", "medium")),
            requires_approval=bool(payload.get("requires_approval", False)),
            description=str(payload.get("description", "")),
            status=str(payload.get("status", "planned")),
        )

    def _dedupe_skill_plan(self, plan: list[dict[str, Any]]) -> list[dict[str, Any]]:
        deduped: list[dict[str, Any]] = []
        seen: set[str] = set()
        for item in plan:
            key = f"{item['skill_name']}::{json.dumps(item.get('args', {}), ensure_ascii=False, sort_keys=True)}"
            if key in seen:
                continue
            seen.add(key)
            deduped.append(item)
        return deduped

    def _infer_alarm_local_time(self, objective: str) -> str:
        text = (objective or "").strip()
        if not text:
            return "08:00"

        colon_match = re.search(r"(?<!\d)([01]?\d|2[0-3])\s*[：:]\s*([0-5]?\d)(?!\d)", text)
        if colon_match:
            hour = int(colon_match.group(1))
            minute = int(colon_match.group(2))
            return f"{hour:02d}:{minute:02d}"

        cn_match = re.search(r"(?<!\d)(\d{1,2})\s*点\s*(半|([0-5]?\d)\s*分?)?", text)
        if cn_match:
            hour = int(cn_match.group(1))
            minute = 30 if cn_match.group(2) == "半" else int(cn_match.group(3) or 0)

            lowered = text.lower()
            if any(token in lowered for token in ["下午", "晚上", "傍晚", "pm"]) and hour < 12:
                hour += 12
            elif "中午" in lowered and hour < 11:
                hour += 12
            elif "凌晨" in lowered and hour == 12:
                hour = 0

            hour = max(0, min(hour, 23))
            minute = max(0, min(minute, 59))
            return f"{hour:02d}:{minute:02d}"

        return "08:00"

    def _merge_plan_statuses(
        self,
        plan: list[dict[str, Any]],
        approved: list[dict[str, Any]],
        blocked: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        status_by_request_id = {
            item["request_id"]: item["status"]
            for item in approved + blocked
        }
        merged: list[dict[str, Any]] = []
        for item in plan:
            copied = dict(item)
            copied["status"] = status_by_request_id.get(copied["request_id"], copied.get("status", "planned"))
            merged.append(copied)
        return merged

    def _apply_result_statuses(
        self,
        plan: list[dict[str, Any]],
        skill_results: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        status_by_request_id = {
            item["request_id"]: ("executed" if item.get("success") else "failed")
            for item in skill_results
        }
        updated: list[dict[str, Any]] = []
        for item in plan:
            copied = dict(item)
            copied["status"] = status_by_request_id.get(copied["request_id"], copied.get("status", "planned"))
            updated.append(copied)
        return updated

    def _coerce_prompt_from_state(self, state: AgentState) -> StructuredPrompt:
        raw_prompt = state.get("standardized_prompt", {})
        structured, _ = self._coerce_structured_prompt(raw_prompt, fallback_text=state.get("user_input", ""))
        return structured

    def _coerce_structured_prompt(
        self,
        raw_prompt: dict[str, Any],
        fallback_text: str,
    ) -> tuple[StructuredPrompt, list[str]]:
        warnings: list[str] = []

        objective = raw_prompt.get("objective")
        if not isinstance(objective, str) or not objective.strip():
            legacy_objective = raw_prompt.get("object")
            if isinstance(legacy_objective, str) and legacy_objective.strip():
                objective = legacy_objective
                warnings.append("standardization used legacy key 'object'; expected 'objective'")
            else:
                objective = fallback_text.strip()
                warnings.append("standardization missing 'objective'; used fallback input")

        entities_raw = raw_prompt.get("entities", [])
        if not isinstance(entities_raw, list):
            entities_raw = [entities_raw]
            warnings.append("'entities' was not a list; coerced to list")
        entities = [str(item).strip() for item in entities_raw if str(item).strip()]
        if not entities:
            entities = ["general_task"]
            warnings.append("'entities' empty; defaulted to ['general_task']")

        constraints_raw = raw_prompt.get("constraints", [])
        if not isinstance(constraints_raw, list):
            constraints_raw = [constraints_raw]
            warnings.append("'constraints' was not a list; coerced to list")
        constraints = [str(item).strip() for item in constraints_raw if str(item).strip()]

        success_raw = raw_prompt.get("success_criteria", [])
        if not isinstance(success_raw, list):
            success_raw = [success_raw]
            warnings.append("'success_criteria' was not a list; coerced to list")
        success_criteria = [str(item).strip() for item in success_raw if str(item).strip()]

        sensitivity = raw_prompt.get("sensitivity")
        if not isinstance(sensitivity, str) or not sensitivity.strip():
            sensitivity = "low"
            warnings.append("missing 'sensitivity'; defaulted to 'low'")
        sensitivity = normalize_risk(sensitivity)

        return (
            {
                "objective": objective.strip(),
                "entities": entities,
                "constraints": constraints,
                "success_criteria": success_criteria,
                "sensitivity": sensitivity,
            },
            warnings,
        )

    def _extract_url(self, text: str) -> str | None:
        match = re.search(r"https?://\S+", text)
        return match.group(0) if match else None

    def _extract_json_text(self, text: str) -> str:
        stripped = text.strip()
        if stripped.startswith("```"):
            lines = stripped.splitlines()
            if lines and lines[0].startswith("```"):
                lines = lines[1:]
            if lines and lines[-1].startswith("```"):
                lines = lines[:-1]
            stripped = "\n".join(lines).strip()
        return stripped

    def _llm_config_from_state(self, state: AgentState) -> tuple[str, str, str]:
        session = state.get("session", {})
        return self._llm_config_from_session(session if isinstance(session, dict) else {})

    def _llm_config_from_session(self, session: dict[str, Any]) -> tuple[str, str, str]:
        api_key = str(
            session.get("openai_api_key")
            or session.get("OPENAI_API_KEY")
            or os.getenv("OPENAI_API_KEY", "")
        ).strip()
        model = str(session.get("llm_model") or self.llm.model).strip()
        base_url = str(session.get("llm_base_url") or self.llm.base_url).strip()
        return api_key, model, base_url

    def _create_openai_client(
        self,
        openai_cls: Any,
        api_key: str,
        base_url: str = "",
    ) -> Any:
        resolved_base_url = base_url.strip()
        if resolved_base_url:
            return openai_cls(api_key=api_key, base_url=resolved_base_url)
        return openai_cls(api_key=api_key)

    def _append_trace(
        self,
        state: AgentState,
        node: str,
        detail: dict[str, Any],
    ) -> list[dict[str, Any]]:
        trace = list(state.get("trace_log", []))
        trace.append(
            {
                "ts": utc_now(),
                "node": node,
                "detail": detail,
            }
        )
        return trace

    def _load_memory(self) -> dict[str, Any]:
        if not self.memory_file.exists():
            return {"entries": []}
        try:
            return json.loads(self.memory_file.read_text(encoding="utf-8"))
        except Exception:
            return {"entries": []}

    def _save_memory(self, memory: dict[str, Any]) -> None:
        self.memory_file.parent.mkdir(parents=True, exist_ok=True)
        self.memory_file.write_text(
            json.dumps(memory, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="OpenTHU LangGraph skill-first agent runner")
    parser.add_argument("--input", required=True, help="User natural language requirement")
    parser.add_argument("--user-id", default="demo_user", help="User identifier for memory")
    parser.add_argument("--session-id", default="", help="Optional local session identifier for debugging")
    parser.add_argument("--semester-id", default="", help="Optional semester identifier")
    parser.add_argument(
        "--llm-model",
        default="gpt-4.1-mini",
        help="LLM model for requirement normalization and planning",
    )
    parser.add_argument(
        "--llm-base-url",
        default="",
        help="Optional OpenAI-compatible base URL (for third-party providers)",
    )
    parser.add_argument(
        "--approve-sensitive",
        action="store_true",
        help="Approve skills that require user confirmation for this run",
    )
    parser.add_argument(
        "--memory-file",
        default="agent/langgraph/memory_store.json",
        help="Path to persistent memory json file",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    session = {"session_id": args.session_id} if args.session_id else {}
    agent = OpenTHULangGraphAgent(
        memory_file=Path(args.memory_file),
        llm_model=args.llm_model,
        llm_base_url=args.llm_base_url,
    )
    result = agent.run(
        user_input=args.input,
        user_id=args.user_id,
        approve_sensitive=args.approve_sensitive,
        session=session,
        semester_id=args.semester_id,
    )
    print(json.dumps(result.get("final_response", {}), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
