from __future__ import annotations

import asyncio

import pytest

from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.language import RetryingLanguageModel
from ai_service.property_chat.models import DraftAnswer, DraftSentence, PropertyQueryPlan


class ScriptedLanguageModel:
    def __init__(self, *, failures: int) -> None:
        self.failures = failures
        self.plan_calls = 0
        self.draft_calls = 0

    async def plan_query(self, _request: ChatbotQueryRequest) -> PropertyQueryPlan:
        self.plan_calls += 1
        if self.plan_calls <= self.failures:
            raise RuntimeError("provider detail must not escape")
        return PropertyQueryPlan(capability="complex_identity", complex_name="잠실엘스")

    async def draft_answer(self, **_kwargs: object) -> DraftAnswer:
        self.draft_calls += 1
        if self.draft_calls <= self.failures:
            raise RuntimeError("provider detail must not escape")
        return DraftAnswer(
            sentences=[DraftSentence(text="근거가 없습니다.", fact_ids=[])]
        )


def test_retries_primary_once_before_using_secondary() -> None:
    primary = ScriptedLanguageModel(failures=2)
    secondary = ScriptedLanguageModel(failures=0)
    model = RetryingLanguageModel(primary=primary, secondary=secondary)

    plan = asyncio.run(model.plan_query(ChatbotQueryRequest(question="위치")))

    assert plan.complex_name == "잠실엘스"
    assert primary.plan_calls == 2
    assert secondary.plan_calls == 1


def test_draft_uses_primary_when_retry_succeeds() -> None:
    primary = ScriptedLanguageModel(failures=1)
    secondary = ScriptedLanguageModel(failures=0)
    model = RetryingLanguageModel(primary=primary, secondary=secondary)

    draft = asyncio.run(model.draft_answer(facts=[], limitations=[], question="위치"))

    assert draft.sentences[0].text == "근거가 없습니다."
    assert primary.draft_calls == 2
    assert secondary.draft_calls == 0


def test_all_model_failures_are_mapped_without_provider_details() -> None:
    model = RetryingLanguageModel(
        primary=ScriptedLanguageModel(failures=3),
        secondary=ScriptedLanguageModel(failures=3),
    )

    with pytest.raises(ChatbotProviderUnavailable) as raised:
        asyncio.run(model.plan_query(ChatbotQueryRequest(question="위치")))

    assert str(raised.value) == ""
