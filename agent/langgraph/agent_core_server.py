from __future__ import annotations

import argparse
import json
import logging
import logging.handlers
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
import uvicorn

try:
    from .agent_events import agent_event, encode_ndjson
    from .openthu_agent import OpenTHULangGraphAgent
    from .skill_core import MissingSkillHandler, SkillInvocation
except ImportError:
    from agent_events import agent_event, encode_ndjson
    from openthu_agent import OpenTHULangGraphAgent
    from skill_core import MissingSkillHandler, SkillInvocation

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)
# Suppress verbose HTTP-level debug noise from openai/httpx libraries.
logging.getLogger("openai._base_client").setLevel(logging.WARNING)
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)
logger = logging.getLogger("agent_core_server")
DEVICE_RESULT_WAIT_HEARTBEAT_SECONDS = 8.0
DEVICE_RESULT_WAIT_TIMEOUT_SECONDS = 300.0


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class DeviceRegisterRequest(BaseModel):
    device_id: str
    user_id: str = "demo_user"
    platform: str = "android"
    fcm_token: str | None = None
    app_version: str | None = None
    capabilities: list[str] = Field(default_factory=list)


class ChatMessageItem(BaseModel):
    role: str
    text: str


class PlanTaskRequest(BaseModel):
    device_id: str
    user_id: str = "demo_user"
    goal: str
    approve_sensitive: bool = False
    semester_id: str = ""
    session: dict[str, Any] = Field(default_factory=dict)
    history: list[ChatMessageItem] = Field(default_factory=list)


class ChatTurnRequest(BaseModel):
    device_id: str = ""
    user_id: str = "demo_user"
    message: str
    session: dict[str, Any] = Field(default_factory=dict)
    history: list[ChatMessageItem] = Field(default_factory=list)


class AgentRunStreamRequest(BaseModel):
    device_id: str
    user_id: str = "demo_user"
    message: str
    approve_sensitive: bool = True
    semester_id: str = ""
    session: dict[str, Any] = Field(default_factory=dict)
    history: list[ChatMessageItem] = Field(default_factory=list)


class SkillResultSubmitRequest(BaseModel):
    device_id: str
    request_id: str
    skill_name: str
    code: str
    message: str = ""
    data: dict[str, Any] = Field(default_factory=dict)
    source: str = "android_app"
    from_cache: bool = False
    fetched_at: str | None = None


class SkillDecisionRequest(BaseModel):
    device_id: str
    request_id: str
    decision: str
    user_id: str = "demo_user"
    reason: str = ""


