from __future__ import annotations

import asyncio
from datetime import UTC, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.chat import (
    _apply_presentation_rollbacks,
    _answer_outcome_metric,
    _agent_outcome_metric,
    _agentic_request,
    _internal_axis_count,
    ConfiguredChatbotEngine,
    ChatbotProviderUnavailable,
    get_enabled_property_capabilities,
    get_enabled_reference_capabilities,
    get_academy_registry_repository,
    get_academy_location_repository,
    get_point_facility_repository,
    get_rail_station_repository,
    get_childcare_repository,
    get_grounded_language_model,
    get_query_timeout_seconds,
    get_answer_first_enabled,
    get_answer_first_fallback_capabilities,
    get_agentic_orchestration_enabled,
    get_dependent_workflow_enabled,
    get_artifact_v2_enabled,
    get_decision_report_enabled,
    get_property_overview_enabled,
    get_official_web_search_enabled,
    get_property_fact_repository,
    get_semantic_goal_planner_enabled,
    get_school_fact_repository,
)
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.language import RetryingLanguageModel, UnavailableLanguageModel
from ai_service.property_chat.models import (
    ComplexRecord,
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    QueryPlan,
)


@pytest.fixture(autouse=True)
def clear_language_model_cache() -> None:
    get_grounded_language_model.cache_clear()
    get_enabled_property_capabilities.cache_clear()
    get_enabled_reference_capabilities.cache_clear()
    get_school_fact_repository.cache_clear()
    get_property_fact_repository.cache_clear()
    get_academy_registry_repository.cache_clear()
    get_academy_location_repository.cache_clear()
    get_point_facility_repository.cache_clear()
    get_rail_station_repository.cache_clear()
    get_childcare_repository.cache_clear()
    get_query_timeout_seconds.cache_clear()
    yield
    get_grounded_language_model.cache_clear()
    get_enabled_property_capabilities.cache_clear()
    get_enabled_reference_capabilities.cache_clear()
    get_school_fact_repository.cache_clear()
    get_property_fact_repository.cache_clear()
    get_academy_registry_repository.cache_clear()
    get_academy_location_repository.cache_clear()
    get_point_facility_repository.cache_clear()
    get_rail_station_repository.cache_clear()
    get_childcare_repository.cache_clear()
    get_query_timeout_seconds.cache_clear()


def test_property_repository_pool_size_comes_from_runtime_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}
    repository = object()
    monkeypatch.setenv("HOME_AI_PROPERTY_DSN", "postgresql://runtime/property")
    monkeypatch.setenv("HOME_AI_DB_POOL_MIN_SIZE", "1")
    monkeypatch.setenv("HOME_AI_DB_POOL_MAX_SIZE", "2")
    monkeypatch.setattr(
        "ai_service.property_chat.postgres.PostgresPropertyFactRepository",
        lambda dsn, **kwargs: captured.update(dsn=dsn, **kwargs) or repository,
    )

    assert get_property_fact_repository() is repository
    assert captured == {
        "dsn": "postgresql://runtime/property",
        "min_pool_size": 1,
        "max_pool_size": 2,
    }


@pytest.mark.parametrize(
    ("minimum", "maximum"),
    [("0", "2"), ("3", "2"), ("one", "2"), ("1", "21")],
)
def test_property_repository_rejects_invalid_runtime_pool_size(
    monkeypatch: pytest.MonkeyPatch,
    minimum: str,
    maximum: str,
) -> None:
    monkeypatch.setenv("HOME_AI_PROPERTY_DSN", "postgresql://runtime/property")
    monkeypatch.setenv("HOME_AI_DB_POOL_MIN_SIZE", minimum)
    monkeypatch.setenv("HOME_AI_DB_POOL_MAX_SIZE", maximum)

    with pytest.raises(ChatbotProviderUnavailable):
        get_property_fact_repository()


def test_missing_openai_configuration_fails_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (
        "HOME_AI_OPENAI_API_KEY",
        "HOME_AI_OPENAI_PRIMARY_MODEL",
        "HOME_AI_OPENAI_SECONDARY_MODEL",
        "HOME_AI_OPENAI_TIMEOUT_SECONDS",
    ):
        monkeypatch.delenv(name, raising=False)

    assert isinstance(get_grounded_language_model(), UnavailableLanguageModel)


@pytest.mark.parametrize(
    "missing_name",
    [
        "HOME_AI_OPENAI_API_KEY",
        "HOME_AI_OPENAI_PRIMARY_MODEL",
        "HOME_AI_OPENAI_SECONDARY_MODEL",
    ],
)
def test_partial_openai_configuration_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    missing_name: str,
) -> None:
    monkeypatch.setenv("HOME_AI_OPENAI_API_KEY", "test-api-key")
    monkeypatch.setenv("HOME_AI_OPENAI_PRIMARY_MODEL", "primary-model")
    monkeypatch.setenv("HOME_AI_OPENAI_SECONDARY_MODEL", "secondary-model")
    monkeypatch.delenv(missing_name, raising=False)

    assert isinstance(get_grounded_language_model(), UnavailableLanguageModel)


