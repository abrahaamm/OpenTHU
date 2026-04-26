from __future__ import annotations

import argparse
import os
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

try:
    from .calendar_handlers import (
        AdbCalendarBridge,
        CalendarBridgeError,
        CreateCalendarEventHandler,
        DeleteCalendarEventHandler,
        DetectCalendarConflictsHandler,
    )
    from .skill_core import SkillInvocation, build_default_registry
    from .skill_manager import SkillManager
except ImportError:
    from calendar_handlers import (
        AdbCalendarBridge,
        CalendarBridgeError,
        CreateCalendarEventHandler,
        DeleteCalendarEventHandler,
        DetectCalendarConflictsHandler,
    )
    from skill_core import SkillInvocation, build_default_registry
    from skill_manager import SkillManager


UTC = timezone.utc
BASE_TIME = datetime(2030, 1, 1, 8, 0, tzinfo=UTC)


def iso_at(hours_offset: int, duration_hours: int = 1) -> tuple[str, str]:
    start = BASE_TIME + timedelta(hours=hours_offset)
    end = start + timedelta(hours=duration_hours)
    return start.isoformat(), end.isoformat()


def _safe_int(raw: str | None) -> int:
    return int(raw or "0")


@dataclass
class TestResult:
    name: str
    passed: bool
    detail: str


class MockCalendarBridge:
    """In-memory bridge used when adb is unavailable."""

    def __init__(self) -> None:
        self._calendar_id = 1
        self._next_event_id = 1000
        self._events: dict[int, dict[str, Any]] = {}

    def resolve_writable_calendar_id(self) -> int:
        return self._calendar_id

    def query_conflicts(self, start_ms: int, end_ms: int) -> list[dict[str, Any]]:
        conflicts: list[dict[str, Any]] = []
        for event_id, event in sorted(self._events.items()):
            existing_start = event["start_ms"]
            existing_end = event["end_ms"]
            if existing_start < end_ms and start_ms < existing_end:
                conflicts.append(
                    {
                        "event_id": str(event_id),
                        "title": event["title"],
                        "start_time": datetime.fromtimestamp(existing_start / 1000, tz=UTC).isoformat(),
                        "end_time": datetime.fromtimestamp(existing_end / 1000, tz=UTC).isoformat(),
                        "start_ms": existing_start,
                        "end_ms": existing_end,
                    }
                )
        return conflicts

    def query_events_by_ids(self, event_ids: list[int]) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        for event_id in event_ids:
            if event_id not in self._events:
                continue
            event = self._events[event_id]
            rows.append(
                {
                    "event_id": str(event_id),
                    "title": event["title"],
                    "start_time": datetime.fromtimestamp(event["start_ms"] / 1000, tz=UTC).isoformat(),
                    "end_time": datetime.fromtimestamp(event["end_ms"] / 1000, tz=UTC).isoformat(),
                }
            )
        return rows

    def insert_event(
        self,
        *,
        calendar_id: int,
        title: str,
        description: str,
        start_ms: int,
        end_ms: int,
    ) -> str:
        if calendar_id != self._calendar_id:
            raise RuntimeError("calendar id mismatch")
        event_id = self._next_event_id
        self._next_event_id += 1
        self._events[event_id] = {
            "title": title,
            "description": description,
            "start_ms": start_ms,
            "end_ms": end_ms,
        }
        return str(event_id)

    def delete_events(self, event_ids: list[int]) -> int:
        deleted = 0
        for event_id in event_ids:
            if event_id in self._events:
                del self._events[event_id]
                deleted += 1
        return deleted

    def event_count(self) -> int:
        return len(self._events)


class CalendarSkillHarness:
    def __init__(self, *, bridge: Any) -> None:
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


def _test_create_and_detect(harness: CalendarSkillHarness, bridge: MockCalendarBridge) -> None:
    start_time, end_time = iso_at(0)
    created = harness.invoke(
        "create_calendar_event",
        {
            "title": "CAL_TEST_CREATE",
            "description": "first event",
            "start_time": start_time,
            "end_time": end_time,
            "conflict_decision": "coexist",
        },
    )
    _expect(created["code"] == "OK", f"create failed: {created}")
    _expect(created["data"]["status"] == "created", f"create status mismatch: {created['data']}")
    _expect(bridge.event_count() == 1, "event should be inserted into bridge")

    conflicts = harness.invoke(
        "detect_calendar_conflicts",
        {
            "start_time": start_time,
            "end_time": end_time,
        },
    )
    _expect(conflicts["code"] == "OK", f"detect failed: {conflicts}")
    _expect(conflicts["data"]["conflict_count"] >= 1, "should detect at least one conflict")


def _test_conflict_prompt_skip_delete(harness: CalendarSkillHarness, bridge: MockCalendarBridge) -> None:
    start_time, end_time = iso_at(0)
    before = bridge.event_count()

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
    _expect(bridge.event_count() == before, "skip_write should not change event count")

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


def _test_conflict_delete_then_create(harness: CalendarSkillHarness, bridge: MockCalendarBridge) -> None:
    start_time, end_time = iso_at(0)
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
    _expect(bridge.event_count() == 1, "delete_conflicts should leave only the new event")


def _test_delete_requires_confirm(harness: CalendarSkillHarness) -> None:
    result = harness.invoke(
        "delete_calendar_event",
        {
            "event_id": "1000",
            "confirm_delete": False,
        },
    )
    _expect(result["code"] == "APPROVAL_REQUIRED", f"delete should require confirm: {result}")


