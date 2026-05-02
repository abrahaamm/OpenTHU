from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable

try:
    from .homework_handlers import (
        CrawlCourseHomeworksHandler,
        CrawlUnsubmittedHomeworksHandler,
        HomeworkSkillBridge,
        PreviewHomeworkAttachmentsHandler,
        SubmitHomeworkHandler,
        UploadHomeworkAttachmentHandler,
    )
    from .skill_core import SkillInvocation, build_default_registry
    from .skill_manager import SkillManager
except ImportError:
    from homework_handlers import (
        CrawlCourseHomeworksHandler,
        CrawlUnsubmittedHomeworksHandler,
        HomeworkSkillBridge,
        PreviewHomeworkAttachmentsHandler,
        SubmitHomeworkHandler,
        UploadHomeworkAttachmentHandler,
    )
    from skill_core import SkillInvocation, build_default_registry
    from skill_manager import SkillManager


UTC = timezone.utc


@dataclass
class TestResult:
    name: str
    passed: bool
    detail: str


class MockHomeworkBridge(HomeworkSkillBridge):
    def __init__(self) -> None:
        self.last_invocation: dict[str, Any] | None = None
        self._attachment_seq = 0
        self._homeworks: dict[str, dict[str, Any]] = {
            "hw_math_1": {
                "homework_id": "hw_math_1",
                "course_id": "course_math",
                "course_name": "Advanced Math",
                "title": "Homework 1",
                "submitted": False,
                "deadline": "2030-01-08T23:59:00+00:00",
                "attachments": [],
            },
            "hw_cs_1": {
                "homework_id": "hw_cs_1",
                "course_id": "course_cs",
                "course_name": "AI Intro",
                "title": "Project Proposal",
                "submitted": True,
                "deadline": "2030-01-05T23:59:00+00:00",
                "submitted_at": "2030-01-05T20:10:00+00:00",
                "attachments": [
                    {
                        "attachment_token": "att_existing_1",
                        "file_name": "proposal.pdf",
                        "preview_url": "https://mock.local/preview/proposal.pdf",
                    }
                ],
            },
        }
        self._uploaded: dict[str, dict[str, Any]] = {}

    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        self.last_invocation = invocation
        skill_name = str(invocation.get("skill_name", "")).strip()
        args = invocation.get("args", {})
        if not isinstance(args, dict):
            return self._fail(invocation, "INVALID_PARAM", "args must be object")

        if skill_name == "crawl_course_homeworks":
            return self._crawl_homeworks(invocation, args, include_submitted=bool(args.get("include_submitted", True)))
        if skill_name == "crawl_unsubmitted_homeworks":
            return self._crawl_homeworks(invocation, args, include_submitted=False)
        if skill_name == "upload_homework_attachment":
            return self._upload(invocation, args)
        if skill_name == "submit_homework":
            return self._submit(invocation, args)
        if skill_name == "preview_homework_attachments":
            return self._preview(invocation, args)
        return self._fail(invocation, "SKILL_EXECUTION_FAILED", f"unsupported skill: {skill_name}")

    def _crawl_homeworks(self, invocation: dict[str, Any], args: dict[str, Any], include_submitted: bool) -> dict[str, Any]:
        course_ids = [str(item).strip() for item in args.get("course_ids", []) if str(item).strip()]
        rows: list[dict[str, Any]] = []
        for homework in self._homeworks.values():
            if course_ids and homework["course_id"] not in course_ids:
                continue
            if not include_submitted and bool(homework.get("submitted")):
                continue
            rows.append(
                {
                    "homework_id": homework["homework_id"],
                    "course_id": homework["course_id"],
                    "course_name": homework["course_name"],
                    "title": homework["title"],
                    "submitted": bool(homework.get("submitted")),
                    "deadline": homework.get("deadline"),
                }
            )
        rows.sort(key=lambda item: item.get("deadline", ""))
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_homework_bridge_mock",
            "data": {
                "status": "ok",
                "count": len(rows),
                "homeworks": rows,
            },
        }

    def _upload(self, invocation: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
        homework_id = str(args.get("homework_id", "")).strip()
        if homework_id not in self._homeworks:
            return self._fail(invocation, "INVALID_PARAM", "homework_id not found")

        file_name = str(args.get("file_name", "")).strip()
        if not file_name:
            raw = str(args.get("file_path", "")).strip() or str(args.get("file_uri", "")).strip()
            file_name = raw.split("/")[-1] if raw else "attachment.bin"

        self._attachment_seq += 1
        token = f"att_upload_{self._attachment_seq}"
        self._uploaded[token] = {
            "homework_id": homework_id,
            "file_name": file_name,
            "uploaded_at": datetime.now(UTC).isoformat(),
        }
        self._homeworks[homework_id]["attachments"].append(
            {
                "attachment_token": token,
                "file_name": file_name,
                "preview_url": f"https://mock.local/preview/{token}/{file_name}",
            }
        )
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_homework_bridge_mock",
            "data": {
                "status": "uploaded",
                "homework_id": homework_id,
                "attachment_token": token,
                "file_name": file_name,
            },
        }

    def _submit(self, invocation: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
        homework_id = str(args.get("homework_id", "")).strip()
        if homework_id not in self._homeworks:
            return self._fail(invocation, "INVALID_PARAM", "homework_id not found")

        tokens = [str(item).strip() for item in args.get("attachment_tokens", []) if str(item).strip()]
        for token in tokens:
            if token not in self._uploaded:
                return self._fail(invocation, "INVALID_PARAM", f"attachment_token not found: {token}")
            if self._uploaded[token]["homework_id"] != homework_id:
                return self._fail(invocation, "INVALID_PARAM", f"attachment_token does not belong to homework_id: {token}")

        row = self._homeworks[homework_id]
        row["submitted"] = True
        row["submitted_at"] = datetime.now(UTC).isoformat()
        row["submission_text"] = str(args.get("submission_text", "")).strip()
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_homework_bridge_mock",
            "data": {
                "status": "submitted",
                "homework_id": homework_id,
                "submitted_at": row["submitted_at"],
                "attachment_count": len(row.get("attachments", [])),
            },
        }

    def _preview(self, invocation: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
        homework_id = str(args.get("homework_id", "")).strip()
        if homework_id not in self._homeworks:
            return self._fail(invocation, "INVALID_PARAM", "homework_id not found")
        row = self._homeworks[homework_id]
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_homework_bridge_mock",
            "data": {
                "status": "ok",
                "homework_id": homework_id,
                "attachments": list(row.get("attachments", [])),
            },
        }

    def _fail(self, invocation: dict[str, Any], code: str, message: str) -> dict[str, Any]:
        return {
            "request_id": invocation["request_id"],
            "code": code,
            "source": "android_kotlin_homework_bridge_mock",
            "data": {"status": "failed", "message": message},
        }


