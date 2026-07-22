from __future__ import annotations

import asyncio
from datetime import date

import pytest

from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.language import LanguageModelStageError, RetryingLanguageModel
from ai_service.property_chat.models import (
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    EvidenceFact,
    FactClaim,
    QueryPlan,
)


class ScriptedLanguageModel:
    def __init__(self, *, failures: int) -> None:
        self.failures = failures
        self.plan_calls = 0
        self.draft_calls = 0

    async def plan_query(self, _request: ChatbotQueryRequest) -> QueryPlan:
        self.plan_calls += 1
        if self.plan_calls <= self.failures:
            raise RuntimeError("provider detail must not escape")
        return QueryPlan(capability="complex_identity", complex_name="잠실엘스")

    async def draft_answer(self, **_kwargs: object) -> DraftAnswer:
        self.draft_calls += 1
        if self.draft_calls <= self.failures:
            raise RuntimeError("provider detail must not escape")
        return DraftAnswer(
            sentences=[DraftSentence(text="근거가 없습니다.", fact_ids=[])]
        )


def test_language_model_stage_rejects_non_allowlisted_value() -> None:
    with pytest.raises(ValueError, match="invalid language model stage"):
        LanguageModelStageError("QUESTION")  # type: ignore[arg-type]


def test_uses_primary_once_before_secondary() -> None:
    primary = ScriptedLanguageModel(failures=2)
    secondary = ScriptedLanguageModel(failures=0)
    model = RetryingLanguageModel(primary=primary, secondary=secondary)

    plan = asyncio.run(model.plan_query(ChatbotQueryRequest(question="위치")))

    assert plan.complex_name == "잠실엘스"
    assert primary.plan_calls == 1
    assert secondary.plan_calls == 1


def test_draft_uses_secondary_after_primary_failure() -> None:
    primary = ScriptedLanguageModel(failures=1)
    secondary = ScriptedLanguageModel(failures=0)
    model = RetryingLanguageModel(primary=primary, secondary=secondary)

    draft = asyncio.run(model.draft_answer(facts=[], limitations=[], question="위치"))

    assert draft.sentences[0].text == "근거가 없습니다."
    assert primary.draft_calls == 1
    assert secondary.draft_calls == 1


def test_all_model_failures_are_mapped_without_provider_details() -> None:
    model = RetryingLanguageModel(
        primary=ScriptedLanguageModel(failures=3),
        secondary=ScriptedLanguageModel(failures=3),
    )

    with pytest.raises(ChatbotProviderUnavailable) as raised:
        asyncio.run(model.plan_query(ChatbotQueryRequest(question="위치")))

    assert str(raised.value) == ""
    assert isinstance(raised.value, LanguageModelStageError)
    assert raised.value.stage == "PLAN"


def test_all_draft_failures_keep_only_safe_draft_stage() -> None:
    model = RetryingLanguageModel(
        primary=ScriptedLanguageModel(failures=3),
        secondary=ScriptedLanguageModel(failures=3),
    )

    with pytest.raises(ChatbotProviderUnavailable) as raised:
        asyncio.run(model.draft_answer(facts=[], limitations=[], question="위치"))

    assert str(raised.value) == ""
    assert isinstance(raised.value, LanguageModelStageError)
    assert raised.value.stage == "DRAFT"


class ScriptedDraftModel:
    def __init__(self, drafts: list[DraftAnswer]) -> None:
        self._drafts = drafts
        self.draft_calls = 0

    async def plan_query(self, _request: ChatbotQueryRequest) -> QueryPlan:
        return QueryPlan(capability="recent_trade_lookup", complex_name="잠실엘스")

    async def draft_answer(self, **_kwargs: object) -> DraftAnswer:
        draft = self._drafts[min(self.draft_calls, len(self._drafts) - 1)]
        self.draft_calls += 1
        return draft


def _trade_fact() -> EvidenceFact:
    return EvidenceFact(
        fact_id="property-trade-7",
        claims=(FactClaim("120000", "10_000_KRW"),),
        data_as_of=date(2026, 7, 16),
        payload={"dealAmountTenThousandKrw": 120000},
    )


def _trade_draft(text: str) -> DraftAnswer:
    return DraftAnswer(
        sentences=[
            DraftSentence(
                text=text,
                fact_ids=["property-trade-7"],
                claims=[
                    DraftClaim(
                        fact_id="property-trade-7",
                        value="120000",
                        unit="10_000_KRW",
                    )
                ],
            )
        ]
    )


def test_draft_uses_secondary_when_primary_grounding_validation_fails() -> None:
    primary = ScriptedDraftModel(
        [_trade_draft("최근 3건 중 120000만원입니다."), _trade_draft("120000만원입니다.")]
    )
    secondary = ScriptedDraftModel([_trade_draft("120000만원입니다.")])
    model = RetryingLanguageModel(primary=primary, secondary=secondary)

    draft = asyncio.run(
        model.draft_answer(facts=[_trade_fact()], limitations=[], question="최근 거래")
    )

    assert draft.sentences[0].text == "120000만원입니다."
    assert primary.draft_calls == 1
    assert secondary.draft_calls == 1


def test_draft_uses_secondary_after_primary_grounding_validation_failure() -> None:
    primary = ScriptedDraftModel([_trade_draft("최근 3건 중 120000만원입니다.")])
    secondary = ScriptedDraftModel([_trade_draft("120000만원입니다.")])
    model = RetryingLanguageModel(primary=primary, secondary=secondary)

    draft = asyncio.run(
        model.draft_answer(facts=[_trade_fact()], limitations=[], question="최근 거래")
    )

    assert draft.sentences[0].text == "120000만원입니다."
    assert primary.draft_calls == 1
    assert secondary.draft_calls == 1