def test_complete_openai_configuration_builds_retrying_model(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("HOME_AI_OPENAI_API_KEY", "test-api-key")
    monkeypatch.setenv("HOME_AI_OPENAI_PRIMARY_MODEL", "primary-model")
    monkeypatch.setenv("HOME_AI_OPENAI_SECONDARY_MODEL", "secondary-model")
    monkeypatch.setenv("HOME_AI_OPENAI_TIMEOUT_SECONDS", "6")

    assert isinstance(get_grounded_language_model(), RetryingLanguageModel)


def test_invalid_timeout_fails_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME_AI_OPENAI_API_KEY", "test-api-key")
    monkeypatch.setenv("HOME_AI_OPENAI_PRIMARY_MODEL", "primary-model")
    monkeypatch.setenv("HOME_AI_OPENAI_SECONDARY_MODEL", "secondary-model")
    monkeypatch.setenv("HOME_AI_OPENAI_TIMEOUT_SECONDS", "not-a-number")

    assert isinstance(get_grounded_language_model(), UnavailableLanguageModel)


@pytest.mark.parametrize("value", ["not-a-number", "0", "61", "nan", "inf"])
def test_invalid_total_query_timeout_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    value: str,
) -> None:
    monkeypatch.setenv("HOME_AI_QUERY_TIMEOUT_SECONDS", value)

    assert get_query_timeout_seconds() is None


