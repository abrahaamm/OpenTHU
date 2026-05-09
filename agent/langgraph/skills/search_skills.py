from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass, asdict
from datetime import datetime, timedelta, timezone
from html import unescape
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener

try:
    from ..skill_core import SkillHandler, SkillInvocation, SkillResult
except ImportError:
    from skill_core import SkillHandler, SkillInvocation, SkillResult


USER_AGENT = "OpenTHU-Agent-Core/1.0 (+https://github.com/thu-info-community/thu-info-app)"
DEFAULT_CACHE_TTL_SECONDS = 6 * 60 * 60


@dataclass
class WebSearchResult:
    title: str
    url: str
    snippet: str = ""
    source: str = "web"
    published_at: str = ""
    score: float = 0.0


@dataclass
class WebDocument:
    title: str
    url: str
    text: str
    snippet: str = ""
    source: str = "web"


class SearchProviderError(RuntimeError):
    pass


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _coerce_limit(args: dict[str, Any], key: str, default: int, upper: int) -> int:
    try:
        value = int(args.get(key, default))
    except (TypeError, ValueError):
        value = default
    return max(1, min(value, upper))


def _coerce_bool(value: Any, default: bool = True) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() not in {"0", "false", "no", "off"}
    return default


def _coerce_domains(raw: Any) -> list[str]:
    if isinstance(raw, list):
        values = [str(item).strip() for item in raw]
    elif isinstance(raw, str):
        values = [item.strip() for item in raw.replace("，", ",").split(",")]
    else:
        values = []
    return [item for item in values if item]


class SearchSkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        args = dict(invocation.args or {})
        query = str(args.get("query", "")).strip()
        if not query:
            return SkillResult(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="INVALID_PARAM",
                data={"status": "invalid_param", "message": "`query` is required", "results": []},
                from_cache=False,
                fetched_at=_utc_now(),
                source="search_skill",
            )

        max_results = _coerce_limit(args, "max_results", 5, 10)
        fetch_limit = _coerce_limit(args, "fetch_limit", max_results, 8)
        scope = str(args.get("scope", "web") or "web").strip().lower()
        domains = _coerce_domains(args.get("domains") or args.get("domain"))
        freshness_days = _coerce_limit(args, "freshness_days", 30, 3650)
        use_rag = _coerce_bool(args.get("use_rag", True), default=True)
        language = str(args.get("language", "zh-CN") or "zh-CN")
        warnings: list[str] = []

        if scope not in {"web", "all"}:
            warnings.append(f"Scope `{scope}` is not implemented yet; using web search.")

        try:
            provider = _build_provider()
            results, search_from_cache = provider.search(
                query=query,
                max_results=max_results,
                domains=domains,
                freshness_days=freshness_days,
                language=language,
            )
        except SearchProviderError as exc:
            return SkillResult(
                skill_name=invocation.skill_name,
                request_id=invocation.request_id,
                code="UPSTREAM_UNAVAILABLE",
                data={
                    "status": "provider_unavailable",
                    "message": str(exc),
                    "answer": "",
                    "results": [],
                    "citations": [],
                    "warnings": warnings,
                },
                from_cache=False,
                fetched_at=_utc_now(),
                source="search_provider",
            )

        documents = fetch_documents(results[:fetch_limit], warnings) if use_rag else []
        rag = build_search_answer(query, results, documents, max_results=max_results) if use_rag else {
            "answer": "",
            "citations": [],
            "evidence": [],
        }

        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "status": "ok",
                "query": query,
                "scope": scope,
                "answer": rag["answer"],
                "results": [asdict(item) for item in results],
                "citations": rag["citations"],
                "evidence": rag["evidence"],
                "warnings": warnings,
                "retrieved_at": _utc_now(),
                "provider": provider.name,
            },
            from_cache=search_from_cache,
            fetched_at=_utc_now(),
            source=f"web_search:{provider.name}",
        )


class SearchProvider:
    name = "base"

    def search(
        self,
        *,
        query: str,
        max_results: int,
        domains: list[str],
        freshness_days: int,
        language: str,
    ) -> tuple[list[WebSearchResult], bool]:
        raise NotImplementedError


