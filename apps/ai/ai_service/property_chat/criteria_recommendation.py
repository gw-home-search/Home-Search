from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from enum import Enum
import json
import re
from types import MappingProxyType
from typing import Literal, Mapping

from .models import ComplexRecord

CRITERIA_POLICY_VERSION = "criteria-recommendation-policy-v1"


class CriterionKey(str, Enum):
    REGION = "REGION"
    STATION_SCOPE = "STATION_SCOPE"
    MAX_BUDGET = "MAX_BUDGET"
    EXCLUSIVE_AREA = "EXCLUSIVE_AREA"
    MIN_UNIT_COUNT = "MIN_UNIT_COUNT"
    END_DATE = "END_DATE"
    RESULT_LIMIT = "RESULT_LIMIT"
    TRANSIT = "TRANSIT"
    ACADEMY = "ACADEMY"
    SCHOOL = "SCHOOL"
    SHOPPING = "SHOPPING"
    CHILDCARE = "CHILDCARE"


CriteriaMetric = CriterionKey
ActiveMetricKey = Literal["TRANSIT", "ACADEMY", "SCHOOL", "SHOPPING"]


@dataclass(frozen=True)
class CriteriaCandidateScope:
    scope_label: str
    candidates: tuple[ComplexRecord, ...]

    def __post_init__(self) -> None:
        if (
            not 1 <= len(self.scope_label.strip()) <= 100
            or len(self.candidates) > 101
            or len({item.complex_id for item in self.candidates}) != len(self.candidates)
        ):
            raise ValueError("criteria candidate scope is invalid")


@dataclass(frozen=True)
class RecommendationMetric:
    availability: Literal["available", "unavailable"]
    value: int | None
    nearest_distance_meters: int | None
    observed_at: date | None
    fact_ids: tuple[str, ...]
    reason: str | None = None

    def __post_init__(self) -> None:
        if self.availability == "available":
            if (
                self.value is None
                or isinstance(self.value, bool)
                or self.value < 0
                or self.nearest_distance_meters is not None
                and (
                    isinstance(self.nearest_distance_meters, bool)
                    or self.nearest_distance_meters < 0
                )
                or self.observed_at is None
                or not _valid_fact_ids(self.fact_ids)
                or self.reason is not None
            ):
                raise ValueError("available recommendation metric is invalid")
        elif self.availability == "unavailable":
            if (
                self.value is not None
                or self.nearest_distance_meters is not None
                or self.observed_at is None
                or not _valid_fact_ids(self.fact_ids)
                or self.reason is None
                or not 1 <= len(self.reason.strip()) <= 2_000
            ):
                raise ValueError("unavailable recommendation metric is invalid")
        else:
            raise ValueError("recommendation metric availability is invalid")


@dataclass(frozen=True)
class CriteriaRecommendationCandidate:
    complex_record: ComplexRecord
    metrics: Mapping[ActiveMetricKey, RecommendationMetric]

    def __post_init__(self) -> None:
        if (
            not self.complex_record.marker_safe
            or self.complex_record.latitude is None
            or self.complex_record.longitude is None
            or any(key not in CriteriaRecommendationPolicy.ACTIVE_METRIC_VALUES for key in self.metrics)
        ):
            raise ValueError("criteria recommendation candidate is invalid")
        object.__setattr__(self, "metrics", MappingProxyType(dict(self.metrics)))


class CriteriaRecommendationPolicy:
    ACTIVE_METRICS = frozenset({
        CriterionKey.TRANSIT,
        CriterionKey.ACADEMY,
        CriterionKey.SCHOOL,
        CriterionKey.SHOPPING,
    })
    ACTIVE_METRIC_VALUES = frozenset(metric.value for metric in ACTIVE_METRICS)

    def __init__(
        self,
        *,
        minimum_unit_count: int | None,
        criteria: tuple[ActiveMetricKey, ...] | None = None,
        criteria_order: tuple[ActiveMetricKey, ...],
    ) -> None:
        if minimum_unit_count is not None and (
            isinstance(minimum_unit_count, bool)
            or not 1 <= minimum_unit_count <= 100_000
        ):
            raise ValueError("minimum unit count is invalid")
        selected = criteria if criteria is not None else criteria_order
        if (
            len(selected) != len(set(selected))
            or any(key not in self.ACTIVE_METRIC_VALUES for key in selected)
            or len(criteria_order) != len(set(criteria_order))
            or any(key not in self.ACTIVE_METRIC_VALUES for key in criteria_order)
            or not set(criteria_order).issubset(selected)
        ):
            raise ValueError("criteria recommendation metrics are invalid")
        if not selected and minimum_unit_count is None:
            raise ValueError("at least one measurable criterion is required")
        if len(selected) > 1 and set(criteria_order) != set(selected):
            raise ValueError("multiple metrics require an explicit priority")
        self.minimum_unit_count = minimum_unit_count
        self.criteria = selected
        self.criteria_order = criteria_order

    def rank(
        self, candidates: tuple[CriteriaRecommendationCandidate, ...]
    ) -> tuple[CriteriaRecommendationCandidate, ...]:
        qualified = tuple(
            candidate
            for candidate in candidates
            if self.minimum_unit_count is None
            or candidate.complex_record.unit_count is not None
            and candidate.complex_record.unit_count >= self.minimum_unit_count
        )
        return tuple(sorted(qualified, key=self._sort_key))

    def _sort_key(self, candidate: CriteriaRecommendationCandidate) -> tuple[int, ...]:
        values: list[int] = []
        for key in self.criteria_order:
            metric = candidate.metrics.get(key)
            if metric is None or metric.availability == "unavailable":
                values.extend((1, 0, 0))
                continue
            if key == CriterionKey.ACADEMY.value:
                values.extend((
                    0,
                    -metric.value,  # type: ignore[operator]
                    metric.nearest_distance_meters
                    if metric.nearest_distance_meters is not None else 2_147_483_647,
                ))
            else:
                distance = (
                    metric.nearest_distance_meters
                    if metric.nearest_distance_meters is not None else metric.value
                )
                values.extend((0, distance, 0))  # type: ignore[arg-type]
        values.append(candidate.complex_record.complex_id)
        return tuple(values)