def test_total_query_timeout_defaults_inside_bff_budget(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("HOME_AI_QUERY_TIMEOUT_SECONDS", raising=False)

    assert get_query_timeout_seconds() == 45


def test_total_query_timeout_accepts_sixty_seconds(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("HOME_AI_QUERY_TIMEOUT_SECONDS", "60")

    assert get_query_timeout_seconds() == 60


def test_answer_first_flags_default_on_and_can_be_rolled_back(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("HOME_AI_ANSWER_FIRST_ORCHESTRATION_ENABLED", raising=False)
    monkeypatch.delenv("HOME_AI_PROPERTY_OVERVIEW_ENABLED", raising=False)
    monkeypatch.delenv("HOME_AI_SEMANTIC_GOAL_PLANNER_ENABLED", raising=False)
    monkeypatch.delenv("HOME_AI_DEPENDENT_WORKFLOW_ENABLED", raising=False)
    monkeypatch.delenv("HOME_AI_DECISION_REPORT_ENABLED", raising=False)
    monkeypatch.delenv("HOME_AI_ARTIFACT_V2_ENABLED", raising=False)
    assert get_answer_first_enabled() is True
    assert get_property_overview_enabled() is True
    assert get_semantic_goal_planner_enabled() is True
    assert get_dependent_workflow_enabled() is True
    assert get_decision_report_enabled() is True
    assert get_artifact_v2_enabled() is True

    monkeypatch.setenv("HOME_AI_ANSWER_FIRST_ORCHESTRATION_ENABLED", "false")
    monkeypatch.setenv("HOME_AI_PROPERTY_OVERVIEW_ENABLED", "false")
    monkeypatch.setenv("HOME_AI_SEMANTIC_GOAL_PLANNER_ENABLED", "false")
    monkeypatch.setenv("HOME_AI_DEPENDENT_WORKFLOW_ENABLED", "false")
    monkeypatch.setenv("HOME_AI_DECISION_REPORT_ENABLED", "false")
    monkeypatch.setenv("HOME_AI_ARTIFACT_V2_ENABLED", "false")
    assert get_answer_first_enabled() is False
    assert get_property_overview_enabled() is False
    assert get_semantic_goal_planner_enabled() is False
    assert get_dependent_workflow_enabled() is False
    assert get_decision_report_enabled() is False
    assert get_artifact_v2_enabled() is False


def test_agentic_and_official_web_flags_are_independent_and_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("HOME_AI_AGENTIC_ORCHESTRATION_ENABLED", raising=False)
    monkeypatch.delenv("HOME_AI_OFFICIAL_WEB_SEARCH_ENABLED", raising=False)
    assert get_agentic_orchestration_enabled() is False
    assert get_official_web_search_enabled() is False

    monkeypatch.setenv("HOME_AI_AGENTIC_ORCHESTRATION_ENABLED", "true")
    monkeypatch.setenv("HOME_AI_OFFICIAL_WEB_SEARCH_ENABLED", "false")
    assert get_agentic_orchestration_enabled() is True
    assert get_official_web_search_enabled() is False

    monkeypatch.setenv("HOME_AI_OFFICIAL_WEB_SEARCH_ENABLED", "yes")
    assert get_official_web_search_enabled() is False


def test_internal_axis_count_prevents_research_for_well_grounded_recommendations() -> None:
    assert _internal_axis_count(
        "송파구 20억원 이하 전용 84㎡ 단지를 거래와 교통 기준으로 추천해줘"
    ) >= 3
    assert _internal_axis_count("송파구 아파트 추천해줘") == 1
    assert _agentic_request("잠실 정비사업 최신 공고를 알려줘") is True
    assert _agentic_request("헬리오시티 최신 실거래를 알려줘") is False


def test_presentation_rollbacks_keep_legacy_answer_and_remove_new_artifacts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    response = {
        "answer": "기존 답변",
        "uiArtifacts": [
            {"artifactId": "comparison-v2", "type": "comparisonTable", "version": 2},
            {"artifactId": "profile-v1", "type": "candidateProfile", "version": 1},
            {"artifactId": "fact-v1", "type": "factList", "version": 1},
        ],
        "uiReport": {
            "primaryArtifactId": "comparison-v2",
            "detailArtifactIds": ["profile-v1"],
        },
    }
    monkeypatch.setenv("HOME_AI_ARTIFACT_V2_ENABLED", "false")
    monkeypatch.setenv("HOME_AI_DECISION_REPORT_ENABLED", "true")

    without_v2 = _apply_presentation_rollbacks(response)

    assert without_v2["answer"] == "기존 답변"
    assert [
        artifact["artifactId"] for artifact in without_v2["uiArtifacts"]
    ] == ["profile-v1", "fact-v1"]
    assert without_v2["uiReport"] is None

    monkeypatch.setenv("HOME_AI_ARTIFACT_V2_ENABLED", "true")
    monkeypatch.setenv("HOME_AI_DECISION_REPORT_ENABLED", "false")
    without_report = _apply_presentation_rollbacks(response)
    assert [
        artifact["artifactId"] for artifact in without_report["uiArtifacts"]
    ] == ["comparison-v2", "fact-v1"]
    assert without_report["uiReport"] is None


def test_invalid_answer_first_flag_fails_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME_AI_ANSWER_FIRST_ORCHESTRATION_ENABLED", "yes")
    assert get_answer_first_enabled() is False


def test_answer_first_capability_fallbacks_are_independently_configurable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv(
        "HOME_AI_ANSWER_FIRST_FALLBACK_CAPABILITIES",
        "recent_trade_lookup,school_location",
    )
    assert get_answer_first_fallback_capabilities() == frozenset(
        {"recent_trade_lookup", "school_location"}
    )

    monkeypatch.setenv(
        "HOME_AI_ANSWER_FIRST_FALLBACK_CAPABILITIES",
        "recent_trade_lookup,unknown",
    )
    assert get_answer_first_fallback_capabilities() == frozenset()


def test_answer_outcome_metric_contains_no_question_or_context() -> None:
    metric = _answer_outcome_metric(
        {
            "question": "잠실엘스 가격",
            "conversationResolution": {
                "version": 1,
                "answerMode": "PARTIAL",
                "goals": [
                    {"capability": "price_trend", "status": "answered"},
                    {"capability": "school_location", "status": "unavailable"},
                ],
            },
        },
        elapsed_milliseconds=125,
    )

    assert metric == {
        "event": "chatbot_answer_completed",
        "answer_mode": "PARTIAL",
        "goal_count": 2,
        "answered_goal_count": 1,
        "degraded_goal_count": 0,
        "unavailable_goal_count": 1,
        "elapsed_milliseconds": 125,
    }
    assert "잠실엘스" not in str(metric)


def test_agent_outcome_metric_contains_only_bounded_operational_values() -> None:
    class Result:
        route = "repair"
        tool_rounds = 2
        tool_calls = 4
        web_used = True

    class Model:
        def operational_metrics(self) -> dict[str, int]:
            return {
                "provider_latency_milliseconds": 120,
                "input_tokens": 30,
                "output_tokens": 10,
                "provider_response_bytes": 900,
            }

    metric = _agent_outcome_metric(
        agent_result=Result(), models=(Model(), Model()),
        response={"answer": "저장하지 않는 답변"}, elapsed_milliseconds=400,
    )

    assert metric["agent_success"] is True
    assert metric["repair_used"] is True
    assert metric["secondary_used"] is False
    assert metric["input_tokens"] == 60
    assert metric["response_bytes"] > 0
    assert "저장하지 않는 답변" not in str(metric)


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("complex_identity", frozenset({"complex_identity"})),
        (
            "complex_identity,recent_trade_lookup",
            frozenset({"complex_identity", "recent_trade_lookup"}),
        ),
        (
            "complex_identity,recent_trade_lookup,price_trend",
            frozenset(
                {"complex_identity", "recent_trade_lookup", "price_trend"}
            ),
        ),
        (
            "complex_identity,recent_trade_lookup,price_trend,recommendation",
            frozenset(
                {
                    "complex_identity",
                    "recent_trade_lookup",
                    "price_trend",
                    "recommendation",
                }
            ),
        ),
        (
            "complex_identity,recent_trade_lookup,price_trend,recommendation,comparison",
            frozenset(
                {
                    "complex_identity",
                    "recent_trade_lookup",
                    "price_trend",
                    "recommendation",
                    "comparison",
                }
            ),
        ),
    ],
)
def test_only_approved_property_capability_configuration_is_enabled(
    monkeypatch: pytest.MonkeyPatch,
    value: str,
    expected: frozenset[str],
) -> None:
    monkeypatch.setenv("HOME_AI_ENABLED_PROPERTY_CAPABILITIES", value)

    assert get_enabled_property_capabilities() == expected


@pytest.mark.parametrize(
    "value",
    [
        None,
        "",
        "recent_trade_lookup",
        "recent_trade_lookup,complex_identity",
        "complex_identity,price_trend",
        "complex_identity,complex_identity",
        "complex_identity, price_trend",
        "comparison",
        "complex_identity,recent_trade_lookup,price_trend,comparison",
        "complex_identity,recent_trade_lookup,price_trend,recommendation,comparison,childcare_lookup",
        "complex_identity,recent_trade_lookup,price_trend,recommendation,childcare_lookup",
        "unknown",
    ],
)
def test_unapproved_or_invalid_property_capability_configuration_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    value: str | None,
) -> None:
    if value is None:
        monkeypatch.delenv("HOME_AI_ENABLED_PROPERTY_CAPABILITIES", raising=False)
    else:
        monkeypatch.setenv("HOME_AI_ENABLED_PROPERTY_CAPABILITIES", value)

    assert get_enabled_property_capabilities() == frozenset()


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("academy_lookup", frozenset({"academy_lookup"})),
        (None, frozenset()),
        ("", frozenset()),
        ("school_location", frozenset()),
        ("academy_registry_summary", frozenset()),
        ("retail_location", frozenset()),
        ("rail_station_lookup", frozenset()),
        ("childcare_lookup", frozenset()),
        ("kakao_place_search", frozenset()),
        (
            "academy_lookup,rail_station_lookup",
            frozenset({"academy_lookup", "rail_station_lookup"}),
        ),
        (
            "academy_lookup,rail_station_lookup,school_location",
            frozenset(
                {"academy_lookup", "rail_station_lookup", "school_location"}
            ),
        ),
        (
            "academy_lookup,rail_station_lookup,school_location,retail_location",
            frozenset(
                {
                    "academy_lookup",
                    "rail_station_lookup",
                    "school_location",
                    "retail_location",
                }
            ),
        ),
        (" school_location", frozenset()),
        ("school_location,school_location", frozenset()),
        ("rail_station_lookup,academy_lookup", frozenset()),
        ("school_location,academy_lookup,rail_station_lookup", frozenset()),
        (
            "academy_lookup,rail_station_lookup,school_location,childcare_lookup",
            frozenset(),
        ),
        ("unknown", frozenset()),
    ],
)
def test_reference_capability_allowlist_is_exact_and_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
    value: str | None,
    expected: frozenset[str],
) -> None:
    if value is None:
        monkeypatch.delenv("HOME_AI_ENABLED_REFERENCE_CAPABILITIES", raising=False)
    else:
        monkeypatch.setenv("HOME_AI_ENABLED_REFERENCE_CAPABILITIES", value)

    assert get_enabled_reference_capabilities() == expected


