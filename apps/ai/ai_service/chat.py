from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import math
import os
import re
import time
from functools import lru_cache
from typing import Protocol, cast

from .auth import AuthenticatedUser
from .models import ChatbotQueryRequest
from .operational_metrics import SUPERVISOR_METRICS
from .terminal_response import (
    terminal_outcome,
    unavailable_response,
    with_terminal_outcome,
)
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


def _repository_pool_sizes() -> dict[str, int]:
    try:
        min_pool_size = int(os.getenv("HOME_AI_DB_POOL_MIN_SIZE", "1"))
        max_pool_size = int(os.getenv("HOME_AI_DB_POOL_MAX_SIZE", "5"))
    except ValueError as exception:
        raise ChatbotProviderUnavailable() from exception
    if not 1 <= min_pool_size <= max_pool_size <= 20:
        raise ChatbotProviderUnavailable()
    return {
        "min_pool_size": min_pool_size,
        "max_pool_size": max_pool_size,
    }


@lru_cache
def get_property_fact_repository() -> object:
    dsn = os.getenv("HOME_AI_PROPERTY_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.postgres import PostgresPropertyFactRepository
    from .property_chat.property_search_fallback import PropertySearchCandidateDiscovery

    try:
        discovery = None
        if _boolean_flag("HOME_AI_PROPERTY_SEARCH_FALLBACK_ENABLED", False):
            base_url = os.getenv("HOME_AI_PROPERTY_SEARCH_BASE_URL", "").strip()
            if not base_url:
                raise ValueError("property search fallback base URL is required")
            discovery = PropertySearchCandidateDiscovery(base_url)
        options: dict[str, object] = _repository_pool_sizes()
        if discovery is not None:
            options["candidate_discovery"] = discovery
        return PostgresPropertyFactRepository(dsn, **options)
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_school_fact_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.school_postgres import PostgresSchoolFactRepository

    try:
        return PostgresSchoolFactRepository(dsn, **_repository_pool_sizes())
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_academy_registry_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.academy_registry import PostgresAcademyRegistryRepository

    try:
        return PostgresAcademyRegistryRepository(dsn, **_repository_pool_sizes())
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_academy_location_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.academy_locations import PostgresAcademyLocationRepository

    try:
        return PostgresAcademyLocationRepository(dsn, **_repository_pool_sizes())
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_point_facility_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.reference_facilities import PostgresPointFacilityRepository

    try:
        return PostgresPointFacilityRepository(dsn, **_repository_pool_sizes())
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_rail_station_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.rail_stations import PostgresRailStationRepository

    try:
        return PostgresRailStationRepository(dsn, **_repository_pool_sizes())
    except Exception as exception:
        raise ChatbotProviderUnavailable() from exception


@lru_cache
def get_childcare_repository() -> object:
    dsn = os.getenv("HOME_AI_REFERENCE_DSN", "").strip()
    if not dsn:
        raise ChatbotProviderUnavailable()
    from .property_chat.childcare_centers import PostgresChildcareRepository

    try:
        return PostgresChildcareRepository(dsn, **_repository_pool_sizes())
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
        timeout_seconds = float(os.getenv("HOME_AI_QUERY_TIMEOUT_SECONDS", "55"))
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


def get_agentic_orchestration_enabled() -> bool:
    return _boolean_flag("HOME_AI_AGENTIC_ORCHESTRATION_ENABLED", False)


def get_official_web_search_enabled() -> bool:
    return _boolean_flag("HOME_AI_OFFICIAL_WEB_SEARCH_ENABLED", False)


@lru_cache
def get_supervisor_graph_mode() -> str:
    mode = os.getenv("HOME_AI_SUPERVISOR_GRAPH_MODE", "off").strip().lower()
    if mode not in {"off", "shadow", "canary", "active"}:
        raise ValueError("HOME_AI_SUPERVISOR_GRAPH_MODE must be off|shadow|canary|active")
    if mode == "off":
        return mode
    deployment_tier = os.getenv("HOME_AI_DEPLOYMENT_TIER", "").strip().lower()
    if deployment_tier not in {"local", "offline", "staging", "production"}:
        raise ValueError(
            "HOME_AI_DEPLOYMENT_TIER must be local|offline|staging|production "
            "when supervisor graph mode is enabled"
        )
    if mode == "shadow" and deployment_tier not in {"offline", "staging"}:
        raise ValueError("supervisor graph shadow mode is limited to offline or staging")
    return mode


@lru_cache
def get_supervisor_graph_canary_percent() -> int:
    raw = os.getenv("HOME_AI_SUPERVISOR_GRAPH_CANARY_PERCENT", "0").strip()
    try:
        percent = int(raw)
    except ValueError as exception:
        raise ValueError("supervisor graph canary percent must be an integer") from exception
    if not 0 <= percent <= 100:
        raise ValueError("supervisor graph canary percent must be within 0..100")
    return percent


def _supervisor_graph_bucket(user_id: int) -> int:
    digest = hashlib.sha256(f"{user_id}:supervisor-graph-v1".encode()).digest()
    return int.from_bytes(digest[:8], "big") % 100


def _select_supervisor_graph(mode: str, percent: int, user_id: int) -> bool:
    if mode == "active":
        return True
    if mode in {"canary", "shadow"}:
        effective_percent = min(percent, 5) if mode == "shadow" else percent
        return _supervisor_graph_bucket(user_id) < effective_percent
    return False


def _agentic_request(question: str) -> bool:
    from .property_chat.question_normalizer import normalize_question

    if normalize_question(question).overview:
        return False
    if re.search(r"(실거래|거래내역|가격\s*(?:흐름|추이)|시세\s*추이)", question):
        return False
    if re.search(r"(최신|현재|공고|고시|계획|예정|개통)", question):
        return True
    return re.search(r"(추천|어때|어떄|괜찮아|살기\s*어)", question) is not None


def _out_of_scope_request(question: str) -> bool:
    return re.search(
        r"(?:법률\s*(?:판단|상담)|소송|세금\s*(?:상담|신고)|가격\s*예측|"
        r"시세\s*예측|즐겨찾기|알람|메일\s*(?:발송|구독)|순위\s*매겨)",
        question,
    ) is not None


def _price_trend_requires_area(question: str) -> bool:
    if re.search(r"(?:추천|골라\s*줘|비교|차이|대조)", question):
        return False
    requests_trend = re.search(
        r"(?:가격\s*(?:흐름|추이)|시세\s*추이|월별|거래량)", question,
        re.IGNORECASE,
    ) is not None
    has_area = re.search(
        r"(?:전용\s*)?[0-9]+(?:\.[0-9]+)?\s*(?:㎡|m2|제곱미터)",
        question,
        re.IGNORECASE,
    ) is not None
    requests_other_supported_fact = re.search(
        r"(?:실거래|최근\s*거래|거래\s*(?:내역|결과)|주소|기본정보|"
        r"단지\s*정보|학원|교습소|철도|지하철|가까운\s*역|학교|"
        r"대규모점포|대형마트|백화점|쇼핑시설|어린이집|유치원)",
        question,
    ) is not None
    return requests_trend and not has_area and not requests_other_supported_fact


def _requested_candidate_count(question: str) -> int:
    match = re.search(r"(?<!\d)([1-5])\s*(?:개|곳|단지)", question)
    return int(match.group(1)) if match else 3


def _scope_label(question: str) -> str:
    match = re.search(r"([가-힣]{1,20}(?:시|군|구))", question)
    return match.group(1) if match else "질문에서 확인한 범위"


def _internal_axis_count(question: str) -> int:
    axis_patterns = (
        r"[가-힣]{1,20}(?:시|군|구)",
        r"\d+(?:\.\d+)?\s*(?:억|만원).{0,8}(?:이하|미만|예산)",
        r"(?:전용|면적)\s*\d+(?:\.\d+)?\s*㎡?",
        r"\d[\d,]*\s*세대",
        r"(?:실거래|거래\s*(?:내역|량)|가격\s*(?:흐름|추이))",
        r"(?:학원|학교|역|철도|교통|대규모점포|마트|백화점)",
    )
    return sum(re.search(pattern, question) is not None for pattern in axis_patterns)


def _agent_models(question: str) -> tuple[object, object]:
    from .property_chat.agentic_openai import OpenAIResponsesAgentModel
    from .property_chat.openai_responses import OpenAIResponsesSettings
    from .property_chat.web_evidence import WebEvidenceMode, WebEvidencePolicy

    api_key = os.getenv("HOME_AI_OPENAI_API_KEY", "").strip()
    primary_model = os.getenv("HOME_AI_OPENAI_PRIMARY_MODEL", "").strip()
    secondary_model = os.getenv("HOME_AI_OPENAI_SECONDARY_MODEL", "").strip()
    if not api_key or not primary_model or not secondary_model:
        raise ChatbotProviderUnavailable()
    try:
        timeout_seconds = float(os.getenv("HOME_AI_OPENAI_TIMEOUT_SECONDS", "8"))
        web_mode = WebEvidencePolicy().classify(
            question, internal_axis_count=_internal_axis_count(question),
        )
        web_enabled = (
            get_official_web_search_enabled()
            and web_mode is not WebEvidenceMode.DISABLED
        )
        web_required = web_enabled and web_mode is WebEvidenceMode.REQUIRED
        primary = OpenAIResponsesAgentModel(
            settings=OpenAIResponsesSettings(
                api_key=api_key, model=primary_model, timeout_seconds=timeout_seconds,
            ),
            web_search_enabled=web_enabled, web_search_required=web_required,
        )
        secondary = OpenAIResponsesAgentModel(
            settings=OpenAIResponsesSettings(
                api_key=api_key, model=secondary_model, timeout_seconds=timeout_seconds,
            ),
            web_search_enabled=web_enabled, web_search_required=web_required,
        )
    except (TypeError, ValueError) as exception:
        raise ChatbotProviderUnavailable() from exception
    return primary, secondary


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
        if _out_of_scope_request(request.question):
            return unavailable_response(
                request_id,
                answer=(
                    "현재는 단지 정보, 실거래, 가격 흐름과 승인된 주변 시설 조회만 지원합니다. "
                    "확인할 단지와 실거래 기간을 질문해 주세요."
                ),
                reason="OUT_OF_SCOPE",
            )
        if _price_trend_requires_area(request.question):
            response = unavailable_response(
                request_id,
                answer=(
                    "가격 흐름은 서로 다른 면적을 섞지 않도록 전용면적을 지정해 주세요. "
                    "예: 전용 84㎡ 최근 1년 가격 흐름"
                ),
                reason="INSUFFICIENT_EVIDENCE",
            )
            response["conversationResolution"] = {
                "version": 1,
                "answerMode": "NO_RESULT",
                "goals": [{"capability": "price_trend", "status": "unavailable"}],
                "assumptions": [],
                "omissions": ["가격 흐름 집계에 필요한 전용면적이 지정되지 않았습니다."],
            }
            evidence = response["evidenceSummary"]
            assert isinstance(evidence, dict)
            evidence["capabilities"] = ["price_trend"]
            return response
        started_at = time.monotonic()
        try:
            async with asyncio.timeout(timeout_seconds):
                repository = await asyncio.to_thread(get_property_fact_repository)
                try:
                    supervisor_mode = get_supervisor_graph_mode()
                    supervisor_percent = get_supervisor_graph_canary_percent()
                except ValueError as exception:
                    raise ChatbotProviderUnavailable() from exception
                graph_selected = _select_supervisor_graph(
                    supervisor_mode, supervisor_percent, user.user_id
                )
                minimal_fallback = False
                if (
                    not graph_selected
                    and get_agentic_orchestration_enabled()
                    and _agentic_request(request.question)
                ):
                    from .property_chat.agentic import BoundedAgentOrchestrator
                    from .property_chat.agentic_response import build_agentic_response
                    from .property_chat.agentic_tools import PropertyAgentTools

                    try:
                        primary, secondary = _agent_models(request.question)
                        requested_count = _requested_candidate_count(request.question)
                        async with asyncio.timeout(max(timeout_seconds - 8, 1)):
                            agent_result = await BoundedAgentOrchestrator(
                                primary=primary,  # type: ignore[arg-type]
                                secondary=secondary,  # type: ignore[arg-type]
                                tools=PropertyAgentTools(repository),  # type: ignore[arg-type]
                            ).run(
                                question=request.question,
                                requested_count=requested_count,
                            )
                        if agent_result.route != "minimal_fallback":
                            response = build_agentic_response(
                                request=request, request_id=request_id,
                                result=agent_result, requested_count=requested_count,
                                scope_label=_scope_label(request.question),
                            )
                            _LOGGER.info(
                                "chatbot_agent_completed",
                                extra=_agent_outcome_metric(
                                    agent_result=agent_result,
                                    models=(primary, secondary),
                                    response=response,
                                    elapsed_milliseconds=round(
                                        (time.monotonic() - started_at) * 1000
                                    ),
                                ),
                            )
                            return _apply_presentation_rollbacks(response)
                        minimal_fallback = True
                    except (ChatbotProviderUnavailable, TimeoutError):
                        minimal_fallback = True
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
                async def graph_query() -> dict[str, object]:
                    from .property_chat.supervisor_execution import GroundedGoalExecutor
                    from .property_chat.supervisor_graph import SupervisorGraphEngine

                    return await SupervisorGraphEngine(
                        planner=engine,
                        executor=GroundedGoalExecutor(engine, repository),
                        timeout_seconds=timeout_seconds,
                        metrics=SUPERVISOR_METRICS,
                    ).query(request=request, request_id=request_id)

                if graph_selected and supervisor_mode != "shadow":
                    response = await graph_query()
                else:
                    response = await engine.query(
                        request=request,
                        user=user,
                        request_id=request_id,
                    )
                    if graph_selected and supervisor_mode == "shadow":
                        shadow_task = asyncio.create_task(graph_query())
                        shadow_task.add_done_callback(_consume_shadow_result)
                response = with_terminal_outcome(_apply_presentation_rollbacks(response))
                if minimal_fallback:
                    response = _mark_minimal_agent_fallback(response)
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


def _consume_shadow_result(task: asyncio.Task[dict[str, object]]) -> None:
    try:
        response = task.result()
    except Exception:
        _LOGGER.warning("supervisor_graph_shadow_failed")
        return
    outcome = response.get("terminalOutcome")
    status = outcome.get("status") if isinstance(outcome, dict) else "UNKNOWN"
    reason = outcome.get("reason") if isinstance(outcome, dict) else "UNKNOWN"
    _LOGGER.info(
        "supervisor_graph_shadow_completed",
        extra={"terminal_status": status, "terminal_reason": reason},
    )


def _mark_minimal_agent_fallback(response: dict[str, object]) -> dict[str, object]:
    result = dict(response)
    existing_answer = result.get("answer")
    result["answer"] = (
        "AI 비교 분석을 완료하지 못해 확인 가능한 후보만 표시합니다. "
        + (existing_answer if isinstance(existing_answer, str) else "")
    ).strip()
    result["status"] = "partial_success"
    limitations = result.get("limitations")
    result["limitations"] = [
        *(limitations if isinstance(limitations, list) else []),
        "생성 경로가 모두 실패해 maintenance fallback을 사용했습니다.",
    ]
    evidence = result.get("evidenceSummary")
    if isinstance(evidence, dict):
        result["evidenceSummary"] = {**evidence, "status": "partial"}
    resolution = result.get("conversationResolution")
    if isinstance(resolution, dict):
        result["conversationResolution"] = {
            **resolution,
            "answerMode": "PARTIAL",
        }
    result["terminalOutcome"] = terminal_outcome(
        "PARTIAL", "PARTIAL_EVIDENCE", retryable=True
    )
    result["agentExecution"] = {
        "policyVersion": "agentic-recommendation-v1",
        "route": "minimal_fallback", "toolRounds": 0, "toolCalls": 0,
        "webUsed": False,
    }
    return result


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
    metric: dict[str, object] = {
        "event": "chatbot_answer_completed",
        "answer_mode": answer_mode,
        "goal_count": len(statuses),
        "answered_goal_count": statuses.count("answered"),
        "degraded_goal_count": statuses.count("degraded"),
        "unavailable_goal_count": statuses.count("unavailable"),
        "elapsed_milliseconds": max(elapsed_milliseconds, 0),
    }
    terminal = response.get("terminalOutcome")
    if isinstance(terminal, dict):
        metric.update({
            "terminal_status": terminal.get("status", "UNKNOWN"),
            "terminal_reason": terminal.get("reason", "UNKNOWN"),
            "terminal_retryable": terminal.get("retryable") is True,
        })
    execution = response.get("agentExecution")
    if isinstance(execution, dict):
        route = execution.get("route")
        metric.update({
            "agent_success": route in {"primary", "repair", "secondary"},
            "repair_used": route == "repair",
            "secondary_used": route == "secondary",
            "minimal_fallback_used": route == "minimal_fallback",
            "tool_rounds": execution.get("toolRounds", 0),
            "tool_calls": execution.get("toolCalls", 0),
            "web_used": execution.get("webUsed") is True,
            "grounding_rejection_category": (
                "ALL_GENERATION_PATHS_FAILED" if route == "minimal_fallback" else "NONE"
            ),
        })
    return metric


def _agent_outcome_metric(
    *, agent_result: object, models: tuple[object, object],
    response: dict[str, object], elapsed_milliseconds: int,
) -> dict[str, object]:
    route = getattr(agent_result, "route", "minimal_fallback")
    totals = {
        "provider_latency_milliseconds": 0,
        "input_tokens": 0,
        "output_tokens": 0,
        "provider_response_bytes": 0,
    }
    for model in models:
        metrics_reader = getattr(model, "operational_metrics", None)
        metrics = metrics_reader() if callable(metrics_reader) else {}
        if not isinstance(metrics, dict):
            continue
        for key in totals:
            value = metrics.get(key)
            if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                totals[key] += value
    return {
        "event": "chatbot_agent_completed",
        "agent_success": route in {"primary", "repair", "secondary"},
        "repair_used": route == "repair",
        "secondary_used": route == "secondary",
        "minimal_fallback_used": route == "minimal_fallback",
        "route": route,
        "tool_rounds": getattr(agent_result, "tool_rounds", 0),
        "tool_calls": getattr(agent_result, "tool_calls", 0),
        "web_used": getattr(agent_result, "web_used", False) is True,
        "grounding_rejection_category": (
            "GROUNDING_REPAIRED" if route == "repair" else "NONE"
        ),
        "elapsed_milliseconds": max(elapsed_milliseconds, 0),
        "response_bytes": len(json.dumps(response, ensure_ascii=False).encode()),
        **totals,
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
