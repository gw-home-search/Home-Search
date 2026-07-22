from __future__ import annotations

import asyncio
import logging
import math
import os
import time
from functools import lru_cache
from typing import Protocol, cast

from .auth import AuthenticatedUser
from .models import ChatbotQueryRequest
from .property_chat.models import PropertyCapability, QueryCapability, ReferenceCapability

_LOGGER = logging.getLogger(__name__)
_ANSWER_FIRST_FALLBACK_CAPABILITIES = frozenset({
    "complex_identity",
    "recent_trade_lookup",
    "price_trend",
    "school_location",
    "retail_location",
    "academy_registry_summary",
    "academy_lookup",
    "rail_station_lookup",
    "childcare_lookup",
    "kakao_place_search",
    "comparison",
    "recommendation",
})

_APPROVED_PROPERTY_CAPABILITY_CONFIGURATIONS = frozenset(
    {
        ("complex_identity",),
        ("complex_identity", "recent_trade_lookup"),
        ("complex_identity", "recent_trade_lookup", "price_trend"),
        (
            "complex_identity",
            "recent_trade_lookup",
            "price_trend",
            "recommendation",
        ),
        (
            "complex_identity",
            "recent_trade_lookup",
            "price_trend",
            "recommendation",
            "comparison",
        ),
    }
)
_APPROVED_REFERENCE_CAPABILITY_CONFIGURATIONS = frozenset(
    {
        ("academy_lookup",),
        ("academy_lookup", "rail_station_lookup"),
        ("academy_lookup", "rail_station_lookup", "school_location"),
        (
            "academy_lookup",
            "rail_station_lookup",
            "school_location",
            "retail_location",
        ),
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


class _UnavailablePointFacilityRepository:
    def nearby(self, **_kwargs: object) -> object:
        raise ChatbotProviderUnavailable()


class _UnavailableRailStationRepository:
    def nearby(self, **_kwargs: object) -> object:
        raise ChatbotProviderUnavailable()


class _UnavailableChildcareRepository:
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
def get_point_facility_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.reference_facilities import PostgresPointFacilityRepository

    try:
        return PostgresPointFacilityRepository(dsn)
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_rail_station_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.rail_stations import PostgresRailStationRepository

    try:
        return PostgresRailStationRepository(dsn)
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_childcare_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.childcare_centers import PostgresChildcareRepository

    try:
        return PostgresChildcareRepository(dsn)
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
    if not raw_value:
        return frozenset()
    values = raw_value.split(",")
    if (
        any(not value or value != value.strip() for value in values)
        or len(values) != len(set(values))
        or tuple(values) not in _APPROVED_REFERENCE_CAPABILITY_CONFIGURATIONS
    ):
        return frozenset()
    return cast(frozenset[ReferenceCapability], frozenset(values))


@lru_cache
def get_query_timeout_seconds() -> float | None:
    try:
        timeout_seconds = float(os.getenv("HOME_AI_QUERY_TIMEOUT_SECONDS", "45"))
    except (TypeError, ValueError):
        return None
    if not math.isfinite(timeout_seconds) or not 1 <= timeout_seconds <= 60:
        return None
    return timeout_seconds


def get_answer_first_enabled() -> bool:
    return _boolean_flag("HOME_AI_ANSWER_FIRST_ORCHESTRATION_ENABLED", True)


def get_property_overview_enabled() -> bool:
    return _boolean_flag("HOME_AI_PROPERTY_OVERVIEW_ENABLED", True)


def get_semantic_goal_planner_enabled() -> bool:
    return _boolean_flag("HOME_AI_SEMANTIC_GOAL_PLANNER_ENABLED", True)


def get_dependent_workflow_enabled() -> bool:
    return _boolean_flag("HOME_AI_DEPENDENT_WORKFLOW_ENABLED", True)


def get_decision_report_enabled() -> bool:
    return _boolean_flag("HOME_AI_DECISION_REPORT_ENABLED", True)


def get_artifact_v2_enabled() -> bool:
    return _boolean_flag("HOME_AI_ARTIFACT_V2_ENABLED", True)


def get_answer_first_fallback_capabilities() -> frozenset[QueryCapability]:
    raw_value = os.getenv("HOME_AI_ANSWER_FIRST_FALLBACK_CAPABILITIES")
    if raw_value is None:
        return cast(
            frozenset[QueryCapability], _ANSWER_FIRST_FALLBACK_CAPABILITIES
        )
    configured = frozenset(
        value.strip() for value in raw_value.split(",") if value.strip()
    )
    if not configured or not configured.issubset(_ANSWER_FIRST_FALLBACK_CAPABILITIES):
        return frozenset()
    return cast(frozenset[QueryCapability], configured)


def _boolean_flag(name: str, default: bool) -> bool:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    value = raw_value.strip().lower()
    if value == "true":
        return True
    if value == "false":
        return False
    return False


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
        started_at = time.monotonic()
        try:
            async with asyncio.timeout(timeout_seconds):
                repository = await asyncio.to_thread(get_property_fact_repository)
                language_model = await asyncio.to_thread(get_grounded_language_model)
                enabled_reference_capabilities = get_enabled_reference_capabilities()
                school_repository = None
                academy_registry_repository = None
                academy_location_repository = None
                point_facility_repository = None
                rail_station_repository = None
                childcare_repository = None
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
                if "retail_location" in enabled_reference_capabilities:
                    try:
                        point_facility_repository = await asyncio.to_thread(
                            get_point_facility_repository
                        )
                    except ChatbotProviderUnavailable:
                        point_facility_repository = (
                            _UnavailablePointFacilityRepository()
                        )
                if "rail_station_lookup" in enabled_reference_capabilities:
                    try:
                        rail_station_repository = await asyncio.to_thread(
                            get_rail_station_repository
                        )
                    except ChatbotProviderUnavailable:
                        rail_station_repository = _UnavailableRailStationRepository()
                if "childcare_lookup" in enabled_reference_capabilities:
                    try:
                        childcare_repository = await asyncio.to_thread(
                            get_childcare_repository
                        )
                    except ChatbotProviderUnavailable:
                        childcare_repository = _UnavailableChildcareRepository()
                engine = GroundedChatbotEngine(
                    repository=repository,  # type: ignore[arg-type]
                    school_repository=school_repository,  # type: ignore[arg-type]
                    academy_registry_repository=(
                        academy_registry_repository  # type: ignore[arg-type]
                    ),
                    academy_location_repository=(
                        academy_location_repository  # type: ignore[arg-type]
                    ),
                    point_facility_repository=(
                        point_facility_repository  # type: ignore[arg-type]
                    ),
                    rail_station_repository=(
                        rail_station_repository  # type: ignore[arg-type]
                    ),
                    childcare_repository=(
                        childcare_repository  # type: ignore[arg-type]
                    ),
                    language_model=language_model,  # type: ignore[arg-type]
                    enabled_capabilities=get_enabled_property_capabilities(),
                    enabled_reference_capabilities=enabled_reference_capabilities,
                    enabled_recommendation_modes=(
                        frozenset({"CRITERIA", "BUDGET"})
                        if "retail_location" in enabled_reference_capabilities
                        else frozenset({"CRITERIA"})
                    ),
                    answer_first_enabled=get_answer_first_enabled(),
                    property_overview_enabled=get_property_overview_enabled(),
                    answer_first_fallback_capabilities=(
                        get_answer_first_fallback_capabilities()
                    ),
                    semantic_goal_planner_enabled=get_semantic_goal_planner_enabled(),
                    dependent_workflow_enabled=get_dependent_workflow_enabled(),
                    polish_budget_seconds=max(timeout_seconds - 5, 0),
                )
                response = _apply_presentation_rollbacks(await engine.query(
                    request=request,
                    user=user,
                    request_id=request_id,
                ))
                _LOGGER.info(
                    "chatbot_answer_completed",
                    extra=_answer_outcome_metric(
                        response,
                        elapsed_milliseconds=round(
                            (time.monotonic() - started_at) * 1000
                        ),
                    ),
                )
                return response
        except TimeoutError as exception:
            raise ChatbotProviderUnavailable() from exception


def _answer_outcome_metric(
    response: dict[str, object], *, elapsed_milliseconds: int
) -> dict[str, object]:
    resolution = response.get("conversationResolution")
    answer_mode = "UNKNOWN"
    statuses: list[str] = []
    if isinstance(resolution, dict) and resolution.get("version") == 1:
        candidate_mode = resolution.get("answerMode")
        if candidate_mode in {"COMPLETE", "BEST_EFFORT", "PARTIAL", "NO_RESULT"}:
            answer_mode = candidate_mode
        goals = resolution.get("goals")
        if isinstance(goals, list):
            statuses = [
                status
                for goal in goals
                if isinstance(goal, dict)
                and (status := goal.get("status"))
                in {"answered", "degraded", "unavailable"}
            ]
    return {
        "event": "chatbot_answer_completed",
        "answer_mode": answer_mode,
        "goal_count": len(statuses),
        "answered_goal_count": statuses.count("answered"),
        "degraded_goal_count": statuses.count("degraded"),
        "unavailable_goal_count": statuses.count("unavailable"),
        "elapsed_milliseconds": max(elapsed_milliseconds, 0),
    }


def _apply_presentation_rollbacks(
    response: dict[str, object],
) -> dict[str, object]:
    result = dict(response)
    artifacts = result.get("uiArtifacts")
    if not isinstance(artifacts, list):
        return result
    filtered = list(artifacts)
    if not get_artifact_v2_enabled():
        filtered = [
            artifact
            for artifact in filtered
            if not isinstance(artifact, dict) or artifact.get("version") != 2
        ]
    if not get_decision_report_enabled():
        filtered = [
            artifact
            for artifact in filtered
            if not isinstance(artifact, dict)
            or artifact.get("type") != "candidateProfile"
        ]
        result["uiReport"] = None
    else:
        available_ids = {
            artifact_id
            for artifact in filtered
            if isinstance(artifact, dict)
            and isinstance((artifact_id := artifact.get("artifactId")), str)
        }
        report = result.get("uiReport")
        if isinstance(report, dict):
            primary_id = report.get("primaryArtifactId")
            detail_ids = report.get("detailArtifactIds")
            if (
                isinstance(primary_id, str) and primary_id not in available_ids
            ) or (
                isinstance(detail_ids, list)
                and any(
                    isinstance(artifact_id, str)
                    and artifact_id not in available_ids
                    for artifact_id in detail_ids
                )
            ):
                result["uiReport"] = None
    result["uiArtifacts"] = filtered
    return result


_ENGINE = ConfiguredChatbotEngine()


def get_chatbot_engine() -> ChatbotEngine:
    return _ENGINE
