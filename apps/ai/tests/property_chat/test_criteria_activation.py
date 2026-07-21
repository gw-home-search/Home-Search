from __future__ import annotations

import asyncio

import pytest

from ai_service.property_chat.criteria_activation import (
    CriteriaActivationError,
    main,
    run_activation_case,
)
from ai_service.property_chat.models import (
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    QueryPlan,
    QueryPlanBundle,
)


class ActivationLanguageModel:
    async def plan_query(self, _request):
        return QueryPlan(
            capability="recommendation",
            complex_name="영등포구",
            region_name="영등포구",
            recommendation_mode="CRITERIA",
            minimum_unit_count=500,
            recommendation_criteria=("ACADEMY", "TRANSIT"),
            criteria_order=("ACADEMY", "TRANSIT"),
        )

    async def draft_answer(self, *, facts, limitations, question):
        del limitations, question
        return DraftAnswer([
            DraftSentence(
                text="영등포구에서 500세대 이상인 후보를 학원과 역 직선거리 기준으로 확인했습니다.",
                fact_ids=[fact.fact_id for fact in facts],
                claims=[
                    DraftClaim(fact.fact_id, claim.value, claim.unit)
                    for fact in facts
                    for claim in fact.claims
                ],
            )
        ])


def test_activation_case_verifies_live_plan_and_grounded_draft() -> None:
    result = asyncio.run(run_activation_case(ActivationLanguageModel()))

    assert result == {
        "caseId": "criteria-recommendation-academy-transit",
        "capability": "recommendation",
        "mode": "CRITERIA",
        "criteria": ["ACADEMY", "TRANSIT"],
        "factCount": 4,
    }


def test_activation_case_accepts_the_single_fragment_live_planner_contract() -> None:
    model = ActivationLanguageModel()
    original_plan_query = model.plan_query

    async def bundled_plan(request):
        return QueryPlanBundle((await original_plan_query(request),))

    model.plan_query = bundled_plan  # type: ignore[method-assign]

    result = asyncio.run(run_activation_case(model))

    assert result["criteria"] == ["ACADEMY", "TRANSIT"]


def test_activation_case_rejects_a_mode_outside_the_approved_slice() -> None:
    model = ActivationLanguageModel()

    async def budget_plan(_request):
        return QueryPlan(
            capability="recommendation",
            complex_name="영등포구",
            region_name="영등포구",
            recommendation_mode="BUDGET",
            maximum_budget_ten_thousand_krw=200_000,
            exclusive_area_square_meters=84,
        )

    model.plan_query = budget_plan  # type: ignore[method-assign]

    with pytest.raises(CriteriaActivationError, match="PLAN_POLICY_FAILED"):
        asyncio.run(run_activation_case(model))


def test_activation_case_rejects_a_non_plan_provider_result() -> None:
    model = ActivationLanguageModel()

    async def invalid_plan(_request):
        return object()

    model.plan_query = invalid_plan  # type: ignore[method-assign]

    with pytest.raises(CriteriaActivationError, match="PLAN_POLICY_FAILED"):
        asyncio.run(run_activation_case(model))


def test_activation_case_hides_plan_stage_failure_details() -> None:
    model = ActivationLanguageModel()

    async def failed_plan(_request):
        raise RuntimeError("must-not-leak")

    model.plan_query = failed_plan  # type: ignore[method-assign]

    with pytest.raises(CriteriaActivationError, match="PLAN_STAGE_FAILED") as raised:
        asyncio.run(run_activation_case(model))
    assert "must-not-leak" not in str(raised.value)


def test_activation_case_hides_draft_stage_failure_details() -> None:
    model = ActivationLanguageModel()

    async def failed_draft(*, facts, limitations, question):
        del facts, limitations, question
        raise RuntimeError("must-not-leak")

    model.draft_answer = failed_draft  # type: ignore[method-assign]

    with pytest.raises(CriteriaActivationError, match="DRAFT_STAGE_FAILED") as raised:
        asyncio.run(run_activation_case(model))
    assert "must-not-leak" not in str(raised.value)


def test_activation_case_requires_every_sanitized_fact() -> None:
    model = ActivationLanguageModel()

    async def partial_draft(*, facts, limitations, question):
        del limitations, question
        fact = facts[0]
        return DraftAnswer([DraftSentence(
            text="영등포구에서 500세대 이상인 후보를 확인했습니다.",
            fact_ids=[fact.fact_id],
            claims=[
                DraftClaim(fact.fact_id, claim.value, claim.unit)
                for claim in fact.claims
            ],
        )])

    model.draft_answer = partial_draft  # type: ignore[method-assign]

    with pytest.raises(CriteriaActivationError, match="DRAFT_GROUNDING_FAILED"):
        asyncio.run(run_activation_case(model))


def test_activation_cli_reports_only_bounded_success_fields(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setattr(
        "ai_service.chat.get_grounded_language_model",
        lambda: ActivationLanguageModel(),
    )

    assert main() == 0
    output = capsys.readouterr().out
    assert "상태: Pass" in output
    assert "criteria: ACADEMY,TRANSIT" in output
    assert "providerRequestUpperBound: 6" in output


def test_activation_cli_hides_failure_details(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    model = ActivationLanguageModel()

    async def invalid_plan(_request):
        return object()

    model.plan_query = invalid_plan  # type: ignore[method-assign]
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", lambda: model)

    assert main() == 1
    output = capsys.readouterr().out
    assert "reasonCode: PLAN_POLICY_FAILED" in output
    assert "object" not in output
