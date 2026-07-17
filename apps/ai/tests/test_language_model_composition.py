from __future__ import annotations

import pytest

from ai_service.chat import get_grounded_language_model
from ai_service.property_chat.language import RetryingLanguageModel, UnavailableLanguageModel


@pytest.fixture(autouse=True)
def clear_language_model_cache() -> None:
    get_grounded_language_model.cache_clear()
    yield
    get_grounded_language_model.cache_clear()


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
