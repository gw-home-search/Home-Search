from __future__ import annotations

import asyncio
from datetime import UTC, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.chat import (
    ConfiguredChatbotEngine,
    ChatbotProviderUnavailable,
    get_enabled_property_capabilities,
    get_enabled_reference_capabilities,
    get_academy_registry_repository,
    get_academy_location_repository,
    get_grounded_language_model,
    get_query_timeout_seconds,
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
    get_academy_registry_repository.cache_clear()
    get_academy_location_repository.cache_clear()
    get_query_timeout_seconds.cache_clear()
    yield
    get_grounded_language_model.cache_clear()
    get_enabled_property_capabilities.cache_clear()
    get_enabled_reference_capabilities.cache_clear()
    get_school_fact_repository.cache_clear()
    get_academy_registry_repository.cache_clear()
    get_academy_location_repository.cache_clear()
    get_query_timeout_seconds.cache_clear()


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
        ("school_location", frozenset({"school_location"})),
        (None, frozenset()),
        ("", frozenset()),
        (" school_location", frozenset()),
        ("school_location,school_location", frozenset()),
        ("academy_registry_summary", frozenset()),
        ("academy_lookup", frozenset()),
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

    assert response == {"success": True}
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

    assert response == {"success": True}
    if repository_available:
        assert captured["academy_location_repository"] is location_repository


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
                request=ChatbotQueryRequest(question="가격 추이"),
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


def test_reference_pool_failure_is_503_for_school_query(
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
        "ai_service.chat.get_school_fact_repository",
        lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()),
    )
    monkeypatch.setenv("HOME_AI_ENABLED_REFERENCE_CAPABILITIES", "school_location")

    with pytest.raises(ChatbotProviderUnavailable):
        asyncio.run(
            ConfiguredChatbotEngine().query(
                request=ChatbotQueryRequest(question="잠실엘스 주변 학교"),
                user=AuthenticatedUser(user_id=42),
                request_id="request-reference-failure",
            )
        )


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
        "ai_service.chat.get_school_fact_repository",
        lambda: (_ for _ in ()).throw(ChatbotProviderUnavailable()),
    )
    monkeypatch.setenv("HOME_AI_ENABLED_PROPERTY_CAPABILITIES", "complex_identity")
    monkeypatch.setenv("HOME_AI_ENABLED_REFERENCE_CAPABILITIES", "school_location")

    response = asyncio.run(
        ConfiguredChatbotEngine().query(
            request=ChatbotQueryRequest(question="잠실엘스 주소"),
            user=AuthenticatedUser(user_id=42),
            request_id="request-property-with-reference-failure",
        )
    )

    assert response["success"] is True
    assert response["evidenceSummary"]["capabilities"] == ["complex_identity"]
