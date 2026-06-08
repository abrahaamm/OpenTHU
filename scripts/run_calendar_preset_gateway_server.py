#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import traceback
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def iso_after(minutes: int) -> str:
    return (datetime.now(timezone.utc) + timedelta(minutes=minutes)).replace(microsecond=0).isoformat()


@dataclass
class TaskDoc:
    task_id: str
    device_id: str
    goal: str
    title_keyword: str
    approved_skills: list[dict[str, Any]]
    blocked_skills: list[dict[str, Any]] = field(default_factory=list)
    status: str = "ready_for_device_execution"
    created_at: str = field(default_factory=utc_now)
    updated_at: str = field(default_factory=utc_now)
    in_flight_request_ids: set[str] = field(default_factory=set)
    completed_request_ids: set[str] = field(default_factory=set)
    device_results: list[dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "task_id": self.task_id,
            "device_id": self.device_id,
            "goal": self.goal,
            "status": self.status,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "approved_skills": self.approved_skills,
            "blocked_skills": self.blocked_skills,
            "in_flight_request_ids": sorted(self.in_flight_request_ids),
            "completed_request_ids": sorted(self.completed_request_ids),
            "device_results": self.device_results,
            "title_keyword": self.title_keyword,
        }


class PresetGatewayStore:
    def __init__(self) -> None:
        self.devices: dict[str, dict[str, Any]] = {}
        self.tasks: dict[str, TaskDoc] = {}
        self.seq = 0

    def register_device(self, payload: dict[str, Any]) -> dict[str, Any]:
        device_id = str(payload.get("device_id", "")).strip()
        if not device_id:
            raise ValueError("device_id is required")
        item = {
            "device_id": device_id,
            "user_id": str(payload.get("user_id", "debug_user")),
            "platform": str(payload.get("platform", "android")),
            "capabilities": payload.get("capabilities", []),
            "registered_at": utc_now(),
            "last_seen_at": utc_now(),
        }
        self.devices[device_id] = item
        return item

    def create_preset_plan(self, payload: dict[str, Any]) -> TaskDoc:
        device_id = str(payload.get("device_id", "")).strip()
        goal = str(payload.get("goal", "preset calendar flow")).strip()
        if not device_id:
            raise ValueError("device_id is required")
        if device_id not in self.devices:
            raise KeyError("device_not_registered")

        self.seq += 1
        task_id = f"task_preset_calendar_{self.seq}"
        title = f"OPENTHU_CAL_E2E_PRESET_{self.seq}"

        approved_skills = [
            {
                "skill_name": "create_calendar_event",
                "request_id": f"req_cal_create_{self.seq}",
                "task_id": task_id,
                "args": {
                    "title": title,
                    "description": "preset gateway e2e create",
                    "start_time": iso_after(10),
                    "end_time": iso_after(40),
                    "conflict_decision": "coexist",
                },
                "risk_level": "medium",
                "requires_approval": True,
                "description": "preset create calendar event",
                "status": "approved",
            },
            {
                "skill_name": "delete_calendar_event",
                "request_id": f"req_cal_delete_{self.seq}",
                "task_id": task_id,
                "args": {
                    "title_keyword": title,
                    "confirm_delete": True,
                },
                "risk_level": "high",
                "requires_approval": True,
                "description": "preset delete calendar event by title_keyword",
                "status": "approved",
            },
        ]

        doc = TaskDoc(
            task_id=task_id,
            device_id=device_id,
            goal=goal,
            title_keyword=title,
            approved_skills=approved_skills,
        )
        self.tasks[task_id] = doc
        return doc

    def pop_next(self, device_id: str) -> dict[str, Any] | None:
        for task in self.tasks.values():
            if task.device_id != device_id:
                continue
            if task.status not in {"ready_for_device_execution", "in_progress"}:
                continue
            for skill in task.approved_skills:
                request_id = str(skill.get("request_id", ""))
                if not request_id:
                    continue
                if request_id in task.completed_request_ids or request_id in task.in_flight_request_ids:
                    continue
                task.in_flight_request_ids.add(request_id)
                task.status = "in_progress"
                task.updated_at = utc_now()
                skill["status"] = "dispatched"
                return {
                    "task_id": task.task_id,
                    "request_id": request_id,
                    "device_id": device_id,
                    "dispatched_at": utc_now(),
                    "skill_invocation": skill,
                }
        return None

    def submit_result(self, task_id: str, payload: dict[str, Any]) -> TaskDoc:
        task = self.tasks.get(task_id)
        if task is None:
            raise KeyError("task_not_found")
        if str(payload.get("device_id", "")) != task.device_id:
            raise PermissionError("task_device_mismatch")

        request_id = str(payload.get("request_id", "")).strip()
        if not request_id:
            raise ValueError("request_id is required")
        approved_ids = {str(item.get("request_id", "")) for item in task.approved_skills}
        if request_id not in approved_ids:
            raise ValueError("request_id_not_in_approved_skills")

        if request_id in task.completed_request_ids:
            return task

        task.in_flight_request_ids.discard(request_id)
        task.completed_request_ids.add(request_id)
        code = str(payload.get("code", "SKILL_EXECUTION_FAILED")).strip() or "SKILL_EXECUTION_FAILED"
        message = str(payload.get("message", "")).strip()
        result_item = {
            "request_id": request_id,
            "skill_name": str(payload.get("skill_name", "")),
            "code": code,
            "success": code == "OK",
            "message": message,
            "data": payload.get("data", {}),
            "source": str(payload.get("source", "android_app")),
            "from_cache": bool(payload.get("from_cache", False)),
            "fetched_at": str(payload.get("fetched_at", utc_now())),
        }
        task.device_results.append(result_item)

        for skill in task.approved_skills:
            if str(skill.get("request_id", "")) == request_id:
                skill["status"] = "executed" if code == "OK" else "failed"
                break

        expected = len(task.approved_skills)
        received = len(task.completed_request_ids)
        if received < expected:
            task.status = "in_progress"
        else:
            task.status = "completed" if all(item.get("code") == "OK" for item in task.device_results) else "failed"
        task.updated_at = utc_now()
        return task

    def get_task(self, task_id: str) -> TaskDoc | None:
        return self.tasks.get(task_id)


