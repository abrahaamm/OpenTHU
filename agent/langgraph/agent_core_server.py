from __future__ import annotations

import argparse
import json
import logging
import logging.handlers
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field
import uvicorn

try:
    from .openthu_agent import OpenTHULangGraphAgent
except ImportError:
    from openthu_agent import OpenTHULangGraphAgent

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


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class DeviceRegisterRequest(BaseModel):
    device_id: str
    user_id: str = "demo_user"
    platform: str = "android"
    fcm_token: str | None = None
    app_version: str | None = None
    capabilities: list[str] = Field(default_factory=list)


class PlanTaskRequest(BaseModel):
    device_id: str
    user_id: str = "demo_user"
    goal: str
    approve_sensitive: bool = False
    semester_id: str = ""
    session: dict[str, Any] = Field(default_factory=dict)


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


class AgentCoreStore:
    def __init__(self, store_file: Path) -> None:
        self.store_file = store_file
        self._lock = threading.Lock()
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
                "device_results": [],
                "in_flight_request_ids": [],
                "completed_request_ids": [],
            }
            self._state["tasks"][task_id] = task_doc
            self._save_locked()
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

                    in_flight.add(request_id)
                    task_doc["in_flight_request_ids"] = sorted(in_flight)
                    task_doc["status"] = "in_progress"
                    task_doc["updated_at"] = utc_now()
                    self._mark_skill_status(task_doc, request_id, "dispatched")
                    self._save_locked()
                    skill_name = skill.get("skill_name", "unknown")
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

            expected = len([item for item in task_doc.get("approved_skills", []) if isinstance(item, dict)])
            received = len(task_doc["completed_request_ids"])
            if received < expected:
                task_doc["status"] = "in_progress"
            else:
                task_doc["status"] = "completed" if all(item.get("code") == "OK" for item in task_doc["device_results"]) else "failed"
            task_doc["updated_at"] = utc_now()
            self._save_locked()
            logger.info(
                "[result] task_id=%s updated status=%s completed=%d/%d",
                task_id,
                task_doc["status"],
                len(task_doc["completed_request_ids"]),
                len([item for item in task_doc.get("approved_skills", []) if isinstance(item, dict)]),
            )
            return dict(task_doc)


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
