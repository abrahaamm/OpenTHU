from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable

try:
    from .homework_handlers import (
        CrawlCourseHomeworksHandler,
        CrawlUnsubmittedHomeworksHandler,
        GetHomeworkCookieHandler,
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
        GetHomeworkCookieHandler,
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
        self._attachment_seq = 0
        self._uploaded: dict[str, dict[str, Any]] = {}
        self._homeworks: dict[str, dict[str, Any]] = {
            "hw_math_1": {
                "homework_id": "hw_math_1",
                "student_homework_id": "xs_hw_math_1",
                "course_id": "course_math",
                "course_name": "Advanced Math",
                "title": "Homework 1",
                "submitted": False,
                "deadline": "2030-01-08T23:59:00+00:00",
                "attachments": [],
            },
            "hw_cs_1": {
                "homework_id": "hw_cs_1",
                "student_homework_id": "xs_hw_cs_1",
                "course_id": "course_cs",
                "course_name": "AI Intro",
                "title": "Project Proposal",
                "submitted": True,
                "deadline": "2030-01-05T23:59:00+00:00",
                "attachments": [
                    {
                        "attachment_token": "att_existing_1",
                        "file_name": "proposal.pdf",
                    }
                ],
            },
        }

    def execute(self, invocation: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
        skill_name = str(invocation.get("skill_name", "")).strip()
        args = invocation.get("args", {})
        if not isinstance(args, dict):
            return self._fail(invocation, "INVALID_PARAM", "args must be object")

        if skill_name == "get_homework_cookie":
            return {
                "request_id": invocation["request_id"],
                "code": "OK",
                "source": "android_kotlin_homework_bridge_mock",
                "data": {
                    "status": "cookie_ready",
                    "cookie_source": "provided_cookie",
                    "has_csrf": True,
                },
            }
        if skill_name == "crawl_course_homeworks":
            return self._crawl(invocation, args, include_submitted=bool(args.get("include_submitted", True)))
        if skill_name == "crawl_unsubmitted_homeworks":
            return self._crawl(invocation, args, include_submitted=False)
        if skill_name == "preview_homework_attachments":
            return self._preview(invocation, args)
        if skill_name == "upload_homework_attachment":
            return self._upload(invocation, args)
        if skill_name == "submit_homework":
            return self._submit(invocation, args)
        return self._fail(invocation, "SKILL_EXECUTION_FAILED", f"unsupported skill: {skill_name}")

    def _crawl(self, invocation: dict[str, Any], args: dict[str, Any], include_submitted: bool) -> dict[str, Any]:
        course_ids = [str(item).strip() for item in args.get("course_ids", []) if str(item).strip()]
        rows: list[dict[str, Any]] = []
        for homework in self._homeworks.values():
            if course_ids and homework["course_id"] not in course_ids:
                continue
            if not include_submitted and bool(homework.get("submitted")):
                continue
            rows.append(dict(homework))
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_homework_bridge_mock",
            "data": {
                "status": "crawled",
                "count": len(rows),
                "homeworks": rows,
            },
        }

    def _preview(self, invocation: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
        homework_id = str(args.get("homework_id", "")).strip()
        row = self._homeworks.get(homework_id)
        if row is None:
            return self._fail(invocation, "INVALID_PARAM", "homework_id not found")
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_homework_bridge_mock",
            "data": {
                "status": "preview_ready",
                "homework_id": homework_id,
                "attachments": list(row.get("attachments", [])),
            },
        }

    def _upload(self, invocation: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
        homework_id = str(args.get("homework_id", "")).strip()
        if homework_id not in self._homeworks:
            return self._fail(invocation, "INVALID_PARAM", "homework_id not found")
        self._attachment_seq += 1
        token = f"att_upload_{self._attachment_seq}"
        file_name = str(args.get("file_name", "")).strip() or "attachment.bin"
        self._uploaded[token] = {"homework_id": homework_id, "file_name": file_name}
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
        self._homeworks[homework_id]["submitted"] = True
        return {
            "request_id": invocation["request_id"],
            "code": "OK",
            "source": "android_kotlin_homework_bridge_mock",
            "data": {
                "status": "submitted",
                "homework_id": homework_id,
                "submitted_at": datetime.now(UTC).isoformat(),
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
    def __init__(self) -> None:
        self.registry = build_default_registry()
        self.manager = SkillManager(registry=self.registry)
        bridge = MockHomeworkBridge()
        self.registry.register_handler("get_homework_cookie", GetHomeworkCookieHandler(bridge))
        self.registry.register_handler("crawl_course_homeworks", CrawlCourseHomeworksHandler(bridge))
        self.registry.register_handler("crawl_unsubmitted_homeworks", CrawlUnsubmittedHomeworksHandler(bridge))
        self.registry.register_handler("preview_homework_attachments", PreviewHomeworkAttachmentsHandler(bridge))
        self.registry.register_handler("upload_homework_attachment", UploadHomeworkAttachmentHandler(bridge))
        self.registry.register_handler("submit_homework", SubmitHomeworkHandler(bridge))
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
        "get_homework_cookie",
        "crawl_course_homeworks",
        "crawl_unsubmitted_homeworks",
        "preview_homework_attachments",
        "upload_homework_attachment",
        "submit_homework",
    ]:
        handler = registry.get_handler(skill_name)
        _expect(handler.__class__.__name__ != "MissingSkillHandler", f"{skill_name} still uses MissingSkillHandler")


def _test_cookie_ready(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke("get_homework_cookie", {"cookies": "JSESSIONID=abc; XSRF-TOKEN=xyz"})
    _expect(result["code"] == "OK", f"cookie load failed: {result}")
    _expect(result["data"]["status"] == "cookie_ready", f"cookie status mismatch: {result}")


def _test_crawl_unsubmitted(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke("crawl_unsubmitted_homeworks", {"course_ids": ["course_math", "course_cs"]})
    _expect(result["code"] == "OK", f"crawl failed: {result}")
    _expect(all(not item["submitted"] for item in result["data"]["homeworks"]), f"found submitted row: {result}")


def _test_upload_missing_path(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke("upload_homework_attachment", {"homework_id": "hw_math_1"})
    _expect(result["code"] == "INVALID_PARAM", f"upload should reject missing path/uri: {result}")


def _test_submit_requires_confirm(harness: HomeworkSkillHarness) -> None:
    result = harness.invoke(
        "submit_homework",
        {"homework_id": "hw_math_1", "submission_text": "answer", "confirm_submit": False},
    )
    _expect(result["code"] == "APPROVAL_REQUIRED", f"submit should require confirm_submit=true: {result}")


def _test_upload_then_submit(harness: HomeworkSkillHarness) -> None:
    uploaded = harness.invoke(
        "upload_homework_attachment",
        {"homework_id": "hw_math_1", "file_path": "/sdcard/Download/hw1.pdf", "file_name": "hw1.pdf"},
    )
    _expect(uploaded["code"] == "OK", f"upload failed: {uploaded}")
    submitted = harness.invoke(
        "submit_homework",
        {
            "homework_id": "hw_math_1",
            "submission_text": "my answer",
            "attachment_tokens": [uploaded["data"]["attachment_token"]],
            "confirm_submit": True,
        },
    )
    _expect(submitted["code"] == "OK", f"submit failed: {submitted}")
    _expect(submitted["data"]["status"] == "submitted", f"submit status mismatch: {submitted}")


def run_mock_suite() -> list[TestResult]:
    harness = HomeworkSkillHarness()
    tests: list[tuple[str, Callable[[], None]]] = [
        ("registry_binding", _test_registry_binding),
        ("cookie_ready", lambda: _test_cookie_ready(harness)),
        ("crawl_unsubmitted", lambda: _test_crawl_unsubmitted(harness)),
        ("upload_missing_path", lambda: _test_upload_missing_path(harness)),
        ("submit_requires_confirm", lambda: _test_submit_requires_confirm(harness)),
        ("upload_then_submit", lambda: _test_upload_then_submit(harness)),
    ]
    results: list[TestResult] = []
    for name, fn in tests:
        try:
            fn()
            results.append(TestResult(name=name, passed=True, detail="ok"))
        except Exception as exc:  # noqa: BLE001
            results.append(TestResult(name=name, passed=False, detail=f"{type(exc).__name__}: {exc}"))
    return results


def main() -> None:
    results = run_mock_suite()
    passed = sum(1 for item in results if item.passed)
    total = len(results)
    print("=" * 72)
    print(f"Homework Skill Test Report: {passed}/{total} passed")
    print("-" * 72)
    for item in results:
        print(f"[{'PASS' if item.passed else 'FAIL'}] {item.name}: {item.detail}")
    print("=" * 72)
    raise SystemExit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
