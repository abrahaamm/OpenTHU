from __future__ import annotations

import json
import sys
import tempfile
import types
from pathlib import Path
from typing import Any


def _install_fake_langgraph_if_needed() -> None:
    try:
        import langgraph.graph  # type: ignore  # noqa: F401
        return
    except ModuleNotFoundError:
        pass

    class _FakeStateGraph:
        def __init__(self, *_: Any, **__: Any) -> None:
            pass

        def add_node(self, *_: Any, **__: Any) -> None:
            pass

        def add_edge(self, *_: Any, **__: Any) -> None:
            pass

        def add_conditional_edges(self, *_: Any, **__: Any) -> None:
            pass

        def compile(self) -> "_FakeStateGraph":
            return self

    fake_graph = types.SimpleNamespace(END="END", START="START", StateGraph=_FakeStateGraph)
    sys.modules.setdefault("langgraph", types.SimpleNamespace())
    sys.modules["langgraph.graph"] = fake_graph


_install_fake_langgraph_if_needed()

try:
    from .openthu_agent import OpenTHULangGraphAgent
    from .skill_manager import SkillManager
except ImportError:
    from openthu_agent import OpenTHULangGraphAgent
    from skill_manager import SkillManager


FAKE_COMPLETION_PAYLOADS: list[dict[str, Any]] = []


class _FakeMessage:
    def __init__(self, content: str) -> None:
        self.content = content


class _FakeChoice:
    def __init__(self, content: str) -> None:
        self.message = _FakeMessage(content)


class _FakeCompletion:
    def __init__(self, content: str) -> None:
        self.choices = [_FakeChoice(content)]


class _FakeCompletions:
    def create(self, **kwargs: Any) -> _FakeCompletion:
        messages = kwargs.get("messages", [])
        payload = json.loads(messages[-1]["content"])
        FAKE_COMPLETION_PAYLOADS.append(payload)
        user_input = str(payload.get("user_input", "")).lower()
        if "homework" in user_input and "not submitted" in user_input:
            return _FakeCompletion(
                json.dumps(
                    {
                        "reply": "我来检查网络学堂里还没有提交的作业。",
                        "confidence": 0.92,
                        "structured_prompt": {
                            "objective": "Check unsubmitted Tsinghua Learn homework",
                            "entities": ["homework", "unsubmitted"],
                            "constraints": [],
                            "success_criteria": ["Return unsubmitted homework records or login guidance"],
                            "sensitivity": "low",
                        },
                        "skill_plan": [
                            {
                                "skill_name": "crawl_unsubmitted_homeworks",
                                "args": {},
                                "description": "检查网络学堂里尚未提交的作业。",
                            }
                        ],
                    },
                    ensure_ascii=False,
                )
            )
        return _FakeCompletion(
            json.dumps(
                {
                    "reply": "Hello，我在。你可以和我聊天，也可以直接说要处理的校园任务。",
                    "confidence": 0.87,
                    "structured_prompt": {
                        "objective": "Casual greeting",
                        "entities": [],
                        "constraints": [],
                        "success_criteria": ["Reply conversationally"],
                        "sensitivity": "low",
                    },
                    "skill_plan": [],
                },
                ensure_ascii=False,
            )
        )


class _FakeChat:
    def __init__(self) -> None:
        self.completions = _FakeCompletions()


class _FakeOpenAI:
    def __init__(self, **_: Any) -> None:
        self.chat = _FakeChat()


def _install_fake_openai() -> None:
    FAKE_COMPLETION_PAYLOADS.clear()
    sys.modules["openai"] = types.SimpleNamespace(OpenAI=_FakeOpenAI)


