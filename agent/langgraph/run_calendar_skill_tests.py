from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

try:
    from .calendar_handlers import (
        CreateCalendarEventHandler,
        DeleteCalendarEventHandler,
        DetectCalendarConflictsHandler,
        KotlinSkillBridge,
    )
    from .skill_core import SkillInvocation, build_default_registry
    from .skill_manager import SkillManager
except ImportError:
    from calendar_handlers import (
        CreateCalendarEventHandler,
        DeleteCalendarEventHandler,
        DetectCalendarConflictsHandler,
        KotlinSkillBridge,
    )
    from skill_core import SkillInvocation, build_default_registry
    from skill_manager import SkillManager


UTC = timezone.utc
BASE_TIME = datetime(2030, 1, 1, 8, 0, tzinfo=UTC)


def iso_at(hours_offset: int, duration_hours: int = 1) -> tuple[str, str]:
    start = BASE_TIME + timedelta(hours=hours_offset)
    end = start + timedelta(hours=duration_hours)
    return start.isoformat(), end.isoformat()


@dataclass
class TestResult:
    name: str
    passed: bool
    detail: str


class MockKotlinBridge(KotlinSkillBridge):
    """Mock Kotlin executor bridge with in-memory calendar state."""

    def __init__(self) -> None:
        self._next_event_id = 1000
        self._events: dict[int, dict[str, Any]] = {}
        self.last_invocation: dict[str, Any] | None = None

    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        self.last_invocation = invocation
        skill_name = str(invocation.get("skill_name", "")).strip()
        args = invocation.get("args", {})
        if not isinstance(args, dict):
            return self._fail(invocation, "INVALID_PARAM", "args must be object")

        if skill_name == "create_calendar_event":
            return self._create(invocation, args)
        if skill_name == "detect_calendar_conflicts":
            return self._detect(invocation, args)
        if skill_name == "delete_calendar_event":
            return self._delete(invocation, args)
        return self._fail(invocation, "SKILL_EXECUTION_FAILED", f"unsupported skill in mock bridge: {skill_name}")

    def _create(self, invocation: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
        title = str(args.get("title", "")).strip()
        start_time = str(args.get("start_time", "")).strip()
        end_time = str(args.get("end_time", "")).strip()
        start_ms = int(datetime.fromisoformat(start_time).timestamp() * 1000)
        end_ms = int(datetime.fromisoformat(end_time).timestamp() * 1000)
        conflict_decision = str(args.get("conflict_decision", "prompt_user")).strip().lower() or "prompt_user"
        conflicts = self._query_conflicts(start_ms, end_ms)

        if conflicts:
            if conflict_decision == "prompt_user":
                return {
                    "request_id": invocation["request_id"],
                    "code": "APPROVAL_REQUIRED",
                    "source": "android_kotlin_bridge_mock",
                    "data": {
                        "status": "conflict_detected",
                        "supports_overlap": True,
                        "conflict_count": len(conflicts),
                        "conflicts": conflicts,
                        "decision_options": ["skip_write", "coexist", "delete_conflicts"],
                    },
                }
            if conflict_decision == "skip_write":
                return {
                    "request_id": invocation["request_id"],
                    "code": "OK",
                    "source": "android_kotlin_bridge_mock",
                    "data": {
                        "status": "skipped_conflict",
                        "conflict_count": len(conflicts),
                        "conflicts": conflicts,
                    },
                }
            if conflict_decision == "delete_conflicts":
                allow_delete = bool(args.get("allow_conflict_delete"))
                if not allow_delete:
                    return self._fail(
                        invocation,
                        "ACTION_NOT_ALLOWED",
                        "allow_conflict_delete=true is required for delete_conflicts",
                    )
                conflict_ids = [int(item["event_id"]) for item in conflicts]
                self._delete_ids(conflict_ids)

        event_id = self._next_event_id
        self._next_event_id += 1
        self._events[event_id] = {
            "title": title,
            "start_ms": start_ms,
            "end_ms": end_ms,
        }
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_bridge_mock",
            "data": {
                "status": "created",
                "event_id": str(event_id),
                "conflict_count": len(conflicts),
            },
        }

    def _detect(self, invocation: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
        start_time = str(args.get("start_time", "")).strip()
        end_time = str(args.get("end_time", "")).strip()
        start_ms = int(datetime.fromisoformat(start_time).timestamp() * 1000)
        end_ms = int(datetime.fromisoformat(end_time).timestamp() * 1000)
        conflicts = self._query_conflicts(start_ms, end_ms)
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_bridge_mock",
            "data": {
                "status": "detected",
                "supports_overlap": True,
                "conflict_count": len(conflicts),
                "conflicts": conflicts,
                "decision_options": ["skip_write", "coexist", "delete_conflicts"],
            },
        }

    def _delete(self, invocation: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
        if not bool(args.get("confirm_delete")):
            return {
                "request_id": invocation["request_id"],
                "code": "APPROVAL_REQUIRED",
                "source": "android_kotlin_bridge_mock",
                "data": {
                    "status": "awaiting_confirmation",
                    "high_risk": True,
                    "message": "confirm_delete=true is required for delete_calendar_event",
                },
            }
        raw_ids = args.get("event_ids", [])
        ids: list[int] = []
        if isinstance(raw_ids, list):
            ids.extend(int(item) for item in raw_ids if str(item).strip())
        if not ids and str(args.get("event_id", "")).strip():
            ids.append(int(str(args["event_id"]).strip()))
        if not ids:
            return self._fail(invocation, "INVALID_PARAM", "event_id/event_ids are required")

        existing = []
        for item in ids:
            event = self._events.get(item)
            if not event:
                continue
            existing.append(
                {
                    "event_id": str(item),
                    "title": event["title"],
                    "start_time": datetime.fromtimestamp(event["start_ms"] / 1000, tz=UTC).isoformat(),
                    "end_time": datetime.fromtimestamp(event["end_ms"] / 1000, tz=UTC).isoformat(),
                }
            )
        deleted_count = self._delete_ids(ids)
        existing_ids = {int(item["event_id"]) for item in existing}
        missing = [str(item) for item in ids if item not in existing_ids]
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_bridge_mock",
            "data": {
                "status": "deleted",
                "deleted_count": deleted_count,
                "deleted": existing,
                "missing_event_ids": missing,
                "high_risk": True,
            },
        }

    def _query_conflicts(self, start_ms: int, end_ms: int) -> list[dict[str, Any]]:
        conflicts: list[dict[str, Any]] = []
        for event_id, event in sorted(self._events.items()):
            existing_start = int(event["start_ms"])
            existing_end = int(event["end_ms"])
            if existing_start < end_ms and start_ms < existing_end:
                conflicts.append(
                    {
                        "event_id": str(event_id),
                        "title": str(event["title"]),
                        "start_time": datetime.fromtimestamp(existing_start / 1000, tz=UTC).isoformat(),
                        "end_time": datetime.fromtimestamp(existing_end / 1000, tz=UTC).isoformat(),
                    }
                )
        return conflicts

    def _delete_ids(self, ids: list[int]) -> int:
        deleted = 0
        for item in ids:
            if item in self._events:
                del self._events[item]
                deleted += 1
        return deleted

    def _fail(self, invocation: dict[str, Any], code: str, message: str) -> dict[str, Any]:
        return {
            "request_id": invocation["request_id"],
            "code": code,
            "source": "android_kotlin_bridge_mock",
            "data": {
                "status": "failed",
                "message": message,
            },
        }


