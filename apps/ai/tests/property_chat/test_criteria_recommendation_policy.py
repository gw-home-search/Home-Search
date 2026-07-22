from __future__ import annotations

from dataclasses import replace
from datetime import UTC, date, datetime

import pytest

from ai_service.property_chat.criteria_recommendation import (
    CriteriaMetric,
    CriteriaRecommendationCandidate,
    CriteriaRecommendationPolicy,
    CriteriaRecommendationRow,
    RecommendationTableArtifact,
    RecommendationMetric,
)
from ai_service.property_chat.models import ComplexRecord, QueryPlan


def _candidate(
    complex_id: int,
    *,
    unit_count: int,
    academy_count: int | None,
    academy_distance: int | None,
    rail_distance: int | None,
) -> CriteriaRecommendationCandidate:
    return CriteriaRecommendationCandidate(
        complex_record=ComplexRecord(
            complex_id=complex_id,
            display_name=f"후보 {complex_id}",
            region_code="11560",
            region_name="영등포구",
            address=f"서울 영등포구 후보 {complex_id}",
            latitude=37.5,
            longitude=126.9,
            marker_safe=True,
            data_updated_at=datetime(2026, 7, 20, tzinfo=UTC),
            unit_count=unit_count,
        ),
        metrics={
            "ACADEMY": RecommendationMetric(
                availability="available",
                value=academy_count,
                nearest_distance_meters=academy_distance,
                observed_at=date(2026, 7, 1),
                fact_ids=(f"academy-{complex_id}",),
            ),
            "TRANSIT": RecommendationMetric(
                availability="available",
                value=rail_distance,
                nearest_distance_meters=rail_distance,
                observed_at=date(2026, 6, 30),
                fact_ids=(f"rail-{complex_id}",),
            ),
        },
    )


def test_criteria_policy_filters_unit_count_and_sorts_lexicographically() -> None:
    policy = CriteriaRecommendationPolicy(
        minimum_unit_count=500,
        criteria_order=("ACADEMY", "TRANSIT"),
    )

    ranked = policy.rank((
        _candidate(3, unit_count=700, academy_count=10, academy_distance=200, rail_distance=300),
        _candidate(2, unit_count=900, academy_count=10, academy_distance=100, rail_distance=500),
        _candidate(1, unit_count=499, academy_count=99, academy_distance=10, rail_distance=10),
    ))

    assert [candidate.complex_record.complex_id for candidate in ranked] == [2, 3]


def test_criteria_policy_requires_priority_for_multiple_metrics() -> None:
    with pytest.raises(ValueError, match="priority"):
        CriteriaRecommendationPolicy(
            minimum_unit_count=500,
            criteria=("ACADEMY", "TRANSIT"),
            criteria_order=(),
        )


def test_criteria_policy_uses_the_user_supplied_priority_order() -> None:
    academy_first = _candidate(
        1, unit_count=800, academy_count=10, academy_distance=200, rail_distance=1000
    )
    transit_first = _candidate(
        2, unit_count=800, academy_count=5, academy_distance=100, rail_distance=100
    )

    policy = CriteriaRecommendationPolicy(
        minimum_unit_count=None,
        criteria=("ACADEMY", "TRANSIT"),
        criteria_order=("TRANSIT", "ACADEMY"),
    )

    assert policy.rank((academy_first, transit_first)) == (
        transit_first,
        academy_first,
    )


def test_childcare_is_typed_but_not_active() -> None:
    assert CriteriaMetric.CHILDCARE.value == "CHILDCARE"
    assert CriteriaMetric.CHILDCARE not in CriteriaRecommendationPolicy.ACTIVE_METRICS


def test_query_plan_infers_criteria_mode_without_budget_or_area() -> None:
    plan = QueryPlan(
        capability="recommendation",
        complex_name="영등포구",
        region_name="영등포구",
        minimum_unit_count=500,
        recommendation_criteria=("ACADEMY",),
    )

    assert plan.recommendation_mode == "CRITERIA"
    assert plan.criteria_order == ("ACADEMY",)
    assert plan.maximum_budget_ten_thousand_krw is None
    assert plan.exclusive_area_square_meters is None


