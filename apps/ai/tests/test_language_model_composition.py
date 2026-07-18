from __future__ import annotations

import pytest

from ai_service.chat import (
    get_enabled_property_capabilities,
    get_grounded_language_model,
)
from ai_service.property_chat.language import RetryingLanguageModel, UnavailableLanguageModel


@pytest.fixture(autouse=True)
def clear_language_model_cache() -> None:
    get_grounded_language_model.cache_clear()
    get_enabled_property_capabilities.cache_clear()
    yield
    get_grounded_language_model.cache_clear()
    get_enabled_property_capabilities.cache_clear()


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


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("complex_identity", frozenset({"complex_identity"})),
        (
            "complex_identity,recent_trade_lookup",
            frozenset({"complex_identity", "recent_trade_lookup"}),
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