class CalendarSkillHarness:
    def __init__(self, *, bridge: KotlinSkillBridge) -> None:
        self.registry = build_default_registry()
        self.manager = SkillManager(registry=self.registry)
        self.bridge = bridge
        self.registry.register_handler("create_calendar_event", CreateCalendarEventHandler(bridge))
        self.registry.register_handler("detect_calendar_conflicts", DetectCalendarConflictsHandler(bridge))
        self.registry.register_handler("delete_calendar_event", DeleteCalendarEventHandler(bridge))
        self._seq = 0

    def invoke(self, skill_name: str, args: dict[str, Any]) -> dict[str, Any]:
        self._seq += 1
        spec = self.registry.get_spec(skill_name)
        if spec is None:
            raise AssertionError(f"Unknown skill in registry: {skill_name}")
        invocation = SkillInvocation(
            skill_name=skill_name,
            request_id=f"req_test_{self._seq}",
            task_id="task_calendar_skill_test",
            args=args,
            risk_level=spec.risk_level,
            requires_approval=spec.requires_approval,
            description=f"test-{skill_name}",
        )
        return self.manager.execute(invocation, {}, {})


def _expect(condition: bool, detail: str) -> None:
    if not condition:
        raise AssertionError(detail)


def _test_registry_binding() -> None:
    registry = build_default_registry()
    for skill_name in [
        "create_calendar_event",
        "detect_calendar_conflicts",
        "delete_calendar_event",
    ]:
        handler = registry.get_handler(skill_name)
        _expect(handler.__class__.__name__ != "MissingSkillHandler", f"{skill_name} still uses MissingSkillHandler")