@dataclass(frozen=True)
class CriteriaRecommendationRow:
    order: int
    complex_id: int
    complex_name: str
    unit_count: int | None
    metrics: Mapping[ActiveMetricKey, RecommendationMetric]
    fact_ids: tuple[str, ...]

    def to_public_dict(self) -> dict[str, object]:
        if (
            not 1 <= self.order <= 5
            or self.complex_id <= 0
            or not 1 <= len(self.complex_name.strip()) <= 100
            or self.unit_count is not None and self.unit_count < 0
            or not _valid_fact_ids(self.fact_ids)
        ):
            raise ValueError("criteria recommendation row is invalid")
        metrics: dict[str, object] = {}
        for key, metric in self.metrics.items():
            if key not in CriteriaRecommendationPolicy.ACTIVE_METRIC_VALUES:
                raise ValueError("criteria recommendation row metric is invalid")
            metrics[key] = {
                "availability": metric.availability,
                "value": metric.value,
                "unit": _metric_unit(key),
                "nearestDistanceMeters": metric.nearest_distance_meters,
                "reason": metric.reason,
                "factIds": list(metric.fact_ids),
            }
        return {
            "order": self.order,
            "complexId": self.complex_id,
            "complexName": self.complex_name.strip(),
            "unitCount": self.unit_count,
            "metrics": metrics,
            "factIds": list(self.fact_ids),
        }


@dataclass(frozen=True)
class RecommendationTableArtifact:
    artifact_id: str
    scope_type: Literal["ADMIN_REGION", "STATION_RADIUS"]
    scope_label: str
    criteria_order: tuple[str, ...]
    minimum_unit_count: int | None
    radius_meters: int | None
    rows: tuple[CriteriaRecommendationRow, ...]

    def to_public_dict(self) -> dict[str, object]:
        if (
            re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,199}", self.artifact_id) is None
            or not 1 <= len(self.scope_label.strip()) <= 100
            or not 1 <= len(self.rows) <= 5
            or tuple(row.order for row in self.rows) != tuple(range(1, len(self.rows) + 1))
            or len({row.complex_id for row in self.rows}) != len(self.rows)
            or any(key not in CriteriaRecommendationPolicy.ACTIVE_METRIC_VALUES for key in self.criteria_order)
        ):
            raise ValueError("recommendation table bounds are invalid")
        artifact: dict[str, object] = {
            "type": "recommendationTable",
            "version": 1,
            "artifactId": self.artifact_id,
            "title": "조건 기반 후보",
            "policyVersion": CRITERIA_POLICY_VERSION,
            "basis": {
                "scopeType": self.scope_type,
                "scopeLabel": self.scope_label.strip(),
                "criteriaOrder": list(self.criteria_order),
                "minimumUnitCount": self.minimum_unit_count,
                "radiusMeters": self.radius_meters,
            },
            "rows": [row.to_public_dict() for row in self.rows],
        }
        if len(json.dumps(artifact, ensure_ascii=False).encode("utf-8")) > 65_536:
            raise ValueError("recommendation table exceeds the public size limit")
        return artifact


def _metric_unit(key: str) -> str:
    return "COUNT" if key == CriterionKey.ACADEMY.value else "METERS"


def _valid_fact_ids(values: tuple[str, ...]) -> bool:
    return (
        1 <= len(values) <= 100
        and len(values) == len(set(values))
        and all(
            re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,199}", value) is not None
            for value in values
        )
    )
