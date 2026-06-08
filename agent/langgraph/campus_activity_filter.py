from __future__ import annotations

import json
import re
from datetime import date
from typing import Any, Callable


LlmRanker = Callable[[dict[str, Any]], dict[str, Any]]


class CampusActivityFilterAgent:
    """Internal post-processor bound to get_campus_activities results."""

    topic_terms = (
        "ai",
        "人工智能",
        "大模型",
        "机器学习",
        "讲座",
        "论坛",
        "沙龙",
        "比赛",
        "音乐",
        "艺术",
        "展览",
        "体育",
        "创业",
        "就业",
        "招聘",
        "志愿",
        "心理",
        "文化",
        "报名",
    )

    def filter_result(
        self,
        result: dict[str, Any],
        *,
        args: dict[str, Any],
        user_input: str,
        memory_context: Any,
        llm_ranker: LlmRanker | None = None,
    ) -> tuple[dict[str, Any], bool]:
        if str(result.get("skill_name", "")) != "get_campus_activities" or result.get("code") != "OK":
            return result, False
        data = result.get("data", {})
        if not isinstance(data, dict):
            return result, False

        candidates = data.get("candidate_activities")
        if not isinstance(candidates, list) or not candidates:
            candidates = data.get("activities", [])
        if not isinstance(candidates, list) or not candidates:
            return self._with_filter_metadata(
                result,
                status="skipped",
                source="campus_activity_filter",
                summary="没有可供筛选的校园活动候选。",
                selected=[],
                candidate_count=0,
                hard_constraints=self._hard_constraints(args, data),
                preference_signals=[],
            ), True

        normalized = self._normalize_candidates(candidates)
        limit = self._coerce_limit(args.get("limit") or data.get("count"), default=10)
        hard_constraints = self._hard_constraints(args, data)
        hard_filtered = self._apply_hard_constraints(normalized, hard_constraints, user_input)
        preference_signals = self._preference_signals(user_input, memory_context)
        rule_ranked = self._rule_rank(hard_filtered, user_input, preference_signals)

        filter_status = "ok"
        filter_source = "campus_activity_filter_rules"
        filter_summary = "已按明确条件和候选活动信息完成规则筛选。"
        selected = rule_ranked[:limit]

        llm_payload = self._llm_payload(rule_ranked, user_input, memory_context, hard_constraints, preference_signals, limit)
        if rule_ranked and llm_ranker is not None and self._should_use_llm(rule_ranked, limit, user_input, memory_context):
            try:
                llm_result = llm_ranker(llm_payload)
                llm_selected = self._select_by_llm_ids(rule_ranked, llm_result, limit)
                if llm_selected:
                    selected = llm_selected
                    filter_source = "campus_activity_filter_llm"
                    filter_summary = str(llm_result.get("summary") or "已结合语义要求和记忆偏好完成排序。").strip()
                    llm_signals = llm_result.get("preference_signals")
                    if isinstance(llm_signals, list):
                        preference_signals = [str(item) for item in llm_signals if str(item).strip()][:8]
                else:
                    filter_status = "fallback"
                    filter_summary = "LLM 未返回有效活动 ID，已回退到规则排序。"
            except Exception as exc:
                filter_status = "fallback"
                filter_summary = f"LLM 筛选不可用，已回退到规则排序：{str(exc)[:160]}"

        if not hard_filtered:
            filter_status = "ok"
            filter_source = "campus_activity_filter_rules"
            filter_summary = "明确筛选条件下没有匹配的校园活动。"
            selected = []

        return self._with_filter_metadata(
            result,
            status=filter_status,
            source=filter_source,
            summary=filter_summary,
            selected=selected,
            candidate_count=len(normalized),
            hard_constraints=hard_constraints,
            preference_signals=preference_signals,
        ), True

    def _with_filter_metadata(
        self,
        result: dict[str, Any],
        *,
        status: str,
        source: str,
        summary: str,
        selected: list[dict[str, Any]],
        candidate_count: int,
        hard_constraints: dict[str, Any],
        preference_signals: list[str],
    ) -> dict[str, Any]:
        updated = dict(result)
        data = dict(result.get("data", {}) if isinstance(result.get("data"), dict) else {})
        data["activities"] = selected
        data["count"] = len(selected)
        data["filter_status"] = status
        data["filter_source"] = source
        data["filter_summary"] = summary
        data["selected_count"] = len(selected)
        data["discarded_count"] = max(0, candidate_count - len(selected))
        data["candidate_count"] = candidate_count
        data["hard_constraints"] = hard_constraints
        data["preference_signals"] = preference_signals
        data["summary"] = self._summary_from_selected(selected, summary)
        data["answer"] = data["summary"]
        updated["data"] = data
        updated["message"] = data["summary"]
        updated["source"] = data.get("source") or updated.get("source", "")
        return updated

    def _normalize_candidates(self, candidates: list[Any]) -> list[dict[str, Any]]:
        normalized: list[dict[str, Any]] = []
        seen: set[str] = set()
        for index, item in enumerate(candidates):
            if not isinstance(item, dict):
                continue
            activity = dict(item)
            activity_id = self._activity_id(activity, index)
            if activity_id in seen:
                continue
            seen.add(activity_id)
            activity["activity_id"] = activity_id
            normalized.append(activity)
        return normalized

    def _activity_id(self, activity: dict[str, Any], index: int) -> str:
        for key in ("activity_id", "id", "url"):
            value = str(activity.get(key, "")).strip()
            if value:
                return value
        title = str(activity.get("title", "")).strip()
        start_time = str(activity.get("start_time") or activity.get("time") or "").strip()
        return f"{title}:{start_time}" if title or start_time else f"candidate_{index}"

    def _hard_constraints(self, args: dict[str, Any], data: dict[str, Any]) -> dict[str, Any]:
        filter_input = data.get("filter_input", {})
        if not isinstance(filter_input, dict):
            filter_input = {}
        return {
            "keywords": self._as_list(args.get("keywords")),
            "start_date": str(args.get("start_date") or args.get("startDate") or filter_input.get("start_date") or "").strip(),
            "end_date": str(args.get("end_date") or args.get("endDate") or filter_input.get("end_date") or "").strip(),
            "exclude_evening": self._has_negative_time_request(str(args.get("query") or filter_input.get("query") or "")),
            "exclude_online": self._has_negative_online_request(str(args.get("query") or filter_input.get("query") or "")),
        }

    def _apply_hard_constraints(
        self,
        activities: list[dict[str, Any]],
        constraints: dict[str, Any],
        user_input: str,
    ) -> list[dict[str, Any]]:
        keywords = [item.lower() for item in self._as_list(constraints.get("keywords")) if item.strip()]
        start_bound = self._parse_date(str(constraints.get("start_date") or ""))
        end_bound = self._parse_date(str(constraints.get("end_date") or ""))
        exclude_evening = bool(constraints.get("exclude_evening")) or self._has_negative_time_request(user_input)
        exclude_online = bool(constraints.get("exclude_online")) or self._has_negative_online_request(user_input)
        filtered: list[dict[str, Any]] = []
        for activity in activities:
            haystack = self._haystack(activity)
            if keywords and not any(keyword in haystack for keyword in keywords):
                continue
            activity_date = self._parse_date(str(activity.get("start_time") or activity.get("time") or ""))
            if activity_date is not None:
                if start_bound is not None and activity_date < start_bound:
                    continue
                if end_bound is not None and activity_date > end_bound:
                    continue
            if exclude_evening and self._is_evening(activity):
                continue
            if exclude_online and ("线上" in haystack or "在线" in haystack or "zoom" in haystack):
                continue
            filtered.append(activity)
        return filtered

    def _rule_rank(
        self,
        activities: list[dict[str, Any]],
        user_input: str,
        preference_signals: list[str],
    ) -> list[dict[str, Any]]:
        query_terms = self._query_terms(user_input)
        preference_terms = self._query_terms(" ".join(preference_signals))

        def score(activity: dict[str, Any]) -> tuple[int, int, int, str, str]:
            haystack = self._haystack(activity)
            query_score = sum(3 for term in query_terms if term in haystack)
            preference_score = sum(1 for term in preference_terms if term in haystack)
            has_time = 0 if str(activity.get("start_time") or activity.get("time") or "").strip() else 1
            return (-query_score, -preference_score, has_time, str(activity.get("start_time") or ""), str(activity.get("title") or ""))

        return sorted(activities, key=score)

    def _llm_payload(
        self,
        activities: list[dict[str, Any]],
        user_input: str,
        memory_context: Any,
        hard_constraints: dict[str, Any],
        preference_signals: list[str],
        limit: int,
    ) -> dict[str, Any]:
        return {
            "user_input": user_input,
            "memory_context": memory_context,
            "hard_constraints": hard_constraints,
            "preference_signals": preference_signals,
            "limit": limit,
            "activities": [
                {
                    "activity_id": item.get("activity_id", ""),
                    "title": item.get("title", ""),
                    "start_time": item.get("start_time") or item.get("time", ""),
                    "location": item.get("location", ""),
                    "organizer": item.get("organizer", ""),
                    "category": item.get("category", ""),
                    "abstract": str(item.get("abstract", ""))[:240],
                }
                for item in activities[:50]
            ],
        }

    def _should_use_llm(self, activities: list[dict[str, Any]], limit: int, user_input: str, memory_context: Any) -> bool:
        if len(activities) > limit:
            return True
        text = f"{user_input}\n{self._memory_text(memory_context)}"
        return any(term in text for term in ("适合", "推荐", "偏好", "喜欢", "更想", "研究方向", "帮我挑", "值得"))

    def _select_by_llm_ids(
        self,
        activities: list[dict[str, Any]],
        llm_result: dict[str, Any],
        limit: int,
    ) -> list[dict[str, Any]]:
        raw_ids = llm_result.get("activity_ids") or llm_result.get("selected_activity_ids") or llm_result.get("ids")
        if not isinstance(raw_ids, list):
            return []
        by_id = {str(item.get("activity_id", "")): item for item in activities}
        selected: list[dict[str, Any]] = []
        seen: set[str] = set()
        for raw_id in raw_ids:
            activity_id = str(raw_id).strip()
            if activity_id in by_id and activity_id not in seen:
                selected.append(by_id[activity_id])
                seen.add(activity_id)
            if len(selected) >= limit:
                break
        for item in activities:
            if len(selected) >= limit:
                break
            activity_id = str(item.get("activity_id", ""))
            if activity_id not in seen:
                selected.append(item)
                seen.add(activity_id)
        return selected

    def _summary_from_selected(self, selected: list[dict[str, Any]], filter_summary: str) -> str:
        if not selected:
            return f"{filter_summary} 暂未找到符合条件的校园活动。"
        lines = [f"{filter_summary} 已筛选出 {len(selected)} 条校园活动："]
        for item in selected[:10]:
            title = str(item.get("title") or "未命名活动").strip()
            details = "，".join(
                part
                for part in [
                    str(item.get("start_time") or item.get("time") or "").strip(),
                    str(item.get("location") or "").strip(),
                ]
                if part
            )
            lines.append(f"- {title}" + (f"（{details}）" if details else ""))
        return "\n".join(lines)

    def _preference_signals(self, user_input: str, memory_context: Any) -> list[str]:
        text = f"{user_input}\n{self._memory_text(memory_context)}".lower()
        signals = [term for term in self.topic_terms if term.lower() in text]
        for pattern in (r"(喜欢|偏好|关注|研究|想看)([^。；;\n]{1,24})", r"(不喜欢|避免|不要)([^。；;\n]{1,24})"):
            for match in re.finditer(pattern, text):
                signals.append("".join(match.groups()))
        return list(dict.fromkeys(item.strip() for item in signals if item.strip()))[:8]

    def _memory_text(self, memory_context: Any) -> str:
        if isinstance(memory_context, str):
            return memory_context
        if isinstance(memory_context, dict):
            return json.dumps(memory_context, ensure_ascii=False)
        if isinstance(memory_context, list):
            return json.dumps(memory_context[:20], ensure_ascii=False)
        return ""

    def _query_terms(self, text: str) -> list[str]:
        terms = [item for item in re.split(r"[\s,，。；;：:、？?！!（）()【】\[\]\"']+", text.lower()) if len(item) >= 2]
        return list(dict.fromkeys(terms))[:16]

    def _haystack(self, activity: dict[str, Any]) -> str:
        return " ".join(
            str(activity.get(key, ""))
            for key in ("title", "abstract", "location", "organizer", "category", "source")
        ).lower()

    def _as_list(self, value: Any) -> list[str]:
        if isinstance(value, list):
            return [str(item).strip() for item in value if str(item).strip()]
        if isinstance(value, str):
            text = value.strip()
            if not text:
                return []
            if text.startswith("["):
                try:
                    loaded = json.loads(text)
                    if isinstance(loaded, list):
                        return [str(item).strip() for item in loaded if str(item).strip()]
                except Exception:
                    pass
            return [item.strip() for item in re.split(r"[,，、]", text) if item.strip()]
        return []

    def _coerce_limit(self, value: Any, *, default: int) -> int:
        try:
            return max(1, min(30, int(value)))
        except Exception:
            return default

    def _parse_date(self, text: str) -> date | None:
        if not text:
            return None
        for pattern in (r"(\d{4})年\s*(\d{1,2})月\s*(\d{1,2})日", r"(\d{4})[-/](\d{1,2})[-/](\d{1,2})"):
            match = re.search(pattern, text)
            if match:
                try:
                    return date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
                except ValueError:
                    return None
        match = re.search(r"\b(\d{4})(\d{2})(\d{2})\b", text)
        if match:
            try:
                return date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
            except ValueError:
                return None
        return None

    def _is_evening(self, activity: dict[str, Any]) -> bool:
        text = str(activity.get("start_time") or activity.get("time") or "")
        match = re.search(r"(\d{1,2}):\d{2}", text)
        if not match:
            return "晚上" in text or "晚间" in text
        return int(match.group(1)) >= 18

    def _has_negative_time_request(self, text: str) -> bool:
        return bool(re.search(r"(不要|不想|避免|排除|别).{0,8}(晚上|晚间|太晚)", text))

    def _has_negative_online_request(self, text: str) -> bool:
        return bool(re.search(r"(不要|不想|避免|排除|别).{0,8}(线上|在线|网课|zoom)", text, re.IGNORECASE))
