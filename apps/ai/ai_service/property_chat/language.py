from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Literal, TypeVar

from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest

from .engine import GroundedLanguageModel, validate_draft
from .models import DraftAnswer, EvidenceFact, PropertyQueryPlan

T = TypeVar("T")


class LanguageModelStageError(ChatbotProviderUnavailable):
    def __init__(self, stage: Literal["PLAN", "DRAFT"]) -> None:
        if stage not in {"PLAN", "DRAFT"}:
            raise ValueError("invalid language model stage")
        super().__init__()
        self.stage = stage


class UnavailableLanguageModel:
    async def plan_query(self, _request: ChatbotQueryRequest) -> PropertyQueryPlan:
        raise ChatbotProviderUnavailable()

    async def draft_answer(
        self,
        *,
        facts: list[EvidenceFact],
        limitations: list[str],
        question: str,
    ) -> DraftAnswer:
        del facts, limitations, question
        raise ChatbotProviderUnavailable()


class RetryingLanguageModel:
    def __init__(
        self,
        *,
        primary: GroundedLanguageModel,
        secondary: GroundedLanguageModel,
    ) -> None:
        self._primary = primary
        self._secondary = secondary

    async def plan_query(self, request: ChatbotQueryRequest) -> PropertyQueryPlan:
        try:
            return await self._execute(
                lambda model: model.plan_query(request),
            )
        except ChatbotProviderUnavailable as exception:
            raise LanguageModelStageError("PLAN") from exception

    async def draft_answer(
        self,
        *,
        facts: list[EvidenceFact],
        limitations: list[str],
        question: str,
    ) -> DraftAnswer:
        async def draft_and_validate(model: GroundedLanguageModel) -> DraftAnswer:
            draft = await model.draft_answer(
                facts=facts,
                limitations=limitations,
                question=question,
            )
            validate_draft(draft, facts, "supported" if facts else "unavailable")
            return draft

        try:
            return await self._execute(draft_and_validate)
        except ChatbotProviderUnavailable as exception:
            raise LanguageModelStageError("DRAFT") from exception

    async def _execute(
        self,
        operation: Callable[[GroundedLanguageModel], Awaitable[T]],
    ) -> T:
        for _attempt in range(2):
            try:
                return await operation(self._primary)
            except Exception:
                pass
        try:
            return await operation(self._secondary)
        except Exception as exception:
            raise ChatbotProviderUnavailable() from exception
