#!/usr/bin/env python3
"""Validate Home Search event contracts without network-installed dependencies."""

from __future__ import annotations

import argparse
import json
import re
import tempfile
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any


EXPECTED_TOPICS = {
    "property.trade-events.v1": (6, {"TradeNormalized", "TradeCanceled"}),
    "property.complex-events.v1": (6, {"ComplexChanged"}),
    "property.insight-events.v1": (3, {"InsightPublished", "NewsSnapshotPublished"}),
    "user.delivery-events.v1": (
        3,
        {"InboxCreated", "EmailRequested", "EmailSuppressed"},
    ),
    "ai.dataset-events.v1": (3, {"DatasetActivated", "DatasetQuarantined"}),
}
EXPECTED_PAYLOAD_ENUMS = {
    "InsightPublished": {
        "insightKind": {"DAILY", "WEEKLY", "ROLLING_7D"},
    },
}
COMMON_REQUIRED = {
    "eventId",
    "eventType",
    "schemaVersion",
    "occurredAt",
    "producer",
    "aggregateType",
    "aggregateId",
    "aggregateVersion",
    "correlationId",
    "causationId",
    "traceId",
    "payload",
}
FORBIDDEN_NAME_FRAGMENTS = {
    "rawpayload",
    "sourcekey",
    "email",
    "token",
    "prompt",
    "answer",
    "credential",
    "password",
    "privatekey",
    "servicekey",
}