def _expect(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def test_skill_metadata() -> None:
    skills = SkillManager().list_for_planner()
    by_name = {item["skill_name"]: item for item in skills}
    homework = by_name["crawl_unsubmitted_homeworks"]
    _expect("not submitted" in homework["when_to_use"], homework["when_to_use"])
    _expect("check my homework that is not submitted" in homework["example_utterances"], str(homework))
    submit_schema = by_name["submit_homework"]["args_json_schema"]
    _expect(submit_schema.get("required") == [], str(submit_schema))
    _expect(by_name["get_campus_activities"]["session_required"] is False, str(by_name["get_campus_activities"]))
    _expect(by_name["get_course_schedule"]["example_utterances"], "course schedule examples missing")
    _expect(by_name["read_notifications"]["when_to_use"], "notification when_to_use missing")


def test_decide_turn_chat() -> None:
    _install_fake_openai()
    with tempfile.TemporaryDirectory() as tmpdir:
        agent = OpenTHULangGraphAgent(memory_file=Path(tmpdir) / "memory.json")
        response = agent.decide_turn(
            "hello",
            user_id="decision_test_user",
            session={"openai_api_key": "test-key"},
        )
    data = response["data"]
    _expect(data["source"] == "llm_decision", str(data))
    _expect(data["should_plan"] is False, str(data))
    _expect("plan_response" not in data, str(data))


def test_decide_turn_homework_plan() -> None:
    _install_fake_openai()
    with tempfile.TemporaryDirectory() as tmpdir:
        agent = OpenTHULangGraphAgent(memory_file=Path(tmpdir) / "memory.json")
        response = agent.decide_turn(
            "check my homework that is not submitted",
            user_id="decision_test_user",
            session={"openai_api_key": "test-key"},
        )
    data = response["data"]
    plan_data = data["plan_response"]["data"]
    planned = plan_data["skill_plan"]
    _expect(data["source"] == "llm_decision", str(data))
    _expect(data["should_plan"] is True, str(data))
    _expect(plan_data["planner_source"] == "llm_decision", str(plan_data))
    _expect(planned[0]["skill_name"] == "crawl_unsubmitted_homeworks", str(planned))
    _expect(planned[0]["status"] == "approved", str(planned))


def test_decide_turn_homework_submit_fallback() -> None:
    _install_fake_openai()
    message = (
        "请你帮我提交这份作业\n\n"
        "[attached_file]\n"
        "file_uri: content://com.android.providers.downloads.documents/document/42\n"
        "file_name: report.docx"
    )
    with tempfile.TemporaryDirectory() as tmpdir:
        agent = OpenTHULangGraphAgent(memory_file=Path(tmpdir) / "memory.json")
        response = agent.decide_turn(
            message,
            user_id="decision_test_user",
            session={"openai_api_key": "test-key"},
        )
    data = response["data"]
    plan_data = data["plan_response"]["data"]
    planned = plan_data["skill_plan"]
    skill_names = [item["skill_name"] for item in planned]
    _expect(data["source"] == "deterministic_after_llm_empty", str(data))
    _expect(data["should_plan"] is True, str(data))
    _expect("submit_homework" in skill_names, str(planned))
    submit = next(item for item in planned if item["skill_name"] == "submit_homework")
    _expect(submit["args"]["file_uri"].startswith("content://"), str(submit))
    _expect(submit["args"]["file_name"] == "report.docx", str(submit))
    _expect(submit["args"]["confirm_submit"] is True, str(submit))


def test_decide_turn_includes_memory_context() -> None:
    _install_fake_openai()
    memory_context = {
        "summary": "- [long/manual_preference/w90] 不要创建早于 08:00 的提醒",
        "entries": [
            {
                "scope": "long",
                "key": "manual_preference",
                "value": "不要创建早于 08:00 的提醒",
                "weight": 90,
                "updated_at_epoch_ms": 1780000000000,
            }
        ],
    }
    with tempfile.TemporaryDirectory() as tmpdir:
        agent = OpenTHULangGraphAgent(memory_file=Path(tmpdir) / "memory.json")
        response = agent.decide_turn(
            "hello",
            user_id="decision_test_user",
            session={"openai_api_key": "test-key", "memory_context": memory_context},
        )
    data = response["data"]
    _expect(data["source"] == "llm_decision", str(data))
    _expect(FAKE_COMPLETION_PAYLOADS, "fake LLM did not receive payload")
    context = FAKE_COMPLETION_PAYLOADS[-1].get("conversation_context", {})
    captured_memory = context.get("memory_context", {})
    _expect(captured_memory.get("summary") == memory_context["summary"], str(captured_memory))
    _expect(captured_memory.get("entries", [{}])[0].get("value") == "不要创建早于 08:00 的提醒", str(captured_memory))


def run_suite() -> list[tuple[str, bool, str]]:
    cases = [
        ("skill_metadata", test_skill_metadata),
        ("decide_turn_chat", test_decide_turn_chat),
        ("decide_turn_homework_plan", test_decide_turn_homework_plan),
        ("decide_turn_homework_submit_fallback", test_decide_turn_homework_submit_fallback),
        ("decide_turn_includes_memory_context", test_decide_turn_includes_memory_context),
    ]
    results: list[tuple[str, bool, str]] = []
    for name, fn in cases:
        try:
            fn()
            results.append((name, True, "ok"))
        except Exception as exc:
            results.append((name, False, f"{type(exc).__name__}: {exc}"))
    return results


def main() -> int:
    results = run_suite()
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("=" * 72)
    print(f"Agent Decision Test Report: {passed}/{total} passed")
    print("-" * 72)
    for name, ok, detail in results:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print("=" * 72)
    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
