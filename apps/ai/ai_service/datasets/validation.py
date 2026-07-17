from __future__ import annotations

import json
import math
from datetime import date, datetime
from typing import Any

from .models import DatasetSourceContract, QualityIssue, StagedRow, ValidationOutcome


class RawPayloadError(ValueError):
    pass


def parse_rows(raw_bytes: bytes, encoding: str) -> list[dict[str, object]]:
    try:
        document = json.loads(raw_bytes.decode(encoding))
    except (UnicodeDecodeError, LookupError, json.JSONDecodeError) as exception:
        raise RawPayloadError("raw object is not valid source JSON") from exception
    if (
        not isinstance(document, dict)
        or set(document) != {"rows"}
        or not isinstance(document["rows"], list)
    ):
        raise RawPayloadError("raw object must contain exactly one rows array")
    if not all(isinstance(row, dict) for row in document["rows"]):
        raise RawPayloadError("every source row must be an object")
    return document["rows"]


def validate_rows(
    contract: DatasetSourceContract,
    rows: list[dict[str, object]],
    previous_active_row_count: int | None,
    *,
    source_date: date,
    collected_at: datetime,
) -> ValidationOutcome:
    staged: list[StagedRow] = []
    issues: list[QualityIssue] = []
    seen_keys: set[tuple[str, ...]] = set()

    for row_number, row in enumerate(rows, start=1):
        rejection_codes: list[str] = []
        missing = [field for field in contract.required_fields if _missing(row.get(field))]
        if missing:
            rejection_codes.append("REQUIRED_FIELD_MISSING")
        if not missing and _has_invalid_coordinate(row):
            rejection_codes.append("INVALID_COORDINATE")

        source_key: str | None = None
        if not missing:
            key = tuple(_canonical_key(row[field]) for field in contract.unique_key_fields)
            source_key = "\u001f".join(key)
            if key in seen_keys:
                rejection_codes.append("DUPLICATE_UNIQUE_KEY")
            else:
                seen_keys.add(key)

        accepted = not rejection_codes
        staged.append(
            StagedRow(
                row_number=row_number,
                row_data=dict(row),
                accepted=accepted,
                rejection_codes=tuple(rejection_codes),
                source_key=source_key if accepted else None,
            )
        )
        for reason_code in rejection_codes:
            issues.append(
                QualityIssue(
                    reason_code=reason_code,
                    severity="WARNING",
                    row_number=row_number,
                    details={"fields": missing} if reason_code == "REQUIRED_FIELD_MISSING" else {},
                )
            )

    raw_count = len(rows)
    rejected_count = sum(not row.accepted for row in staged)
    accepted_count = raw_count - rejected_count
    age_days = (collected_at.date() - source_date).days
    if age_days < 0:
        issues.append(
            QualityIssue(
                reason_code="SOURCE_DATE_IN_FUTURE",
                severity="BLOCKING",
                row_number=None,
                details={"sourceDate": source_date.isoformat()},
            )
        )
    elif age_days > contract.freshness_days:
        issues.append(
            QualityIssue(
                reason_code="DATASET_STALE",
                severity="BLOCKING",
                row_number=None,
                details={"ageDays": age_days, "freshnessDays": contract.freshness_days},
            )
        )
    if not contract.expected_min_rows <= raw_count <= contract.expected_max_rows:
        issues.append(
            QualityIssue(
                reason_code="ROW_COUNT_OUT_OF_RANGE",
                severity="BLOCKING",
                row_number=None,
                details={
                    "actual": raw_count,
                    "expectedMin": contract.expected_min_rows,
                    "expectedMax": contract.expected_max_rows,
                },
            )
        )
    rejected_ratio = rejected_count / raw_count if raw_count else 0.0
    if rejected_ratio > contract.maximum_rejected_ratio:
        issues.append(
            QualityIssue(
                reason_code="REJECTED_ROW_RATIO_EXCEEDED",
                severity="BLOCKING",
                row_number=None,
                details={"actual": rejected_ratio, "maximum": contract.maximum_rejected_ratio},
            )
        )
    if previous_active_row_count is not None:
        change_ratio = _row_change_ratio(previous_active_row_count, accepted_count)
        if change_ratio > contract.maximum_row_change_ratio:
            issues.append(
                QualityIssue(
                    reason_code="ROW_COUNT_CHANGE_EXCEEDED",
                    severity="BLOCKING",
                    row_number=None,
                    details={
                        "previous": previous_active_row_count,
                        "current": accepted_count,
                        "actual": change_ratio,
                        "maximum": contract.maximum_row_change_ratio,
                    },
                )
            )
    return ValidationOutcome(
        raw_row_count=raw_count,
        accepted_row_count=accepted_count,
        rejected_row_count=rejected_count,
        staged_rows=tuple(staged),
        issues=tuple(issues),
    )


def _missing(value: Any) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def _has_invalid_coordinate(row: dict[str, object]) -> bool:
    latitude = row.get("latitude")
    longitude = row.get("longitude")
    if latitude is None and longitude is None:
        return False
    return not (_valid_number(latitude, -90, 90) and _valid_number(longitude, -180, 180))


def _valid_number(value: object, minimum: float, maximum: float) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
        and minimum <= float(value) <= maximum
    )


def _canonical_key(value: object) -> str:
    if isinstance(value, str):
        return " ".join(value.split())
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _row_change_ratio(previous: int, current: int) -> float:
    if previous == 0:
        return 0.0 if current == 0 else math.inf
    return abs(current - previous) / previous