def test_academy_registry_repository_requires_reference_dsn(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("HOME_AI_REFERENCE_DSN", raising=False)

    with pytest.raises(ChatbotProviderUnavailable):
        get_academy_registry_repository()


def test_academy_location_repository_requires_reference_dsn(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("HOME_AI_REFERENCE_DSN", raising=False)

    with pytest.raises(ChatbotProviderUnavailable):
        get_academy_location_repository()


def test_point_facility_repository_requires_reference_dsn(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("HOME_AI_REFERENCE_DSN", raising=False)

    with pytest.raises(ChatbotProviderUnavailable):
        get_point_facility_repository()


def test_point_facility_repository_uses_reference_runtime_dsn(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[str] = []
    repository = object()
    monkeypatch.setenv("HOME_AI_REFERENCE_DSN", "postgresql://runtime/reference")
    monkeypatch.setattr(
        "ai_service.property_chat.reference_facilities.PostgresPointFacilityRepository",
        lambda dsn, **_kwargs: captured.append(dsn) or repository,
    )

    assert get_point_facility_repository() is repository
    assert captured == ["postgresql://runtime/reference"]


def test_rail_station_repository_requires_reference_dsn(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("HOME_AI_REFERENCE_DSN", raising=False)

    with pytest.raises(ChatbotProviderUnavailable):
        get_rail_station_repository()


def test_childcare_repository_requires_reference_dsn(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("HOME_AI_REFERENCE_DSN", raising=False)

    with pytest.raises(ChatbotProviderUnavailable):
        get_childcare_repository()


def test_childcare_repository_uses_reference_runtime_dsn(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[str] = []
    repository = object()
    monkeypatch.setenv("HOME_AI_REFERENCE_DSN", "postgresql://runtime/reference")
    monkeypatch.setattr(
        "ai_service.property_chat.childcare_centers.PostgresChildcareRepository",
        lambda dsn, **_kwargs: captured.append(dsn) or repository,
    )

    assert get_childcare_repository() is repository
    assert captured == ["postgresql://runtime/reference"]


def test_childcare_repository_wraps_connection_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("HOME_AI_REFERENCE_DSN", "postgresql://runtime/reference")
    monkeypatch.setattr(
        "ai_service.property_chat.childcare_centers.PostgresChildcareRepository",
        lambda _dsn, **_kwargs: (_ for _ in ()).throw(ValueError("private connection detail")),
    )

    with pytest.raises(ChatbotProviderUnavailable) as error:
        get_childcare_repository()

    assert "private connection detail" not in str(error.value)


@pytest.mark.parametrize("repository_available", [True, False])
def test_configured_engine_statically_composes_academy_registry_repository(
    monkeypatch: pytest.MonkeyPatch,
    repository_available: bool,
) -> None:
    captured: dict[str, object] = {}
    academy_repository = object()

    class RecordingEngine:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        async def query(self, **_kwargs: object) -> dict[str, object]:
            repository = captured["academy_registry_repository"]
            if not repository_available:
                with pytest.raises(ChatbotProviderUnavailable):
                    repository.summary()  # type: ignore[attr-defined]
            return {"success": True}

    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: object()
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", lambda: object())
    monkeypatch.setattr(
        "ai_service.chat.get_enabled_reference_capabilities",
        lambda: frozenset({"academy_registry_summary"}),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_academy_registry_repository",
        (
            (lambda: academy_repository)
            if repository_available
            else (lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()))
        ),
    )
    monkeypatch.setattr(
        "ai_service.property_chat.engine.GroundedChatbotEngine", RecordingEngine
    )

    response = asyncio.run(
        ConfiguredChatbotEngine().query(
            request=ChatbotQueryRequest(question="공식 등록 학원 수"),
            user=AuthenticatedUser(user_id=42),
            request_id="request-academy-composition",
        )
    )

    assert response["success"] is True
    if repository_available:
        assert captured["academy_registry_repository"] is academy_repository


@pytest.mark.parametrize("repository_available", [True, False])
def test_configured_engine_statically_composes_academy_location_repository(
    monkeypatch: pytest.MonkeyPatch,
    repository_available: bool,
) -> None:
    captured: dict[str, object] = {}
    location_repository = object()

    class RecordingEngine:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        async def query(self, **_kwargs: object) -> dict[str, object]:
            repository = captured["academy_location_repository"]
            if not repository_available:
                with pytest.raises(ChatbotProviderUnavailable):
                    repository.nearby()  # type: ignore[attr-defined]
            return {"success": True}

    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: object()
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", lambda: object())
    monkeypatch.setattr(
        "ai_service.chat.get_enabled_reference_capabilities",
        lambda: frozenset({"academy_lookup"}),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_academy_location_repository",
        (
            (lambda: location_repository)
            if repository_available
            else (lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()))
        ),
    )
    monkeypatch.setattr(
        "ai_service.property_chat.engine.GroundedChatbotEngine", RecordingEngine
    )

    response = asyncio.run(
        ConfiguredChatbotEngine().query(
            request=ChatbotQueryRequest(question="주변 학원 위치"),
            user=AuthenticatedUser(user_id=42),
            request_id="request-academy-location-composition",
        )
    )

    assert response["success"] is True
    if repository_available:
        assert captured["academy_location_repository"] is location_repository


@pytest.mark.parametrize("repository_available", [True, False])
def test_configured_engine_statically_composes_rail_station_repository(
    monkeypatch: pytest.MonkeyPatch,
    repository_available: bool,
) -> None:
    captured: dict[str, object] = {}
    rail_repository = object()

    class RecordingEngine:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        async def query(self, **_kwargs: object) -> dict[str, object]:
            repository = captured["rail_station_repository"]
            if not repository_available:
                with pytest.raises(ChatbotProviderUnavailable):
                    repository.nearby()  # type: ignore[attr-defined]
            return {"success": True}

    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: object()
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", lambda: object())
    monkeypatch.setattr(
        "ai_service.chat.get_enabled_reference_capabilities",
        lambda: frozenset({"rail_station_lookup"}),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_rail_station_repository",
        (
            (lambda: rail_repository)
            if repository_available
            else (lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()))
        ),
    )
    monkeypatch.setattr(
        "ai_service.property_chat.engine.GroundedChatbotEngine", RecordingEngine
    )

    response = asyncio.run(
        ConfiguredChatbotEngine().query(
            request=ChatbotQueryRequest(question="가까운 역과 노선"),
            user=AuthenticatedUser(user_id=42),
            request_id="request-rail-composition",
        )
    )

    assert response["success"] is True
    if repository_available:
        assert captured["rail_station_repository"] is rail_repository


@pytest.mark.parametrize("repository_available", [True, False])
def test_configured_engine_statically_composes_childcare_repository(
    monkeypatch: pytest.MonkeyPatch,
    repository_available: bool,
) -> None:
    captured: dict[str, object] = {}
    childcare_repository = object()

    class RecordingEngine:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        async def query(self, **_kwargs: object) -> dict[str, object]:
            repository = captured["childcare_repository"]
            if not repository_available:
                with pytest.raises(ChatbotProviderUnavailable):
                    repository.nearby()  # type: ignore[attr-defined]
            return {"success": True}

    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: object()
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", lambda: object())
    monkeypatch.setattr(
        "ai_service.chat.get_enabled_reference_capabilities",
        lambda: frozenset({"childcare_lookup"}),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_childcare_repository",
        (
            (lambda: childcare_repository)
            if repository_available
            else (lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()))
        ),
    )
    monkeypatch.setattr(
        "ai_service.property_chat.engine.GroundedChatbotEngine", RecordingEngine
    )

    response = asyncio.run(
        ConfiguredChatbotEngine().query(
            request=ChatbotQueryRequest(question="주변 어린이집"),
            user=AuthenticatedUser(user_id=42),
            request_id="request-childcare-composition",
        )
    )

    assert response["success"] is True
    if repository_available:
        assert captured["childcare_repository"] is childcare_repository


@pytest.mark.parametrize("repository_available", [True, False])
def test_configured_engine_statically_composes_point_facility_repository(
    monkeypatch: pytest.MonkeyPatch,
    repository_available: bool,
) -> None:
    captured: dict[str, object] = {}
    facility_repository = object()

    class RecordingEngine:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        async def query(self, **_kwargs: object) -> dict[str, object]:
            repository = captured["point_facility_repository"]
            if not repository_available:
                with pytest.raises(ChatbotProviderUnavailable):
                    repository.nearby()  # type: ignore[attr-defined]
            return {"success": True}

    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: object()
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", lambda: object())
    monkeypatch.setattr(
        "ai_service.chat.get_enabled_reference_capabilities",
        lambda: frozenset({"retail_location"}),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_point_facility_repository",
        (
            (lambda: facility_repository)
            if repository_available
            else (lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()))
        ),
    )
    monkeypatch.setattr(
        "ai_service.property_chat.engine.GroundedChatbotEngine", RecordingEngine
    )

    response = asyncio.run(
        ConfiguredChatbotEngine().query(
            request=ChatbotQueryRequest(question="주변 대규모점포"),
            user=AuthenticatedUser(user_id=42),
            request_id="request-retail-composition",
        )
    )

    assert response["success"] is True
    if repository_available:
        assert captured["point_facility_repository"] is facility_repository


def test_configured_engine_fails_closed_when_total_query_budget_expires(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class SlowPlanningModel:
        async def plan_query(self, _request: object) -> object:
            await asyncio.sleep(1)
            raise AssertionError("query budget was not enforced")

    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: object()
    )
    monkeypatch.setattr(
        "ai_service.chat.get_grounded_language_model", lambda: SlowPlanningModel()
    )
    monkeypatch.setattr("ai_service.chat.get_query_timeout_seconds", lambda: 0.001)
    monkeypatch.setenv(
        "HOME_AI_ENABLED_PROPERTY_CAPABILITIES",
        "complex_identity,recent_trade_lookup,price_trend",
    )

    with pytest.raises(ChatbotProviderUnavailable) as raised:
        asyncio.run(
            ConfiguredChatbotEngine().query(
                request=ChatbotQueryRequest(question="잠실엘스가 궁금해"),
                user=AuthenticatedUser(user_id=42),
                request_id="request-timeout",
            )
        )

    assert str(raised.value) == ""


class _ReferenceBoundaryPropertyRepository:
    def find_complexes(self, _name: str, _region: str | None, _limit: int):
        return [
            ComplexRecord(
                complex_id=501,
                display_name="잠실동 잠실엘스",
                region_code="11710101",
                region_name="잠실동",
                address="서울 송파구 잠실동 19",
                latitude=37.513,
                longitude=127.082,
                marker_safe=True,
                data_updated_at=datetime(2026, 7, 18, tzinfo=UTC),
            )
        ]

    def recent_trades(self, *_args):
        return []

    def monthly_trends(self, *_args):
        return []

    def latest_trade_date(self):
        return None


class _ReferenceBoundaryLanguageModel:
    def __init__(self, capability: str) -> None:
        self.capability = capability

    async def plan_query(self, _request: object) -> QueryPlan:
        return QueryPlan(
            capability=self.capability,  # type: ignore[arg-type]
            complex_name="잠실엘스",
        )

    async def draft_answer(self, *, facts, limitations, question):
        del question
        if not facts:
            return DraftAnswer([DraftSentence(limitations[0], [], [])])
        fact = facts[0]
        claim = next(claim for claim in fact.claims if claim.unit == "TEXT")
        return DraftAnswer(
            [
                DraftSentence(
                    text="잠실동 잠실엘스입니다.",
                    fact_ids=[fact.fact_id],
                    claims=[DraftClaim(fact.fact_id, claim.value, claim.unit)],
                )
            ]
        )


def test_reference_pool_failure_preserves_complex_result_for_academy_query(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository",
        lambda: _ReferenceBoundaryPropertyRepository(),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_grounded_language_model",
        lambda: _ReferenceBoundaryLanguageModel("academy_lookup"),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_academy_location_repository",
        lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()),
    )
    monkeypatch.setenv("HOME_AI_ENABLED_REFERENCE_CAPABILITIES", "academy_lookup")

    response = asyncio.run(
        ConfiguredChatbotEngine().query(
            request=ChatbotQueryRequest(question="잠실엘스 주변 교육업소"),
            user=AuthenticatedUser(user_id=42),
            request_id="request-reference-failure",
        )
    )

    assert response["success"] is True
    assert response["conversationResolution"]["answerMode"] == "BEST_EFFORT"
    assert response["conversationResolution"]["goals"] == [
        {"capability": "academy_lookup", "status": "degraded"}
    ]
    assert "잠실동 잠실엘스" in response["answer"]
    assert "단지 기본정보만" in response["limitations"][-1]


def test_inactive_school_composition_preserves_complex_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository",
        lambda: _ReferenceBoundaryPropertyRepository(),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_grounded_language_model",
        lambda: _ReferenceBoundaryLanguageModel("school_location"),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_enabled_reference_capabilities",
        lambda: frozenset({"school_location"}),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_school_fact_repository",
        lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()),
    )

    response = asyncio.run(
        ConfiguredChatbotEngine().query(
            request=ChatbotQueryRequest(question="잠실엘스 주변 학교"),
            user=AuthenticatedUser(user_id=42),
            request_id="request-inactive-school-failure",
        )
    )

    assert response["success"] is True
    assert response["conversationResolution"]["answerMode"] == "BEST_EFFORT"
    assert response["conversationResolution"]["goals"] == [
        {"capability": "school_location", "status": "degraded"}
    ]
    assert "잠실동 잠실엘스" in response["answer"]