@pytest.mark.parametrize(
    "kwargs",
    (
        {"availability": "available", "value": -1, "nearest_distance_meters": None,
         "observed_at": date(2026, 7, 1), "fact_ids": ("fact",)},
        {"availability": "available", "value": 1, "nearest_distance_meters": -1,
         "observed_at": date(2026, 7, 1), "fact_ids": ("fact",)},
        {"availability": "available", "value": 1, "nearest_distance_meters": None,
         "observed_at": None, "fact_ids": ("fact",)},
        {"availability": "available", "value": 1, "nearest_distance_meters": None,
         "observed_at": date(2026, 7, 1), "fact_ids": (), "reason": "오류"},
        {"availability": "unavailable", "value": 1, "nearest_distance_meters": None,
         "observed_at": date(2026, 7, 1), "fact_ids": ("fact",), "reason": "없음"},
        {"availability": "unavailable", "value": None, "nearest_distance_meters": None,
         "observed_at": date(2026, 7, 1), "fact_ids": ("fact",), "reason": None},
        {"availability": "unknown", "value": None, "nearest_distance_meters": None,
         "observed_at": date(2026, 7, 1), "fact_ids": ("fact",)},
    ),
)
def test_recommendation_metric_rejects_malformed_shapes(kwargs: dict[str, object]) -> None:
    with pytest.raises(ValueError, match="recommendation metric"):
        RecommendationMetric(**kwargs)  # type: ignore[arg-type]


def test_policy_puts_unavailable_metrics_after_available_metrics() -> None:
    available = _candidate(
        2, unit_count=800, academy_count=3, academy_distance=None, rail_distance=400
    )
    unavailable = CriteriaRecommendationCandidate(
        complex_record=replace(available.complex_record, complex_id=1),
        metrics={
            "ACADEMY": RecommendationMetric(
                "unavailable", None, None, date(2026, 7, 1), ("academy-1",),
                "직선거리 800m 안에서 확인되지 않았습니다.",
            )
        },
    )
    policy = CriteriaRecommendationPolicy(
        minimum_unit_count=None, criteria_order=("ACADEMY",)
    )

    assert policy.rank((unavailable, available)) == (available, unavailable)


@pytest.mark.parametrize(
    ("minimum_unit_count", "criteria", "criteria_order", "message"),
    (
        (0, ("ACADEMY",), ("ACADEMY",), "minimum unit"),
        (None, (), (), "at least one"),
        (None, ("ACADEMY", "ACADEMY"), ("ACADEMY",), "metrics"),
        (None, ("CHILDCARE",), ("CHILDCARE",), "metrics"),
        (None, ("ACADEMY",), ("TRANSIT",), "metrics"),
    ),
)
def test_policy_rejects_invalid_catalog_combinations(
    minimum_unit_count: int | None,
    criteria: tuple[str, ...],
    criteria_order: tuple[str, ...],
    message: str,
) -> None:
    with pytest.raises(ValueError, match=message):
        CriteriaRecommendationPolicy(
            minimum_unit_count=minimum_unit_count,
            criteria=criteria,  # type: ignore[arg-type]
            criteria_order=criteria_order,  # type: ignore[arg-type]
        )


def test_recommendation_table_serializes_fixed_metrics_and_units() -> None:
    candidate = _candidate(
        2, unit_count=800, academy_count=3, academy_distance=120, rail_distance=400
    )
    row = CriteriaRecommendationRow(
        order=1,
        complex_id=2,
        complex_name=" 후보 2 ",
        unit_count=800,
        metrics=candidate.metrics,
        fact_ids=("complex-2", "academy-2", "rail-2"),
    )

    value = RecommendationTableArtifact(
        artifact_id="criteria-recommendation-test",
        scope_type="ADMIN_REGION",
        scope_label=" 영등포구 ",
        criteria_order=("ACADEMY", "TRANSIT"),
        minimum_unit_count=500,
        radius_meters=800,
        rows=(row,),
    ).to_public_dict()

    assert value["basis"]["scopeLabel"] == "영등포구"  # type: ignore[index]
    assert value["rows"][0]["metrics"]["ACADEMY"]["unit"] == "COUNT"  # type: ignore[index]
    assert value["rows"][0]["metrics"]["TRANSIT"]["unit"] == "METERS"  # type: ignore[index]


def test_recommendation_table_rejects_invalid_bounds_and_rows() -> None:
    metric = RecommendationMetric(
        "available", 3, 120, date(2026, 7, 1), ("academy-2",)
    )
    invalid_row = CriteriaRecommendationRow(
        order=0, complex_id=2, complex_name="후보", unit_count=800,
        metrics={"ACADEMY": metric}, fact_ids=("complex-2",),
    )
    with pytest.raises(ValueError, match="row"):
        invalid_row.to_public_dict()

    valid_row = replace(invalid_row, order=1)
    with pytest.raises(ValueError, match="bounds"):
        RecommendationTableArtifact(
            artifact_id="잘못된-id", scope_type="ADMIN_REGION", scope_label="영등포구",
            criteria_order=("ACADEMY",), minimum_unit_count=500,
            radius_meters=800, rows=(valid_row,),
        ).to_public_dict()