class AgentCoreStore:
    def __init__(self, store_file: Path) -> None:
        self.store_file = store_file
        self._lock = threading.Lock()
        self._condition = threading.Condition(self._lock)
        self._state = {
            "devices": {},
            "tasks": {},
        }
        self._load()

    def _load(self) -> None:
        if not self.store_file.exists():
            logger.info("[store] store file not found, starting with empty state: %s", self.store_file)
            return
        try:
            loaded = json.loads(self.store_file.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                self._state["devices"] = loaded.get("devices", {}) if isinstance(loaded.get("devices"), dict) else {}
                self._state["tasks"] = loaded.get("tasks", {}) if isinstance(loaded.get("tasks"), dict) else {}
            logger.info(
                "[store] loaded from %s: devices=%d tasks=%d",
                self.store_file,
                len(self._state["devices"]),
                len(self._state["tasks"]),
            )
        except Exception as exc:
            logger.warning("[store] failed to load store file, resetting state: %s", exc)
            self._state = {"devices": {}, "tasks": {}}

    def _save_locked(self) -> None:
        self.store_file.parent.mkdir(parents=True, exist_ok=True)
        self.store_file.write_text(
            json.dumps(self._state, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        logger.debug(
            "[store] persisted to %s: devices=%d tasks=%d",
            self.store_file,
            len(self._state["devices"]),
            len(self._state["tasks"]),
        )

    def register_device(self, payload: DeviceRegisterRequest) -> dict[str, Any]:
        with self._lock:
            now = utc_now()
            existing = self._state["devices"].get(payload.device_id, {})
            is_new = payload.device_id not in self._state["devices"]
            device = {
                "device_id": payload.device_id,
                "user_id": payload.user_id,
                "platform": payload.platform,
                "fcm_token": payload.fcm_token,
                "app_version": payload.app_version,
                "capabilities": payload.capabilities,
                "registered_at": existing.get("registered_at", now),
                "last_seen_at": now,
            }
            self._state["devices"][payload.device_id] = device
            self._save_locked()
            logger.info(
                "[device] %s device_id=%s user_id=%s platform=%s capabilities=%s",
                "registered" if is_new else "updated",
                payload.device_id,
                payload.user_id,
                payload.platform,
                payload.capabilities,
            )
            return device

    def get_device(self, device_id: str) -> dict[str, Any] | None:
        with self._lock:
            found = self._state["devices"].get(device_id)
            return dict(found) if isinstance(found, dict) else None

    def create_planned_task(
        self,
        *,
        plan_response: dict[str, Any],
        device_id: str,
        user_id: str,
        goal: str,
    ) -> dict[str, Any]:
        with self._lock:
            now = utc_now()
            data = plan_response.get("data", {}) if isinstance(plan_response.get("data"), dict) else {}
            task_id = str(data.get("task_id", ""))
            if not task_id:
                raise ValueError("plan response missing task_id")

            approved_count = len(data.get("approved_skills", []))
            blocked_count = len(data.get("blocked_skills", []))
            task_doc = {
                "task_id": task_id,
                "request_id": plan_response.get("request_id", ""),
                "device_id": device_id,
                "user_id": user_id,
                "goal": goal,
                "status": data.get("task_status", "planned"),
                "created_at": now,
                "updated_at": now,
                "plan_only_response": plan_response,
                "skill_plan": data.get("skill_plan", []),
                "approved_skills": data.get("approved_skills", []),
                "blocked_skills": data.get("blocked_skills", []),
                "final_summary_text": data.get("final_summary_text", ""),
                "device_results": [],
                "in_flight_request_ids": [],
                "completed_request_ids": [],
            }
            self._state["tasks"][task_id] = task_doc
            self._save_locked()
            self._condition.notify_all()
            logger.info(
                "[task] created task_id=%s device_id=%s status=%s "
                "approved_skills=%d blocked_skills=%d goal=%r",
                task_id,
                device_id,
                task_doc["status"],
                approved_count,
                blocked_count,
                goal[:80],
            )
            if blocked_count:
                blocked_names = [
                    item.get("skill_name", "?") for item in data.get("blocked_skills", [])
                    if isinstance(item, dict)
                ]
                logger.warning(
                    "[task] task_id=%s has %d blocked skill(s) pending approval: %s",
                    task_id,
                    blocked_count,
                    blocked_names,
                )
            return dict(task_doc)

    def get_task(self, task_id: str) -> dict[str, Any] | None:
        with self._lock:
            found = self._state["tasks"].get(task_id)
            return dict(found) if isinstance(found, dict) else None

    def _mark_skill_status(
        self,
        task_doc: dict[str, Any],
        request_id: str,
        status: str,
    ) -> None:
        for collection_key in ("skill_plan", "approved_skills"):
            items = task_doc.get(collection_key, [])
            if not isinstance(items, list):
                continue
            for item in items:
                if not isinstance(item, dict):
                    continue
                if str(item.get("request_id", "")) == request_id:
                    item["status"] = status

    def _approved_skill_count(self, task_doc: dict[str, Any]) -> int:
        return len([item for item in task_doc.get("approved_skills", []) if isinstance(item, dict)])

    def _all_results(self, task_doc: dict[str, Any]) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []
        for key in ("server_results", "device_results"):
            value = task_doc.get(key, [])
            if isinstance(value, list):
                results.extend(item for item in value if isinstance(item, dict))
        return results

    def _refresh_task_status_locked(self, task_doc: dict[str, Any]) -> None:
        expected = self._approved_skill_count(task_doc)
        completed = set(str(item) for item in task_doc.get("completed_request_ids", []))
        in_flight = set(str(item) for item in task_doc.get("in_flight_request_ids", []))
        pending_blocked = [
            item
            for item in task_doc.get("blocked_skills", [])
            if isinstance(item, dict) and str(item.get("status", "pending_approval")) == "pending_approval"
        ]
        if expected == 0:
            if pending_blocked:
                task_doc["status"] = "approval_required"
            elif task_doc.get("rejected_skills"):
                task_doc["status"] = "cancelled"
            else:
                task_doc["status"] = "planned"
        elif len(completed) >= expected:
            task_doc["status"] = "completed" if all(item.get("code") == "OK" for item in self._all_results(task_doc)) else "failed"
        elif in_flight:
            task_doc["status"] = "in_progress"
        else:
            task_doc["status"] = "ready_for_device_execution"

    def record_server_result(
        self,
        *,
        task_id: str,
        result: dict[str, Any],
    ) -> dict[str, Any]:
        with self._lock:
            task_doc = self._state["tasks"].get(task_id)
            if not isinstance(task_doc, dict):
                raise KeyError("task_not_found")

            request_id = str(result.get("request_id", ""))
            approved_request_ids = {
                str(item.get("request_id", ""))
                for item in task_doc.get("approved_skills", [])
                if isinstance(item, dict)
            }
            if request_id not in approved_request_ids:
                raise ValueError("request_id_not_in_approved_skills")

            completed = set(str(item) for item in task_doc.get("completed_request_ids", []))
            if request_id in completed:
                return dict(task_doc)
            completed.add(request_id)

            code = str(result.get("code", "")).strip() or "SKILL_EXECUTION_FAILED"
            success = code == "OK"
            result_item = {
                "request_id": request_id,
                "skill_name": result.get("skill_name", ""),
                "code": code,
                "success": success,
                "message": result.get("message")
                or _display_message_from_result(
                    skill_name=str(result.get("skill_name", "")),
                    code=code,
                    data=result.get("data", {}),
                ),
                "data": result.get("data", {}),
                "source": result.get("source", "agent_core"),
                "from_cache": bool(result.get("from_cache", False)),
                "fetched_at": result.get("fetched_at") or utc_now(),
            }
            results = task_doc.get("server_results", [])
            if not isinstance(results, list):
                results = []
            results.append(result_item)
            task_doc["server_results"] = results
            task_doc["completed_request_ids"] = sorted(completed)
            self._mark_skill_status(task_doc, request_id, "executed" if success else "failed")
            self._refresh_task_status_locked(task_doc)
            task_doc["updated_at"] = utc_now()
            self._save_locked()
            self._condition.notify_all()
            return dict(task_doc)

    def suppress_show_summary_for_stream(self, *, task_id: str) -> dict[str, Any]:
        with self._lock:
            task_doc = self._state["tasks"].get(task_id)
            if not isinstance(task_doc, dict):
                raise KeyError("task_not_found")

            completed = set(str(item) for item in task_doc.get("completed_request_ids", []))
            changed = False
            for skill in task_doc.get("approved_skills", []):
                if not isinstance(skill, dict):
                    continue
                if str(skill.get("skill_name", "")) != "show_summary":
                    continue
                request_id = str(skill.get("request_id", ""))
                if not request_id:
                    continue
                completed.add(request_id)
                self._mark_skill_status(task_doc, request_id, "suppressed")
                changed = True

            if changed:
                task_doc["completed_request_ids"] = sorted(completed)
                task_doc["stream_final_summary"] = True
                self._refresh_task_status_locked(task_doc)
                task_doc["updated_at"] = utc_now()
                self._save_locked()
                self._condition.notify_all()
            return dict(task_doc)

    def pop_next_dispatch(self, device_id: str) -> dict[str, Any] | None:
        with self._lock:
            tasks = [
                item
                for item in self._state["tasks"].values()
                if isinstance(item, dict) and str(item.get("device_id", "")) == device_id
            ]
            tasks.sort(key=lambda item: str(item.get("created_at", "")))

            for task_doc in tasks:
                status = str(task_doc.get("status", ""))
                if status not in {"ready_for_device_execution", "in_progress"}:
                    continue
                approved = task_doc.get("approved_skills", [])
                if not isinstance(approved, list):
                    continue
                completed = set(str(item) for item in task_doc.get("completed_request_ids", []))
                in_flight = set(str(item) for item in task_doc.get("in_flight_request_ids", []))
                for skill in approved:
                    if not isinstance(skill, dict):
                        continue
                    request_id = str(skill.get("request_id", ""))
                    if not request_id:
                        continue
                    if request_id in completed or request_id in in_flight:
                        continue
                    skill_name = str(skill.get("skill_name", "unknown"))
                    args = skill.get("args", {})
                    if (
                        skill_name == "show_summary"
                        and isinstance(args, dict)
                        and not str(args.get("content", "")).strip()
                        and any(
                            isinstance(candidate, dict)
                            and str(candidate.get("skill_name", "")) != "show_summary"
                            and str(candidate.get("request_id", "")) not in completed
                            for candidate in approved
                        )
                    ):
                        continue

                    in_flight.add(request_id)
                    task_doc["in_flight_request_ids"] = sorted(in_flight)
                    task_doc["status"] = "in_progress"
                    task_doc["updated_at"] = utc_now()
                    self._mark_skill_status(task_doc, request_id, "dispatched")
                    self._save_locked()
                    logger.info(
                        "[dispatch] task_id=%s request_id=%s skill_name=%s device_id=%s",
                        task_doc.get("task_id", ""),
                        request_id,
                        skill_name,
                        device_id,
                    )
                    logger.debug(
                        "[dispatch] skill_args=%s",
                        json.dumps(skill.get("args", {}), ensure_ascii=False),
                    )
                    return {
                        "task_id": task_doc.get("task_id", ""),
                        "request_id": request_id,
                        "device_id": device_id,
                        "dispatched_at": utc_now(),
                        "skill_invocation": skill,
                    }
            logger.debug("[dispatch] no pending skill for device_id=%s", device_id)
            return None

    def submit_device_result(
        self,
        *,
        task_id: str,
        payload: SkillResultSubmitRequest,
    ) -> dict[str, Any]:
        with self._lock:
            task_doc = self._state["tasks"].get(task_id)
            if not isinstance(task_doc, dict):
                raise KeyError("task_not_found")
            if str(task_doc.get("device_id", "")) != payload.device_id:
                raise PermissionError("task_device_mismatch")

            approved_request_ids = {
                str(item.get("request_id", ""))
                for item in task_doc.get("approved_skills", [])
                if isinstance(item, dict)
            }
            if payload.request_id not in approved_request_ids:
                raise ValueError("request_id_not_in_approved_skills")

            completed = set(str(item) for item in task_doc.get("completed_request_ids", []))
            if payload.request_id in completed:
                return dict(task_doc)

            in_flight = set(str(item) for item in task_doc.get("in_flight_request_ids", []))
            if payload.request_id in in_flight:
                in_flight.remove(payload.request_id)
            completed.add(payload.request_id)

            code = str(payload.code).strip() or "SKILL_EXECUTION_FAILED"
            success = code == "OK"
            logger.info(
                "[result] received task_id=%s request_id=%s skill_name=%s code=%s success=%s device_id=%s",
                task_id,
                payload.request_id,
                payload.skill_name,
                code,
                success,
                payload.device_id,
            )
            if not success:
                logger.warning(
                    "[result] skill execution failed task_id=%s skill_name=%s code=%s message=%r",
                    task_id,
                    payload.skill_name,
                    code,
                    payload.message,
                )
            result_item = {
                "request_id": payload.request_id,
                "skill_name": payload.skill_name,
                "code": code,
                "success": success,
                "message": payload.message or code,
                "data": payload.data,
                "source": payload.source,
                "from_cache": payload.from_cache,
                "fetched_at": payload.fetched_at or utc_now(),
            }
            results = task_doc.get("device_results", [])
            if not isinstance(results, list):
                results = []
            results.append(result_item)
            task_doc["device_results"] = results
            task_doc["in_flight_request_ids"] = sorted(in_flight)
            task_doc["completed_request_ids"] = sorted(completed)
            self._mark_skill_status(task_doc, payload.request_id, "executed" if success else "failed")

            self._refresh_task_status_locked(task_doc)
            task_doc["updated_at"] = utc_now()
            self._save_locked()
            self._condition.notify_all()
            logger.info(
                "[result] task_id=%s updated status=%s completed=%d/%d",
                task_id,
                task_doc["status"],
                len(task_doc["completed_request_ids"]),
                len([item for item in task_doc.get("approved_skills", []) if isinstance(item, dict)]),
            )
            return dict(task_doc)

    def apply_skill_decision(
        self,
        *,
        task_id: str,
        payload: SkillDecisionRequest,
    ) -> dict[str, Any]:
        with self._lock:
            task_doc = self._state["tasks"].get(task_id)
            if not isinstance(task_doc, dict):
                raise KeyError("task_not_found")
            if str(task_doc.get("device_id", "")) != payload.device_id:
                raise PermissionError("task_device_mismatch")

            normalized_decision = payload.decision.strip().lower()
            if normalized_decision in {"approve", "approved", "allow", "allowed"}:
                normalized_decision = "approved"
            elif normalized_decision in {"reject", "rejected", "deny", "denied"}:
                normalized_decision = "rejected"
            else:
                raise ValueError("unsupported_decision")

            blocked = task_doc.get("blocked_skills", [])
            if not isinstance(blocked, list):
                blocked = []
            selected: dict[str, Any] | None = None
            remaining_blocked: list[dict[str, Any]] = []
            for item in blocked:
                if not isinstance(item, dict):
                    continue
                if str(item.get("request_id", "")) == payload.request_id and selected is None:
                    selected = dict(item)
                else:
                    remaining_blocked.append(item)
            if selected is None:
                approved_ids = {
                    str(item.get("request_id", ""))
                    for item in task_doc.get("approved_skills", [])
                    if isinstance(item, dict)
                }
                rejected_ids = {
                    str(item.get("request_id", ""))
                    for item in task_doc.get("rejected_skills", [])
                    if isinstance(item, dict)
                }
                if payload.request_id in approved_ids or payload.request_id in rejected_ids:
                    return dict(task_doc)
                raise ValueError("request_id_not_pending_approval")

            now = utc_now()
            approval_record = {
                "approval_id": f"apv_{payload.request_id}",
                "task_id": task_id,
                "request_id": payload.request_id,
                "skill_name": selected.get("skill_name", ""),
                "risk_level": selected.get("risk_level", ""),
                "decision": normalized_decision,
                "reason": payload.reason,
                "decided_by": payload.user_id,
                "decided_at": now,
            }

            task_doc["blocked_skills"] = remaining_blocked
            if normalized_decision == "approved":
                selected["status"] = "approved"
                selected["approved_at"] = now
                approved = task_doc.get("approved_skills", [])
                if not isinstance(approved, list):
                    approved = []
                if payload.request_id not in {
                    str(item.get("request_id", ""))
                    for item in approved
                    if isinstance(item, dict)
                }:
                    approved.append(selected)
                task_doc["approved_skills"] = approved
            else:
                selected["status"] = "rejected"
                selected["rejected_at"] = now
                rejected = task_doc.get("rejected_skills", [])
                if not isinstance(rejected, list):
                    rejected = []
                rejected.append(selected)
                task_doc["rejected_skills"] = rejected

            for item in task_doc.get("skill_plan", []):
                if isinstance(item, dict) and str(item.get("request_id", "")) == payload.request_id:
                    item["status"] = normalized_decision

            approval_records = task_doc.get("approval_records", [])
            if not isinstance(approval_records, list):
                approval_records = []
            approval_records.append(approval_record)
            task_doc["approval_records"] = approval_records
            task_doc["updated_at"] = now
            self._refresh_task_status_locked(task_doc)
            self._save_locked()
            self._condition.notify_all()
            logger.info(
                "[decision] task_id=%s request_id=%s decision=%s status=%s",
                task_id,
                payload.request_id,
                normalized_decision,
                task_doc.get("status", ""),
            )
            return dict(task_doc)

    def update_skill_args(
        self,
        *,
        task_id: str,
        request_id: str,
        args: dict[str, Any],
    ) -> dict[str, Any]:
        with self._lock:
            task_doc = self._state["tasks"].get(task_id)
            if not isinstance(task_doc, dict):
                raise KeyError("task_not_found")

            updated = False
            for collection_key in ("skill_plan", "approved_skills"):
                items = task_doc.get(collection_key, [])
                if not isinstance(items, list):
                    continue
                for item in items:
                    if not isinstance(item, dict):
                        continue
                    if str(item.get("request_id", "")) == request_id:
                        item["args"] = dict(args)
                        updated = True

            if updated:
                task_doc["updated_at"] = utc_now()
                self._save_locked()
                self._condition.notify_all()
            return dict(task_doc)

    def set_final_summary(
        self,
        *,
        task_id: str,
        summary: str,
    ) -> dict[str, Any]:
        with self._lock:
            task_doc = self._state["tasks"].get(task_id)
            if not isinstance(task_doc, dict):
                raise KeyError("task_not_found")
            task_doc["final_summary_text"] = summary
            task_doc["final_summary_generated_at"] = utc_now()
            task_doc["updated_at"] = utc_now()
            self._save_locked()
            self._condition.notify_all()
            return dict(task_doc)

    def wait_for_task_update(
        self,
        *,
        task_id: str,
        last_updated_at: str,
        timeout_seconds: float,
    ) -> dict[str, Any] | None:
        deadline = time.monotonic() + max(0.0, timeout_seconds)
        with self._condition:
            while True:
                task_doc = self._state["tasks"].get(task_id)
                if not isinstance(task_doc, dict):
                    return None
                if str(task_doc.get("updated_at", "")) != last_updated_at:
                    return dict(task_doc)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return dict(task_doc)
                self._condition.wait(timeout=remaining)


def _skill_invocation_from_dict(payload: dict[str, Any]) -> SkillInvocation:
    return SkillInvocation(
        skill_name=str(payload.get("skill_name", "")),
        request_id=str(payload.get("request_id", "")),
        task_id=str(payload.get("task_id", "")),
        args=payload.get("args", {}) if isinstance(payload.get("args"), dict) else {},
        risk_level=str(payload.get("risk_level", "low")),
        requires_approval=bool(payload.get("requires_approval", False)),
        description=str(payload.get("description", "")),
        status=str(payload.get("status", "approved")),
    )


def execute_server_side_data_skills(
    *,
    agent: OpenTHULangGraphAgent,
    store: AgentCoreStore,
    task_doc: dict[str, Any],
    session: dict[str, Any],
) -> dict[str, Any]:
    current_task = dict(task_doc)
    task_id = str(current_task.get("task_id", ""))
    state = {
        "task_id": task_id,
        "request_id": current_task.get("request_id", ""),
        "user_input": current_task.get("goal", ""),
        "session": session,
        "skill_plan": current_task.get("skill_plan", []),
        "approved_skills": current_task.get("approved_skills", []),
    }
    current_session = dict(session)

    for skill in current_task.get("approved_skills", []):
        if not isinstance(skill, dict):
            continue
        skill_name = str(skill.get("skill_name", ""))
        spec = agent.skill_manager.get_spec(skill_name)
        if spec is None or spec.category != "data":
            continue
        handler = agent.skill_manager.registry.get_handler(skill_name)
        if isinstance(handler, MissingSkillHandler):
            continue
        invocation_payload = dict(skill)
        invocation_payload["task_id"] = task_id
        invocation = _skill_invocation_from_dict(invocation_payload)
        result = agent.skill_manager.execute(invocation, current_session, state)
        result["task_id"] = task_id
        result["status"] = "executed" if result.get("code") == "OK" else "failed"
        result["success"] = result.get("code") == "OK"
        result["skill_name"] = invocation.skill_name
        result["description"] = invocation.description
        current_task = store.record_server_result(task_id=task_id, result=result)
        maybe_session = result.get("data", {}).get("session")
        if isinstance(maybe_session, dict):
            current_session = maybe_session
            state["session"] = current_session
    return current_task


def hydrate_show_summary_skills(
    *,
    store: AgentCoreStore,
    task_doc: dict[str, Any],
) -> dict[str, Any]:
    summary_content = task_doc.get("final_summary_text", "").strip()
    
    device_results = task_doc.get("device_results", [])
    if device_results:
        device_summary = build_server_data_summary(device_results)
        if device_summary:
            if summary_content:
                summary_content += "\n\n" + device_summary
            else:
                summary_content = device_summary
                
    if not summary_content:
        summary_content = build_task_result_summary(task_doc)
    
    if not summary_content:
        return task_doc

    current_task = dict(task_doc)
    completed = set(str(item) for item in current_task.get("completed_request_ids", []))
    for skill in current_task.get("approved_skills", []):
        if not isinstance(skill, dict):
            continue
        if str(skill.get("skill_name", "")) != "show_summary":
            continue
        request_id = str(skill.get("request_id", ""))
        if not request_id or request_id in completed:
            continue
        args = dict(skill.get("args", {}) if isinstance(skill.get("args"), dict) else {})
        args["title"] = args.get("title") or "OpenTHU 查询结果"
        args["content"] = summary_content
        args["format"] = "markdown"
        current_task = store.update_skill_args(
            task_id=str(current_task.get("task_id", "")),
            request_id=request_id,
            args=args,
        )
    return current_task


def build_task_result_summary(task_doc: dict[str, Any]) -> str:
    results: list[dict[str, Any]] = []
    for key in ("server_results", "device_results"):
        value = task_doc.get(key, [])
        if isinstance(value, list):
            results.extend(item for item in value if isinstance(item, dict))
    return build_server_data_summary(results)


def pending_runtime_request_ids(task_doc: dict[str, Any]) -> list[str]:
    approved = task_doc.get("approved_skills", [])
    if not isinstance(approved, list):
        return []
    completed = set(str(item) for item in task_doc.get("completed_request_ids", []))
    pending: list[str] = []
    for skill in approved:
        if not isinstance(skill, dict):
            continue
        skill_name = str(skill.get("skill_name", ""))
        request_id = str(skill.get("request_id", ""))
        if not request_id or request_id in completed:
            continue
        if skill_name == "show_summary":
            continue
        pending.append(request_id)
    return pending


def build_server_data_summary(results: Any) -> str:
    if not isinstance(results, list):
        return ""
    sections: list[str] = []
    for result in results:
        if not isinstance(result, dict) or result.get("code") != "OK":
            continue
        skill_name = str(result.get("skill_name", ""))
        data = result.get("data", {})
        if not isinstance(data, dict):
            continue
        section = _summarize_data_result(skill_name, data, message=str(result.get("message", "")))
        if section:
            sections.append(section)
    return "\n\n".join(sections)


def _display_message_from_result(
    *,
    skill_name: str,
    code: str,
    data: Any,
) -> str:
    if not isinstance(data, dict):
        return code
    message = str(data.get("message", "")).strip()
    if code != "OK":
        return message or code
    if skill_name == "search":
        answer = str(data.get("answer", "") or data.get("summary", "")).strip()
        if answer:
            return answer[:1200]
    if skill_name == "get_campus_activities":
        answer = str(data.get("answer", "")).strip()
        summary = str(data.get("summary", "")).strip()
        if answer:
            return answer[:1200]
        if summary:
            return summary[:1200]
    if message:
        return message
    status = str(data.get("status", "")).strip()
    return status or code


def _summarize_data_result(skill_name: str, data: dict[str, Any], message: str = "") -> str:
    if skill_name == "search":
        lines = ["### 搜索结果"]
        answer = str(data.get("answer") or data.get("summary") or "").strip()
        if answer:
            lines.append(answer[:1200])
        results = data.get("results", [])
        if isinstance(results, list) and results:
            lines.append("来源：")
            for item in results[:5]:
                if not isinstance(item, dict):
                    continue
                title = str(item.get("title", "未命名结果")).strip()
                url = str(item.get("url", "")).strip()
                snippet = str(item.get("snippet", "")).strip()
                line = f"- {title}"
                if url:
                    line += f"：{url}"
                if snippet:
                    line += f"\n  {snippet[:160]}"
                lines.append(line)
        return "\n".join(lines)

    if skill_name == "read_notifications":
        metadata = data.get("metadata", {})
        if not isinstance(metadata, dict):
            metadata = {}
        notifications = data.get("notifications")
        if not isinstance(notifications, list):
            notifications = metadata.get("notifications", [])
        if not isinstance(notifications, list):
            notifications = []
        count_value = data.get("notification_count", metadata.get("notification_count", len(notifications)))
        try:
            count = int(count_value)
        except (TypeError, ValueError):
            count = len(notifications)

        lines = ["### 未读通知"]
        if count <= 0 and not notifications:
            lines.append("没有读取到未读通知。")
            return "\n".join(lines)

        lines.append(f"共读取到 {max(count, len(notifications))} 条未读通知。")
        for item in notifications[:8]:
            if not isinstance(item, dict):
                continue
            package = str(item.get("package", "") or item.get("pkg", "")).strip()
            title = str(item.get("title", "未命名通知")).strip()
            text = str(item.get("text", "") or item.get("body", "")).strip()
            line = f"- {title}"
            if package:
                line = f"- [{package}] {title}"
            if text:
                line += f"：{text[:180]}"
            lines.append(line)

        if len(lines) == 2:
            fallback = message.strip()
            if fallback:
                lines.append(fallback[:1000])
        return "\n".join(lines)

    if skill_name == "get_campus_activities":
        lines = ["### 校园活动"]
        answer = str(data.get("answer") or data.get("summary") or "").strip()
        if answer:
            lines.append(answer[:1000])
        activities = data.get("activities", [])
        if isinstance(activities, list) and activities:
            lines.append("活动：")
            for item in activities[:6]:
                if not isinstance(item, dict):
                    continue
                title = str(item.get("title", "未命名活动")).strip()
                time_label = str(item.get("start_time", "") or item.get("time", "")).strip()
                location = str(item.get("location", "")).strip()
                url = str(item.get("url", "")).strip()
                details = "，".join(part for part in [time_label, location] if part)
                line = f"- {title}"
                if details:
                    line += f"（{details}）"
                if url:
                    line += f"：{url}"
                lines.append(line)
        return "\n".join(lines)

    if skill_name == "get_semesters":
        semesters = data.get("semesters", [])
        if not isinstance(semesters, list):
            semesters = []
        current = str(data.get("current_semester", "")).strip()
        lines = ["### 学期信息"]
        if current:
            lines.append(f"当前学期：{current}")
        lines.append(f"共找到 {len(semesters)} 个学期。")
        for item in semesters[:6]:
            if isinstance(item, dict):
                label = str(item.get("semester_name") or item.get("semester_id") or "").strip()
                first_day = str(item.get("first_day", "")).strip()
                if label:
                    lines.append(f"- {label}" + (f"（教学周从 {first_day} 开始）" if first_day else ""))
            elif item:
                lines.append(f"- {item}")
        return "\n".join(lines)

    if skill_name in {"get_courses", "get_course_schedule"}:
        courses = data.get("courses", [])
        if not isinstance(courses, list):
            courses = []
        schedule_entries = data.get("schedule_entries", [])
        schedule_count = len(schedule_entries) if isinstance(schedule_entries, list) else 0
        title = "课表" if skill_name == "get_course_schedule" else "课程列表"
        lines = [f"### {title}", f"共找到 {len(courses)} 门课程。"]
        if schedule_count:
            lines.append(f"按日期展开的课表条目：{schedule_count} 条。")
        for item in courses[:8]:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or item.get("course_name") or "未命名课程").strip()
            teacher = str(item.get("teacher_name", "")).strip()
            time_blocks = item.get("time_and_location", [])
            details = []
            if teacher:
                details.append(teacher)
            if isinstance(time_blocks, list) and time_blocks:
                block = time_blocks[0]
                if isinstance(block, dict):
                    weekday = block.get("weekday")
                    period = block.get("period", [])
                    location = str(block.get("location", "")).strip()
                    time_label = ""
                    if weekday:
                        time_label += f"周{weekday}"
                    if isinstance(period, list) and len(period) >= 2:
                        time_label += f" 第{period[0]}-{period[1]}节"
                    if location:
                        time_label += f" {location}"
                    if time_label.strip():
                        details.append(time_label.strip())
            line = f"- {name}"
            if details:
                line += f"（{'，'.join(details)}）"
            lines.append(line)
        warnings = data.get("warnings", [])
        if isinstance(warnings, list) and warnings:
            lines.append("提示：" + "；".join(str(item) for item in warnings[:3]))
        return "\n".join(lines)

    if skill_name in {"crawl_course_homeworks", "crawl_unsubmitted_homeworks"}:
        homeworks = data.get("homeworks", [])
        if not isinstance(homeworks, list):
            homeworks = []
        count_value = data.get("count", len(homeworks))
        try:
            count = int(count_value)
        except (TypeError, ValueError):
            count = len(homeworks)
        title = "未提交作业" if skill_name == "crawl_unsubmitted_homeworks" else "作业列表"
        lines = [f"### {title}", f"共找到 {max(count, len(homeworks))} 条作业记录。"]
        for item in homeworks[:8]:
            if not isinstance(item, dict):
                continue
            homework_title = str(item.get("title") or item.get("homework_title") or "未命名作业").strip()
            course_name = str(item.get("course_name", "")).strip()
            deadline = str(item.get("deadline", "")).strip()
            submitted = item.get("submitted")
            details = []
            if course_name:
                details.append(course_name)
            if deadline:
                details.append(f"截止：{deadline}")
            if isinstance(submitted, bool):
                details.append("已提交" if submitted else "未提交")
            line = f"- {homework_title}"
            if details:
                line += f"（{'，'.join(details)}）"
            detail_url = str(item.get("detail_url", "")).strip()
            if detail_url:
                line += f"：{detail_url}"
            lines.append(line)
        return "\n".join(lines)

    if skill_name == "preview_homework_attachments":
        attachments = data.get("attachments", [])
        if not isinstance(attachments, list):
            attachments = []
        lines = ["### 作业附件", f"找到 {len(attachments)} 个附件。"]
        for item in attachments[:8]:
            if not isinstance(item, dict):
                continue
            file_name = str(item.get("file_name", "未命名附件")).strip()
            url = str(item.get("preview_url") or item.get("download_url") or "").strip()
            line = f"- {file_name}"
            if url:
                line += f"：{url}"
            lines.append(line)
        return "\n".join(lines)

    if skill_name in {"upload_homework_attachment", "submit_homework", "get_homework_cookie"}:
        status = str(data.get("status", "")).strip()
        message = str(data.get("message", "")).strip()
        if skill_name == "get_homework_cookie":
            if status == "cookie_ready":
                return "### 网络学堂登录态\nLearn Cookie 已加载，后续作业操作可以继续使用。"
            return f"### 网络学堂登录态\n{message or status or 'Cookie 未配置。'}"
        if skill_name == "upload_homework_attachment":
            file_name = str(data.get("file_name", "")).strip()
            return "### 作业附件上传\n" + (f"附件 {file_name} 已上传。" if status == "uploaded" else (message or status))
        if skill_name == "submit_homework":
            return "### 作业提交\n" + ("作业已提交。" if status == "submitted" else (message or status))

    if skill_name.startswith("get_"):
        message = str(data.get("message", "")).strip()
        warnings = data.get("warnings", [])
        lines = [f"### {skill_name}"]
        if message:
            lines.append(message)
        if isinstance(warnings, list):
            lines.extend(f"- {str(item)}" for item in warnings[:3] if str(item).strip())
        return "\n".join(lines) if len(lines) > 1 else ""

    return ""


def _final_content_from_task(task_doc: dict[str, Any]) -> str:
    final_summary = str(task_doc.get("final_summary_text", "")).strip()
    if final_summary:
        return final_summary
    approved = task_doc.get("approved_skills", [])
    blocked = task_doc.get("blocked_skills", [])
    approved_count = len(approved) if isinstance(approved, list) else 0
    blocked_count = len(blocked) if isinstance(blocked, list) else 0
    for skill in approved if isinstance(approved, list) else []:
        if not isinstance(skill, dict) or str(skill.get("skill_name", "")) != "show_summary":
            continue
        args = skill.get("args", {})
        if not isinstance(args, dict):
            continue
        content = str(args.get("content", "")).strip()
        if content:
            return content
    result_summary = build_task_result_summary(task_doc)
    if result_summary:
        return result_summary
    if blocked_count:
        return f"我已经整理好计划，其中 {approved_count} 个步骤可以继续处理，{blocked_count} 个步骤需要你确认。"
    if approved_count:
        return "我已经完成可在服务端处理的部分，剩余需要手机端执行的动作会继续处理。"
    return "我没有找到需要执行的步骤。你可以补充一下目标，我再继续。"


def _planned_skill_items(task_doc: dict[str, Any]) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    for key in ("approved_skills", "blocked_skills"):
        values = task_doc.get(key, [])
        for item in values if isinstance(values, list) else []:
            if isinstance(item, dict):
                items.append(item)
    if items:
        return items
    plan_only = task_doc.get("plan_only_response", {})
    data = plan_only.get("data", {}) if isinstance(plan_only, dict) else {}
    skill_plan = data.get("skill_plan", []) if isinstance(data, dict) else []
    return [item for item in skill_plan if isinstance(item, dict)]


def _plan_preview_text(task_doc: dict[str, Any]) -> str:
    items = _planned_skill_items(task_doc)
    if not items:
        return "我没有拆出可执行步骤，会直接给你说明。"
    lines = ["计划如下："]
    for idx, item in enumerate(items[:8], start=1):
        skill_name = str(item.get("skill_name", "unknown_skill")).strip() or "unknown_skill"
        description = str(item.get("description", "")).strip()
        risk = str(item.get("risk_level", "")).strip()
        status = str(item.get("status", "")).strip()
        suffix = " / ".join(part for part in (risk, status) if part)
        line = f"{idx}. {skill_name}"
        if description:
            line += f"：{description}"
        if suffix:
            line += f"（{suffix}）"
        lines.append(line)
    return "\n".join(lines)


def create_app(agent: OpenTHULangGraphAgent, store: AgentCoreStore) -> FastAPI:
    app = FastAPI(title="OpenTHU Agent Core Server", version="0.1.0")

    @app.get("/healthz")
    def healthz() -> dict[str, Any]:
        logger.debug("[healthz] health check requested")
        return {"status": "ok", "ts": utc_now()}

    @app.post("/api/v1/devices/register")
    def register_device(payload: DeviceRegisterRequest) -> dict[str, Any]:
        logger.info(
            "[api] POST /devices/register device_id=%s user_id=%s platform=%s",
            payload.device_id,
            payload.user_id,
            payload.platform,
        )
        device = store.register_device(payload)
        return {
            "code": "OK",
            "message": "Device registered",
            "data": device,
        }

    @app.post("/api/v1/agent/tasks/plan")
    def plan_task(payload: PlanTaskRequest) -> dict[str, Any]:
        logger.info(
            "[api] POST /agent/tasks/plan device_id=%s user_id=%s approve_sensitive=%s goal=%r",
            payload.device_id,
            payload.user_id,
            payload.approve_sensitive,
            payload.goal[:80],
        )
        device = store.get_device(payload.device_id)
        if device is None:
            logger.warning("[api] plan rejected: device_id=%s not registered", payload.device_id)
            raise HTTPException(status_code=404, detail="device_not_registered")

        plan_response = agent.run_plan_only(
            user_input=payload.goal,
            user_id=payload.user_id,
            approve_sensitive=payload.approve_sensitive,
            session=payload.session,
            semester_id=payload.semester_id,
            history=[item.dict() for item in payload.history],
        )
        logger.debug(
            "[api] plan_only finished request_id=%s code=%s",
            plan_response.get("request_id", ""),
            plan_response.get("code", ""),
        )
        task_doc = store.create_planned_task(
            plan_response=plan_response,
            device_id=payload.device_id,
            user_id=payload.user_id,
            goal=payload.goal,
        )
        task_doc = execute_server_side_data_skills(
            agent=agent,
            store=store,
            task_doc=task_doc,
            session=plan_response.get("data", {}).get("session", {})
            if isinstance(plan_response.get("data"), dict)
            else {},
        )
        task_doc = hydrate_show_summary_skills(store=store, task_doc=task_doc)
        logger.info(
            "[api] plan complete task_id=%s task_status=%s approved=%d blocked=%d",
            task_doc["task_id"],
            task_doc["status"],
            len(task_doc.get("approved_skills", [])),
            len(task_doc.get("blocked_skills", [])),
        )
        return {
            "code": "OK",
            "message": "Task planned on server",
            "data": {
                "task_id": task_doc["task_id"],
                "task_status": task_doc["status"],
                "approved_skill_count": len(task_doc.get("approved_skills", [])),
                "blocked_skill_count": len(task_doc.get("blocked_skills", [])),
                "plan_only_response": task_doc.get("plan_only_response", {}),
            },
        }

    @app.post("/api/v1/agent/chat")
    def chat_turn(payload: ChatTurnRequest) -> dict[str, Any]:
        logger.info(
            "[api] POST /agent/chat device_id=%s user_id=%s message=%r",
            payload.device_id,
            payload.user_id,
            payload.message[:80],
        )
        if payload.device_id:
            device = store.get_device(payload.device_id)
            if device is None:
                logger.warning("[api] chat rejected: device_id=%s not registered", payload.device_id)
                raise HTTPException(status_code=404, detail="device_not_registered")

        response = agent.chat_turn(
            user_input=payload.message,
            user_id=payload.user_id,
            session=payload.session,
            history=[item.dict() for item in payload.history],
        )
        logger.info(
            "[api] chat complete request_id=%s should_plan=%s source=%s",
            response.get("request_id", ""),
            response.get("data", {}).get("should_plan", False),
            response.get("data", {}).get("source", ""),
        )
        return response

    @app.post("/api/v1/agent/runs/stream")
    def stream_agent_run(payload: AgentRunStreamRequest) -> StreamingResponse:
        logger.info(
            "[api] POST /agent/runs/stream device_id=%s user_id=%s message=%r",
            payload.device_id,
            payload.user_id,
            payload.message[:80],
        )
        device = store.get_device(payload.device_id)
        if device is None:
            logger.warning("[api] stream rejected: device_id=%s not registered", payload.device_id)
            raise HTTPException(status_code=404, detail="device_not_registered")

        def event_stream():
            try:
                yield encode_ndjson(agent_event("assistant_delta", content="我先理解你的意思。"))
                decision_response = agent.decide_turn(
                    user_input=payload.message,
                    user_id=payload.user_id,
                    approve_sensitive=payload.approve_sensitive,
                    session=payload.session,
                    history=[item.dict() for item in payload.history],
                    semester_id=payload.semester_id,
                )
                chat_data = decision_response.get("data", {}) if isinstance(decision_response.get("data"), dict) else {}
                reply = str(chat_data.get("reply", "")).strip()
                should_plan = bool(chat_data.get("should_plan", False))
                if reply:
                    yield encode_ndjson(agent_event("assistant_delta", content=reply))
                if not should_plan:
                    yield encode_ndjson(
                        agent_event(
                            "assistant_final",
                            content=reply or "我在。你可以继续说。",
                            status="completed",
                            data={"mode": "chat", "source": chat_data.get("source", "")},
                        )
                    )
                    return

                plan_response = chat_data.get("plan_response", {})
                if not isinstance(plan_response, dict) or not isinstance(plan_response.get("data"), dict):
                    plan_response = agent.run_plan_only(
                        user_input=payload.message,
                        user_id=payload.user_id,
                        approve_sensitive=payload.approve_sensitive,
                        session=payload.session,
                        semester_id=payload.semester_id,
                        history=[item.dict() for item in payload.history],
                    )
                task_doc = store.create_planned_task(
                    plan_response=plan_response,
                    device_id=payload.device_id,
                    user_id=payload.user_id,
                    goal=payload.message,
                )
                task_id = str(task_doc.get("task_id", ""))
                yield encode_ndjson(agent_event("assistant_delta", content=_plan_preview_text(task_doc)))
                for planned_skill in _planned_skill_items(task_doc):
                    skill_name = str(planned_skill.get("skill_name", ""))
                    if not skill_name:
                        continue
                    yield encode_ndjson(
                        agent_event(
                            "tool_call",
                            title=f"计划 {skill_name}",
                            content=str(planned_skill.get("description", "")).strip(),
                            task_id=task_id,
                            request_id=str(planned_skill.get("request_id", "")),
                            skill_name=skill_name,
                            status="planned",
                            data={
                                "risk_level": planned_skill.get("risk_level", ""),
                                "description": planned_skill.get("description", ""),
                            },
                        )
                    )
                task_doc = store.suppress_show_summary_for_stream(task_id=str(task_doc.get("task_id", "")))
                task_doc = execute_server_side_data_skills(
                    agent=agent,
                    store=store,
                    task_doc=task_doc,
                    session=plan_response.get("data", {}).get("session", {})
                    if isinstance(plan_response.get("data"), dict)
                    else {},
                )
                task_doc = hydrate_show_summary_skills(store=store, task_doc=task_doc)

                task_id = str(task_doc.get("task_id", ""))
                server_results = task_doc.get("server_results", [])
                completed_request_ids = {
                    str(item)
                    for item in task_doc.get("completed_request_ids", [])
                }
                for result in server_results if isinstance(server_results, list) else []:
                    if not isinstance(result, dict):
                        continue
                    skill_name = str(result.get("skill_name", ""))
                    request_id = str(result.get("request_id", ""))
                    result_ok = result.get("code") == "OK"
                    yield encode_ndjson(
                        agent_event(
                            "tool_call",
                            title=f"调用 {skill_name}",
                            task_id=task_id,
                            request_id=request_id,
                            skill_name=skill_name,
                            status="running",
                        )
                    )
                    yield encode_ndjson(
                        agent_event(
                            "tool_result",
                            title=f"{skill_name} 已完成" if result_ok else f"{skill_name} 未完成",
                            content=str(result.get("message", "")).strip(),
                            task_id=task_id,
                            request_id=request_id,
                            skill_name=skill_name,
                            status="ok" if result_ok else "failed",
                            data={"message": result.get("message", ""), "source": result.get("source", "")},
                        )
                    )

                approved_skills = task_doc.get("approved_skills", [])
                queued_runtime_ids = set(pending_runtime_request_ids(task_doc))
                for skill in approved_skills if isinstance(approved_skills, list) else []:
                    if not isinstance(skill, dict):
                        continue
                    request_id = str(skill.get("request_id", ""))
                    if request_id in completed_request_ids or request_id not in queued_runtime_ids:
                        continue
                    skill_name = str(skill.get("skill_name", ""))
                    yield encode_ndjson(
                        agent_event(
                            "tool_call",
                            title=f"等待端侧执行 {skill_name}",
                            content=str(skill.get("description", "")).strip(),
                            task_id=task_id,
                            request_id=request_id,
                            skill_name=skill_name,
                            status="queued",
                        )
                    )

                blocked_skills = task_doc.get("blocked_skills", [])
                for skill in blocked_skills if isinstance(blocked_skills, list) else []:
                    if not isinstance(skill, dict):
                        continue
                    skill_name = str(skill.get("skill_name", ""))
                    yield encode_ndjson(
                        agent_event(
                            "confirmation_required",
                            title=f"是否允许执行 {skill_name}？",
                            content=str(skill.get("description", "")).strip(),
                            task_id=task_id,
                            request_id=str(skill.get("request_id", "")),
                            skill_name=skill_name,
                            status="pending",
                            options=[
                                {"label": "允许", "value": "approve"},
                                {"label": "拒绝", "value": "reject"},
                            ],
                            data={"risk_level": skill.get("risk_level", ""), "description": skill.get("description", "")},
                        )
                    )

                pending_ids = pending_runtime_request_ids(task_doc)
                if pending_ids:
                    yield encode_ndjson(
                        agent_event(
                            "assistant_delta",
                            content="我已经把需要手机端执行的步骤发过去了，会等结果回来后再给你最终总结。",
                        )
                    )
                    wait_started = time.monotonic()
                    while pending_ids and time.monotonic() - wait_started < DEVICE_RESULT_WAIT_TIMEOUT_SECONDS:
                        last_updated_at = str(task_doc.get("updated_at", ""))
                        waited_task = store.wait_for_task_update(
                            task_id=task_id,
                            last_updated_at=last_updated_at,
                            timeout_seconds=DEVICE_RESULT_WAIT_HEARTBEAT_SECONDS,
                        )
                        if waited_task is None:
                            break
                        task_doc = waited_task
                        pending_ids = pending_runtime_request_ids(task_doc)
                        if pending_ids:
                            yield encode_ndjson(
                                agent_event(
                                    "tool_call",
                                    title="等待手机端执行结果",
                                    content=f"还在等待 {len(pending_ids)} 个端侧步骤完成。",
                                    task_id=task_id,
                                    status="waiting",
                                )
                            )

                    task_doc = hydrate_show_summary_skills(store=store, task_doc=task_doc)

                    device_results = task_doc.get("device_results", [])
                    for result in device_results if isinstance(device_results, list) else []:
                        if not isinstance(result, dict):
                            continue
                        request_id = str(result.get("request_id", ""))
                        skill_name = str(result.get("skill_name", ""))
                        if skill_name == "show_summary":
                            continue
                        result_ok = result.get("code") == "OK"
                        yield encode_ndjson(
                            agent_event(
                                "tool_result",
                                title=f"{skill_name} 已完成" if result_ok else f"{skill_name} 未完成",
                                content=str(result.get("message", "")).strip(),
                                task_id=task_id,
                                request_id=request_id,
                                skill_name=skill_name,
                                status="ok" if result_ok else "failed",
                                data={"message": result.get("message", ""), "source": result.get("source", "")},
                            )
                        )

                final_content = _final_content_from_task(task_doc)
                still_pending = pending_runtime_request_ids(task_doc)
                if still_pending:
                    final_content = (
                        f"{final_content}\n\n"
                        f"手机端还有 {len(still_pending)} 个步骤暂时没有返回结果，我会在它们完成后继续更新。"
                    )
                else:
                    summary_session = (
                        plan_response.get("data", {}).get("session", {})
                        if isinstance(plan_response.get("data"), dict)
                        else {}
                    )
                    if not isinstance(summary_session, dict):
                        summary_session = payload.session
                    final_content = agent.synthesize_summary_from_results(
                        user_input=payload.message,
                        session=summary_session,
                        task_doc=task_doc,
                        fallback_summary=final_content,
                        conversation_context=plan_response.get("data", {}).get("conversation_context", {})
                        if isinstance(plan_response.get("data"), dict)
                        else {},
                    )
                    task_doc = store.set_final_summary(task_id=task_id, summary=final_content)
                yield encode_ndjson(
                    agent_event(
                        "assistant_final",
                        content=final_content,
                        task_id=task_id,
                        status=str(task_doc.get("status", "planned")),
                        data={
                            "mode": "task",
                            "approved_count": len(approved_skills) if isinstance(approved_skills, list) else 0,
                            "blocked_count": len(blocked_skills) if isinstance(blocked_skills, list) else 0,
                        },
                    )
                )
            except Exception as exc:
                logger.exception("[api] stream failed")
                yield encode_ndjson(
                    agent_event(
                        "error",
                        title="Agent 执行失败",
                        content=str(exc),
                        status="failed",
                    )
                )

        return StreamingResponse(event_stream(), media_type="application/x-ndjson")

    @app.get("/api/v1/agent/tasks/next")
    def get_next_task(device_id: str = Query(..., min_length=1)) -> dict[str, Any]:
        logger.debug("[api] GET /agent/tasks/next device_id=%s", device_id)
        device = store.get_device(device_id)
        if device is None:
            logger.warning("[api] next_task rejected: device_id=%s not registered", device_id)
            raise HTTPException(status_code=404, detail="device_not_registered")

        next_item = store.pop_next_dispatch(device_id=device_id)
        if next_item is None:
            logger.debug("[api] no pending task for device_id=%s", device_id)
            return {
                "code": "NO_TASK",
                "message": "No pending approved skills for this device",
                "data": {"device_id": device_id},
            }
        logger.info(
            "[api] dispatching task_id=%s request_id=%s skill_name=%s to device_id=%s",
            next_item.get("task_id", ""),
            next_item.get("request_id", ""),
            next_item.get("skill_invocation", {}).get("skill_name", "unknown"),
            device_id,
        )
        return {
            "code": "OK",
            "message": "Task dispatched to device",
            "data": next_item,
        }

    @app.post("/api/v1/agent/tasks/{task_id}/result")
    def submit_result(task_id: str, payload: SkillResultSubmitRequest) -> dict[str, Any]:
        logger.info(
            "[api] POST /agent/tasks/%s/result device_id=%s request_id=%s skill_name=%s code=%s",
            task_id,
            payload.device_id,
            payload.request_id,
            payload.skill_name,
            payload.code,
        )
        try:
            task_doc = store.submit_device_result(task_id=task_id, payload=payload)
            task_doc = hydrate_show_summary_skills(store=store, task_doc=task_doc)
        except KeyError:
            logger.warning("[api] submit_result failed: task_id=%s not found", task_id)
            raise HTTPException(status_code=404, detail="task_not_found")
        except PermissionError:
            logger.warning(
                "[api] submit_result rejected: task_id=%s device_id=%s mismatch",
                task_id,
                payload.device_id,
            )
            raise HTTPException(status_code=403, detail="task_device_mismatch")
        except ValueError as exc:
            logger.warning("[api] submit_result invalid: task_id=%s error=%s", task_id, exc)
            raise HTTPException(status_code=400, detail=str(exc))

        logger.info(
            "[api] result accepted task_id=%s skill_name=%s code=%s task_status=%s received=%d | message=%s",
            task_doc.get("task_id", ""),
            payload.skill_name,
            payload.code,
            task_doc.get("status", ""),
            len(task_doc.get("device_results", [])),
            payload.message or "",
        )
        if payload.data:
            logger.info("[api] result data task_id=%s %s", task_doc.get("task_id", ""), payload.data)
        return {
            "code": "OK",
            "message": "Result accepted",
            "data": {
                "task_id": task_doc.get("task_id", ""),
                "task_status": task_doc.get("status", ""),
                "received_result_count": len(task_doc.get("device_results", [])),
            },
        }

    @app.post("/api/v1/agent/tasks/{task_id}/decision")
    def submit_decision(task_id: str, payload: SkillDecisionRequest) -> dict[str, Any]:
        logger.info(
            "[api] POST /agent/tasks/%s/decision device_id=%s request_id=%s decision=%s",
            task_id,
            payload.device_id,
            payload.request_id,
            payload.decision,
        )
        try:
            task_doc = store.apply_skill_decision(task_id=task_id, payload=payload)
        except KeyError:
            logger.warning("[api] submit_decision failed: task_id=%s not found", task_id)
            raise HTTPException(status_code=404, detail="task_not_found")
        except PermissionError:
            logger.warning(
                "[api] submit_decision rejected: task_id=%s device_id=%s mismatch",
                task_id,
                payload.device_id,
            )
            raise HTTPException(status_code=403, detail="task_device_mismatch")
        except ValueError as exc:
            logger.warning("[api] submit_decision invalid: task_id=%s error=%s", task_id, exc)
            raise HTTPException(status_code=400, detail=str(exc))

        return {
            "code": "OK",
            "message": "Decision accepted",
            "data": {
                "task_id": task_doc.get("task_id", ""),
                "task_status": task_doc.get("status", ""),
                "request_id": payload.request_id,
                "decision": "approved" if payload.decision.strip().lower() in {"approve", "approved", "allow", "allowed"} else "rejected",
                "approved_skill_count": len(task_doc.get("approved_skills", [])),
                "blocked_skill_count": len(task_doc.get("blocked_skills", [])),
            },
        }

    @app.get("/api/v1/agent/tasks/{task_id}")
    def get_task(task_id: str) -> dict[str, Any]:
        logger.debug("[api] GET /agent/tasks/%s", task_id)
        task_doc = store.get_task(task_id)
        if task_doc is None:
            logger.warning("[api] get_task: task_id=%s not found", task_id)
            raise HTTPException(status_code=404, detail="task_not_found")
        logger.debug(
            "[api] get_task task_id=%s status=%s device_id=%s",
            task_id,
            task_doc.get("status", ""),
            task_doc.get("device_id", ""),
        )
        return {
            "code": "OK",
            "message": "Task fetched",
            "data": task_doc,
        }

    return app


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="OpenTHU Agent Core server")
    parser.add_argument("--host", default="0.0.0.0", help="HTTP bind host")
    parser.add_argument("--port", type=int, default=18789, help="HTTP bind port")
    parser.add_argument(
        "--memory-file",
        default="agent/langgraph/memory_store.json",
        help="Path for agent memory JSON",
    )
    parser.add_argument(
        "--store-file",
        default="agent/langgraph/agent_core_store.json",
        help="Path for server device/task state JSON",
    )
    parser.add_argument(
        "--llm-model",
        default="gpt-4.1-mini",
        help="LLM model for server planning",
    )
    parser.add_argument(
        "--llm-base-url",
        default="",
        help="Optional OpenAI-compatible base URL",
    )
    parser.add_argument(
        "--log-dir",
        default="log",
        help="Directory to write rotating log files (default: log/)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    # --- File logging setup ---
    log_dir = Path(args.log_dir)
    log_dir.mkdir(parents=True, exist_ok=True)
    log_file = log_dir / "agent_core_server.log"
    file_handler = logging.handlers.RotatingFileHandler(
        log_file,
        maxBytes=10 * 1024 * 1024,  # 10 MB per file
        backupCount=5,
        encoding="utf-8",
    )
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(
        logging.Formatter(
            "%(asctime)s [%(levelname)s] %(name)s - %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S",
        )
    )
    logging.getLogger().addHandler(file_handler)
    # --- End file logging setup ---

    logger.info(
        "[startup] OpenTHU Agent Core Server starting on %s:%d",
        args.host,
        args.port,
    )
    logger.info("[startup] llm_model=%s llm_base_url=%r", args.llm_model, args.llm_base_url or "(default)")
    logger.info("[startup] store_file=%s memory_file=%s", args.store_file, args.memory_file)
    logger.info("[startup] log_dir=%s log_file=%s", log_dir.resolve(), log_file.resolve())
    agent = OpenTHULangGraphAgent(
        memory_file=Path(args.memory_file),
        llm_model=args.llm_model,
        llm_base_url=args.llm_base_url,
    )
    store = AgentCoreStore(store_file=Path(args.store_file))
    app = create_app(agent=agent, store=store)
    logger.info("[startup] server ready")
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