def test_reference_pool_failure_does_not_break_property_query(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository",
        lambda: _ReferenceBoundaryPropertyRepository(),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_grounded_language_model",
        lambda: _ReferenceBoundaryLanguageModel("complex_identity"),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_academy_location_repository",
        lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()),
    )
    monkeypatch.setenv("HOME_AI_ENABLED_PROPERTY_CAPABILITIES", "complex_identity")
    monkeypatch.setenv("HOME_AI_ENABLED_REFERENCE_CAPABILITIES", "academy_lookup")

    response = asyncio.run(
        ConfiguredChatbotEngine().query(
            request=ChatbotQueryRequest(question="잠실엘스 주소"),
            user=AuthenticatedUser(user_id=42),
            request_id="request-property-with-reference-failure",
        )
    )

    assert response["success"] is True
    assert response["evidenceSummary"]["capabilities"] == ["complex_identity"]


def test_criteria_recommendation_activation_uses_only_approved_reference_sources(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}
    academy_repository = object()
    rail_repository = object()

    class RecordingEngine:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        async def query(self, **_kwargs: object) -> dict[str, object]:
            return {"success": True}

    monkeypatch.setenv(
        "HOME_AI_ENABLED_PROPERTY_CAPABILITIES",
        "complex_identity,recent_trade_lookup,price_trend,recommendation",
    )
    monkeypatch.setenv(
        "HOME_AI_ENABLED_REFERENCE_CAPABILITIES",
        "academy_lookup,rail_station_lookup",
    )
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: object()
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", lambda: object())
    monkeypatch.setattr(
        "ai_service.chat.get_academy_location_repository",
        lambda: academy_repository,
    )
    monkeypatch.setattr(
        "ai_service.chat.get_rail_station_repository", lambda: rail_repository
    )
    monkeypatch.setattr(
        "ai_service.chat.get_childcare_repository",
        lambda: (_ for _ in ()).throw(AssertionError("childcare must stay inactive")),
    )
    monkeypatch.setattr(
        "ai_service.property_chat.engine.GroundedChatbotEngine", RecordingEngine
    )

    response = asyncio.run(ConfiguredChatbotEngine().query(
        request=ChatbotQueryRequest(question="영등포구 학원 우선 후보"),
        user=AuthenticatedUser(user_id=42),
        request_id="request-criteria-activation",
    ))

    assert response["success"] is True
    assert captured["enabled_capabilities"] == frozenset({
        "complex_identity", "recent_trade_lookup", "price_trend", "recommendation",
    })
    assert captured["enabled_reference_capabilities"] == frozenset({
        "academy_lookup", "rail_station_lookup",
    })
    assert captured["enabled_recommendation_modes"] == frozenset({"CRITERIA"})
    assert captured["academy_location_repository"] is academy_repository
    assert captured["rail_station_repository"] is rail_repository
    assert captured["childcare_repository"] is None
    assert captured["school_repository"] is None
    assert captured["point_facility_repository"] is None


