from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import TypeVar

from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest

from .engine import GroundedLanguageModel
from .models import DraftAnswer, EvidenceFact, PropertyQueryPlan

T = TypeVar("T")


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
        return await self._execute(
            lambda model: model.plan_query(request),
        )

    async def draft_answer(
        self,
        *,
        facts: list[EvidenceFact],
        limitations: list[str],
        question: str,
    ) -> DraftAnswer:
        return await self._execute(
            lambda model: model.draft_answer(
                facts=facts,
                limitations=limitations,
                question=question,
            ),
        )

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
