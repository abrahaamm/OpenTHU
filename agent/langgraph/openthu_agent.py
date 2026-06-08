from __future__ import annotations

import argparse
import json
import logging
import os
import re
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
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
    conversation_context: dict[str, Any]
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
    final_summary_text: str


class StructuredPrompt(TypedDict):
    objective: str
    entities: list[str]
    constraints: list[str]
    success_criteria: list[str]
    sensitivity: str


@dataclass(frozen=True)
class ModelContextProfile:
    name: str
    context_tokens: int
    output_reserve_tokens: int
    system_reserve_tokens: int
    memory_reserve_tokens: int
    max_history_tokens: int
    min_history_tokens: int
    max_history_messages: int
    per_message_token_limit: int
    anchor_message_token_limit: int
    memory_entry_limit: int
    memory_value_char_limit: int
    memory_summary_char_limit: int


def model_context_profile(model: str) -> ModelContextProfile:
    normalized = str(model or "").strip().lower()
    if "gpt-4.1" in normalized:
        return ModelContextProfile("openai_1m", 1_000_000, 8_192, 2_048, 8_192, 180_000, 8_000, 160, 8_000, 1_200, 16, 480, 2_400)
    if "gpt-4o-mini" in normalized or "gpt-4o" in normalized:
        return ModelContextProfile("openai_128k", 128_000, 4_096, 2_048, 4_096, 80_000, 6_000, 96, 4_000, 900, 12, 360, 1_800)
    if "moonshot-v1-128k" in normalized or "moonshot-1-128k" in normalized:
        return ModelContextProfile("moonshot_128k", 131_072, 4_096, 2_048, 4_096, 82_000, 6_000, 96, 4_000, 900, 12, 360, 1_800)
    if "moonshot-v1-32k" in normalized or "moonshot-1-32k" in normalized:
        return ModelContextProfile("moonshot_32k", 32_768, 2_048, 1_200, 1_800, 22_000, 3_000, 48, 2_000, 700, 8, 260, 1_200)
    if "moonshot-v1-8k" in normalized or "moonshot-1-8k" in normalized:
        return ModelContextProfile("moonshot_8k", 8_192, 1_024, 800, 900, 4_200, 900, 18, 900, 360, 4, 180, 700)
    if "deepseek" in normalized:
        return ModelContextProfile("deepseek_64k", 64_000, 4_096, 1_800, 3_000, 42_000, 4_000, 72, 3_000, 800, 10, 320, 1_500)
    return ModelContextProfile("default_32k", 32_768, 2_048, 1_200, 1_800, 20_000, 3_000, 48, 2_000, 700, 8, 260, 1_200)


