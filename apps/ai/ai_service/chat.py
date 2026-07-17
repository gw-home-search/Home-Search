from __future__ import annotations

import os
from functools import lru_cache
from typing import Protocol

from .auth import AuthenticatedUser
from .models import ChatbotQueryRequest


class ChatbotProviderUnavailable(Exception):
    pass


class ChatbotEngine(Protocol):
    async def query(
        self,
        *,
        request: ChatbotQueryRequest,
        user: AuthenticatedUser,
        request_id: str,
    ) -> dict[str, object]: ...


@lru_cache
def get_property_fact_repository() -> object:
    dsn = os.getenv("HOME_AI_PROPERTY_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.postgres import PostgresPropertyFactRepository

    try:
        return PostgresPropertyFactRepository(dsn)
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_grounded_language_model() -> object:
    from .property_chat.language import RetryingLanguageModel, UnavailableLanguageModel
    from .property_chat.openai_responses import (
        OpenAIResponsesLanguageModel,
        OpenAIResponsesSettings,
    )

    api_key = os.getenv("HOME_AI_OPENAI_API_KEY", "").strip()
    primary_model = os.getenv("HOME_AI_OPENAI_PRIMARY_MODEL", "").strip()
    secondary_model = os.getenv("HOME_AI_OPENAI_SECONDARY_MODEL", "").strip()
    if not api_key or not primary_model or not secondary_model:
        return UnavailableLanguageModel()
    try:
        timeout_seconds = float(os.getenv("HOME_AI_OPENAI_TIMEOUT_SECONDS", "8"))
        primary = OpenAIResponsesLanguageModel(
            settings=OpenAIResponsesSettings(
                api_key=api_key,
                model=primary_model,
                timeout_seconds=timeout_seconds,
            )
        )
        secondary = OpenAIResponsesLanguageModel(
            settings=OpenAIResponsesSettings(
                api_key=api_key,
                model=secondary_model,
                timeout_seconds=timeout_seconds,
            )
        )
    except (TypeError, ValueError):
        return UnavailableLanguageModel()
    return RetryingLanguageModel(primary=primary, secondary=secondary)


class ConfiguredChatbotEngine:
    async def query(
        self,
        *,
        request: ChatbotQueryRequest,
        user: AuthenticatedUser,
        request_id: str,
    ) -> dict[str, object]:
        from .property_chat.engine import GroundedChatbotEngine

        engine = GroundedChatbotEngine(
            repository=get_property_fact_repository(),  # type: ignore[arg-type]
            language_model=get_grounded_language_model(),  # type: ignore[arg-type]
        )
        return await engine.query(request=request, user=user, request_id=request_id)


_ENGINE = ConfiguredChatbotEngine()


def get_chatbot_engine() -> ChatbotEngine:
    return _ENGINE