def _test_create_missing_required(harness: CalendarSkillHarness) -> None:
    result = harness.invoke(
        "create_calendar_event",
        {
            "title": "No start/end",
        },
    )
    _expect(result["code"] == "INVALID_PARAM", f"unexpected code: {result['code']}")


def _test_schema_rejects_unknown_field(harness: CalendarSkillHarness) -> None:
    start_time, end_time = iso_at(0)
    result = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_SCHEMA_UNKNOWN",
            "start_time": start_time,
            "end_time": end_time,
            "unexpected_field": "should fail by schema",
        },
    )
    _expect(result["code"] == "INVALID_PARAM", f"unknown args should fail schema validation: {result}")
    errors = result.get("data", {}).get("errors", [])
    _expect(any("unknown field `unexpected_field`" in str(item) for item in errors), f"unexpected errors: {errors}")


def _test_bridge_receives_invocation(harness: CalendarSkillHarness, bridge: MockKotlinBridge) -> None:
    start_time, end_time = iso_at(0)
    result = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_CREATE",
            "description": "first event",
            "start_time": start_time,
            "end_time": end_time,
            "conflict_decision": "coexist",
        },
    )
    _expect(result["code"] == "OK", f"create failed: {result}")
    _expect(bridge.last_invocation is not None, "bridge should receive invocation payload")
    _expect(
        bridge.last_invocation.get("skill_name") == "create_calendar_event",
        f"unexpected skill dispatched: {bridge.last_invocation}",
    )


def _test_create_and_detect(harness: CalendarSkillHarness) -> None:
    start_time, end_time = iso_at(1)
    created = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_CREATE_2",
            "description": "first event",
            "start_time": start_time,
            "end_time": end_time,
            "conflict_decision": "coexist",
        },
    )
    _expect(created["code"] == "OK", f"create failed: {created}")
    _expect(created["data"]["status"] == "created", f"create status mismatch: {created['data']}")

    conflicts = harness.invoke(
        "detect_calendar_conflicts",
        {
            "start_time": start_time,
            "end_time": end_time,
        },
    )
    _expect(conflicts["code"] == "OK", f"detect failed: {conflicts}")
    _expect(conflicts["data"]["conflict_count"] >= 1, "should detect at least one conflict")


def _test_conflict_prompt_skip_delete(harness: CalendarSkillHarness) -> None:
    start_time, end_time = iso_at(1)
    prompt = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_PROMPT",
            "start_time": start_time,
            "end_time": end_time,
            "conflict_decision": "prompt_user",
        },
    )
    _expect(prompt["code"] == "APPROVAL_REQUIRED", f"prompt code mismatch: {prompt}")

    skipped = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_SKIP",
            "start_time": start_time,
            "end_time": end_time,
            "conflict_decision": "skip_write",
        },
    )
    _expect(skipped["code"] == "OK", f"skip code mismatch: {skipped}")
    _expect(skipped["data"]["status"] == "skipped_conflict", f"skip status mismatch: {skipped['data']}")

    blocked = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_DELETE_BLOCKED",
            "start_time": start_time,
            "end_time": end_time,
            "conflict_decision": "delete_conflicts",
        },
    )
    _expect(blocked["code"] == "ACTION_NOT_ALLOWED", f"delete_conflicts should be blocked: {blocked}")


def _test_conflict_delete_then_create(harness: CalendarSkillHarness) -> None:
    start_time, end_time = iso_at(1)
    result = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_DELETE_ALLOWED",
            "start_time": start_time,
            "end_time": end_time,
            "conflict_decision": "delete_conflicts",
            "allow_conflict_delete": True,
        },
    )
    _expect(result["code"] == "OK", f"delete_conflicts allowed should pass: {result}")
    _expect(result["data"]["status"] == "created", f"expected created: {result['data']}")