class HomeworkSkillHarness:
    def __init__(self, *, bridge: HomeworkSkillBridge) -> None:
        self.registry = build_default_registry()
        self.manager = SkillManager(registry=self.registry)
        self.bridge = bridge
        self.registry.register_handler("crawl_course_homeworks", CrawlCourseHomeworksHandler(bridge))
        self.registry.register_handler("crawl_unsubmitted_homeworks", CrawlUnsubmittedHomeworksHandler(bridge))
        self.registry.register_handler("upload_homework_attachment", UploadHomeworkAttachmentHandler(bridge))
        self.registry.register_handler("submit_homework", SubmitHomeworkHandler(bridge))
        self.registry.register_handler("preview_homework_attachments", PreviewHomeworkAttachmentsHandler(bridge))
        self._seq = 0

    def invoke(self, skill_name: str, args: dict[str, Any]) -> dict[str, Any]:
        self._seq += 1
        spec = self.registry.get_spec(skill_name)
        if spec is None:
            raise AssertionError(f"Unknown skill in registry: {skill_name}")
        invocation = SkillInvocation(
            skill_name=skill_name,
            request_id=f"req_hw_test_{self._seq}",
            task_id="task_homework_skill_test",
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
        "crawl_course_homeworks",
        "crawl_unsubmitted_homeworks",
        "upload_homework_attachment",
        "submit_homework",
        "preview_homework_attachments",
    ]:
        handler = registry.get_handler(skill_name)
        _expect(handler.__class__.__name__ != "MissingSkillHandler", f"{skill_name} still uses MissingSkillHandler")