def test_school_activation_composes_only_the_approved_cumulative_sources(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}
    school_repository = object()
    academy_repository = object()
    rail_repository = object()

    class RecordingEngine:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        async def query(self, **_kwargs: object) -> dict[str, object]:
            return {"success": True}

    monkeypatch.setenv(
        "HOME_AI_ENABLED_REFERENCE_CAPABILITIES",
        "academy_lookup,rail_station_lookup,school_location",
    )
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: object()
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", lambda: object())
    monkeypatch.setattr(
        "ai_service.chat.get_school_fact_repository", lambda: school_repository
    )
    monkeypatch.setattr(
        "ai_service.chat.get_academy_location_repository",
        lambda: academy_repository,
    )
    monkeypatch.setattr(
        "ai_service.chat.get_rail_station_repository", lambda: rail_repository
    )
    monkeypatch.setattr(
        "ai_service.chat.get_childcare_repository",
        lambda: (_ for _ in ()).throw(AssertionError("childcare must stay inactive")),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_point_facility_repository",
        lambda: (_ for _ in ()).throw(AssertionError("retail must stay inactive")),
    )
    monkeypatch.setattr(
        "ai_service.property_chat.engine.GroundedChatbotEngine", RecordingEngine
    )

    response = asyncio.run(ConfiguredChatbotEngine().query(
        request=ChatbotQueryRequest(question="잠실엘스 주변 초등학교"),
        user=AuthenticatedUser(user_id=42),
        request_id="request-school-activation",
    ))

    assert response["success"] is True
    assert captured["enabled_reference_capabilities"] == frozenset({
        "academy_lookup", "rail_station_lookup", "school_location",
    })
    assert captured["school_repository"] is school_repository
    assert captured["academy_location_repository"] is academy_repository
    assert captured["rail_station_repository"] is rail_repository
    assert captured["childcare_repository"] is None
    assert captured["point_facility_repository"] is None