def _test_delete_requires_confirm(harness: CalendarSkillHarness) -> None:
    result = harness.invoke(
        "delete_calendar_event",
        {
            "event_id": "1000",
            "confirm_delete": False,
        },
    )
    _expect(result["code"] == "APPROVAL_REQUIRED", f"delete should require confirm: {result}")


def _test_delete_success(harness: CalendarSkillHarness) -> None:
    created = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_DELETE_TARGET",
            "start_time": iso_at(5)[0],
            "end_time": iso_at(5)[1],
            "conflict_decision": "coexist",
        },
    )
    _expect(created["code"] == "OK", f"create target failed: {created}")
    event_id = created["data"].get("event_id")
    result = harness.invoke(
        "delete_calendar_event",
        {
            "event_ids": [str(event_id), "999999"],
            "confirm_delete": True,
        },
    )
    _expect(result["code"] == "OK", f"delete failed: {result}")
    _expect(result["data"]["deleted_count"] >= 1, f"deleted_count mismatch: {result['data']}")
    _expect("999999" in result["data"]["missing_event_ids"], "missing ids should include non-existing id")


def _test_delete_schema_coercion(harness: CalendarSkillHarness) -> None:
    created = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_DELETE_COERCE",
            "start_time": iso_at(7)[0],
            "end_time": iso_at(7)[1],
            "conflict_decision": "coexist",
        },
    )
    _expect(created["code"] == "OK", f"create target failed: {created}")
    event_id = str(created["data"].get("event_id"))
    result = harness.invoke(
        "delete_calendar_event",
        {
            "event_ids": event_id,
            "confirm_delete": "true",
        },
    )
    _expect(result["code"] == "OK", f"schema coercion delete failed: {result}")
    _expect(result["data"]["deleted_count"] >= 1, f"deleted_count mismatch after coercion: {result['data']}")


def run_mock_suite() -> list[TestResult]:
    results: list[TestResult] = []
    bridge = MockKotlinBridge()
    harness = CalendarSkillHarness(bridge=bridge)
    tests: list[tuple[str, Callable[[], None]]] = [
        ("registry_binding", _test_registry_binding),
        ("create_missing_required", lambda: _test_create_missing_required(harness)),
        ("schema_rejects_unknown_field", lambda: _test_schema_rejects_unknown_field(harness)),
        ("bridge_receives_invocation", lambda: _test_bridge_receives_invocation(harness, bridge)),
        ("create_and_detect", lambda: _test_create_and_detect(harness)),
        ("conflict_prompt_skip_delete", lambda: _test_conflict_prompt_skip_delete(harness)),
        ("conflict_delete_then_create", lambda: _test_conflict_delete_then_create(harness)),
        ("delete_requires_confirm", lambda: _test_delete_requires_confirm(harness)),
        ("delete_success", lambda: _test_delete_success(harness)),
        ("delete_schema_coercion", lambda: _test_delete_schema_coercion(harness)),
    ]
    for name, fn in tests:
        try:
            fn()
            results.append(TestResult(name=name, passed=True, detail="ok"))
        except Exception as exc:
            results.append(TestResult(name=name, passed=False, detail=f"{type(exc).__name__}: {exc}"))
    return results


def print_report(results: list[TestResult]) -> int:
    passed = sum(1 for item in results if item.passed)
    total = len(results)
    print("=" * 72)
    print(f"Calendar Skill Test Report: {passed}/{total} passed")
    print("-" * 72)
    for item in results:
        status = "PASS" if item.passed else "FAIL"
        print(f"[{status}] {item.name}: {item.detail}")
    print("=" * 72)
    return 0 if passed == total else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Calendar skill bridge test runner")
    parser.add_argument(
        "--mode",
        choices=["mock"],
        default="mock",
        help="mock: validates calendar handlers via a mock Kotlin bridge",
    )
    return parser.parse_args()


def main() -> None:
    _ = parse_args()
    raise_code = print_report(run_mock_suite())
    raise SystemExit(raise_code)


if __name__ == "__main__":
    main()
