from __future__ import annotations

import asyncio
import math
import os
from functools import lru_cache
from typing import Protocol, cast

from .auth import AuthenticatedUser
from .models import ChatbotQueryRequest
from .property_chat.models import PropertyCapability, ReferenceCapability

_APPROVED_PROPERTY_CAPABILITY_CONFIGURATIONS = frozenset(
    {
        ("complex_identity",),
        ("complex_identity", "recent_trade_lookup"),
        ("complex_identity", "recent_trade_lookup", "price_trend"),
    }
)


class ChatbotProviderUnavailable(Exception):
    pass


class _UnavailableSchoolFactRepository:
    def active_snapshot(self) -> object:
        raise ChatbotProviderUnavailable()

    def nearby_schools(self, **_kwargs: object) -> object:
        raise ChatbotProviderUnavailable()


class _UnavailableAcademyRegistryRepository:
    def summary(self, **_kwargs: object) -> object:
        raise ChatbotProviderUnavailable()


class _UnavailableAcademyLocationRepository:
    def nearby(self, **_kwargs: object) -> object:
        raise ChatbotProviderUnavailable()


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
def get_school_fact_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.school_postgres import PostgresSchoolFactRepository

    try:
        return PostgresSchoolFactRepository(dsn)
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_academy_registry_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.academy_registry import PostgresAcademyRegistryRepository

    try:
        return PostgresAcademyRegistryRepository(dsn)
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_academy_location_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.academy_locations import PostgresAcademyLocationRepository

    try:
        return PostgresAcademyLocationRepository(dsn)
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


@lru_cache
def get_enabled_property_capabilities() -> frozenset[PropertyCapability]:
    raw_value = os.getenv("HOME_AI_ENABLED_PROPERTY_CAPABILITIES", "")
    if not raw_value:
        return frozenset()
    values = raw_value.split(",")
    if (
        any(not value or value != value.strip() for value in values)
        or len(values) != len(set(values))
        or tuple(values) not in _APPROVED_PROPERTY_CAPABILITY_CONFIGURATIONS
    ):
        return frozenset()
    return cast(frozenset[PropertyCapability], frozenset(values))


@lru_cache
def get_enabled_reference_capabilities() -> frozenset[ReferenceCapability]:
    raw_value = os.getenv("HOME_AI_ENABLED_REFERENCE_CAPABILITIES", "")
    if raw_value != "school_location":
        return frozenset()
    return frozenset({"school_location"})


@lru_cache
def get_query_timeout_seconds() -> float | None:
    try:
        timeout_seconds = float(os.getenv("HOME_AI_QUERY_TIMEOUT_SECONDS", "45"))
    except (TypeError, ValueError):
        return None
    if not math.isfinite(timeout_seconds) or not 1 <= timeout_seconds <= 60:
        return None
    return timeout_seconds


class ConfiguredChatbotEngine:
    async def query(
        self,
        *,
        request: ChatbotQueryRequest,
        user: AuthenticatedUser,
        request_id: str,
    ) -> dict[str, object]:
        from .property_chat.engine import GroundedChatbotEngine

        timeout_seconds = get_query_timeout_seconds()
        if timeout_seconds is None:
            raise ChatbotProviderUnavailable()
        try:
            async with asyncio.timeout(timeout_seconds):
                repository = await asyncio.to_thread(get_property_fact_repository)
                language_model = await asyncio.to_thread(get_grounded_language_model)
                enabled_reference_capabilities = get_enabled_reference_capabilities()
                school_repository = None
                academy_registry_repository = None
                academy_location_repository = None
                if "school_location" in enabled_reference_capabilities:
                    try:
                        school_repository = await asyncio.to_thread(get_school_fact_repository)
                    except ChatbotProviderUnavailable:
                        school_repository = _UnavailableSchoolFactRepository()
                if "academy_registry_summary" in enabled_reference_capabilities:
                    try:
                        academy_registry_repository = await asyncio.to_thread(
                            get_academy_registry_repository
                        )
                    except ChatbotProviderUnavailable:
                        academy_registry_repository = (
                            _UnavailableAcademyRegistryRepository()
                        )
                if "academy_lookup" in enabled_reference_capabilities:
                    try:
                        academy_location_repository = await asyncio.to_thread(
                            get_academy_location_repository
                        )
                    except ChatbotProviderUnavailable:
                        academy_location_repository = (
                            _UnavailableAcademyLocationRepository()
                        )
                engine = GroundedChatbotEngine(
                    repository=repository,  # type: ignore[arg-type]
                    school_repository=school_repository,  # type: ignore[arg-type]
                    academy_registry_repository=(
                        academy_registry_repository  # type: ignore[arg-type]
                    ),
                    academy_location_repository=(
                        academy_location_repository  # type: ignore[arg-type]
                    ),
                    language_model=language_model,  # type: ignore[arg-type]
                    enabled_capabilities=get_enabled_property_capabilities(),
                    enabled_reference_capabilities=enabled_reference_capabilities,
                )
                return await engine.query(
                    request=request,
                    user=user,
                    request_id=request_id,
                )
        except TimeoutError as exception:
            raise ChatbotProviderUnavailable() from exception


_ENGINE = ConfiguredChatbotEngine()


def get_chatbot_engine() -> ChatbotEngine:
    return _ENGINE