class PresetGatewayHandler(BaseHTTPRequestHandler):
    store: PresetGatewayStore
    protocol_version = "HTTP/1.1"

    def _json_response(self, status: int, payload: dict[str, Any]) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)
        self.wfile.flush()

    def _log(self, message: str) -> None:
        print(f"[preset-gateway] {message}")

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        parsed = json.loads(raw) if raw.strip() else {}
        if not isinstance(parsed, dict):
            raise ValueError("JSON body must be an object")
        return parsed

    def do_GET(self) -> None:  # noqa: N802
        try:
            parsed = urlparse(self.path)
            if parsed.path == "/healthz":
                self._json_response(200, {"status": "ok", "ts": utc_now()})
                return

            if parsed.path == "/api/v1/agent/tasks/next":
                qs = parse_qs(parsed.query)
                device_id = (qs.get("device_id") or [""])[0].strip()
                if not device_id:
                    self._json_response(400, {"code": "INVALID_PARAM", "message": "device_id is required", "data": {}})
                    return
                item = self.store.pop_next(device_id=device_id)
                if item is None:
                    self._json_response(
                        200,
                        {
                            "code": "NO_TASK",
                            "message": "No pending approved skills for this device",
                            "data": {"device_id": device_id},
                        },
                    )
                    return
                self._log(f"dispatch next task_id={item.get('task_id')} request_id={item.get('request_id')}")
                self._json_response(200, {"code": "OK", "message": "Task dispatched to device", "data": item})
                return

            task_match = re.match(r"^/api/v1/agent/tasks/([^/]+)$", parsed.path)
            if task_match:
                task_id = task_match.group(1)
                task = self.store.get_task(task_id)
                if task is None:
                    self._json_response(404, {"detail": "task_not_found"})
                    return
                self._json_response(200, {"code": "OK", "message": "Task fetched", "data": task.to_dict()})
                return

            self._json_response(404, {"detail": "not_found"})
        except Exception as exc:  # noqa: BLE001
            self._log(f"GET {self.path} failed: {exc}")
            traceback.print_exc()
            self._json_response(500, {"code": "INTERNAL_ERROR", "message": str(exc), "data": {}})

    def do_POST(self) -> None:  # noqa: N802
        try:
            parsed = urlparse(self.path)

            if parsed.path == "/api/v1/devices/register":
                try:
                    payload = self._read_json()
                    device = self.store.register_device(payload)
                    self._log(f"register device_id={device.get('device_id')}")
                    self._json_response(200, {"code": "OK", "message": "Device registered", "data": device})
                except ValueError as exc:
                    self._json_response(400, {"code": "INVALID_PARAM", "message": str(exc), "data": {}})
                return

            if parsed.path == "/api/v1/agent/tasks/plan":
                try:
                    payload = self._read_json()
                    task = self.store.create_preset_plan(payload)
                    self._log(f"plan task_id={task.task_id} device_id={task.device_id} goal={task.goal!r}")
                    self._json_response(
                        200,
                        {
                            "code": "OK",
                            "message": "Task planned on preset gateway",
                            "data": {
                                "task_id": task.task_id,
                                "task_status": task.status,
                                "approved_skill_count": len(task.approved_skills),
                                "blocked_skill_count": len(task.blocked_skills),
                                "plan_only_response": {
                                    "request_id": f"req_plan_{task.task_id}",
                                    "code": "OK",
                                    "message": "Preset plan generated",
                                    "data": {
                                        "mode": "plan_only",
                                        "task_id": task.task_id,
                                        "task_status": task.status,
                                        "approved_skills": task.approved_skills,
                                        "blocked_skills": task.blocked_skills,
                                    },
                                },
                            },
                        },
                    )
                except KeyError:
                    self._json_response(404, {"detail": "device_not_registered"})
                except ValueError as exc:
                    self._json_response(400, {"code": "INVALID_PARAM", "message": str(exc), "data": {}})
                return

            result_match = re.match(r"^/api/v1/agent/tasks/([^/]+)/result$", parsed.path)
            if result_match:
                task_id = result_match.group(1)
                try:
                    payload = self._read_json()
                    task = self.store.submit_result(task_id=task_id, payload=payload)
                    self._log(
                        f"result task_id={task.task_id} status={task.status} received={len(task.device_results)}",
                    )
                    self._json_response(
                        200,
                        {
                            "code": "OK",
                            "message": "Result accepted",
                            "data": {
                                "task_id": task.task_id,
                                "task_status": task.status,
                                "received_result_count": len(task.device_results),
                            },
                        },
                    )
                except KeyError:
                    self._json_response(404, {"detail": "task_not_found"})
                except PermissionError:
                    self._json_response(403, {"detail": "task_device_mismatch"})
                except ValueError as exc:
                    self._json_response(400, {"detail": str(exc)})
                return

            self._json_response(404, {"detail": "not_found"})
        except Exception as exc:  # noqa: BLE001
            self._log(f"POST {self.path} failed: {exc}")
            traceback.print_exc()
            self._json_response(500, {"code": "INTERNAL_ERROR", "message": str(exc), "data": {}})

    def log_message(self, format: str, *args: Any) -> None:
        return


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Preset Agent-Core gateway for calendar e2e test")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host")
    parser.add_argument("--port", type=int, default=28791, help="Bind port")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    store = PresetGatewayStore()
    handler_cls = type("PresetGatewayHandlerImpl", (PresetGatewayHandler,), {"store": store})
    server = ThreadingHTTPServer((args.host, args.port), handler_cls)
    print(f"[preset-gateway] listening on {args.host}:{args.port}")
    print("[preset-gateway] flow = create_calendar_event -> delete_calendar_event(title_keyword)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        print("[preset-gateway] stopped")


if __name__ == "__main__":
    main()
