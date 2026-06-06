from __future__ import annotations

import re
from copy import deepcopy
from dataclasses import replace
from datetime import datetime, timezone
from typing import Any

try:
    from .skill_core import SkillInvocation, SkillRegistry, SkillSpec, build_default_registry
except ImportError:
    from skill_core import SkillInvocation, SkillRegistry, SkillSpec, build_default_registry


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class SkillManager:
    """Unified manager between agent core and concrete skill handlers."""

    def __init__(self, registry: SkillRegistry | None = None) -> None:
        self.registry = registry or build_default_registry()

    def get_spec(self, skill_name: str) -> SkillSpec | None:
        return self.registry.get_spec(skill_name)

    def list_for_planner(self) -> list[dict[str, Any]]:
        planner_view: list[dict[str, Any]] = []
        for spec in self.registry.list_specs():
            item = spec.to_planner_dict()
            if not item.get("args_json_schema"):
                derived = self._derive_json_schema_from_legacy(spec.args_schema)
                if derived:
                    item["args_json_schema"] = derived
            planner_view.append(item)
        return planner_view

    def get_arg_json_schema(self, skill_name: str) -> dict[str, Any]:
        spec = self.get_spec(skill_name)
        if spec is None:
            return {}
        if isinstance(spec.args_json_schema, dict) and spec.args_json_schema:
            return deepcopy(spec.args_json_schema)
        return self._derive_json_schema_from_legacy(spec.args_schema)

    def validate_and_normalize_args(
        self,
        skill_name: str,
        args: dict[str, Any] | Any,
    ) -> tuple[dict[str, Any], list[str], list[str]]:
        if not isinstance(args, dict):
            return {}, ["args must be an object"], []
        schema = self.get_arg_json_schema(skill_name)
        if not schema:
            return dict(args), [], []
        return self._validate_args_by_schema(args, schema)

    def execute(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> dict[str, Any]:
        normalized_args, arg_errors, arg_warnings = self.validate_and_normalize_args(
            invocation.skill_name,
            invocation.args,
        )
        if arg_errors:
            return self._normalize_result(
                invocation,
                {
                    "skill_name": invocation.skill_name,
                    "request_id": invocation.request_id,
                    "code": "INVALID_PARAM",
                    "data": {
                        "status": "invalid_param",
                        "message": "schema validation failed for skill args",
                        "errors": arg_errors,
                        "warnings": arg_warnings,
                    },
                    "from_cache": False,
                    "fetched_at": _utc_now(),
                    "source": "skill_manager_schema",
                },
            )

        invocation_for_handler = replace(invocation, args=normalized_args)
        handler = self.registry.get_handler(invocation.skill_name)
        try:
            raw_result = handler.invoke(invocation_for_handler, session, state)
            if hasattr(raw_result, "to_dict"):
                result = raw_result.to_dict()
            elif isinstance(raw_result, dict):
                result = dict(raw_result)
            else:
                raise TypeError("Skill handler returned non-dict result")
        except Exception as exc:
            result = {
                "skill_name": invocation.skill_name,
                "request_id": invocation.request_id,
                "code": "SKILL_EXECUTION_FAILED",
                "data": {
                    "status": "handler_error",
                    "message": f"Skill handler raised an exception: {exc}",
                },
                "from_cache": False,
                "fetched_at": _utc_now(),
                "source": "skill_manager",
            }

        return self._normalize_result(invocation, result)

    def _derive_json_schema_from_legacy(self, args_schema: dict[str, str]) -> dict[str, Any]:
        if not isinstance(args_schema, dict) or not args_schema:
            return {}
        properties: dict[str, Any] = {}
        required: list[str] = []
        for arg_name, hint_raw in args_schema.items():
            name = str(arg_name).strip()
            if not name:
                continue
            hint = str(hint_raw or "").strip()
            properties[name] = self._infer_field_schema_from_hint(hint)
            if self._is_required_hint(hint):
                required.append(name)
        if not properties:
            return {}
        return {
            "type": "object",
            "properties": properties,
            "required": required,
            "additionalProperties": True,
        }

    def _infer_field_schema_from_hint(self, hint: str) -> dict[str, Any]:
        lower_hint = hint.lower()
        field_type = "string"

        list_match = re.search(r"list\[(.*?)\]", lower_hint)
        if list_match:
            inner = list_match.group(1).strip() or "string"
            item_type = self._map_type_name(inner)
            return {
                "type": "array",
                "items": {"type": item_type},
            }

        if any(token in lower_hint for token in {"bool", "boolean"}):
            field_type = "boolean"
        elif any(token in lower_hint for token in {"integer", " int"}):
            field_type = "integer"
        elif any(token in lower_hint for token in {"number", "float", "double"}):
            field_type = "number"

        schema: dict[str, Any] = {"type": field_type}

        enum_values = self._extract_enum_candidates(hint)
        if enum_values:
            schema["enum"] = enum_values

        return schema

    def _extract_enum_candidates(self, hint: str) -> list[str]:
        if "|" not in hint:
            return []
        raw_parts = [part.strip() for part in hint.split("|")]
        candidates: list[str] = []
        for part in raw_parts:
            token = re.sub(r"\s*\(.*?\)\s*$", "", part).strip()
            if not token:
                continue
            if re.match(r"^[A-Za-z0-9_.:-]+$", token):
                candidates.append(token)
        deduped: list[str] = []
        seen: set[str] = set()
        for item in candidates:
            if item in seen:
                continue
            seen.add(item)
            deduped.append(item)
        return deduped if len(deduped) >= 2 else []

    def _map_type_name(self, token: str) -> str:
        normalized = token.strip().lower()
        if normalized in {"str", "string", "text", "datetime", "date"}:
            return "string"
        if normalized in {"bool", "boolean"}:
            return "boolean"
        if normalized in {"int", "integer", "long"}:
            return "integer"
        if normalized in {"float", "double", "number"}:
            return "number"
        return "string"

    def _is_required_hint(self, hint: str) -> bool:
        lowered = hint.lower()
        return "required" in lowered and "optional" not in lowered

    def _validate_args_by_schema(
        self,
        args: dict[str, Any],
        schema: dict[str, Any],
    ) -> tuple[dict[str, Any], list[str], list[str]]:
        errors: list[str] = []
        warnings: list[str] = []
        if schema.get("type") not in {None, "object"}:
            return dict(args), errors, warnings

        properties = schema.get("properties", {})
        if not isinstance(properties, dict):
            properties = {}

        required_raw = schema.get("required", [])
        required = [str(item).strip() for item in required_raw if str(item).strip()]
        additional_allowed = bool(schema.get("additionalProperties", True))

        normalized: dict[str, Any] = {}
        seen_fields: set[str] = set()

        for key, value in args.items():
            key_str = str(key)
            field_schema = properties.get(key_str)
            if field_schema is None:
                if additional_allowed:
                    normalized[key_str] = value
                    warnings.append(f"unknown field `{key_str}` was kept because additionalProperties=true")
                else:
                    errors.append(f"unknown field `{key_str}`")
                continue
            seen_fields.add(key_str)
            coerced, err = self._coerce_value_by_schema(key_str, value, field_schema)
            if err:
                errors.append(err)
                continue
            normalized[key_str] = coerced

        for field in required:
            if field not in seen_fields:
                errors.append(f"missing required field `{field}`")
                continue
            if field not in normalized:
                continue
            if self._is_empty_value(normalized[field]):
                errors.append(f"required field `{field}` cannot be empty")

        return normalized, errors, warnings

    def _coerce_value_by_schema(
        self,
        field_name: str,
        value: Any,
        schema: dict[str, Any],
    ) -> tuple[Any, str | None]:
        expected_type = schema.get("type")
        coerced: Any = value

        if expected_type == "string":
            coerced = value if isinstance(value, str) else str(value)
        elif expected_type == "boolean":
            parsed = self._coerce_bool(value)
            if parsed is None:
                return None, f"field `{field_name}` expects boolean"
            coerced = parsed
        elif expected_type == "integer":
            parsed_int = self._coerce_int(value)
            if parsed_int is None:
                return None, f"field `{field_name}` expects integer"
            coerced = parsed_int
        elif expected_type == "number":
            parsed_number = self._coerce_number(value)
            if parsed_number is None:
                return None, f"field `{field_name}` expects number"
            coerced = parsed_number
        elif expected_type == "array":
            item_schema = schema.get("items", {"type": "string"})
            raw_items: list[Any]
            if isinstance(value, list):
                raw_items = value
            elif isinstance(value, tuple):
                raw_items = list(value)
            elif isinstance(value, str):
                text = value.strip()
                raw_items = [part.strip() for part in text.split(",")] if "," in text else [text]
            else:
                raw_items = [value]

            items: list[Any] = []
            for idx, item in enumerate(raw_items):
                coerced_item, err = self._coerce_value_by_schema(
                    f"{field_name}[{idx}]",
                    item,
                    item_schema if isinstance(item_schema, dict) else {"type": "string"},
                )
                if err:
                    return None, err
                items.append(coerced_item)
            coerced = items

        enum_values = schema.get("enum")
        if isinstance(enum_values, list) and enum_values:
            if coerced not in enum_values:
                return None, f"field `{field_name}` must be one of {enum_values}"

        pattern = schema.get("pattern")
        if expected_type == "string" and isinstance(pattern, str) and pattern:
            if re.fullmatch(pattern, str(coerced)) is None:
                return None, f"field `{field_name}` does not match required pattern {pattern!r}"

        return coerced, None

    def _coerce_bool(self, value: Any) -> bool | None:
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            lowered = value.strip().lower()
            if lowered in {"1", "true", "yes", "y", "on"}:
                return True
            if lowered in {"0", "false", "no", "n", "off"}:
                return False
            return None
        if isinstance(value, int) and not isinstance(value, bool):
            if value in {0, 1}:
                return bool(value)
        return None

    def _coerce_int(self, value: Any) -> int | None:
        if isinstance(value, int) and not isinstance(value, bool):
            return value
        if isinstance(value, str):
            text = value.strip()
            if re.match(r"^-?\d+$", text):
                return int(text)
        return None

    def _coerce_number(self, value: Any) -> float | None:
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
        if isinstance(value, str):
            text = value.strip()
            try:
                return float(text)
            except ValueError:
                return None
        return None

    def _is_empty_value(self, value: Any) -> bool:
        if value is None:
            return True
        if isinstance(value, str):
            return not value.strip()
        if isinstance(value, (list, dict, tuple, set)):
            return len(value) == 0
        return False

    def _normalize_result(
        self,
        invocation: SkillInvocation,
        result: dict[str, Any],
    ) -> dict[str, Any]:
        normalized = dict(result)
        normalized["skill_name"] = invocation.skill_name
        normalized["request_id"] = invocation.request_id

        if not isinstance(normalized.get("code"), str) or not normalized["code"]:
            normalized["code"] = "SKILL_EXECUTION_FAILED"

        data = normalized.get("data", {})
        if not isinstance(data, dict):
            data = {
                "status": "handler_error",
                "message": "Skill returned invalid data payload",
            }
        normalized["data"] = data

        normalized["from_cache"] = bool(normalized.get("from_cache", False))

        fetched_at = normalized.get("fetched_at")
        if not isinstance(fetched_at, str) or not fetched_at.strip():
            normalized["fetched_at"] = _utc_now()

        source = normalized.get("source")
        if not isinstance(source, str) or not source.strip():
            normalized["source"] = "skill_manager"

        return normalized