def _test_schema_reject_unknown_field(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke(
        "crawl_course_homeworks",
        {
            "course_ids": ["course_math"],
            "unknown_field": "should_fail",
        },
    )
    _expect(result["code"] == "INVALID_PARAM", f"unknown args should fail schema validation: {result}")


def _test_crawl_all(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke(
        "crawl_course_homeworks",
        {
            "course_ids": ["course_math", "course_cs"],
            "include_submitted": True,
        },
    )
    _expect(result["code"] == "OK", f"crawl failed: {result}")
    _expect(result["data"]["count"] >= 2, f"expected >=2 homeworks: {result['data']}")


def _test_crawl_unsubmitted(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke(
        "crawl_unsubmitted_homeworks",
        {
            "course_ids": ["course_math", "course_cs"],
        },
    )
    _expect(result["code"] == "OK", f"crawl unsubmitted failed: {result}")
    _expect(all(not item["submitted"] for item in result["data"]["homeworks"]), f"found submitted row: {result['data']}")


def _test_upload_missing_path(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke(
        "upload_homework_attachment",
        {
            "homework_id": "hw_math_1",
        },
    )
    _expect(result["code"] == "INVALID_PARAM", f"upload should reject missing path/uri: {result}")


def _test_submit_requires_confirm(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke(
        "submit_homework",
        {
            "homework_id": "hw_math_1",
            "submission_text": "answer",
            "confirm_submit": False,
        },
    )
    _expect(result["code"] == "APPROVAL_REQUIRED", f"submit should require confirm_submit=true: {result}")


def _test_upload_then_submit(harness: HomeworkSkillHarness) -> None:
    uploaded = harness.invoke(
        "upload_homework_attachment",
        {
            "homework_id": "hw_math_1",
            "file_path": "/sdcard/Download/hw1.pdf",
        },
    )
    _expect(uploaded["code"] == "OK", f"upload failed: {uploaded}")
    token = uploaded["data"].get("attachment_token")
    submitted = harness.invoke(
        "submit_homework",
        {
            "homework_id": "hw_math_1",
            "submission_text": "my answer",
            "attachment_tokens": [token],
            "confirm_submit": True,
        },
    )
    _expect(submitted["code"] == "OK", f"submit failed: {submitted}")
    _expect(submitted["data"]["status"] == "submitted", f"submit status mismatch: {submitted['data']}")


def _test_preview(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke(
        "preview_homework_attachments",
        {
            "homework_id": "hw_math_1",
            "include_feedback_attachments": True,
        },
    )
    _expect(result["code"] == "OK", f"preview failed: {result}")
    _expect(isinstance(result["data"].get("attachments"), list), f"attachments should be list: {result['data']}")


def run_mock_suite() -> list[TestResult]:
    results: list[TestResult] = []
    bridge = MockHomeworkBridge()
    harness = HomeworkSkillHarness(bridge=bridge)
    tests: list[tuple[str, Callable[[], None]]] = [
        ("registry_binding", _test_registry_binding),
        ("schema_rejects_unknown_field", lambda: _test_schema_reject_unknown_field(harness)),
        ("crawl_all", lambda: _test_crawl_all(harness)),
        ("crawl_unsubmitted", lambda: _test_crawl_unsubmitted(harness)),
        ("upload_missing_path", lambda: _test_upload_missing_path(harness)),
        ("submit_requires_confirm", lambda: _test_submit_requires_confirm(harness)),
        ("upload_then_submit", lambda: _test_upload_then_submit(harness)),
        ("preview", lambda: _test_preview(harness)),
    ]
    for name, fn in tests:
        try:
            fn()
            results.append(TestResult(name=name, passed=True, detail="ok"))
        except Exception as exc:  # noqa: BLE001
            results.append(TestResult(name=name, passed=False, detail=f"{type(exc).__name__}: {exc}"))
    return results


def print_report(results: list[TestResult]) -> int:
    passed = sum(1 for item in results if item.passed)
    total = len(results)
    print("=" * 72)
    print(f"Homework Skill Test Report: {passed}/{total} passed")
    print("-" * 72)
    for item in results:
        status = "PASS" if item.passed else "FAIL"
        print(f"[{status}] {item.name}: {item.detail}")
    print("=" * 72)
    return 0 if passed == total else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Homework skill bridge test runner")
    parser.add_argument(
        "--mode",
        choices=["mock"],
        default="mock",
        help="mock: validates homework handlers via a mock Kotlin bridge",
    )
    return parser.parse_args()


def main() -> None:
    _ = parse_args()
    raise_code = print_report(run_mock_suite())
    raise SystemExit(raise_code)


if __name__ == "__main__":
    main()