class MockSearchProvider(SearchProvider):
    name = "mock"

    def search(
        self,
        *,
        query: str,
        max_results: int,
        domains: list[str],
        freshness_days: int,
        language: str,
    ) -> tuple[list[WebSearchResult], bool]:
        records = [
            WebSearchResult(
                title="清华大学近期 AI 讲座与学术活动",
                url="https://www.tsinghua.edu.cn/mock/ai-lecture",
                snippet="包含人工智能、大模型、机器学习相关讲座的时间、地点与报名方式。",
                source="mock",
                score=0.91,
            ),
            WebSearchResult(
                title="OpenTHU Agent-Core 搜索 Skill 设计说明",
                url="https://github.com/thu-info-community/thu-info-app",
                snippet="搜索引擎检索、网页正文抽取、轻量 RAG 召回和带引用总结。",
                source="mock",
                score=0.72,
            ),
            WebSearchResult(
                title="校园资讯与活动信息整合",
                url="https://info.tsinghua.edu.cn/mock/campus",
                snippet="校园动态、报名活动、论坛与招聘通知的统一检索入口。",
                source="mock",
                score=0.65,
            ),
        ]
        return records[:max_results], True


class SearxngSearchProvider(SearchProvider):
    name = "searxng"

    def __init__(self, endpoint: str) -> None:
        self.endpoint = endpoint.rstrip("/")

    def search(
        self,
        *,
        query: str,
        max_results: int,
        domains: list[str],
        freshness_days: int,
        language: str,
    ) -> tuple[list[WebSearchResult], bool]:
        scoped_query = _with_domain_filters(query, domains)
        cache_key = _cache_key("searxng", scoped_query, max_results, freshness_days, language)
        cached = _read_cache(cache_key)
        if cached:
            return [_result_from_dict(item) for item in cached.get("results", [])], True

        url = f"{self.endpoint}/search?{urlencode({'q': scoped_query, 'format': 'json', 'language': language})}"
        payload = _json_request(url)
        rows = payload.get("results", []) if isinstance(payload, dict) else []
        results = [
            WebSearchResult(
                title=_clean_text(row.get("title", "")),
                url=str(row.get("url", "")).strip(),
                snippet=_clean_text(row.get("content", "")),
                source="searxng",
                score=float(row.get("score", 0) or 0),
            )
            for row in rows
            if isinstance(row, dict) and row.get("url")
        ][:max_results]
        _write_cache(cache_key, {"results": [asdict(item) for item in results]})
        return results, False


class BraveSearchProvider(SearchProvider):
    name = "brave"

    def __init__(self, api_key: str, endpoint: str = "https://api.search.brave.com/res/v1/web/search") -> None:
        self.api_key = api_key
        self.endpoint = endpoint

    def search(
        self,
        *,
        query: str,
        max_results: int,
        domains: list[str],
        freshness_days: int,
        language: str,
    ) -> tuple[list[WebSearchResult], bool]:
        scoped_query = _with_domain_filters(query, domains)
        cache_key = _cache_key("brave", scoped_query, max_results, freshness_days, language)
        cached = _read_cache(cache_key)
        if cached:
            return [_result_from_dict(item) for item in cached.get("results", [])], True

        params = {
            "q": scoped_query,
            "count": str(max_results),
            "search_lang": "zh" if language.lower().startswith("zh") else "en",
            "freshness": f"pd{freshness_days}",
        }
        payload = _json_request(
            f"{self.endpoint}?{urlencode(params)}",
            headers={"X-Subscription-Token": self.api_key, "Accept": "application/json"},
        )
        rows = payload.get("web", {}).get("results", []) if isinstance(payload, dict) else []
        results = [
            WebSearchResult(
                title=_clean_text(row.get("title", "")),
                url=str(row.get("url", "")).strip(),
                snippet=_clean_text(row.get("description", "")),
                source="brave",
                published_at=str(row.get("age", "") or ""),
                score=0.0,
            )
            for row in rows
            if isinstance(row, dict) and row.get("url")
        ][:max_results]
        _write_cache(cache_key, {"results": [asdict(item) for item in results]})
        return results, False