class ContractError(ValueError):
    pass


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(f"{path}: invalid JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise ContractError(f"{path}: root must be an object")
    return value


def normalized_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def reject_forbidden_names(value: Any, path: str) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = normalized_name(key)
            if any(fragment in normalized for fragment in FORBIDDEN_NAME_FRAGMENTS):
                raise ContractError(f"{path}: forbidden field name {key}")
            reject_forbidden_names(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_forbidden_names(child, f"{path}[{index}]")


def validate_scalar(value: Any, schema: dict[str, Any], path: str) -> None:
    expected_type = schema.get("type")
    if isinstance(expected_type, list):
        if value is None and "null" in expected_type:
            return
        non_null = [item for item in expected_type if item != "null"]
        if len(non_null) != 1:
            raise ContractError(f"{path}: unsupported union type")
        expected_type = non_null[0]
    type_matches = {
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }
    if expected_type in type_matches and not type_matches[expected_type]:
        raise ContractError(f"{path}: expected {expected_type}")
    if "const" in schema and value != schema["const"]:
        raise ContractError(f"{path}: expected const {schema['const']!r}")
    if "enum" in schema and value not in schema["enum"]:
        raise ContractError(f"{path}: value is outside enum")
    if isinstance(value, int) and "minimum" in schema and value < schema["minimum"]:
        raise ContractError(f"{path}: value is below minimum")
    if isinstance(value, str):
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            raise ContractError(f"{path}: value exceeds maxLength")
        if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
            raise ContractError(f"{path}: value does not match pattern")
        if schema.get("format") == "uuid":
            try:
                uuid.UUID(value)
            except ValueError as exc:
                raise ContractError(f"{path}: invalid UUID") from exc
        if schema.get("format") == "date-time":
            try:
                parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ContractError(f"{path}: invalid date-time") from exc
            if parsed.utcoffset() != timedelta(0):
                raise ContractError(f"{path}: date-time must use UTC")


def validate_value(value: Any, schema: dict[str, Any], path: str) -> None:
    expected_type = schema.get("type")
    if isinstance(expected_type, list) and value is None and "null" in expected_type:
        return
    if expected_type == "object":
        if not isinstance(value, dict):
            raise ContractError(f"{path}: expected object")
        properties = schema.get("properties", {})
        required = set(schema.get("required", []))
        missing = required - set(value)
        if missing:
            raise ContractError(f"{path}: missing required fields {sorted(missing)}")
        if schema.get("additionalProperties") is False:
            unexpected = set(value) - set(properties)
            if unexpected:
                raise ContractError(f"{path}: unexpected fields {sorted(unexpected)}")
        for key, child in value.items():
            if key in properties:
                validate_value(child, properties[key], f"{path}.{key}")
        return
    if expected_type == "array":
        if not isinstance(value, list):
            raise ContractError(f"{path}: expected array")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            raise ContractError(f"{path}: too many items")
        for index, child in enumerate(value):
            validate_value(child, schema.get("items", {}), f"{path}[{index}]")
        return
    validate_scalar(value, schema, path)


def validate_schema(schema: dict[str, Any], path: Path) -> str:
    if schema.get("$schema") != "http://json-schema.org/draft-07/schema#":
        raise ContractError(f"{path}: Glue-compatible JSON Schema Draft-07 is required")
    if not str(schema.get("$id", "")).startswith("https://home-search.internal/events/"):
        raise ContractError(f"{path}: stable internal $id is required")
    if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
        raise ContractError(f"{path}: closed object schema is required")
    required = set(schema.get("required", []))
    if required != COMMON_REQUIRED:
        raise ContractError(f"{path}: envelope required fields do not match v1")
    properties = schema.get("properties")
    if not isinstance(properties, dict) or set(properties) != COMMON_REQUIRED:
        raise ContractError(f"{path}: envelope properties do not match v1")
    event_type = properties.get("eventType", {}).get("const")
    if not isinstance(event_type, str) or not event_type:
        raise ContractError(f"{path}: eventType const is required")
    if properties.get("schemaVersion", {}).get("const") != 1:
        raise ContractError(f"{path}: schemaVersion must be const 1")
    payload = properties.get("payload", {})
    if payload.get("type") != "object" or payload.get("additionalProperties") is not False:
        raise ContractError(f"{path}: payload must be a closed object")
    for field, expected_values in EXPECTED_PAYLOAD_ENUMS.get(event_type, {}).items():
        actual_values = set(payload.get("properties", {}).get(field, {}).get("enum", []))
        if actual_values != expected_values:
            raise ContractError(
                f"{path}: payload.{field} enum must be {sorted(expected_values)}"
            )
    reject_forbidden_names(schema, str(path))
    return event_type


def validate_topics(root: Path, schemas: dict[str, tuple[Path, dict[str, Any]]]) -> None:
    manifest = load_json(root / "topics.json")
    if manifest.get("version") != 1 or manifest.get("maxMessageBytes") != 262_144:
        raise ContractError("topics.json: version/maxMessageBytes policy mismatch")
    if manifest.get("mainRetentionHours") != 336 or manifest.get("dlqRetentionHours") != 720:
        raise ContractError("topics.json: retention policy mismatch")
    topics = manifest.get("topics")
    if not isinstance(topics, list):
        raise ContractError("topics.json: topics must be an array")
    actual_names = {topic.get("name") for topic in topics if isinstance(topic, dict)}
    if actual_names != set(EXPECTED_TOPICS):
        raise ContractError("topics.json: topic ownership set mismatch")
    covered_events: set[str] = set()
    for topic in topics:
        name = topic["name"]
        expected_partitions, expected_events = EXPECTED_TOPICS[name]
        if topic.get("partitions") != expected_partitions:
            raise ContractError(f"topics.json: {name} partition policy mismatch")
        if topic.get("autoDelete") is not False or topic.get("cleanupPolicy") != "delete":
            raise ContractError(f"topics.json: {name} deletion policy mismatch")
        events = topic.get("events")
        if not isinstance(events, list):
            raise ContractError(f"topics.json: {name} events must be an array")
        names = {event.get("eventType") for event in events if isinstance(event, dict)}
        if names != expected_events:
            raise ContractError(f"topics.json: {name} event ownership mismatch")
        for event in events:
            event_type = event["eventType"]
            schema_path = root / event["schema"]
            if event_type not in schemas or schemas[event_type][0] != schema_path:
                raise ContractError(f"topics.json: {event_type} schema mapping mismatch")
            covered_events.add(event_type)
    if covered_events != set(schemas):
        raise ContractError("topics.json: every schema must be owned by one topic")


def compatibility_errors(old: dict[str, Any], new: dict[str, Any], path: str) -> list[str]:
    errors: list[str] = []
    if old.get("type") != new.get("type"):
        errors.append(f"{path}: type changed")
        return errors
    if "const" in new and old.get("const") != new.get("const"):
        errors.append(f"{path}: const changed")
    if "format" in new and old.get("format") != new.get("format"):
        errors.append(f"{path}: format changed")
    old_enum = set(old.get("enum", []))
    new_enum = set(new.get("enum", []))
    if new_enum and (not old_enum or not old_enum.issubset(new_enum)):
        errors.append(f"{path}: enum was narrowed")
    old_max_length = old.get("maxLength")
    new_max_length = new.get("maxLength")
    if new_max_length is not None and (
        old_max_length is None or new_max_length < old_max_length
    ):
        errors.append(f"{path}: maxLength was narrowed")
    old_minimum = old.get("minimum")
    new_minimum = new.get("minimum")
    if new_minimum is not None and (
        old_minimum is None or new_minimum > old_minimum
    ):
        errors.append(f"{path}: minimum was narrowed")
    old_maximum = old.get("maximum")
    new_maximum = new.get("maximum")
    if new_maximum is not None and (
        old_maximum is None or new_maximum < old_maximum
    ):
        errors.append(f"{path}: maximum was narrowed")
    old_min_length = old.get("minLength")
    new_min_length = new.get("minLength")
    if new_min_length is not None and (
        old_min_length is None or new_min_length > old_min_length
    ):
        errors.append(f"{path}: minLength was narrowed")
    old_max_items = old.get("maxItems")
    new_max_items = new.get("maxItems")
    if new_max_items is not None and (
        old_max_items is None or new_max_items < old_max_items
    ):
        errors.append(f"{path}: maxItems was narrowed")
    if "pattern" in new and old.get("pattern") != new.get("pattern"):
        errors.append(f"{path}: pattern changed")
    if old.get("additionalProperties") is not False and new.get(
        "additionalProperties"
    ) is False:
        errors.append(f"{path}: additional properties were closed")

    old_required = set(old.get("required", []))
    new_required = set(new.get("required", []))
    if not new_required.issubset(old_required):
        errors.append(f"{path}: new required fields reject old messages")
    old_properties = old.get("properties", {})
    new_properties = new.get("properties", {})
    for name, old_property in old_properties.items():
        if name not in new_properties:
            errors.append(f"{path}.{name}: existing property was removed")
            continue
        new_property = new_properties[name]
        errors.extend(compatibility_errors(old_property, new_property, f"{path}.{name}"))
    if old.get("type") == "array" and isinstance(old.get("items"), dict):
        new_items = new.get("items")
        if not isinstance(new_items, dict):
            errors.append(f"{path}: array items schema was removed")
        else:
            errors.extend(compatibility_errors(old["items"], new_items, f"{path}[]"))
    return errors


def validate_contracts(root: Path, baseline: Path | None = None) -> None:
    schema_dir = root / "schemas"
    example_dir = root / "examples"
    schema_files = sorted(schema_dir.glob("*.schema.json"))
    if not schema_files:
        raise ContractError(f"{schema_dir}: no event schemas")
    schemas: dict[str, tuple[Path, dict[str, Any]]] = {}
    for path in schema_files:
        schema = load_json(path)
        event_type = validate_schema(schema, path)
        if event_type in schemas:
            raise ContractError(f"{path}: duplicate eventType {event_type}")
        schemas[event_type] = (path, schema)
        example_path = example_dir / f"{event_type}.json"
        example = load_json(example_path)
        reject_forbidden_names(example, str(example_path))
        validate_value(example, schema, str(example_path))
        encoded = json.dumps(example, ensure_ascii=False, separators=(",", ":")).encode()
        if len(encoded) > 262_144:
            raise ContractError(f"{example_path}: example exceeds 256KiB")
    validate_topics(root, schemas)
    if baseline is not None:
        for event_type, (path, schema) in schemas.items():
            baseline_path = baseline / "schemas" / path.name
            if not baseline_path.exists():
                continue
            old = load_json(baseline_path)
            errors = compatibility_errors(old, schema, event_type)
            if errors:
                raise ContractError("; ".join(errors))


def self_test() -> None:
    old = {
        "type": "object",
        "required": ["id"],
        "properties": {"id": {"type": "string"}},
    }
    incompatible = {
        "type": "object",
        "required": ["id", "email"],
        "properties": {
            "id": {"type": "string"},
            "email": {"type": "string"},
        },
    }
    if not compatibility_errors(old, incompatible, "Fixture"):
        raise ContractError("self-test: incompatible required field was accepted")
    nested_old = {
        "type": "object",
        "required": ["payload"],
        "additionalProperties": False,
        "properties": {
            "payload": {
                "type": "object",
                "required": ["status"],
                "additionalProperties": False,
                "properties": {
                    "status": {"type": "string", "enum": ["ACTIVE", "QUARANTINED"]}
                },
            }
        },
    }
    nested_incompatible = json.loads(json.dumps(nested_old))
    nested_incompatible["properties"]["payload"]["properties"]["status"]["enum"] = [
        "ACTIVE"
    ]
    if not compatibility_errors(nested_old, nested_incompatible, "NestedFixture"):
        raise ContractError("self-test: nested enum narrowing was accepted")
    with tempfile.TemporaryDirectory() as directory:
        forbidden = Path(directory) / "forbidden.json"
        forbidden.write_text(
            '{"payload":{"providerAccessToken":"must-not-pass"}}',
            encoding="utf-8",
        )
        try:
            reject_forbidden_names(load_json(forbidden), str(forbidden))
        except ContractError:
            pass
        else:
            raise ContractError("self-test: forbidden event field was accepted")
    try:
        validate_scalar(
            "2026-07-24T12:00:00+09:00",
            {"type": "string", "format": "date-time"},
            "UtcFixture",
        )
    except ContractError:
        pass
    else:
        raise ContractError("self-test: non-UTC event timestamp was accepted")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
        else:
            if args.root is None:
                parser.error("root is required unless --self-test is used")
            validate_contracts(args.root, args.baseline)
    except ContractError as exc:
        print(f"상태: Fail - {exc}")
        return 1
    print("상태: Pass - event contract validation")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