def estimate_context_tokens(text: str) -> int:
    if not text or not str(text).strip():
        return 0
    tokens = 0
    latin_run = 0

    def flush_latin() -> None:
        nonlocal tokens, latin_run
        if latin_run:
            tokens += max(1, (latin_run + 3) // 4)
            latin_run = 0

    for char in str(text):
        if char.isspace():
            flush_latin()
        elif "\u4e00" <= char <= "\u9fff" or "\u3400" <= char <= "\u4dbf" or "\u3040" <= char <= "\u30ff" or "\uac00" <= char <= "\ud7af":
            flush_latin()
            tokens += 1
        elif char.isalnum():
            latin_run += 1
        else:
            flush_latin()
            tokens += 1
    flush_latin()
    return tokens


def trim_text_to_token_budget(text: str, token_budget: int) -> str:
    value = str(text or "").strip()
    if token_budget <= 0 or not value:
        return ""
    if estimate_context_tokens(value) <= token_budget:
        return value
    low, high = 0, len(value)
    while low < high:
        mid = (low + high + 1) // 2
        if estimate_context_tokens(value[:mid]) <= token_budget - 1:
            low = mid
        else:
            high = mid - 1
    return value[:low].rstrip() + "…"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def risk_rank(risk_level: str) -> int:
    return {"low": 0, "medium": 1, "high": 2}.get(risk_level, 1)


def normalize_risk(risk_level: str) -> str:
    risk = str(risk_level).strip().lower()
    return risk if risk in {"low", "medium", "high"} else "medium"


def _preview_text_for_log(text: str, limit: int = 1200) -> str:
    preview = str(text).strip()
    if len(preview) <= limit:
        return preview
    return preview[:limit] + "...<truncated>"


class RequirementLLM:
    """Normalize user requirement into a stable prompt structure."""

    def __init__(self, model: str = "gpt-4.1-mini", base_url: str = "") -> None:
        self.model = model
        self.base_url = base_url.strip()
        self.last_mode = "not_configured"
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
            self.last_mode = "not_configured"
            self.last_error = "OPENAI_API_KEY is not configured"
            return {}
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
                "Use concise, execution-oriented values. "
                "If the input is JSON with conversation_context, use recent_messages and memory_context "
                "to resolve follow-up references and user preferences. "
                "If the input contains an [attached_file] block, preserve its file_uri and file_name "
                "verbatim in constraints so downstream homework upload skills can use them. "
                "Return JSON only."
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
            self.last_mode = "llm_error"
            self.last_error = f"{type(exc).__name__}: {exc}"
            return {}

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
        workflow.add_node("synthesize_summary", self._synthesize_summary)
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
        workflow.add_edge("memory_update", "synthesize_summary")
        workflow.add_edge("synthesize_summary", "finalize")
        workflow.add_edge("finalize", END)

        return workflow.compile()

    def run(
        self,
        user_input: str,
        user_id: str = "demo_user",
        approve_sensitive: bool = False,
        session: dict[str, Any] | None = None,
        semester_id: str = "",
        history: list[dict[str, Any]] | None = None,
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
            history=history,
        )
        if not self._llm_config_from_state(initial)[0]:
            deterministic_update = self._deterministic_plan_update(initial)
            if deterministic_update:
                initial.update(deterministic_update)
                for node_fn in (self._safety_check, self._execute_skills):
                    initial.update(node_fn(initial))
                if initial.get("needs_replan"):
                    initial.update(self._replan_failed(initial))
                for node_fn in (self._audit_record, self._memory_update, self._finalize):
                    initial.update(node_fn(initial))
                return initial
            return {
                "final_response": self._plan_error_response(
                    initial,
                    code="LLM_NOT_CONFIGURED",
                    message=self._llm_not_configured_message(),
                )
            }
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
        history: list[dict[str, Any]] | None = None,
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
            history=history,
        )
        logger.debug(
            "[agent.run_plan_only] task_id=%s request_id=%s",
            state["task_id"],
            state["request_id"],
        )
        if not self._llm_config_from_state(state)[0]:
            deterministic_update = self._deterministic_plan_update(state)
            if deterministic_update:
                state.update(deterministic_update)
                for node_fn in (
                    self._safety_check,
                    self._audit_record,
                    self._memory_update,
                ):
                    state.update(node_fn(state))
                code, message = self._set_plan_only_status(state)
                return self._plan_only_response_from_state(state, code=code, message=message)
            return self._plan_error_response(
                state,
                code="LLM_NOT_CONFIGURED",
                message=self._llm_not_configured_message(),
            )

        for node_fn in (
            self._normalize_requirement,
            self._plan_skills,
            self._safety_check,
            self._audit_record,
            self._memory_update,
        ):
            state.update(node_fn(state))

        if state.get("task_status") == "model_unavailable":
            return self._plan_error_response(
                state,
                code="LLM_UNAVAILABLE",
                message=self._llm_unavailable_message(),
            )

        code, message = self._set_plan_only_status(state)
        approved_count = len(state.get("approved_skills", []))
        blocked_count = len(state.get("blocked_skills", []))

        logger.info(
            "[agent.run_plan_only] finished task_id=%s code=%s task_status=%s "
            "approved=%d blocked=%d normalizer=%s planner=%s",
            state["task_id"],
            code,
            state.get("task_status", "planned"),
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
        return self._plan_only_response_from_state(state, code=code, message=message)

    def _set_plan_only_status(self, state: AgentState) -> tuple[str, str]:
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
        return code, message

    def _plan_only_response_from_state(
        self,
        state: AgentState,
        *,
        code: str,
        message: str,
    ) -> dict[str, Any]:
        return {
            "request_id": state["request_id"],
            "code": code,
            "message": message,
            "data": {
                "mode": "plan_only",
                "task_id": state["task_id"],
                "task_status": state.get("task_status", "planned"),
                "session": state.get("session", {}),
                "conversation_context": state.get("conversation_context", {}),
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
                "normalizer_source": state.get("normalizer_source", "not_run"),
                "planner_source": state.get("planner_source", "not_run"),
            },
        }

    def chat_turn(
        self,
        user_input: str,
        user_id: str = "demo_user",
        session: dict[str, Any] | None = None,
        history: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        return self.decide_turn(
            user_input=user_input,
            user_id=user_id,
            approve_sensitive=False,
            session=session,
            history=history,
        )

    def decide_turn(
        self,
        user_input: str,
        user_id: str = "demo_user",
        approve_sensitive: bool = False,
        session: dict[str, Any] | None = None,
        history: list[dict[str, Any]] | None = None,
        semester_id: str = "",
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
                    "source": "validation",
                },
            }

        state = self._build_initial_state(
            user_input=text,
            user_id=user_id,
            approve_sensitive=approve_sensitive,
            session=session,
            semester_id=semester_id,
            history=history,
        )
        if not self._llm_config_from_state(state)[0]:
            deterministic_update = self._deterministic_plan_update(state)
            if deterministic_update:
                state.update(deterministic_update)
                for node_fn in (
                    self._safety_check,
                    self._audit_record,
                    self._memory_update,
                ):
                    state.update(node_fn(state))
                code, message = self._set_plan_only_status(state)
                plan_response = self._plan_only_response_from_state(state, code=code, message=message)
                return {
                    "request_id": f"chat_{uuid4().hex[:12]}",
                    "code": "OK",
                    "message": "turn decision generated",
                    "data": {
                        "mode": "task",
                        "should_plan": True,
                        "reply": self._deterministic_reply_for_plan(state.get("skill_plan", [])),
                        "confidence": 0.9,
                        "source": "deterministic",
                        "user_id": user_id,
                        "plan_response": plan_response,
                    },
                }
            return self._chat_error_response(
                code="LLM_NOT_CONFIGURED",
                message=self._llm_not_configured_message(),
                source="not_configured",
                user_id=user_id,
            )

        decision = self._decide_turn_via_llm(state=state, history=history or [])
        if decision is None:
            return self._chat_error_response(
                code="LLM_UNAVAILABLE",
                message=self._llm_unavailable_message(),
                source="llm_error",
                user_id=user_id,
            )

        reply = str(decision.get("reply", "")).strip()
        confidence = float(decision.get("confidence", 0.7) or 0.7)
        skill_plan = decision.get("skill_plan", [])
        if not isinstance(skill_plan, list):
            skill_plan = []
        if not skill_plan:
            deterministic_update = self._deterministic_plan_update(state)
            if deterministic_update:
                state.update(deterministic_update)
                for node_fn in (
                    self._safety_check,
                    self._audit_record,
                    self._memory_update,
                ):
                    state.update(node_fn(state))
                code, message = self._set_plan_only_status(state)
                plan_response = self._plan_only_response_from_state(state, code=code, message=message)
                return {
                    "request_id": f"chat_{uuid4().hex[:12]}",
                    "code": "OK",
                    "message": "turn decision generated by deterministic fallback",
                    "data": {
                        "mode": "task",
                        "should_plan": True,
                        "reply": self._deterministic_reply_for_plan(state.get("skill_plan", [])),
                        "confidence": 0.88,
                        "source": "deterministic_after_llm_empty",
                        "user_id": user_id,
                        "plan_response": plan_response,
                    },
                }
            return {
                "request_id": f"chat_{uuid4().hex[:12]}",
                "code": "OK",
                "message": "chat turn generated",
                "data": {
                    "mode": "chat",
                    "should_plan": False,
                    "reply": reply,
                    "confidence": max(0.0, min(confidence, 1.0)),
                    "source": "llm_decision",
                    "user_id": user_id,
                },
            }

        structured_raw = decision.get("structured_prompt", {})
        if not isinstance(structured_raw, dict):
            structured_raw = {}
        structured_prompt, warnings = self._coerce_structured_prompt(
            structured_raw,
            source_text=text,
        )
        state.update(
            {
                "standardized_prompt": structured_prompt,
                "normalization_warnings": warnings,
                "skill_plan": skill_plan,
                "normalizer_source": "llm_decision",
                "planner_source": "llm_decision",
                "task_status": "planned",
                "trace_log": self._append_trace(
                    state,
                    node="decide_turn",
                    detail={
                        "planned_count": len(skill_plan),
                        "planned_skills": [item.get("skill_name", "") for item in skill_plan],
                        "source": "llm_decision",
                    },
                ),
            }
        )
        for node_fn in (
            self._safety_check,
            self._audit_record,
            self._memory_update,
        ):
            state.update(node_fn(state))
        code, message = self._set_plan_only_status(state)
        plan_response = self._plan_only_response_from_state(state, code=code, message=message)
        return {
            "request_id": f"chat_{uuid4().hex[:12]}",
            "code": "OK",
            "message": "turn decision generated",
            "data": {
                "mode": "task",
                "should_plan": True,
                "reply": reply or "好的，我来处理。",
                "confidence": max(0.0, min(confidence, 1.0)),
                "source": "llm_decision",
                "user_id": user_id,
                "plan_response": plan_response,
            },
        }

    def _llm_not_configured_message(self) -> str:
        return "模型没有配置好。请先配置 API Key、模型名称和 Base URL，然后我才能进行对话或规划任务。"

    def _llm_unavailable_message(self) -> str:
        return "模型服务当前不可用。请检查 API Key、模型名称、Base URL 或网络连接后再试。"

    def _chat_error_response(
        self,
        *,
        code: str,
        message: str,
        source: str,
        user_id: str,
    ) -> dict[str, Any]:
        return {
            "request_id": f"chat_{uuid4().hex[:12]}",
            "code": code,
            "message": message,
            "data": {
                "mode": "chat",
                "should_plan": False,
                "reply": message,
                "confidence": 1.0,
                "source": source,
                "user_id": user_id,
            },
        }

    def _plan_error_response(
        self,
        state: AgentState,
        *,
        code: str,
        message: str,
    ) -> dict[str, Any]:
        return {
            "request_id": state["request_id"],
            "code": code,
            "message": message,
            "data": {
                "mode": "plan_only",
                "task_id": state["task_id"],
                "task_status": "model_unavailable",
                "session": state.get("session", {}),
                "conversation_context": state.get("conversation_context", {}),
                "standardized_prompt": {},
                "normalization_warnings": [message],
                "skill_plan": [],
                "approved_skills": [],
                "blocked_skills": [],
                "safety_report": {},
                "approval_records": [],
                "audit_log": [],
                "memory_update": {},
                "trace_log": [
                    {
                        "ts": utc_now(),
                        "node": "model_config",
                        "detail": {"code": code, "message": message},
                    }
                ],
                "available_skills": self.skill_manager.list_for_planner(),
                "normalizer_source": "not_configured" if code == "LLM_NOT_CONFIGURED" else "llm_error",
                "planner_source": "not_run",
            },
        }

    def _compact_chat_history(
        self,
        history: list[dict[str, Any]],
        *,
        limit: int | None = None,
        profile: ModelContextProfile | None = None,
        current_user_input: str = "",
        fixed_prompt_text: str = "",
    ) -> list[dict[str, str]]:
        resolved_profile = profile or model_context_profile("")
        eligible: list[tuple[int, str, str]] = []
        for index, item in enumerate(history):
            if not isinstance(item, dict):
                continue
            role = str(item.get("role", "")).strip().lower()
            text = str(item.get("text", item.get("content", ""))).strip()
            if role not in {"user", "assistant"} or not text:
                continue
            eligible.append((index, role, text))
        if not eligible:
            return []

        fixed_tokens = estimate_context_tokens(current_user_input) + estimate_context_tokens(fixed_prompt_text)
        raw_history_budget = (
            resolved_profile.context_tokens
            - resolved_profile.output_reserve_tokens
            - resolved_profile.system_reserve_tokens
            - resolved_profile.memory_reserve_tokens
            - fixed_tokens
        )
        if raw_history_budget <= 0:
            history_budget = 0
        else:
            history_budget = min(max(resolved_profile.min_history_tokens, raw_history_budget), resolved_profile.max_history_tokens)
        max_messages = min(limit, resolved_profile.max_history_messages) if limit is not None else resolved_profile.max_history_messages
        selected: list[tuple[int, dict[str, str]]] = []
        used_indexes: set[int] = set()
        remaining = history_budget

        for index, role, text in reversed(eligible):
            if len(selected) >= max_messages:
                break
            text_budget = min(resolved_profile.per_message_token_limit, remaining - 6)
            if text_budget < 48:
                break
            compact_text = trim_text_to_token_budget(text, text_budget)
            if not compact_text:
                continue
            selected.append((index, {"role": role, "content": compact_text}))
            used_indexes.add(index)
            remaining -= estimate_context_tokens(compact_text) + 6

        if remaining > 128:
            anchors = [item for item in eligible[:2] if item[0] not in used_indexes]
            anchor_budget = min(resolved_profile.anchor_message_token_limit, remaining // max(len(anchors), 1)) if anchors else 0
            for index, role, text in anchors:
                compact_text = trim_text_to_token_budget(text, anchor_budget - 6)
                if compact_text:
                    selected.append((index, {"role": role, "content": compact_text}))

        return [item for _, item in sorted(selected, key=lambda pair: pair[0])]

    def _build_conversation_context(
        self,
        history: list[dict[str, Any]],
        *,
        user_input: str,
        session: dict[str, Any] | None = None,
        user_id: str = "",
    ) -> dict[str, Any]:
        _, model, _ = self._llm_config_from_session(session or {})
        profile = model_context_profile(model)
        recent_messages = self._compact_chat_history(
            history,
            profile=profile,
            current_user_input=user_input,
            fixed_prompt_text=str((session or {}).get("memory_context", "")),
        )
        current = user_input.strip()
        if (
            recent_messages
            and recent_messages[-1].get("role") == "user"
            and recent_messages[-1].get("content", "").strip() == current
        ):
            recent_messages = recent_messages[:-1]

        reference_candidates: list[dict[str, Any]] = []
        for message_index, message in enumerate(recent_messages):
            if message.get("role") != "assistant":
                continue
            for line in message.get("content", "").splitlines():
                text = line.strip()
                if not text:
                    continue
                numbered = any(
                    text.startswith(prefix)
                    for number in range(1, 10)
                    for prefix in (f"{number}.", f"{number}、", f"{number})", f"{number}）")
                )
                bulleted = text.startswith("- ") or text.startswith("• ")
                if numbered or bulleted:
                    reference_candidates.append(
                        {
                            "message_index": message_index,
                            "text": text[:500],
                        }
                    )

        session_memory = self._compact_session_memory_context(session or {}, profile=profile)
        stored_memory = self._compact_stored_memory_context(
            user_id=user_id,
            user_input=current,
            limit=profile.memory_entry_limit,
            value_limit=profile.memory_value_char_limit,
            summary_limit=profile.memory_summary_char_limit,
        )
        memory_context = self._merge_memory_context(
            session_memory,
            stored_memory,
            limit=profile.memory_entry_limit,
            summary_limit=profile.memory_summary_char_limit,
        )

        return {
            "latest_user_input": current,
            "recent_messages": recent_messages,
            "reference_candidates": reference_candidates[-12:],
            "memory_context": memory_context,
            "context_window": asdict(profile),
        }

    def _compact_session_memory_context(
        self,
        session: dict[str, Any],
        *,
        profile: ModelContextProfile | None = None,
    ) -> dict[str, Any]:
        resolved_profile = profile or model_context_profile("")
        raw_memory = session.get("memory_context") or session.get("memory_records") or session.get("memories")
        if not raw_memory:
            return {}
        if isinstance(raw_memory, str):
            try:
                raw_memory = json.loads(raw_memory)
            except Exception:
                summary = raw_memory.strip()[: resolved_profile.memory_summary_char_limit]
                return {"summary": summary} if summary else {}

        if isinstance(raw_memory, list):
            raw_memory = {"entries": raw_memory}
        if not isinstance(raw_memory, dict):
            return {}

        entries_raw = raw_memory.get("entries", [])
        if not isinstance(entries_raw, list):
            entries_raw = []

        entries: list[dict[str, Any]] = []
        for item in entries_raw:
            if not isinstance(item, dict):
                continue
            value = str(item.get("value", "")).strip()
            if not value:
                continue
            entries.append(
                {
                    "scope": str(item.get("scope", "")).strip()[:20],
                    "key": str(item.get("key", "")).strip()[:80],
                    "value": value[: resolved_profile.memory_value_char_limit],
                    "weight": self._safe_int(item.get("weight"), default=0),
                    "updated_at_epoch_ms": self._safe_int(
                        item.get("updated_at_epoch_ms", item.get("updatedAtEpochMs")),
                        default=0,
                    ),
                }
            )

        entries.sort(
            key=lambda item: (
                {"long": 3, "mid": 2, "short": 1}.get(str(item.get("scope", "")).lower(), 0),
                int(item.get("weight", 0)),
                int(item.get("updated_at_epoch_ms", 0)),
            ),
            reverse=True,
        )
        entries = entries[: resolved_profile.memory_entry_limit]
        summary = str(raw_memory.get("summary", "")).strip()
        if not summary and entries:
            summary = "\n".join(
                f"- [{item['scope']}/{item['key']}/w{item['weight']}] {item['value'][:160]}"
                for item in entries[:8]
            )
        return {
            "summary": summary[: resolved_profile.memory_summary_char_limit],
            "entries": entries,
        } if summary or entries else {}

    def _compact_stored_memory_context(
        self,
        *,
        user_id: str,
        user_input: str,
        limit: int = 8,
        value_limit: int = 500,
        summary_limit: int = 1500,
    ) -> dict[str, Any]:
        memory = self._load_memory()
        entries_raw = memory.get("entries", [])
        if not isinstance(entries_raw, list):
            return {}

        tokens = self._memory_tokens(user_input)
        candidates: list[dict[str, Any]] = []
        for item in entries_raw:
            if not isinstance(item, dict):
                continue
            item_user_id = str(item.get("user_id", "")).strip()
            if user_id and item_user_id and item_user_id != user_id:
                continue
            objective = str(item.get("objective", "")).strip()
            if not objective:
                continue
            objective_lc = objective.lower()
            overlap = sum(1 for token in tokens if token in objective_lc)
            success_count = self._safe_int(item.get("success_count"), default=0)
            failure_count = self._safe_int(item.get("failure_count"), default=0)
            blocked_count = self._safe_int(item.get("blocked_count"), default=0)
            ts = self._safe_epoch_ms(item.get("ts"))
            weight = max(10, min(80, 35 + overlap * 10 + min(success_count, 3) * 5 - blocked_count * 5))
            value = (
                f"{objective}；技能数 {self._safe_int(item.get('planned_skill_count'), default=0)}，"
                f"成功 {success_count}，失败 {failure_count}，阻塞 {blocked_count}"
            )
            candidates.append(
                {
                    "scope": "mid",
                    "key": "agent_task_history",
                    "value": value[:value_limit],
                    "weight": weight,
                    "updated_at_epoch_ms": ts,
                    "_overlap": overlap,
                }
            )

        if not candidates:
            return {}
        candidates.sort(
            key=lambda item: (
                int(item.get("_overlap", 0)),
                int(item.get("weight", 0)),
                int(item.get("updated_at_epoch_ms", 0)),
            ),
            reverse=True,
        )
        entries = [{key: value for key, value in item.items() if key != "_overlap"} for item in candidates[:limit]]
        summary = "\n".join(
            f"- [{item['scope']}/{item['key']}/w{item['weight']}] {item['value'][:160]}"
            for item in entries[:6]
        )
        return {"summary": summary[:summary_limit], "entries": entries}

    def _merge_memory_context(
        self,
        primary: dict[str, Any],
        secondary: dict[str, Any],
        limit: int = 12,
        summary_limit: int = 1500,
    ) -> dict[str, Any]:
        entries: list[dict[str, Any]] = []
        summaries: list[str] = []
        for context in (primary, secondary):
            if not isinstance(context, dict):
                continue
            summary = str(context.get("summary", "")).strip()
            if summary:
                summaries.append(summary)
            raw_entries = context.get("entries", [])
            if isinstance(raw_entries, list):
                entries.extend(item for item in raw_entries if isinstance(item, dict))

        deduped: dict[tuple[str, str, str], dict[str, Any]] = {}
        for item in entries:
            value = str(item.get("value", "")).strip()
            if not value:
                continue
            key = (str(item.get("scope", "")), str(item.get("key", "")), value)
            previous = deduped.get(key)
            if previous is None or self._safe_int(item.get("weight"), default=0) > self._safe_int(previous.get("weight"), default=0):
                deduped[key] = item

        merged_entries = list(deduped.values())
        merged_entries.sort(
            key=lambda item: (
                {"long": 3, "mid": 2, "short": 1}.get(str(item.get("scope", "")).lower(), 0),
                self._safe_int(item.get("effective_weight", item.get("weight")), default=0),
                self._safe_int(item.get("updated_at_epoch_ms", item.get("updatedAtEpochMs")), default=0),
            ),
            reverse=True,
        )
        merged_entries = merged_entries[:limit]
        summary = "\n".join(dict.fromkeys(summaries)).strip()
        if not summary and merged_entries:
            summary = "\n".join(
                f"- [{item.get('scope', '')}/{item.get('key', '')}/w{item.get('effective_weight', item.get('weight', 0))}] {str(item.get('value', ''))[:160]}"
                for item in merged_entries[:8]
            )
        return {"summary": summary[:summary_limit], "entries": merged_entries} if summary or merged_entries else {}

    def _memory_tokens(self, text: str) -> set[str]:
        return {
            token
            for token in re.split(r"[^0-9A-Za-z\u4e00-\u9fff]+", text.lower())
            if len(token) >= 2
        }

    def _safe_epoch_ms(self, value: Any) -> int:
        if isinstance(value, (int, float)):
            return int(value)
        text = str(value or "").strip()
        if not text:
            return 0
        try:
            normalized = text.replace("Z", "+00:00")
            return int(datetime.fromisoformat(normalized).timestamp() * 1000)
        except Exception:
            return 0

    def _safe_int(self, value: Any, *, default: int = 0) -> int:
        try:
            return int(value)
        except Exception:
            return default

    def _turn_decision_system_prompt(self) -> str:
        return (
            "You are the controller for OpenTHU, a conversational mobile campus agent. "
            "Your job is to decide whether the user's latest message should be answered as normal chat "
            "or handled by available skills. Return strict JSON only with keys: "
            "reply, confidence, structured_prompt, skill_plan. "
            "skill_plan must be an array of objects with skill_name, args, description. "
            "Use an empty skill_plan for casual chat, identity questions, capability questions, opinions, "
            "or general conversation that does not need app/campus/device data. "
            "When an available skill can satisfy the request, produce a skill_plan instead of refusing. "
            "Do not say you lack access to personal campus data; the tools handle authorization and will "
            "return login-required when needed. "
            "Use conversation_context to resolve pronouns and ordinal references such as this, that, it, "
            "the second one, 刚才那个, 第二个, or 上一个 before choosing skills. "
            "Use conversation_context.memory_context as durable user preferences and feedback; "
            "respect long-term preferences unless the latest user message explicitly overrides them. "
            "Use only skill_name values from available_skills, and keep plans between 1 and 8 steps. "
            "Do not add show_summary only to produce the final answer; the runtime has a final summary node. "
            "Use get_homework_cookie only when the user explicitly provides a cookie/token/header. "
            "For requests about course DDL/deadlines/作业DDL/作业截止时间, use get_assignments when available. "
            "For requests about unsubmitted/not submitted/missing homework or 未交/未提交作业, "
            "prefer crawl_unsubmitted_homeworks. For all homework lists, use crawl_course_homeworks. "
            "For explicit homework submission/turn-in/提交/递交 requests, use submit_homework; "
            "if that explicit submit request includes an attached file, plan upload_homework_attachment first "
            "and then submit_homework. Do not satisfy submit/递交/提交 intent with upload_homework_attachment alone. "
            "for upload-only homework attachment requests, use upload_homework_attachment. "
            "For class timetable/课表, use get_semesters first, then get_course_schedule. For campus events/活动/讲座, "
            "use get_campus_activities. For web or campus retrieval searches, use search. "
            "For alarms/reminders/calendar writes, plan the relevant action and let the safety layer ask "
            "for confirmation if needed. "
            "For calendar writes or calendar conflict detection, start_time/end_time args may be concrete "
            "ISO-8601 datetimes or the user's original time text. A calendar time is complete only when the "
            "user explicitly provides a year, date, and time, or when it comes from an upstream skill result. "
            "Month/day/time without a year is incomplete even if it sounds concrete: 6月21日下午4点, 06/21 16:00, "
            "and June 21 at 4pm are year-underspecified. For relative or year-underspecified calendar requests, "
            "you must plan get_current_time before calendar skills. For those cases, keep the original phrase in "
            "time_text/start_time and set time_source=user_text; do not fill in a year yourself and do not mark it "
            "explicit_absolute. If the user gives a full absolute date with explicit year and time, you may pass ISO "
            "and set time_source=explicit_absolute. "
            "If time comes from an upstream skill result such as an assignment deadline, pass that ISO and set time_source=upstream_skill with source_skill/source_field. "
            "If you nevertheless infer missing date parts yourself, set time_source=planner_inferred. Do not invent current_time yourself. "
            "The reply should be natural and concise. If skill_plan is non-empty, reply with one short "
            "sentence saying what you are about to do, not the final result."
        )

    def _decide_turn_via_llm(
        self,
        *,
        state: AgentState,
        history: list[dict[str, Any]],
    ) -> dict[str, Any] | None:
        api_key, model, base_url = self._llm_config_from_state(state)
        if not api_key:
            return None

        payload = {
            "user_input": state.get("user_input", ""),
            "user_id": state.get("user_id", "demo_user"),
            "semester_id": state.get("semester_id", ""),
            "conversation_context": state.get(
                "conversation_context",
                self._build_conversation_context(
                    history,
                    user_input=state.get("user_input", ""),
                    session=state.get("session", {}),
                    user_id=state.get("user_id", ""),
                ),
            ),
            "available_skills": self.skill_manager.list_for_planner(),
            "response_schema": {
                "reply": "natural language reply shown to user",
                "confidence": "number from 0 to 1",
                "structured_prompt": {
                    "objective": "execution objective or chat topic",
                    "entities": ["important entities"],
                    "constraints": ["important constraints"],
                    "success_criteria": ["what success means"],
                    "sensitivity": "low|medium|high",
                },
                "skill_plan": [
                    {
                        "skill_name": "one available skill_name",
                        "args": {},
                        "description": "short user-facing purpose",
                    }
                ],
            },
        }

        try:
            from openai import OpenAI

            client = self._create_openai_client(OpenAI, api_key, base_url=base_url)
            completion = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": self._turn_decision_system_prompt()},
                    {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
                ],
                max_tokens=1400,
                temperature=0.35,
            )
            raw = (completion.choices[0].message.content or "").strip()
            parsed = json.loads(self._extract_json_text(raw))
            if not isinstance(parsed, dict):
                return None
            reply = str(parsed.get("reply", "")).strip()
            if not reply:
                return None
            raw_plan = parsed.get("skill_plan", [])
            if not isinstance(raw_plan, list):
                raw_plan = []
            sanitized_plan = self._apply_search_scene_overrides(
                self._sanitize_skill_plan(raw_plan, state["task_id"]),
                state,
            )
            structured_prompt = parsed.get("structured_prompt", {})
            if not isinstance(structured_prompt, dict):
                structured_prompt = {}
            confidence_raw = parsed.get("confidence", 0.7)
            try:
                confidence = float(confidence_raw)
            except (TypeError, ValueError):
                confidence = 0.7
            logger.info(
                "[agent.decide] task_id=%s plan_size=%d skills=%s",
                state.get("task_id", ""),
                len(sanitized_plan),
                [item.get("skill_name", "") for item in sanitized_plan],
            )
            return {
                "reply": reply,
                "confidence": max(0.0, min(confidence, 1.0)),
                "structured_prompt": structured_prompt,
                "skill_plan": sanitized_plan,
            }
        except Exception as exc:
            logger.warning("[agent.decide] LLM turn decision failed: %s", exc)
            return None

    def _build_initial_state(
        self,
        user_input: str,
        user_id: str,
        approve_sensitive: bool,
        session: dict[str, Any] | None,
        semester_id: str,
        history: list[dict[str, Any]] | None = None,
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
            "conversation_context": self._build_conversation_context(
                history or [],
                user_input=user_input,
                session=session or {},
                user_id=user_id,
            ),
        }

    def _contextual_user_input(self, state: AgentState) -> str:
        context = state.get("conversation_context", {})
        if not isinstance(context, dict) or not context.get("recent_messages"):
            return state.get("user_input", "")
        payload = {
            "latest_user_input": state.get("user_input", ""),
            "conversation_context": context,
        }
        return json.dumps(payload, ensure_ascii=False)

    def _deterministic_plan_update(self, state: AgentState) -> dict[str, Any] | None:
        text = str(state.get("user_input", "") or "").strip()
        lowered = text.lower()
        homework_terms = ("作业", "homework", "assignment", "assignments", "ddl", "deadline")
        if not any(term in lowered for term in homework_terms):
            return None

        submit_intent = (
            any(term in lowered for term in ("submit", "turn in", "hand in"))
            or any(term in text for term in ("提交", "递交"))
            or re.search(r"(?:请|帮我|把|直接|现在).{0,16}交.{0,16}作业", text) is not None
        )
        upload_intent = any(term in lowered for term in ("upload", "attach")) or any(term in text for term in ("上传", "附件"))
        if submit_intent or upload_intent:
            attached_args = self._extract_attached_file_args(text)
            id_args = self._extract_homework_identifier_args(text)
            action_args = {**id_args, **attached_args}
            constraints = self._extract_attached_file_constraints(text)
            if not any(key in action_args for key in ("homework_id", "zyid", "homework_zyid", "xszyid", "student_homework_id")):
                action_args["lookup_hint"] = text
            planned_payloads: list[dict[str, Any]] = []
            if not any(key in action_args for key in ("homework_id", "zyid", "homework_zyid", "xszyid", "student_homework_id")):
                planned_payloads.append(
                    {
                        "skill_name": "crawl_unsubmitted_homeworks",
                        "args": {},
                        "description": "先读取未提交作业列表，帮助确认要提交的作业对象。",
                    }
                )
            if submit_intent and attached_args:
                upload_args = dict(action_args)
                planned_payloads.append(
                    {
                        "skill_name": "upload_homework_attachment",
                        "args": upload_args,
                        "description": "先把本地附件上传到作业提交表单并准备提交会话。",
                    }
                )
                submit_args = {
                    key: value
                    for key, value in action_args.items()
                    if key not in {"file_uri", "file_path", "file_name"}
                }
                planned_payloads.append(
                    {
                        "skill_name": "submit_homework",
                        "args": submit_args,
                        "description": "在附件上传完成后提交作业。",
                    }
                )
            else:
                planned_payloads.append(
                    {
                        "skill_name": "submit_homework" if submit_intent else "upload_homework_attachment",
                        "args": action_args,
                        "description": (
                            "提交作业内容或附件。"
                            if submit_intent
                            else "把本地附件上传到作业提交表单。"
                        ),
                    }
                )
            structured_prompt: StructuredPrompt = {
                "objective": text,
                "entities": ["homework", "submit" if submit_intent else "upload"],
                "constraints": constraints,
                "success_criteria": ["Create a homework submission/upload action for the Android device"],
                "sensitivity": "high" if submit_intent else "medium",
            }
            planned = self._sanitize_skill_plan(planned_payloads, state["task_id"])
            if not planned:
                return None
            return {
                "standardized_prompt": structured_prompt,
                "normalization_warnings": [],
                "skill_plan": planned,
                "normalizer_source": "deterministic",
                "planner_source": "deterministic",
                "task_status": "planned",
                "trace_log": self._append_trace(
                    state,
                    node="deterministic_plan",
                    detail={
                        "planned_count": len(planned),
                        "planned_skills": [item.get("skill_name", "") for item in planned],
                        "source": "deterministic",
                    },
                ),
            }

        unsubmitted_terms = (
            "未完成",
            "未提交",
            "未交",
            "没交",
            "待提交",
            "待完成",
            "unsubmitted",
            "not submitted",
            "unfinished",
            "missing homework",
        )
        unsubmitted = any(term in lowered for term in unsubmitted_terms)
        ddl_terms = ("ddl", "deadline", "due date", "due dates", "截止", "期限")
        wants_deadlines = any(term in lowered for term in ddl_terms) or any(term in text for term in ("截止", "期限"))
        skill_name = "get_assignments" if wants_deadlines else ("crawl_unsubmitted_homeworks" if unsubmitted else "crawl_course_homeworks")
        description = (
            "获取当前课程作业与 DDL。"
            if wants_deadlines
            else
            "检查网络学堂里尚未完成或未提交的作业。"
            if unsubmitted
            else "抓取网络学堂作业列表。"
        )
        structured_prompt: StructuredPrompt = {
            "objective": text,
            "entities": ["homework", "ddl"] if wants_deadlines else (["homework", "unsubmitted"] if unsubmitted else ["homework"]),
            "constraints": [],
            "success_criteria": ["Return homework records or login guidance"],
            "sensitivity": "low",
        }
        planned = self._sanitize_skill_plan(
            [{"skill_name": skill_name, "args": {}, "description": description}],
            state["task_id"],
        )
        if not planned:
            return None
        return {
            "standardized_prompt": structured_prompt,
            "normalization_warnings": [],
            "skill_plan": planned,
            "normalizer_source": "deterministic",
            "planner_source": "deterministic",
            "task_status": "planned",
            "trace_log": self._append_trace(
                state,
                node="deterministic_plan",
                detail={
                    "planned_count": len(planned),
                    "planned_skills": [item.get("skill_name", "") for item in planned],
                    "source": "deterministic",
                },
            ),
        }

    def _deterministic_reply_for_plan(self, plan: list[dict[str, Any]]) -> str:
        skill_names = [str(item.get("skill_name", "")) for item in plan if isinstance(item, dict)]
        if "submit_homework" in skill_names:
            return "我会先把提交作业这一步规划出来，需要手机端用本地登录态和附件继续执行。"
        if "upload_homework_attachment" in skill_names:
            return "我会把附件上传到作业提交表单这一步交给手机端处理。"
        if "crawl_unsubmitted_homeworks" in skill_names:
            return "我来检查网络学堂里还没有完成的作业。"
        if "crawl_course_homeworks" in skill_names:
            return "我来抓取网络学堂作业列表。"
        return "我会把这个任务拆成可执行步骤。"

    def _extract_attached_file_args(self, source_text: str) -> dict[str, str]:
        args: dict[str, str] = {}
        for item in self._extract_attached_file_constraints(source_text):
            if "=" not in item:
                continue
            key, value = item.split("=", 1)
            key = key.strip().removeprefix("attached_file.")
            value = value.strip()
            if key in {"file_uri", "file_name"} and value:
                args[key] = value
        return args

    def _extract_homework_identifier_args(self, source_text: str) -> dict[str, str]:
        patterns = {
            "homework_id": r"(?:homework_id|homework id|作业ID|作业id|zyid|zy_id)\s*[:=：]\s*([A-Za-z0-9_-]+)",
            "xszyid": r"(?:xszyid|student_homework_id|student homework id|学生作业ID|学生作业id)\s*[:=：]\s*([A-Za-z0-9_-]+)",
            "course_id": r"(?:course_id|wlkcid|课程ID|课程id)\s*[:=：]\s*([A-Za-z0-9_-]+)",
        }
        args: dict[str, str] = {}
        for key, pattern in patterns.items():
            match = re.search(pattern, source_text, flags=re.IGNORECASE)
            if match:
                args[key] = match.group(1)
        return args

    def _normalize_requirement(self, state: AgentState) -> dict[str, Any]:
        logger.debug(
            "[node.normalize] task_id=%s input=%r",
            state.get("task_id", ""),
            state["user_input"][:100],
        )
        api_key, model, base_url = self._llm_config_from_state(state)
        source_text = self._contextual_user_input(state)
        raw_prompt = self.llm.normalize(
            source_text,
            api_key=api_key,
            model=model,
            base_url=base_url,
        )
        standardized, warnings = self._coerce_structured_prompt(
            raw_prompt,
            source_text=source_text,
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
            "task_status": "model_unavailable" if self.llm.last_mode in {"not_configured", "llm_error"} else state.get("task_status", "planned"),
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
        if state.get("task_status") == "model_unavailable":
            deterministic_update = self._deterministic_plan_update(state)
            if deterministic_update:
                return deterministic_update
            return {
                "skill_plan": [],
                "planner_source": "not_run",
                "trace_log": self._append_trace(
                    state,
                    node="plan_skills",
                    detail={"planned_count": 0, "planned_skills": [], "source": "not_run"},
                ),
            }
        structured_prompt = self._coerce_prompt_from_state(state)
        planned = self._plan_skills_via_llm(state, structured_prompt)
        planner_source = "llm"
        if not planned:
            logger.warning(
                "[node.plan] task_id=%s no skills generated by configured model",
                state.get("task_id", ""),
            )
            planner_source = "llm_empty"
            deterministic_update = self._deterministic_plan_update(state)
            if deterministic_update:
                return deterministic_update

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
        if state.get("task_status") == "model_unavailable":
            return {
                "skill_plan": [],
                "approved_skills": [],
                "blocked_skills": [],
                "approval_records": [],
                "safety_report": {"approved_count": 0, "blocked_count": 0, "risk_details": []},
                "task_status": "model_unavailable",
                "trace_log": self._append_trace(
                    state,
                    node="safety_check",
                    detail={"approved_count": 0, "blocked_count": 0, "blocked_skills": []},
                ),
            }
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
        if state.get("task_status") == "model_unavailable":
            return {
                "skill_results": [],
                "failed_skills": [],
                "needs_replan": False,
                "task_status": "model_unavailable",
                "trace_log": self._append_trace(
                    state,
                    node="execute_skills",
                    detail={"executed_count": 0, "failed_count": 0, "result_codes": []},
                ),
            }
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

    def _synthesize_summary(self, state: AgentState) -> dict[str, Any]:
        user_input = state.get("user_input", "")
        skill_results = state.get("skill_results", [])

        logger.info(
            "[node.synthesize_summary] arrived task_id=%s skill_result_count=%d",
            state.get("task_id", ""),
            len(skill_results),
        )
        
        if not skill_results:
            return {"final_summary_text": ""}

        openai_key, llm_model, llm_base_url = self._llm_config_from_state(state)
        if not openai_key:
            logger.debug("[llm.synthesize] OPENAI_API_KEY not set, skipping LLM synthesis")
            return {"final_summary_text": ""}

        system_prompt = (
            "你是一个贴心的校园生活助手。请根据用户的原始提问以及后台工具返回的 JSON 结果数据，"
            "为用户撰写一段连贯、自然、排版美观的 Markdown 摘要总结。"
            "剔除不需要关注的底层字段(如状态码、ID等)，突出用户关心的重点。"
            "如果查询到了活动或日程等，可以用亲切的语气进行提示。如果没有有用信息，委婉地告知。"
        )
        user_content = f"【提问】\n{user_input}\n\n【返回数据】\n{json.dumps(skill_results, ensure_ascii=False)}"
        
        try:
            from openai import OpenAI
            client = self._create_openai_client(OpenAI, openai_key, base_url=llm_base_url)
            response = client.chat.completions.create(
                model=llm_model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_content}
                ],
                temperature=0.7,
                max_tokens=2048,
            )
            final_summary = response.choices[0].message.content or "操作已完成。"
            logger.info(
                "[node.synthesize_summary] llm_beautified_text task_id=%s text=%s",
                state.get("task_id", ""),
                _preview_text_for_log(final_summary),
            )
            return {
                "final_summary_text": final_summary.strip(),
                "trace_log": self._append_trace(
                    state, "synthesize_summary", {"status": "success", "length": len(final_summary.strip())}
                )
            }
        except Exception as e:
            logger.warning(f"[llm.synthesize] LLM error: {e}")
            return {
                "final_summary_text": "",
                "trace_log": self._append_trace(
                    state, "synthesize_summary", {"status": "error", "error": str(e)}
                )
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
                "conversation_context": state.get("conversation_context", {}),
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
                "final_summary_text": state.get("final_summary_text", ""),
                "available_skills": self.skill_manager.list_for_planner(),
                "trace_log": state.get("trace_log", []),
                "normalizer_source": state.get("normalizer_source", "not_run"),
                "planner_source": state.get("planner_source", "not_run"),
            },
        }
        return {"final_response": response}

    def _summarize_outcome(self, state: AgentState) -> tuple[str, str]:
        if state.get("task_status") == "model_unavailable":
            if state.get("normalizer_source") == "not_configured":
                return "LLM_NOT_CONFIGURED", self._llm_not_configured_message()
            return "LLM_UNAVAILABLE", self._llm_unavailable_message()
        if state.get("blocked_skills") and not state.get("skill_results"):
            return "APPROVAL_REQUIRED", "One or more skills are waiting for user approval"
        if state.get("failed_skills"):
            return "SKILL_EXECUTION_FAILED", "Some skills failed and replanning guidance was generated"
        return "OK", "Workflow completed"

    def synthesize_summary_from_results(
        self,
        *,
        user_input: str,
        session: dict[str, Any],
        task_doc: dict[str, Any],
        fallback_summary: str,
        conversation_context: dict[str, Any] | None = None,
    ) -> str:
        server_results = task_doc.get("server_results", [])
        device_results = task_doc.get("device_results", [])
        logger.info(
            "[agent.summary] arrived task_id=%s server_result_count=%d device_result_count=%d",
            task_doc.get("task_id", ""),
            len(server_results) if isinstance(server_results, list) else 0,
            len(device_results) if isinstance(device_results, list) else 0,
        )
        api_key, model, base_url = self._llm_config_from_session(session if isinstance(session, dict) else {})
        if not api_key:
            return fallback_summary

        summary_context = (
            conversation_context
            if isinstance(conversation_context, dict) and conversation_context
            else self._build_conversation_context(
                [],
                user_input=user_input,
                session=session if isinstance(session, dict) else {},
                user_id=str((session or {}).get("user_id", "")) if isinstance(session, dict) else "",
            )
        )
        results: list[dict[str, Any]] = []
        for key in ("server_results", "device_results"):
            raw_results = task_doc.get(key, [])
            if isinstance(raw_results, list):
                results.extend(self._compact_result_for_summary(item, key) for item in raw_results if isinstance(item, dict))

        payload = {
            "user_input": user_input,
            "conversation_context": summary_context,
            "task_status": task_doc.get("status", ""),
            "results": results,
            "blocked_skills": [
                {
                    "skill_name": item.get("skill_name", ""),
                    "description": item.get("description", ""),
                    "risk_level": item.get("risk_level", ""),
                }
                for item in task_doc.get("blocked_skills", [])
                if isinstance(item, dict)
            ],
            "fallback_summary": fallback_summary,
        }

        system_prompt = (
            "你是 OpenTHU 移动端 Agent 的最终总结节点。"
            "你会收到用户原始请求、云端 skill 和手机端 skill 的结构化执行结果。"
            "请在所有结果基础上给用户一段自然、明确、有帮助的中文最终回复。"
            "不要暴露 request_id、内部 JSON、工具日志或实现细节；"
            "可以参考 conversation_context.memory_context 中的用户偏好和近期反馈来决定表达重点，"
            "但不要直接暴露这些内部记忆条目；"
            "如果有失败或权限问题，要说明用户下一步应该怎么做；"
            "如果已有具体结果，直接总结结果，不要只说任务已完成。"
        )

        try:
            from openai import OpenAI

            client = self._create_openai_client(OpenAI, api_key, base_url=base_url)
            completion = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(payload, ensure_ascii=False)[:14000]},
                ],
                max_tokens=800,
                temperature=0.35,
            )
            text = (completion.choices[0].message.content or "").strip()
            logger.info(
                "[agent.summary] llm_beautified_text task_id=%s text=%s",
                task_doc.get("task_id", ""),
                _preview_text_for_log(text),
            )
            return text or fallback_summary
        except Exception as exc:
            logger.warning("[agent.summary] final synthesis failed: %s", exc)
            return fallback_summary

    def _compact_result_for_summary(self, result: dict[str, Any], result_group: str) -> dict[str, Any]:
        data = result.get("data", {})
        compact: dict[str, Any] = {
            "group": result_group,
            "skill_name": result.get("skill_name", ""),
            "code": result.get("code", ""),
            "success": bool(result.get("success", result.get("code") == "OK")),
            "message": result.get("message", ""),
            "source": result.get("source", ""),
        }
        if isinstance(data, dict):
            for key in (
                "status",
                "reason",
                "message",
                "answer",
                "summary",
                "query",
                "count",
                "candidate_count",
                "selected_count",
                "discarded_count",
                "filter_status",
                "filter_source",
                "filter_summary",
                "notification_count",
                "semantic",
            ):
                value = data.get(key)
                if value not in (None, "", [], {}):
                    compact[key] = value
            for key in ("results", "citations", "activities", "notifications", "warnings"):
                value = data.get(key)
                if isinstance(value, list) and value:
                    item_limit = 10 if compact.get("skill_name") == "get_campus_activities" and key == "activities" else 6
                    compact[key] = value[:item_limit]
            for key in ("hard_constraints", "preference_signals"):
                value = data.get(key)
                if value not in (None, "", [], {}):
                    compact[key] = value
        return compact

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
            "user_input": state.get("user_input", ""),
            "conversation_context": state.get("conversation_context", {}),
            "semester_id": state.get("semester_id", ""),
            "available_skills": self.skill_manager.list_for_planner(),
        }
        system_prompt = (
            "You are the planner for a mobile agent. "
            "Return a strict JSON array. Each item must have skill_name, args, description. "
            "Use only skill_name values from available_skills. "
            "available_skills includes description, when_to_use, avoid_when, example_utterances, and args schemas; "
            "select skills semantically from that catalog instead of keyword rules. "
            "Prefer data skills before action skills. "
            "Do not invent backend calls. Keep the plan between 1 and 8 skills. "
            "If an available skill can satisfy the user's request, plan it instead of refusing because data is personal; "
            "the skill will report login-required/not-configured if credentials are missing. "
            "For alarm-related requests, prefer local-time semantics (`HH:mm`) in set_alarm args. "
            "When user intent contains relative time words (e.g. 明天/后天/今晚/下周/tomorrow/next week) or a calendar date without a year, you must add `get_current_time` before time-dependent skills. "
            "For `create_calendar_event` and `detect_calendar_conflicts`, `start_time` and `end_time` may be concrete ISO-8601 datetimes or the user's original time text. "
            "A complete user-provided absolute calendar time must include an explicit year, date, and time. Month/day/time without a year is incomplete: examples include `6月21日下午4点`, `06/21 16:00`, and `June 21 at 4pm`. "
            "For those relative or year-underspecified calendar requests, keep the calendar skill after `get_current_time`, keep the original time phrase in `time_text`/`start_time`, and set `time_source=user_text`; do not fill in a year yourself and do not set `time_source=explicit_absolute`. "
            "If the user gives a full absolute date with explicit year and time, do not add `get_current_time` just for calendar timing; pass ISO if useful and set `time_source=explicit_absolute`. "
            "If time comes from an upstream skill result such as a DDL/deadline, pass that ISO and set `time_source=upstream_skill`, `source_skill`, and `source_field`. "
            "If you infer missing date parts yourself, set `time_source=planner_inferred` so the runtime can verify it. Do not invent `current_time` yourself. "
            "For campus activity/news/event queries, use `get_campus_activities` with the user's query; do not add `get_semesters` unless the user explicitly asks for semesters or courses. "
            "For class timetable or 课表 requests, use `get_semesters` before `get_course_schedule`; for course catalog/list requests, use `get_semesters` then `get_courses`. "
            "For course assignment DDL/deadline queries, use `get_assignments`; for Tsinghua Learn homework crawl queries, use crawl_unsubmitted_homeworks or crawl_course_homeworks; "
            "phrases like `check my homework that is not submitted`, `unsubmitted assignments`, `未交作业`, `未提交作业` map to crawl_unsubmitted_homeworks; "
            "when the user explicitly asks to submit/turn in/递交/提交 homework, use submit_homework; "
            "when that explicit submit/turn-in/递交/提交 request includes an attached file, plan upload_homework_attachment immediately followed by submit_homework; "
            "never plan upload_homework_attachment alone for a submit/turn-in/递交/提交 request. "
            "when the user asks only to upload/stage an attached file, use upload_homework_attachment; "
            "use get_homework_cookie only when the user provides a Learn cookie. "
            "Use conversation_context to resolve follow-up references like `the second one`, `that activity`, `刚才那个`, `第二个`, or `上一个`; "
            "when a reference maps to a prior result, copy the concrete title, time, query, or object from context into skill args. "
            "Use conversation_context.memory_context as durable user preferences and feedback; "
            "prefer plans that align with long-term preferences and avoid actions the user recently ignored, "
            "unless the latest user_input clearly asks otherwise. "
            "If user_input or structured_prompt constraints include an [attached_file] block with file_uri/file_name, copy those exact values into upload_homework_attachment args when the user asks to upload or submit homework."
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
            sanitized = self._apply_search_scene_overrides(
                self._sanitize_skill_plan(parsed, state["task_id"]),
                state,
            )
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

        course_context_skills = {"get_courses", "get_course_schedule", "get_assignments", "get_notices", "get_files"}
        if (
            any(item.get("skill_name") == "get_campus_activities" for item in normalized)
            and not any(item.get("skill_name") in course_context_skills for item in normalized)
        ):
            normalized = [
                item
                for item in normalized
                if item.get("skill_name") != "get_semesters"
            ]

        has_schedule = any(item.get("skill_name") == "get_course_schedule" for item in normalized)
        has_semesters = any(item.get("skill_name") == "get_semesters" for item in normalized)
        if has_schedule and not has_semesters:
            semester_probe = self._build_skill_invocation(
                skill_name="get_semesters",
                task_id=task_id,
                args={},
                description="Resolve available semesters before fetching the course schedule",
            )
            first_schedule_index = next(
                (index for index, item in enumerate(normalized) if item.get("skill_name") == "get_course_schedule"),
                0,
            )
            normalized.insert(first_schedule_index, semester_probe)

        return normalized[:8]

    def _search_scene_prefers_general(self, state: AgentState) -> bool:
        session = state.get("session", {})
        if not isinstance(session, dict):
            return False
        return str(session.get("search_scene", "")).strip().lower() == "general"

    def _apply_search_scene_overrides(
        self,
        plan: list[dict[str, Any]],
        state: AgentState,
    ) -> list[dict[str, Any]]:
        if not plan or not self._search_scene_prefers_general(state):
            return plan
        overridden: list[dict[str, Any]] = []
        for item in plan:
            if item.get("skill_name") != "get_campus_activities":
                overridden.append(item)
                continue
            args = dict(item.get("args") or {})
            query = str(args.get("query") or state.get("user_input", "")).strip()
            overridden.append(
                self._build_skill_invocation(
                    skill_name="search",
                    task_id=state["task_id"],
                    args={"query": query, "scene": "general"},
                    description="按通用网页搜索范围检索相关信息。",
                )
            )
        return overridden

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
        structured, _ = self._coerce_structured_prompt(raw_prompt, source_text=state.get("user_input", ""))
        return structured

    def _coerce_structured_prompt(
        self,
        raw_prompt: dict[str, Any],
        source_text: str,
    ) -> tuple[StructuredPrompt, list[str]]:
        warnings: list[str] = []

        objective = raw_prompt.get("objective")
        if not isinstance(objective, str) or not objective.strip():
            legacy_objective = raw_prompt.get("object")
            if isinstance(legacy_objective, str) and legacy_objective.strip():
                objective = legacy_objective
                warnings.append("standardization used legacy key 'object'; expected 'objective'")
            else:
                objective = source_text.strip()
                warnings.append("standardization missing 'objective'; used original input")

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
        attached_constraints = self._extract_attached_file_constraints(source_text)
        for item in attached_constraints:
            if item not in constraints:
                constraints.append(item)

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

    def _extract_attached_file_constraints(self, source_text: str) -> list[str]:
        if "[attached_file]" not in source_text:
            return []
        values: dict[str, str] = {}
        for raw_line in source_text.splitlines():
            line = raw_line.strip()
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            key = key.strip()
            if key in {"file_uri", "file_name"}:
                values[key] = value.strip()
        constraints: list[str] = []
        file_uri = values.get("file_uri", "")
        file_name = values.get("file_name", "")
        if file_uri:
            constraints.append(f"attached_file.file_uri={file_uri}")
        if file_name:
            constraints.append(f"attached_file.file_name={file_name}")
        return constraints

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