def _build_provider() -> SearchProvider:
    provider = os.getenv("OPENTHU_SEARCH_PROVIDER", "mock").strip().lower() or "mock"
    if provider == "mock":
        return MockSearchProvider()
    if provider == "searxng":
        endpoint = os.getenv("OPENTHU_SEARCH_ENDPOINT", "").strip()
        if not endpoint:
            raise SearchProviderError("OPENTHU_SEARCH_ENDPOINT is required for searxng provider")
        return SearxngSearchProvider(endpoint)
    if provider == "brave":
        api_key = os.getenv("OPENTHU_SEARCH_API_KEY", "").strip()
        if not api_key:
            raise SearchProviderError("OPENTHU_SEARCH_API_KEY is required for brave provider")
        endpoint = os.getenv("OPENTHU_SEARCH_ENDPOINT", "https://api.search.brave.com/res/v1/web/search").strip()
        return BraveSearchProvider(api_key, endpoint)
    raise SearchProviderError(f"Unsupported search provider `{provider}`")


def fetch_documents(results: list[WebSearchResult], warnings: list[str]) -> list[WebDocument]:
    documents: list[WebDocument] = []
    for result in results:
        if result.source == "mock":
            documents.append(
                WebDocument(
                    title=result.title,
                    url=result.url,
                    text=f"{result.title}\n{result.snippet}",
                    snippet=result.snippet,
                    source=result.source,
                )
            )
            continue
        cached = _read_cache(_cache_key("document", result.url))
        if cached:
            documents.append(WebDocument(**cached))
            continue
        try:
            html = _text_request(result.url)
            text = extract_readable_text(html)
            if not text:
                warnings.append(f"No readable text extracted from {result.url}")
                continue
            document = WebDocument(
                title=result.title,
                url=result.url,
                text=text[:12000],
                snippet=result.snippet,
                source=result.source,
            )
            documents.append(document)
            _write_cache(_cache_key("document", result.url), asdict(document))
        except Exception as exc:
            warnings.append(f"Fetch failed for {result.url}: {exc}")
    return documents


def build_search_answer(
    query: str,
    results: list[WebSearchResult],
    documents: list[WebDocument],
    max_results: int,
) -> dict[str, Any]:
    chunks = []
    for document in documents:
        for idx, chunk in enumerate(chunk_document(document.text)):
            score = score_text(query, f"{document.title}\n{chunk}")
            if score > 0:
                chunks.append((score, document, idx, chunk))
    chunks.sort(key=lambda item: item[0], reverse=True)
    selected = chunks[: max(1, min(max_results, 5))]

    if not selected:
        citations = [
            {"title": item.title, "url": item.url, "snippet": item.snippet}
            for item in results[:max_results]
        ]
        answer = f"已检索到 {len(results)} 条与“{query}”相关的网页，但未能从页面正文中抽取到足够证据。"
        if citations:
            answer += f"可以先查看：{citations[0]['title']}。"
        return {"answer": answer, "citations": citations, "evidence": []}

    citations = []
    evidence = []
    seen_urls: set[str] = set()
    for score, document, idx, chunk in selected:
        if document.url not in seen_urls:
            citations.append({"title": document.title, "url": document.url, "snippet": document.snippet})
            seen_urls.add(document.url)
        evidence.append(
            {
                "title": document.title,
                "url": document.url,
                "chunk_index": idx,
                "score": round(score, 3),
                "text": chunk[:500],
            }
        )

    lead = citations[0]["title"] if citations else results[0].title if results else ""
    answer = f"根据检索到的网页证据，找到 {len(citations)} 个来源与“{query}”相关。"
    if lead:
        answer += f"优先参考：{lead}。"
    if len(citations) > 1:
        answer += "其他相关来源包括：" + "；".join(item["title"] for item in citations[1:3]) + "。"
    return {"answer": answer, "citations": citations, "evidence": evidence}


def chunk_document(text: str, chunk_size: int = 700, overlap: int = 120) -> list[str]:
    clean = _clean_text(text)
    if not clean:
        return []
    chunks = []
    start = 0
    while start < len(clean):
        end = min(len(clean), start + chunk_size)
        chunks.append(clean[start:end])
        if end >= len(clean):
            break
        start = max(start + 1, end - overlap)
    return chunks


def score_text(query: str, text: str) -> float:
    lower = text.lower()
    score = 0.0
    for term in query_terms(query):
        if term in lower:
            score += 1.0 + min(lower.count(term), 5) * 0.2
    return score