def _test_delete_success(harness: CalendarSkillHarness, bridge: MockCalendarBridge) -> None:
    existing_ids = list(bridge._events.keys())  # noqa: SLF001
    _expect(bool(existing_ids), "bridge should contain at least one event before delete")
    result = harness.invoke(
        "delete_calendar_event",
        {
            "event_ids": [str(existing_ids[0]), "999999"],
            "confirm_delete": True,
        },
    )
    _expect(result["code"] == "OK", f"delete failed: {result}")
    _expect(result["data"]["deleted_count"] >= 1, f"deleted_count mismatch: {result['data']}")
    _expect("999999" in result["data"]["missing_event_ids"], "missing ids should include non-existing id")
    _expect(bridge.event_count() == 0, "all tracked events should be deleted")


def run_mock_suite() -> list[TestResult]:
    results: list[TestResult] = []
    bridge = MockCalendarBridge()
    harness = CalendarSkillHarness(bridge=bridge)
    tests: list[tuple[str, Callable[[], None]]] = [
        ("registry_binding", _test_registry_binding),
        ("create_missing_required", lambda: _test_create_missing_required(harness)),
        ("create_and_detect", lambda: _test_create_and_detect(harness, bridge)),
        ("conflict_prompt_skip_delete", lambda: _test_conflict_prompt_skip_delete(harness, bridge)),
        ("conflict_delete_then_create", lambda: _test_conflict_delete_then_create(harness, bridge)),
        ("delete_requires_confirm", lambda: _test_delete_requires_confirm(harness)),
        ("delete_success", lambda: _test_delete_success(harness, bridge)),
    ]
    for name, fn in tests:
        try:
            fn()
            results.append(TestResult(name=name, passed=True, detail="ok"))
        except Exception as exc:
            results.append(TestResult(name=name, passed=False, detail=f"{type(exc).__name__}: {exc}"))
    return results


def run_adb_smoke() -> list[TestResult]:
    results: list[TestResult] = []
    created_ids: list[int] = []
    try:
        bridge = AdbCalendarBridge()
        calendar_id = bridge.resolve_writable_calendar_id()
        results.append(TestResult(name="adb_writable_calendar", passed=True, detail=f"calendar_id={calendar_id}"))

        start_time, end_time = iso_at(72)
        start_ms = int(datetime.fromisoformat(start_time).timestamp() * 1000)
        end_ms = int(datetime.fromisoformat(end_time).timestamp() * 1000)
        unique_title = f"CAL_SMOKE_AUTOTEST_{int(datetime.now(tz=UTC).timestamp())}"
        created_id = bridge.insert_event(
            calendar_id=calendar_id,
            title=unique_title,
            description="autotest event",
            start_ms=start_ms,
            end_ms=end_ms,
        )
        created_ids.append(_safe_int(created_id))
        results.append(TestResult(name="adb_create_event", passed=True, detail=f"event_id={created_id}"))

        conflicts = bridge.query_conflicts(start_ms, end_ms)
        has_self = any(item.get("event_id") == created_id for item in conflicts)
        _expect(has_self, "created event should be found in conflict query")
        results.append(TestResult(name="adb_detect_event", passed=True, detail=f"conflict_count={len(conflicts)}"))

        present_after_create = bridge.query_events_by_ids([_safe_int(created_id)])
        _expect(bool(present_after_create), "created event should be queryable before delete")
        results.append(TestResult(name="adb_query_created_event", passed=True, detail=f"rows={len(present_after_create)}"))

        deleted = bridge.delete_events([_safe_int(created_id)])
        _expect(deleted >= 1, "created event should be deletable")
        results.append(TestResult(name="adb_delete_event", passed=True, detail=f"deleted={deleted}"))

        remaining_after_delete = bridge.query_events_by_ids([_safe_int(created_id)])
        _expect(not remaining_after_delete, "deleted event should not appear in query")
        results.append(
            TestResult(
                name="adb_verify_deleted_absent",
                passed=True,
                detail="event removed from provider",
            )
        )
        created_ids.clear()
    except CalendarBridgeError as exc:
        results.append(TestResult(name="adb_smoke", passed=False, detail=str(exc)))
    except Exception as exc:
        results.append(TestResult(name="adb_smoke", passed=False, detail=f"{type(exc).__name__}: {exc}"))
    finally:
        if created_ids:
            try:
                bridge = AdbCalendarBridge()
                bridge.delete_events(created_ids)
            except Exception as cleanup_exc:
                results.append(
                    TestResult(
                        name="adb_cleanup_created_event",
                        passed=False,
                        detail=f"{type(cleanup_exc).__name__}: {cleanup_exc}",
                    )
                )
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
    parser = argparse.ArgumentParser(description="Calendar skill test runner")
    parser.add_argument(
        "--mode",
        choices=["mock", "adb"],
        default="mock",
        help="mock: no adb required; adb: real device smoke test",
    )
    parser.add_argument("--adb-serial", default="", help="Optional adb device serial for adb mode")
    parser.add_argument(
        "--timezone",
        default="UTC",
        help="Timezone used for inserted events in adb mode (default UTC)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.adb_serial:
        os.environ["OPENTHU_ADB_SERIAL"] = args.adb_serial
    if args.timezone:
        os.environ["OPENTHU_CALENDAR_TIMEZONE"] = args.timezone

    if args.mode == "mock":
        raise_code = print_report(run_mock_suite())
    else:
        raise_code = print_report(run_adb_smoke())

    raise SystemExit(raise_code)


if __name__ == "__main__":
    main()
