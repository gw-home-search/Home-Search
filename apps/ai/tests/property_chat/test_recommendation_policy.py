from __future__ import annotations

from dataclasses import replace
from datetime import UTC, date, datetime

import pytest

from ai_service.property_chat.comparison import RecentThreeTradeBasis
from ai_service.property_chat.models import ComplexRecord, TradeRecord
from ai_service.property_chat.recommendation import (
    RecommendationCandidate,
    RecommendationCard,
    RecommendationCardsArtifact,
    RecommendationPolicy,
    RecommendationScoreItem,
    ScoreBreakdown,
)


def _candidate(
    complex_id: int,
    amounts: tuple[int, ...],
    *,
    rail_distance: int | None = 750,
    retail_distance: int | None = 500,
) -> RecommendationCandidate:
    cutoff = date(2026, 7, 20)
    trades = tuple(
        TradeRecord(
            trade_id=complex_id * 10 + index,
            complex_id=complex_id,
            deal_date=date(2026, 7, 21 - index),
            deal_amount_ten_thousand_krw=amount,
            exclusive_area_square_meters=84.0,
            floor=10,
        )
        for index, amount in enumerate(amounts, start=1)
    )
    return RecommendationCandidate(
        complex_record=ComplexRecord(
            complex_id=complex_id,
            display_name=f"후보 {complex_id}",
            region_code="11710",
            region_name="송파구",
            address=f"서울 송파구 후보 {complex_id}",
            latitude=37.5,
            longitude=127.1,
            marker_safe=True,
            data_updated_at=datetime(2026, 7, 20, tzinfo=UTC),
        ),
        trade_basis=RecentThreeTradeBasis.from_trades(
            complex_id=complex_id,
            cutoff=cutoff,
            exclusive_area_square_meters=84.0,
            trades=trades,
        ),
        rail_distance_meters=rail_distance,
        retail_distance_meters=retail_distance,
    )


def test_policy_excludes_under_sampled_and_over_budget_candidates() -> None:
    policy = RecommendationPolicy(maximum_budget_ten_thousand_krw=200_000)

    results = policy.rank((
        _candidate(1, (180_000, 190_000)),
        _candidate(2, (199_000, 205_000, 210_000)),
        _candidate(3, (190_000, 195_000, 200_000)),
    ))

    assert [result.candidate.complex_record.complex_id for result in results] == [3]


def test_policy_gives_no_price_bonus_to_the_cheaper_candidate() -> None:
    policy = RecommendationPolicy(maximum_budget_ten_thousand_krw=200_000)

    results = policy.rank((
        _candidate(20, (100_000, 110_000, 120_000)),
        _candidate(10, (180_000, 190_000, 200_000)),
    ))

    assert [result.total_score for result in results] == [80.0, 80.0]
    assert [result.breakdown.price_points for result in results] == [60.0, 60.0]
    assert [result.candidate.complex_record.complex_id for result in results] == [10, 20]


def test_policy_uses_linear_distance_scores_and_fixed_v1_weights() -> None:
    result = RecommendationPolicy(
        maximum_budget_ten_thousand_krw=200_000
    ).rank((_candidate(1, (180_000, 190_000, 200_000)),))[0]

    assert result.breakdown.price_points == 60.0
    assert result.breakdown.rail_points == 12.5
    assert result.breakdown.retail_points == 7.5
    assert result.total_score == 80.0
    assert result.breakdown.policy_version == "recommendation-policy-v1"


def test_policy_treats_verified_no_facility_in_radius_as_zero_points() -> None:
    policy = RecommendationPolicy(maximum_budget_ten_thousand_krw=200_000)

    assert policy.rank((
        _candidate(
            1,
            (180_000, 190_000, 200_000),
            rail_distance=None,
            retail_distance=None,
        ),
    ))[0].total_score == 60.0


def test_recommendation_value_objects_reject_invalid_policy_artifacts() -> None:
    with pytest.raises(ValueError, match="budget"):
        RecommendationPolicy(maximum_budget_ten_thousand_krw=0)
    with pytest.raises(ValueError, match="breakdown"):
        ScoreBreakdown(60.0, 26.0, 0.0)
    with pytest.raises(ValueError, match="candidate"):
        replace(
            _candidate(1, (180_000, 190_000, 200_000)),
            complex_record=replace(
                _candidate(1, (180_000, 190_000, 200_000)).complex_record,
                marker_safe=False,
            ),
        )
    with pytest.raises(ValueError, match="score item"):
        RecommendationScoreItem(
            "PRICE", "예산 조건", 60.0, 60.0, None, ("invalid fact id",)
        ).to_public_dict()

    fact_ids = ("complex-1", "trade-1", "rail-1", "retail-1")
    card = RecommendationCard(
        rank=1,
        complex_id=1,
        complex_name="후보 1",
        total_score=80.0,
        latest_trade_date=date(2026, 7, 20),
        latest_trade_amount_ten_thousand_krw=190_000,
        median_amount_ten_thousand_krw=190_000,
        latest_trade_fact_ids=("trade-1",),
        median_fact_ids=("trade-1",),
        score_breakdown=(
            RecommendationScoreItem(
                "PRICE", "예산 조건", 60.0, 60.0, None, ("trade-1",)
            ),
            RecommendationScoreItem(
                "TRANSIT", "철도 접근성", 25.0, 12.5, 750, ("rail-1",)
            ),
            RecommendationScoreItem(
                "SHOPPING", "대규모점포 접근성", 15.0, 7.5, 500, ("retail-1",)
            ),
        ),
        limitations=(),
        fact_ids=fact_ids,
    )
    artifact = RecommendationCardsArtifact("recommendation-valid", (card,))

    assert artifact.to_public_dict()["policyVersion"] == "recommendation-policy-v1"
    with pytest.raises(ValueError, match="card"):
        replace(card, total_score=99.0).to_public_dict()
    with pytest.raises(ValueError, match="bounds"):
        RecommendationCardsArtifact("recommendation-empty", ()).to_public_dict()
