from __future__ import annotations

from typing import Any

try:
    from .skill_core import SkillInvocation
    from .skills.course_info_skills import (
        GetCourseScheduleSkill,
        GetCoursesSkill,
        GetSemestersSkill,
    )
except ImportError:
    from skill_core import SkillInvocation
    from skills.course_info_skills import (
        GetCourseScheduleSkill,
        GetCoursesSkill,
        GetSemestersSkill,
    )


class FakeCourseInfoClient:
    def __init__(self, session: dict[str, Any]) -> None:
        self.learn_base_url = session.get("learn_base_url", "https://learn.tsinghua.edu.cn")
        self.learn_cookie = session.get("learn_cookie", "")
        self.webvpn_cookie = session.get("webvpn_cookie", "")
        self.csrf_token = "csrf_mock"

    def fetch_learn_csrf(self) -> str:
        return self.csrf_token

    def get_learn_json(
        self,
        path: str,
        params: dict[str, Any] | None = None,
    ) -> Any:
        if "getCurrentAndNextSemester" in path:
            return {
                "message": "success",
                "result": {
                    "id": "2025-2026-2",
                    "xnxqmc": "2025-2026 春",
                    "kssj": "2026-02-27",
                    "jssj": "2026-06-27",
                },
                "resultList": [
                    {
                        "id": "2026-2027-1",
                        "xnxqmc": "2026-2027 秋",
                        "kssj": "2026-09-07",
                        "jssj": "2027-01-10",
                    }
                ],
            }
        if "queryxnxq" in path:
            return ["2024-2025-2", "2025-2026-2"]
        if "loadCourseBySemesterId" in path:
            return {
                "resultList": [
                    {
                        "wlkcid": "course_ai",
                        "kcm": "人工智能导论",
                        "ywkcm": "Intro to AI",
                        "jsm": "张老师",
                        "kch": "30240243",
                        "kxh": "1",
                        "sjddb": "周一第1-2节 六教6A101",
                    }
                ]
            }
        if "v_wlkc_xk_sjddb/detail" in path:
            return {
                "resultList": [
                    {
                        "xqj": "3",
                        "ksjc": "3",
                        "jsjc": "4",
                        "jxdd": "六教6B201",
                        "zc": "1-16周",
                    }
                ]
            }
        raise AssertionError(f"unexpected learn path: {path}")

    def get_webvpn_text(self, url: str) -> str:
        if "jxmh_out.do" in url:
            return (
                'm([{"nq":"2026-02-23","nr":"人工智能导论","dd":"六教6A101",'
                '"fl":"教学安排","kssj":"08:00","jssj":"09:35","grrlID":42}])'
            )
        if "bks_ejkbSearch" in url:
            return ""
        raise AssertionError(f"unexpected webvpn url: {url}")


def _invoke(skill_name: str, args: dict[str, Any]) -> SkillInvocation:
    return SkillInvocation(
        skill_name=skill_name,
        request_id=f"req_{skill_name}",
        task_id="task_course_info_test",
        args=args,
        risk_level="low",
        requires_approval=False,
        description=f"Invoke {skill_name}",
    )


def _expect(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def _run_case(name: str, fn) -> tuple[str, bool, str]:
    try:
        fn()
        return name, True, "ok"
    except Exception as exc:
        return name, False, f"{type(exc).__name__}: {exc}"


def test_semesters() -> None:
    skill = GetSemestersSkill(client_factory=FakeCourseInfoClient)
    result = skill.invoke(_invoke("get_semesters", {}), {"learn_cookie": "JSESSIONID=abc"}, {})
    data = result.data
    _expect(result.code == "OK", f"unexpected code: {result.code}")
    _expect(data["current_semester"] == "2025-2026-2", f"unexpected current semester: {data}")
    first = data["semesters"][0]
    _expect(first["first_day"] == "2026-02-23", f"first day should align to Monday: {first}")


def test_courses_with_detail() -> None:
    skill = GetCoursesSkill(client_factory=FakeCourseInfoClient)
    result = skill.invoke(
        _invoke("get_courses", {"semester_id": "2025-2026-2"}),
        {"learn_cookie": "JSESSIONID=abc"},
        {},
    )
    data = result.data
    _expect(result.code == "OK", f"unexpected code: {result.code}")
    course = data["courses"][0]
    _expect(course["course_id"] == "course_ai", f"course id mismatch: {course}")
    _expect(len(course["time_and_location"]) >= 2, f"detail time blocks missing: {course}")


def test_webvpn_schedule() -> None:
    skill = GetCourseScheduleSkill(client_factory=FakeCourseInfoClient)
    result = skill.invoke(
        _invoke("get_course_schedule", {"include_secondary": False}),
        {"learn_cookie": "JSESSIONID=abc", "webvpn_cookie": "webvpn=ok"},
        {},
    )
    data = result.data
    _expect(result.code == "OK", f"unexpected code: {result.code}")
    _expect(data["source"] == "webvpn_teaching_calendar", f"source mismatch: {data}")
    _expect(data["schedule_count"] == 1, f"schedule count mismatch: {data}")
    entry = data["schedule_entries"][0]
    _expect(entry["week"] == 1 and entry["weekday"] == 1, f"entry date normalization failed: {entry}")


def test_missing_cookie() -> None:
    skill = GetCoursesSkill(client_factory=FakeCourseInfoClient)
    result = skill.invoke(_invoke("get_courses", {}), {}, {})
    _expect(result.code == "NOT_CONFIGURED", f"missing cookie should be not configured: {result.code}")


def run_mock_suite() -> list[tuple[str, bool, str]]:
    return [
        _run_case("semesters", test_semesters),
        _run_case("courses_with_detail", test_courses_with_detail),
        _run_case("webvpn_schedule", test_webvpn_schedule),
        _run_case("missing_cookie", test_missing_cookie),
    ]


def print_report(results: list[tuple[str, bool, str]]) -> int:
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("=" * 72)
    print(f"Course Info Skill Test Report: {passed}/{total} passed")
    print("-" * 72)
    for name, ok, detail in results:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print("=" * 72)
    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(print_report(run_mock_suite()))
