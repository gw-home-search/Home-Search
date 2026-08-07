from datetime import date

import pytest

from ai_service.property_chat.models import EvidenceFact, FactClaim, QueryPlan
from ai_service.property_chat.presentation import (
    AnswerLeadBuilder,
    AnswerPresentation,
    AppliedCriterion,
    FollowUpPrompt,
    FragmentPresentation,
    GroundedPresentationText,
    Interpretation,
    PresentationAssembler,
)


def fact() -> EvidenceFact:
    return EvidenceFact(
        fact_id="fact-1",
        claims=(FactClaim("1", "COUNT"),),
        data_as_of=date(2026, 7, 21),
        payload={"complexId": 1},
    )


@pytest.mark.parametrize(
    "factory",
    [
        lambda: GroundedPresentationText(" ", ("fact-1",)),
        lambda: GroundedPresentationText("문장", ()),
        lambda: AppliedCriterion("bad key", "조건", "값", ("fact-1",)),
        lambda: AppliedCriterion("REGION", " ", "값", ("fact-1",)),
        lambda: Interpretation("KEY", "해석", " ", ("fact-1",)),
        lambda: FollowUpPrompt(" "),
    ],
)
def test_presentation_value_objects_reject_malformed_public_text(factory) -> None:
    with pytest.raises(ValueError):
        factory()


def test_answer_presentation_rejects_unknown_fact_and_collection_overflow() -> None:
    headline = GroundedPresentationText("결론", ("fact-1",))
    with pytest.raises(ValueError, match="unknown fact"):
        AnswerPresentation(headline).to_public_dict(set())

    criteria = tuple(
        AppliedCriterion(f"KEY_{index}", "조건", "값", ("fact-1",))
        for index in range(9)
    )
    with pytest.raises(ValueError, match="collection"):
        AnswerPresentation(headline, criteria=criteria).to_public_dict({"fact-1"})


def test_fragment_presentation_accepts_failed_fragment_without_fact() -> None:
    assert FragmentPresentation(
        "fragment-1", "recent_trade_lookup", "failed",
        "필요한 데이터가 아직 준비되지 않았습니다.", (),
    ).to_public_dict()["status"] == "failed"


def test_assembler_keeps_text_fallback_for_unavailable_and_builds_reference_scope() -> None:
    assembler = PresentationAssembler()
    unavailable, artifacts = assembler.present(
        plan=QueryPlan("academy_lookup", "잠실엘스"),
        used_facts=[],
        readiness="unavailable",
        artifacts=[],
    )
    assert unavailable is None
    assert artifacts == []


def test_trade_result_limit_is_not_reused_as_a_monthly_trend_row_limit() -> None:
    assembler = PresentationAssembler()
    trend, _ = assembler.present(
        plan=QueryPlan(
            "price_trend", "헬리오시티", limit=5,
            start_date=date(2025, 8, 3), end_date=date(2026, 8, 3),
            exclusive_area_square_meters=59.0,
        ),
        used_facts=[fact()],
        readiness="supported",
        artifacts=[],
    )
    trade, _ = assembler.present(
        plan=QueryPlan("recent_trade_lookup", "헬리오시티", limit=3),
        used_facts=[fact()],
        readiness="supported",
        artifacts=[],
    )

    assert trend is not None
    assert "RESULT_LIMIT" not in {criterion.key for criterion in trend.criteria}
    assert trade is not None
    assert "RESULT_LIMIT" in {criterion.key for criterion in trade.criteria}

    summary, artifacts = assembler.present(
        plan=QueryPlan("academy_lookup", "잠실엘스", radius_meters=800),
        used_facts=[fact()],
        readiness="supported",
        artifacts=[],
    )
    assert summary is not None
    public = summary.to_public_dict({"fact-1"})
    assert public["criteria"][0]["key"] == "RADIUS"
    assert artifacts == []


def _evidence(fact_id: str, payload: dict[str, object]) -> EvidenceFact:
    return EvidenceFact(
        fact_id=fact_id,
        claims=(FactClaim("1", "COUNT"),),
        data_as_of=date(2026, 8, 3),
        payload=payload,
    )


@pytest.mark.parametrize(
    ("plan", "facts", "artifacts", "expected"),
    [
        (
            QueryPlan("complex_identity", "헬리오시티"),
            [_evidence("property-complex-1", {"displayName": "헬리오시티"})],
            [],
            "헬리오시티의 검증된 위치 정보는 현재 확인할 수 없습니다.",
        ),
        (
            QueryPlan("recent_trade_lookup", "헬리오시티"),
            [_evidence("property-complex-1", {"displayName": "헬리오시티"})],
            [],
            "헬리오시티의 요청 조건에서는 실거래가 0건으로 확인됐습니다.",
        ),
        (
            QueryPlan(
                "price_trend", "헬리오시티",
                start_date=date(2025, 8, 3), end_date=date(2026, 8, 3),
            ),
            [_evidence("property-complex-1", {"displayName": "헬리오시티"})],
            [],
            "헬리오시티의 2025-08-03~2026-08-03에서는 월별 가격 관찰값이 0건으로 확인됐습니다.",
        ),
        (
            QueryPlan("academy_lookup", "헬리오시티", radius_meters=800),
            [
                _evidence("property-complex-1", {"displayName": "헬리오시티"}),
                _evidence("academy-scope-1", {"verifiedZero": True}),
            ],
            [],
            "헬리오시티 중심 800m에서 학원 위치가 0곳으로 확인됐습니다.",
        ),
        (
            QueryPlan("rail_station_lookup", "헬리오시티"),
            [_evidence("property-complex-1", {"displayName": "헬리오시티"})],
            [],
            "헬리오시티의 가까운 철도역·노선은 현재 검증 가능한 근거가 없어 답할 수 없습니다.",
        ),
        (
            QueryPlan("comparison", "비교", complex_names=("A", "B")),
            [_evidence("comparison-1", {"value": 1})],
            [{
                "type": "comparisonTable",
                "columns": [{"label": "A"}, {"label": "B"}],
                "rows": [{
                    "label": "세대수",
                    "cells": [
                        {"availability": "available", "value": "1,000세대"},
                        {"availability": "available", "value": "2,000세대"},
                    ],
                }],
            }],
            "요청 조건에서 A와 B를 비교하면 세대수는 A 1,000세대, B 2,000세대입니다.",
        ),
    ],
)
def test_answer_lead_builder_handles_zero_unavailable_and_comparison(
    plan: QueryPlan,
    facts: list[EvidenceFact],
    artifacts: list[dict[str, object]],
    expected: str,
) -> None:
    lead = AnswerLeadBuilder().build(plan=plan, facts=facts, artifacts=artifacts)

    assert lead.text == expected
