from __future__ import annotations

import argparse
import json
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph


class AgentState(TypedDict, total=False):
    user_input: str
    user_id: str
    approve_sensitive: bool
    standardized_prompt: dict[str, Any]
    plan: list[dict[str, Any]]
    safety_report: dict[str, Any]
    approved_actions: list[dict[str, Any]]
    blocked_actions: list[dict[str, Any]]
    execution_results: list[dict[str, Any]]
    failed_actions: list[dict[str, Any]]
    needs_replan: bool
    replanned_actions: list[dict[str, Any]]
    audit_log: list[dict[str, Any]]
    memory_update: dict[str, Any]
    final_response: dict[str, Any]
    normalization_warnings: list[str]


class StructuredPrompt(TypedDict):
    objective: str
    entities: list[str]
    constraints: list[str]
    success_criteria: list[str]
    sensitivity: str


@dataclass
class RequirementLLM:
    """Normalize free-form requirement into structured execution prompt.

    If OPENAI_API_KEY and openai SDK are available, this node calls a model.
    Otherwise it falls back to deterministic extraction for offline demos.
    """

    model: str = "gpt-4.1-mini"

    def normalize(self, user_input: str) -> dict[str, Any]:
        openai_key = os.getenv("OPENAI_API_KEY")
        if not openai_key:
            return self._fallback(user_input)

        try:
            from openai import OpenAI

            client = OpenAI(api_key=openai_key)
            prompt = (
                "Convert the user requirement into JSON with keys: objective, "
                "entities, constraints, success_criteria, sensitivity. "
                "Return strict JSON only."
            )
            response = client.responses.create(
                model=self.model,
                input=[
                    {"role": "system", "content": prompt},
                    {"role": "user", "content": user_input},
                ],
                max_output_tokens=400,
                temperature=0.1,
            )
            text = response.output_text.strip()
            return json.loads(text)
        except Exception:
            return self._fallback(user_input)

    def _fallback(self, user_input: str) -> dict[str, Any]:
        lower = user_input.lower()
        entities: list[str] = []
        sensitivity = "low"

        if any(keyword in lower for keyword in ["课程", "课表", "ddl", "deadline", "exam", "考试"]):
            entities.append("schedule")
        if any(keyword in lower for keyword in ["活动", "news", "资讯", "校园"]):
            entities.append("campus_info")
        if any(keyword in lower for keyword in ["支付", "验证码", "alipay", "wechat", "qq"]):
            entities.append("account_sensitive")
            sensitivity = "high"

        if not entities:
            entities.append("general_task")

        return {
            "objective": user_input.strip(),
            "entities": entities,
            "constraints": ["mobile-first", "user-confirm-high-risk"],
            "success_criteria": [
                "action plan generated",
                "safety review completed",
                "execution trace auditable",
            ],
            "sensitivity": sensitivity,
        }


