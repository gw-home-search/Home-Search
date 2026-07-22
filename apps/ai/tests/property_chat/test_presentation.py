from datetime import date

import pytest

from ai_service.property_chat.models import EvidenceFact, FactClaim, QueryPlan
from ai_service.property_chat.presentation import (
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