def query_terms(query: str) -> list[str]:
    normalized = query.lower()
    terms = [item for item in re.split(r"[\s,，。；;：:、？?！!（）()【】\[\]\"']+", normalized) if len(item) >= 2]
    phrase_bank = (
        "ai",
        "人工智能",
        "大模型",
        "机器学习",
        "校园",
        "活动",
        "讲座",
        "报名",
        "论坛",
        "搜索",
        "agent",
        "rag",
    )
    terms.extend(phrase for phrase in phrase_bank if phrase in normalized)
    compact = re.sub(r"[^\u4e00-\u9fffA-Za-z0-9]+", "", query)
    if len(compact) <= 16:
        terms.extend(compact[idx : idx + size].lower() for size in (2, 3) for idx in range(0, max(len(compact) - size + 1, 0)))
    return list(dict.fromkeys(term for term in terms if term))


def extract_readable_text(html: str) -> str:
    text = re.sub(r"(?is)<(script|style|noscript|svg).*?>.*?</\1>", " ", html)
    text = re.sub(r"(?is)<br\s*/?>", "\n", text)
    text = re.sub(r"(?is)</(p|div|li|h1|h2|h3|tr)>", "\n", text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    return _clean_text(unescape(text))


def _clean_text(text: Any) -> str:
    return re.sub(r"\s+", " ", unescape(str(text or ""))).strip()


def _json_request(url: str, headers: dict[str, str] | None = None) -> dict[str, Any]:
    raw = _text_request(url, headers=headers)
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SearchProviderError(f"Search provider returned non-JSON response: {exc}") from exc
    if not isinstance(parsed, dict):
        raise SearchProviderError("Search provider returned unexpected JSON shape")
    return parsed


def _text_request(url: str, headers: dict[str, str] | None = None) -> str:
    request_headers = {"User-Agent": USER_AGENT}
    request_headers.update(headers or {})
    request = Request(url, headers=request_headers)
    try:
        with build_opener().open(request, timeout=12) as response:
            raw = response.read(1_500_000)
            content_type = response.headers.get("Content-Type", "")
    except HTTPError as exc:
        raise SearchProviderError(f"HTTP {exc.code} for {url}") from exc
    except URLError as exc:
        raise SearchProviderError(str(exc)) from exc
    charset = "utf-8"
    match = re.search(r"charset=([^;\s]+)", content_type, re.I)
    if match:
        charset = match.group(1)
    try:
        return raw.decode(charset, errors="replace")
    except LookupError:
        return raw.decode("utf-8", errors="replace")


def _with_domain_filters(query: str, domains: list[str]) -> str:
    if not domains:
        return query
    filters = " OR ".join(f"site:{domain}" for domain in domains)
    return f"({query}) ({filters})"


def _result_from_dict(item: dict[str, Any]) -> WebSearchResult:
    return WebSearchResult(
        title=str(item.get("title", "")),
        url=str(item.get("url", "")),
        snippet=str(item.get("snippet", "")),
        source=str(item.get("source", "web")),
        published_at=str(item.get("published_at", "")),
        score=float(item.get("score", 0) or 0),
    )


def _cache_dir() -> Path:
    return Path(os.getenv("OPENTHU_SEARCH_CACHE_DIR", "/tmp/openthu_search_cache"))


def _cache_key(*parts: Any) -> str:
    payload = json.dumps(parts, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _cache_path(key: str) -> Path:
    return _cache_dir() / f"{key}.json"


def _read_cache(key: str) -> dict[str, Any] | None:
    path = _cache_path(key)
    if not path.exists():
        return None
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None
    cached_at = str(loaded.get("cached_at", ""))
    try:
        cached_time = datetime.fromisoformat(cached_at)
    except ValueError:
        return None
    ttl = int(os.getenv("OPENTHU_SEARCH_CACHE_TTL_SECONDS", str(DEFAULT_CACHE_TTL_SECONDS)))
    if cached_time + timedelta(seconds=ttl) < datetime.now(timezone.utc):
        return None
    data = loaded.get("data")
    return data if isinstance(data, dict) else None


def _write_cache(key: str, data: dict[str, Any]) -> None:
    try:
        path = _cache_path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"cached_at": _utc_now(), "data": data}, ensure_ascii=False), encoding="utf-8")
    except Exception:
        pass
