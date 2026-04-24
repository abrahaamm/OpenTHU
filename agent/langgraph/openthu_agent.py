from __future__ import annotations

import argparse
import json
import os
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, TypedDict
from uuid import uuid4

from langgraph.graph import END, START, StateGraph

try:
    from .skill_core import SkillInvocation, SkillRegistry, build_default_registry
except ImportError:
    from skill_core import SkillInvocation, SkillRegistry, build_default_registry


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


class StructuredPrompt(TypedDict):
    objective: str
    entities: list[str]
    constraints: list[str]
    success_criteria: list[str]
    sensitivity: str


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def risk_rank(risk_level: str) -> int:
    return {"low": 0, "medium": 1, "high": 2}.get(risk_level, 1)


def normalize_risk(risk_level: str) -> str:
    risk = str(risk_level).strip().lower()
    return risk if risk in {"low", "medium", "high"} else "medium"


class RequirementLLM:
    """Normalize user requirement into a stable prompt structure."""

    def __init__(self, model: str = "gpt-4.1-mini") -> None:
        self.model = model

    def normalize(self, user_input: str) -> dict[str, Any]:
        openai_key = os.getenv("OPENAI_API_KEY")
        if not openai_key:
            return self._fallback(user_input)

        try:
            from openai import OpenAI

            client = OpenAI(api_key=openai_key)
            system_prompt = (
                "Convert the user requirement into strict JSON with keys: "
                "objective, entities, constraints, success_criteria, sensitivity. "
                "Use concise, execution-oriented values. Return JSON only."
            )
            response = client.responses.create(
                model=self.model,
                input=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_input},
                ],
                max_output_tokens=500,
                temperature=0.1,
            )
            return json.loads(response.output_text.strip())
        except Exception:
            return self._fallback(user_input)

    def _fallback(self, user_input: str) -> dict[str, Any]:
        lower = user_input.lower()
        entities: list[str] = []
        sensitivity = "low"

        if any(token in lower for token in ["作业", "ddl", "deadline", "assignment", "homework"]):
            entities.append("assignments")
        if any(token in lower for token in ["课程", "课表", "上课", "course", "schedule"]):
            entities.append("courses")
        if any(token in lower for token in ["通知", "公告", "notice"]):
            entities.append("notices")
        if any(token in lower for token in ["文件", "课件", "资料", "file"]):
            entities.append("files")
        if any(token in lower for token in ["活动", "讲座", "资讯", "校园", "activity", "news"]):
            entities.append("activities")
        if any(token in lower for token in ["搜索", "查找", "search"]):
            entities.append("search")
        if any(token in lower for token in ["提醒", "待办", "reminder"]):
            entities.append("reminder")
        if any(token in lower for token in ["日历", "calendar"]):
            entities.append("calendar")
        if any(token in lower for token in ["闹钟", "alarm"]):
            entities.append("alarm")
        if any(token in lower for token in ["通知我", "推送", "notification"]):
            entities.append("notification")
        if any(token in lower for token in ["app", "微信", "qq", "支付宝", "launch"]):
            entities.append("cross_app")
            sensitivity = "high"
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
    ) -> None:
        self.memory_file = memory_file
        self.llm = RequirementLLM()
        self.skill_registry = skill_registry or build_default_registry()
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
        initial: AgentState = {
            "request_id": f"req_{uuid4().hex[:12]}",
            "session": session or {},
            "task_id": f"task_{uuid4().hex[:10]}",
            "task_status": "planned",
            "semester_id": semester_id,
            "user_input": user_input,
            "user_id": user_id,
            "approve_sensitive": approve_sensitive,
        }
        return self.graph.invoke(initial)

    def _normalize_requirement(self, state: AgentState) -> dict[str, Any]:
        raw_prompt = self.llm.normalize(state["user_input"])
        standardized, warnings = self._coerce_structured_prompt(
            raw_prompt,
            fallback_text=state["user_input"],
        )
        return {
            "standardized_prompt": standardized,
            "normalization_warnings": warnings,
        }

    def _plan_skills(self, state: AgentState) -> dict[str, Any]:
        structured_prompt = self._coerce_prompt_from_state(state)
        planned = self._plan_skills_via_llm(state, structured_prompt)
        if not planned:
            planned = self._fallback_skill_plan(state, structured_prompt)

        if not planned:
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

        return {
            "skill_plan": planned,
            "task_status": "planned",
        }

    def _safety_check(self, state: AgentState) -> dict[str, Any]:
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
        }

    def _execute_skills(self, state: AgentState) -> dict[str, Any]:
        approved_skills = state.get("approved_skills", [])
        current_plan = state.get("skill_plan", [])
        current_session = dict(state.get("session", {}))
        skill_results: list[dict[str, Any]] = []
        failed_skills: list[dict[str, Any]] = []

        for invocation_dict in approved_skills:
            invocation = self._skill_invocation_from_dict(invocation_dict)
            handler = self.skill_registry.get_handler(invocation.skill_name)

            try:
                result = handler.invoke(invocation, current_session, state).to_dict()
            except Exception as exc:
                result = {
                    "skill_name": invocation.skill_name,
                    "request_id": invocation.request_id,
                    "code": "SKILL_EXECUTION_FAILED",
                    "data": {
                        "status": "handler_error",
                        "message": f"Skill handler raised an exception: {exc}",
                    },
                    "from_cache": False,
                    "fetched_at": utc_now(),
                    "source": "skill_handler",
                }

            success = result.get("code") == "OK"
            result["task_id"] = state["task_id"]
            result["status"] = "executed" if success else "failed"
            result["success"] = success
            result["skill_name"] = invocation.skill_name
            result["description"] = invocation.description
            skill_results.append(result)

            if success:
                maybe_session = result.get("data", {}).get("session")
                if isinstance(maybe_session, dict):
                    current_session = maybe_session
            else:
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

        return {
            "session": current_session,
            "skill_plan": updated_plan,
            "skill_results": skill_results,
            "failed_skills": failed_skills,
            "needs_replan": bool(failed_skills),
            "task_status": task_status,
        }

    def _route_after_execution(self, state: AgentState) -> str:
        return "replan_failed" if state.get("needs_replan") else "audit_record"

    def _replan_failed(self, state: AgentState) -> dict[str, Any]:
        replanned: list[dict[str, Any]] = []

        for failed_skill in state.get("failed_skills", []):
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

        return {"audit_log": audit_log}

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
        return {"memory_update": entry}

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
                "available_skills": self.skill_registry.describe_for_planner(),
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
        openai_key = os.getenv("OPENAI_API_KEY")
        if not openai_key:
            return []

        payload = {
            "structured_prompt": structured_prompt,
            "semester_id": state.get("semester_id", ""),
            "available_skills": self.skill_registry.describe_for_planner(),
        }
        system_prompt = (
            "You are the planner for a mobile agent. "
            "Return a strict JSON array. Each item must have skill_name, args, description. "
            "Use only skill_name values from available_skills. "
            "Prefer data skills before action skills. "
            "Do not invent backend calls. Keep the plan between 1 and 8 skills."
        )

        try:
            from openai import OpenAI

            client = OpenAI(api_key=openai_key)
            response = client.responses.create(
                model=self.llm.model,
                input=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
                ],
                max_output_tokens=1000,
                temperature=0.2,
            )
            parsed = json.loads(response.output_text.strip())
            if not isinstance(parsed, list):
                return []
            return self._sanitize_skill_plan(parsed, state["task_id"])
        except Exception:
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
            if self.skill_registry.get_spec(skill_name) is None:
                continue
            normalized.append(
                self._build_skill_invocation(
                    skill_name=skill_name,
                    task_id=task_id,
                    args=args,
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
                },
                "将解析结果写入系统日历",
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

        if "alarm" in entities:
            append_skill(
                "set_alarm",
                {
                    "time": "08:00",
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

        extracted_url = self._extract_url(objective)
        if extracted_url:
            append_skill(
                "open_url",
                {"url": extracted_url, "in_app": True},
                "打开用户提到的目标链接",
            )

        if "cross_app" in entities:
            append_skill(
                "launch_app",
                {
                    "package_name": "com.tencent.mm",
                    "action": "android.intent.action.VIEW",
                    "extras": {"objective": objective},
                },
                "执行受控跨应用跳转",
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
        spec = self.skill_registry.get_spec(skill_name)
        base_risk = normalize_risk(spec.risk_level if spec else planned_skill.get("risk_level", "medium"))

        if skill_name == "launch_app":
            return "high", "launch_app always requires the strongest guardrail"

        if any(token in args_text for token in ["password", "token", "ticket", "otp", "验证码"]):
            return "high", "credential-like parameters detected"

        if skill_name in {"create_reminder", "create_calendar_event"}:
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

            client = OpenAI(api_key=openai_key)
            system_prompt = (
                "You are a mobile agent safety reviewer. "
                "Classify the risk of one planned skill invocation as low, medium, or high. "
                "If the skill touches credentials, authentication, account state, or external app jumping, "
                "treat it as high. If it writes into system apps like reminders or calendar, treat it as at least medium. "
                "Return strict JSON only: {\"risk\":\"low|medium|high\",\"reason\":\"short reason\"}."
            )
            payload = {
                "structured_prompt": structured_prompt,
                "planned_skill": planned_skill,
            }
            response = client.responses.create(
                model=self.llm.model,
                input=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
                ],
                max_output_tokens=250,
                temperature=0.0,
            )
            parsed = json.loads(response.output_text.strip())
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
        spec = self.skill_registry.get_spec(skill_name)
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
    agent = OpenTHULangGraphAgent(memory_file=Path(args.memory_file))
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