class OpenTHULangGraphAgent:
    def __init__(self, memory_file: Path) -> None:
        self.memory_file = memory_file
        self.llm = RequirementLLM()
        self.graph = self._build_graph()

    def _build_graph(self):
        workflow = StateGraph(AgentState)

        workflow.add_node("normalize_requirement", self._normalize_requirement)
        workflow.add_node("plan_actions", self._plan_actions)
        workflow.add_node("safety_review", self._safety_review)
        workflow.add_node("execute_actions", self._execute_actions)
        workflow.add_node("replan_failed", self._replan_failed)
        workflow.add_node("audit_record", self._audit_record)
        workflow.add_node("memory_update", self._memory_update)
        workflow.add_node("finalize", self._finalize)

        workflow.add_edge(START, "normalize_requirement")
        workflow.add_edge("normalize_requirement", "plan_actions")
        workflow.add_edge("plan_actions", "safety_review")
        workflow.add_edge("safety_review", "execute_actions")

        workflow.add_conditional_edges(
            "execute_actions",
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

    def run(self, user_input: str, user_id: str = "default_user", approve_sensitive: bool = False) -> dict[str, Any]:
        initial: AgentState = {
            "user_input": user_input,
            "user_id": user_id,
            "approve_sensitive": approve_sensitive,
        }
        return self.graph.invoke(initial)

    def _normalize_requirement(self, state: AgentState) -> dict[str, Any]:
        raw_prompt = self.llm.normalize(state["user_input"])
        standardized, warnings = self._coerce_structured_prompt(raw_prompt, fallback_text=state["user_input"])
        return {
            "standardized_prompt": standardized,
            "normalization_warnings": warnings,
        }

    def _plan_actions(self, state: AgentState) -> dict[str, Any]:
        structured_prompt = self._coerce_prompt_from_state(state)
        actions = self._plan_actions_via_llm(structured_prompt)
        if not actions:
            actions = self._fallback_plan_actions(structured_prompt)
        return {"plan": actions}

    def _safety_review(self, state: AgentState) -> dict[str, Any]:
        approve_sensitive = state.get("approve_sensitive", False)
        plan = state.get("plan", [])
        structured_prompt = self._coerce_prompt_from_state(state)

        approved: list[dict[str, Any]] = []
        blocked: list[dict[str, Any]] = []
        risk_details: list[dict[str, Any]] = []

        for action in plan:
            assessed_risk, reason, source = self._assess_action_risk(action, structured_prompt)
            assessed_action = dict(action)
            assessed_action["risk"] = assessed_risk
            assessed_action["risk_reason"] = reason
            assessed_action["risk_source"] = source

            risk_details.append(
                {
                    "action_id": assessed_action.get("id", ""),
                    "assessed_risk": assessed_risk,
                    "source": source,
                    "reason": reason,
                }
            )

            if assessed_risk == "high" and not approve_sensitive:
                blocked.append(assessed_action)
            else:
                approved.append(assessed_action)

        report = {
            "approved_count": len(approved),
            "blocked_count": len(blocked),
            "risk_details": risk_details,
            "blocked_reasons": [
                {"action_id": item["id"], "reason": "High-risk action requires explicit approval"}
                for item in blocked
            ],
        }

        return {
            "approved_actions": approved,
            "blocked_actions": blocked,
            "safety_report": report,
        }

    def _execute_actions(self, state: AgentState) -> dict[str, Any]:
        approved_actions = state.get("approved_actions", [])
        results: list[dict[str, Any]] = []
        failed: list[dict[str, Any]] = []

        for action in approved_actions:
            tool = action["tool"]
            action_id = action["id"]

            if action_id == "cross_app_sensitive":
                result = {
                    "action_id": action_id,
                    "tool": tool,
                    "status": "failed",
                    "message": "Sensitive cross-app execution path unavailable in current device mode",
                }
            else:
                result = {
                    "action_id": action_id,
                    "tool": tool,
                    "status": "success",
                    "message": "Executed",
                }

            results.append(result)
            if result["status"] == "failed":
                failed.append(action)

        return {
            "execution_results": results,
            "failed_actions": failed,
            "needs_replan": len(failed) > 0,
        }

    def _route_after_execution(self, state: AgentState) -> str:
        return "replan_failed" if state.get("needs_replan") else "audit_record"

    def _replan_failed(self, state: AgentState) -> dict[str, Any]:
        failed_actions = state.get("failed_actions", [])
        replanned: list[dict[str, Any]] = []

        for action in failed_actions:
            replanned.append(
                {
                    "id": f"manual_{action['id']}",
                    "tool": "human.confirmation",
                    "risk": "medium",
                    "args": {"reason": f"Fallback for failed action {action['id']}"},
                }
            )

        return {"replanned_actions": replanned}

    def _audit_record(self, state: AgentState) -> dict[str, Any]:
        now = datetime.now(timezone.utc).isoformat()
        log: list[dict[str, Any]] = [
            {
                "ts": now,
                "stage": "normalize_requirement",
                "payload": state.get("standardized_prompt", {}),
            },
            {
                "ts": now,
                "stage": "normalization_warnings",
                "payload": state.get("normalization_warnings", []),
            },
            {
                "ts": now,
                "stage": "plan_actions",
                "payload": state.get("plan", []),
            },
            {
                "ts": now,
                "stage": "safety_review",
                "payload": state.get("safety_report", {}),
            },
            {
                "ts": now,
                "stage": "execute_actions",
                "payload": state.get("execution_results", []),
            },
            {
                "ts": now,
                "stage": "blocked_actions",
                "payload": state.get("blocked_actions", []),
            },
        ]

        replanned = state.get("replanned_actions", [])
        if replanned:
            log.append(
                {
                    "ts": now,
                    "stage": "replan_failed",
                    "payload": replanned,
                }
            )

        return {"audit_log": log}

    def _memory_update(self, state: AgentState) -> dict[str, Any]:
        memory = self._load_memory()
        now = datetime.now(timezone.utc).isoformat()

        entry = {
            "ts": now,
            "user_id": state.get("user_id", "default_user"),
            "objective": state.get("standardized_prompt", {}).get("objective", state.get("user_input", "")),
            "entities": state.get("standardized_prompt", {}).get("entities", []),
            "success_count": len([r for r in state.get("execution_results", []) if r["status"] == "success"]),
            "failure_count": len([r for r in state.get("execution_results", []) if r["status"] == "failed"]),
            "blocked_count": len(state.get("blocked_actions", [])),
        }

        memory.setdefault("entries", []).append(entry)
        memory["entries"] = memory["entries"][-100:]
        self._save_memory(memory)

        return {"memory_update": entry}

    def _finalize(self, state: AgentState) -> dict[str, Any]:
        response = {
            "standardized_prompt": state.get("standardized_prompt", {}),
            "normalization_warnings": state.get("normalization_warnings", []),
            "plan": state.get("plan", []),
            "safety_report": state.get("safety_report", {}),
            "execution_results": state.get("execution_results", []),
            "replanned_actions": state.get("replanned_actions", []),
            "audit_log": state.get("audit_log", []),
            "memory_update": state.get("memory_update", {}),
        }
        return {"final_response": response}

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

    def _assess_action_risk(
        self,
        action: dict[str, Any],
        structured_prompt: StructuredPrompt,
    ) -> tuple[str, str, str]:
        rule_risk, rule_reason = self._assess_action_risk_by_rule(action)
        llm_result = self._assess_action_risk_via_llm(action, structured_prompt)

        if llm_result is None:
            return rule_risk, f"rule-only: {rule_reason}", "rule"

        llm_risk, llm_reason = llm_result
        # Hybrid policy: if either rule or LLM says high, final risk is high.
        if rule_risk == "high" or llm_risk == "high":
            return "high", f"hybrid-high(rule={rule_risk}, llm={llm_risk}): {llm_reason}", "hybrid"

        if rule_risk == "medium" or llm_risk == "medium":
            return "medium", f"hybrid-medium(rule={rule_risk}, llm={llm_risk}): {llm_reason}", "hybrid"

        return "low", f"hybrid-low(rule={rule_risk}, llm={llm_risk}): {llm_reason}", "hybrid"

    def _assess_action_risk_by_rule(self, action: dict[str, Any]) -> tuple[str, str]:
        stated_risk = str(action.get("risk", "medium")).strip().lower()
        if stated_risk not in {"low", "medium", "high"}:
            stated_risk = "medium"

        tool = str(action.get("tool", "")).strip().lower()
        action_id = str(action.get("id", "")).strip().lower()
        args_text = json.dumps(action.get("args", {}), ensure_ascii=False).lower()

        high_signals = ["payment", "pay", "verify", "otp", "password", "login", "account", "sensitive", "验证码"]
        if (
            stated_risk == "high"
            or tool == "ui.automation"
            or any(signal in action_id for signal in high_signals)
            or any(signal in args_text for signal in high_signals)
        ):
            return "high", "rule detected sensitive/account-like automation signal"

        return stated_risk, "rule used declared action risk"

    def _assess_action_risk_via_llm(
        self,
        action: dict[str, Any],
        structured_prompt: StructuredPrompt,
    ) -> tuple[str, str] | None:
        openai_key = os.getenv("OPENAI_API_KEY")
        if not openai_key:
            return None

        system_prompt = (
            "You are a mobile safety reviewer. "
            "Classify one planned action risk into low, medium, or high. "
            "High risk includes account/payment/authentication or potentially harmful automation. "
            "Return strict JSON only: {\"risk\":\"low|medium|high\",\"reason\":\"short reason\"}."
        )
        payload = {
            "structured_prompt": structured_prompt,
            "action": action,
        }

        try:
            from openai import OpenAI

            client = OpenAI(api_key=openai_key)
            response = client.responses.create(
                model=self.llm.model,
                input=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
                ],
                max_output_tokens=200,
                temperature=0.0,
            )
            text = response.output_text.strip()
            parsed = json.loads(text)
            risk = str(parsed.get("risk", "medium")).strip().lower()
            reason = str(parsed.get("reason", "")).strip()
            if risk not in {"low", "medium", "high"}:
                return None
            return risk, (reason or "LLM risk assessment")
        except Exception:
            return None

    def _plan_actions_via_llm(self, structured_prompt: StructuredPrompt) -> list[dict[str, Any]]:
        openai_key = os.getenv("OPENAI_API_KEY")
        if not openai_key:
            return []

        available_tools = [
            {"tool": "browser.open", "risk": "low", "schema": {"url": "string"}},
            {
                "tool": "calendar.create",
                "risk": "low",
                "schema": {"title": "string", "description": "string"},
            },
            {
                "tool": "alarm.set",
                "risk": "medium",
                "schema": {"message": "string", "hour": "int", "minute": "int"},
            },
            {"tool": "ui.automation", "risk": "high", "schema": {"mode": "string"}},
            {"tool": "context.review", "risk": "low", "schema": {"reason": "string"}},
        ]

        system_prompt = (
            "You are a mobile agent planner. Produce a concise action plan in strict JSON array. "
            "Each item must contain: id, tool, risk(low|medium|high), args(object). "
            "Only use tools from available_tools. Keep 1-4 actions."
        )
        user_payload = {
            "structured_prompt": structured_prompt,
            "available_tools": available_tools,
        }

        try:
            from openai import OpenAI

            client = OpenAI(api_key=openai_key)
            response = client.responses.create(
                model=self.llm.model,
                input=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
                ],
                max_output_tokens=600,
                temperature=0.2,
            )
            raw_text = response.output_text.strip()
            parsed = json.loads(raw_text)
            if not isinstance(parsed, list):
                return []
            return self._sanitize_actions(parsed)
        except Exception:
            return []

    def _sanitize_actions(self, actions: list[Any]) -> list[dict[str, Any]]:
        normalized: list[dict[str, Any]] = []
        allowed_risks = {"low", "medium", "high"}
        allowed_tools = {"browser.open", "calendar.create", "alarm.set", "ui.automation", "context.review"}

        for idx, action in enumerate(actions):
            if not isinstance(action, dict):
                continue
            tool = str(action.get("tool", "")).strip()
            risk = str(action.get("risk", "")).strip().lower()
            action_id = str(action.get("id", f"action_{idx + 1}")).strip() or f"action_{idx + 1}"
            args = action.get("args", {})

            if tool not in allowed_tools:
                continue
            if risk not in allowed_risks:
                risk = "medium"
            if not isinstance(args, dict):
                args = {}

            normalized.append(
                {
                    "id": action_id,
                    "tool": tool,
                    "risk": risk,
                    "args": args,
                }
            )

        return normalized[:4]

    def _fallback_plan_actions(self, structured_prompt: StructuredPrompt) -> list[dict[str, Any]]:
        entities = set(structured_prompt["entities"])
        actions: list[dict[str, Any]] = []

        if "campus_info" in entities:
            actions.append(
                {
                    "id": "open_campus_news",
                    "tool": "browser.open",
                    "risk": "low",
                    "args": {"url": "https://www.tsinghua.edu.cn"},
                }
            )
        if "schedule" in entities:
            actions.append(
                {
                    "id": "add_calendar_event",
                    "tool": "calendar.create",
                    "risk": "low",
                    "args": {"title": "Campus task", "description": structured_prompt["objective"]},
                }
            )
            actions.append(
                {
                    "id": "set_deadline_alarm",
                    "tool": "alarm.set",
                    "risk": "medium",
                    "args": {"message": "Campus reminder", "hour": 8, "minute": 0},
                }
            )
        if "account_sensitive" in entities:
            actions.append(
                {
                    "id": "cross_app_sensitive",
                    "tool": "ui.automation",
                    "risk": "high",
                    "args": {"mode": "account_related"},
                }
            )
        if not actions:
            actions.append(
                {
                    "id": "fallback_review",
                    "tool": "context.review",
                    "risk": "low",
                    "args": {"reason": "No high-confidence entity"},
                }
            )

        return actions

    def _coerce_prompt_from_state(self, state: AgentState) -> StructuredPrompt:
        raw = state.get("standardized_prompt", {})
        coerced, _ = self._coerce_structured_prompt(raw, fallback_text=state.get("user_input", ""))
        return coerced

    def _coerce_structured_prompt(
        self,
        raw_prompt: dict[str, Any],
        fallback_text: str,
    ) -> tuple[StructuredPrompt, list[str]]:
        warnings: list[str] = []

        objective = raw_prompt.get("objective")
        if not isinstance(objective, str) or not objective.strip():
            # Compatibility: some models may output "object" by mistake.
            legacy_objective = raw_prompt.get("object")
            if isinstance(legacy_objective, str) and legacy_objective.strip():
                objective = legacy_objective
                warnings.append("standardization used legacy key 'object'; expected 'objective'")
            else:
                objective = fallback_text.strip()
                warnings.append("standardization missing 'objective'; used fallback text")

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
        sensitivity = sensitivity.strip().lower()

        structured: StructuredPrompt = {
            "objective": objective.strip(),
            "entities": entities,
            "constraints": constraints,
            "success_criteria": success_criteria,
            "sensitivity": sensitivity,
        }
        return structured, warnings


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="OpenTHU LangGraph single-stack agent runner")
    parser.add_argument("--input", required=True, help="User natural language requirement")
    parser.add_argument("--user-id", default="demo_user", help="User identifier for memory")
    parser.add_argument(
        "--approve-sensitive",
        action="store_true",
        help="Allow high-risk actions to pass safety review",
    )
    parser.add_argument(
        "--memory-file",
        default="agent/langgraph/memory_store.json",
        help="Path to persistent memory json file",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    agent = OpenTHULangGraphAgent(memory_file=Path(args.memory_file))
    result = agent.run(
        user_input=args.input,
        user_id=args.user_id,
        approve_sensitive=args.approve_sensitive,
    )
    print(json.dumps(result.get("final_response", {}), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
