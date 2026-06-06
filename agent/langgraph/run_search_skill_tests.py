from __future__ import annotations

from typing import Any

try:
    from .skills.search_skills import (
        DEFAULT_DUCKDUCKGO_ENDPOINT,
        LEGACY_DUCKDUCKGO_ENDPOINT,
        SearchProvider,
        WebSearchResult,
        _normalize_duckduckgo_endpoint,
        parse_duckduckgo_html,
        search_with_scene,
    )
except ImportError:
    from skills.search_skills import (
        DEFAULT_DUCKDUCKGO_ENDPOINT,
        LEGACY_DUCKDUCKGO_ENDPOINT,
        SearchProvider,
        WebSearchResult,
        _normalize_duckduckgo_endpoint,
        parse_duckduckgo_html,
        search_with_scene,
    )


class FakeProvider(SearchProvider):
    name = "fake"

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    def search(
        self,
        *,
        query: str,
        max_results: int,
        domains: list[str],
        freshness_days: int,
        language: str,
    ) -> tuple[list[WebSearchResult], bool]:
        self.calls.append(
            {
                "query": query,
                "max_results": max_results,
                "domains": domains,
                "freshness_days": freshness_days,
                "language": language,
            }
        )
        source = "campus" if domains else "general"
        return [
            WebSearchResult(
                title=f"{source} result",
                url=f"https://example.com/{source}",
                snippet="ok",
                source=source,
            )
        ], False


def _expect(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def _run_case(name: str, fn) -> tuple[str, bool, str]:
    try:
        fn()
        return name, True, "ok"
    except Exception as exc:
        return name, False, f"{type(exc).__name__}: {exc}"


def test_duckduckgo_endpoint_normalization() -> None:
    _expect(
        _normalize_duckduckgo_endpoint(LEGACY_DUCKDUCKGO_ENDPOINT) == DEFAULT_DUCKDUCKGO_ENDPOINT,
        "legacy DuckDuckGo endpoint should use lite host",
    )
    _expect(
        _normalize_duckduckgo_endpoint("") == DEFAULT_DUCKDUCKGO_ENDPOINT,
        "blank DuckDuckGo endpoint should use lite host",
    )


def test_parse_duckduckgo_lite_results() -> None:
    html = """
    <tr>
      <td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fopenai.com%2F" class='result-link'>OpenAI | Research &amp; Deployment</a></td>
    </tr>
    <tr>
      <td class='result-snippet'>An <b>OpenAI</b> model update.</td>
    </tr>
    """
    results = parse_duckduckgo_html(html)
    _expect(len(results) == 1, f"expected one parsed lite result: {results}")
    _expect(results[0].url == "https://openai.com/", f"unexpected lite result url: {results[0]}")
    _expect(results[0].title == "OpenAI | Research & Deployment", f"unexpected title: {results[0]}")
    _expect(results[0].snippet == "An OpenAI model update.", f"unexpected snippet: {results[0]}")


def test_general_scene_does_not_apply_campus_domains() -> None:
    provider = FakeProvider()
    results, supplemental, from_cache = search_with_scene(
        provider=provider,
        query="歌手2026 首发阵容",
        scene="general",
        max_results=3,
        supplemental_results=2,
        domains=[],
        freshness_days=30,
        language="zh-CN",
    )
    _expect(len(provider.calls) == 1, f"general scene should make one call: {provider.calls}")
    _expect(provider.calls[0]["domains"] == [], f"general scene should not filter domains: {provider.calls}")
    _expect(results[0].source == "general", f"unexpected result source: {results}")
    _expect(supplemental == [], f"general scene should not return supplemental results: {supplemental}")
    _expect(from_cache is False, "fake provider returns non-cached results")


def test_hybrid_scene_uses_campus_then_general() -> None:
    provider = FakeProvider()
    results, supplemental, _ = search_with_scene(
        provider=provider,
        query="AI 活动",
        scene="hybrid",
        max_results=3,
        supplemental_results=2,
        domains=[],
        freshness_days=30,
        language="zh-CN",
    )
    _expect(len(provider.calls) == 2, f"hybrid scene should make two calls: {provider.calls}")
    _expect(provider.calls[0]["domains"], f"first hybrid call should use campus domains: {provider.calls}")
    _expect(provider.calls[1]["domains"] == [], f"second hybrid call should be general: {provider.calls}")
    _expect(results, "hybrid results should not be empty")
    _expect(supplemental, "hybrid supplemental results should not be empty")


def run_mock_suite() -> list[tuple[str, bool, str]]:
    return [
        _run_case("duckduckgo_endpoint_normalization", test_duckduckgo_endpoint_normalization),
        _run_case("parse_duckduckgo_lite_results", test_parse_duckduckgo_lite_results),
        _run_case("general_scene_does_not_apply_campus_domains", test_general_scene_does_not_apply_campus_domains),
        _run_case("hybrid_scene_uses_campus_then_general", test_hybrid_scene_uses_campus_then_general),
    ]


def print_report(results: list[tuple[str, bool, str]]) -> int:
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("=" * 72)
    print(f"Search Skill Test Report: {passed}/{total} passed")
    print("-" * 72)
    for name, ok, detail in results:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print("=" * 72)
    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(print_report(run_mock_suite()))