def test_retail_activation_enables_shopping_and_budget_without_childcare(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}
    retail_repository = object()

    class RecordingEngine:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        async def query(self, **_kwargs: object) -> dict[str, object]:
            return {"success": True}

    monkeypatch.setenv(
        "HOME_AI_ENABLED_PROPERTY_CAPABILITIES",
        "complex_identity,recent_trade_lookup,price_trend,recommendation,comparison",
    )
    monkeypatch.setenv(
        "HOME_AI_ENABLED_REFERENCE_CAPABILITIES",
        "academy_lookup,rail_station_lookup,school_location,retail_location",
    )
    monkeypatch.setattr("ai_service.chat.get_property_fact_repository", lambda: object())
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", lambda: object())
    monkeypatch.setattr("ai_service.chat.get_school_fact_repository", lambda: object())
    monkeypatch.setattr("ai_service.chat.get_academy_location_repository", lambda: object())
    monkeypatch.setattr("ai_service.chat.get_rail_station_repository", lambda: object())
    monkeypatch.setattr(
        "ai_service.chat.get_point_facility_repository", lambda: retail_repository
    )
    monkeypatch.setattr(
        "ai_service.chat.get_childcare_repository",
        lambda: (_ for _ in ()).throw(AssertionError("childcare must stay inactive")),
    )
    monkeypatch.setattr(
        "ai_service.property_chat.engine.GroundedChatbotEngine", RecordingEngine
    )

    response = asyncio.run(ConfiguredChatbotEngine().query(
        request=ChatbotQueryRequest(question="송파구 20억 이하 84㎡ 추천"),
        user=AuthenticatedUser(user_id=42),
        request_id="request-budget-activation",
    ))

    assert response["success"] is True
    assert captured["point_facility_repository"] is retail_repository
    assert captured["enabled_recommendation_modes"] == frozenset({"CRITERIA", "BUDGET"})
    assert captured["childcare_repository"] is None
