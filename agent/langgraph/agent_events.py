from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def agent_event(
    event_type: str,
    *,
    content: str = "",
    title: str = "",
    task_id: str = "",
    request_id: str = "",
    skill_name: str = "",
    status: str = "",
    options: list[dict[str, Any]] | None = None,
    data: dict[str, Any] | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "event_id": f"evt_{uuid4().hex[:12]}",
        "type": event_type,
        "created_at": utc_now(),
    }
    if content:
        payload["content"] = content
    if title:
        payload["title"] = title
    if task_id:
        payload["task_id"] = task_id
    if request_id:
        payload["request_id"] = request_id
    if skill_name:
        payload["skill_name"] = skill_name
    if status:
        payload["status"] = status
    if options:
        payload["options"] = options
    if data:
        payload["data"] = data
    return payload


def encode_ndjson(event: dict[str, Any]) -> str:
    return json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n"
