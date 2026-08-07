from __future__ import annotations

import pytest

from ai_service.chat import (
    ConfiguredChatbotEngine,
    _price_trend_requires_area,
    _select_supervisor_graph,
    get_supervisor_graph_canary_percent,
    get_supervisor_graph_mode,
)
from ai_service.auth import AuthenticatedUser
from ai_service.models import ChatbotQueryRequest
import asyncio


def clear_settings() -> None:
    get_supervisor_graph_mode.cache_clear()
    get_supervisor_graph_canary_percent.cache_clear()


def test_supervisor_graph_rollout_defaults_to_off(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("HOME_AI_SUPERVISOR_GRAPH_MODE", raising=False)
    monkeypatch.delenv("HOME_AI_SUPERVISOR_GRAPH_CANARY_PERCENT", raising=False)
    clear_settings()

    assert get_supervisor_graph_mode() == "off"
    assert get_supervisor_graph_canary_percent() == 0


def test_supervisor_graph_canary_is_stable_per_authenticated_subject() -> None:
    selections = {_select_supervisor_graph("canary", 25, 42) for _ in range(10)}
    assert len(selections) == 1
    assert _select_supervisor_graph("off", 100, 42) is False
    assert _select_supervisor_graph("active", 0, 42) is True


def test_invalid_rollout_and_production_shadow_fail_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME_AI_SUPERVISOR_GRAPH_MODE", "shadow")
    monkeypatch.delenv("HOME_AI_DEPLOYMENT_TIER", raising=False)
    clear_settings()
    with pytest.raises(ValueError):
        get_supervisor_graph_mode()

    monkeypatch.setenv("HOME_AI_DEPLOYMENT_TIER", "production")
    clear_settings()
    with pytest.raises(ValueError):
        get_supervisor_graph_mode()

    monkeypatch.setenv("HOME_AI_DEPLOYMENT_TIER", "staging")
    clear_settings()
    assert get_supervisor_graph_mode() == "shadow"

    monkeypatch.setenv("HOME_AI_SUPERVISOR_GRAPH_MODE", "canary")
    monkeypatch.setenv("HOME_AI_SUPERVISOR_GRAPH_CANARY_PERCENT", "101")
    clear_settings()
    assert get_supervisor_graph_mode() == "canary"
    with pytest.raises(ValueError):
        get_supervisor_graph_canary_percent()


def test_explicit_later_scope_question_returns_typed_out_of_scope_without_provider() -> None:
    response = asyncio.run(ConfiguredChatbotEngine().query(
        request=ChatbotQueryRequest(question="잠실엘스 가격 예측 알람을 메일로 보내줘"),
        user=AuthenticatedUser(user_id=42),
        request_id="request-1",
    ))

    assert response["terminalOutcome"] == {
        "version": 1, "status": "UNAVAILABLE",
        "reason": "OUT_OF_SCOPE", "retryable": False,
    }


@pytest.mark.parametrize(
    "question",
    (
        "헬리오시티 가격 흐름 보여줘",
    ),
)
def test_price_trend_without_area_requests_required_condition_before_provider(
    monkeypatch: pytest.MonkeyPatch,
    question: str,
) -> None:
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository",
        lambda: (_ for _ in ()).throw(AssertionError("repository must not run")),
    )

    response = asyncio.run(ConfiguredChatbotEngine().query(
        request=ChatbotQueryRequest(question=question),
        user=AuthenticatedUser(user_id=42),
        request_id="request-missing-trend-area",
    ))

    assert response["terminalOutcome"] == {
        "version": 1,
        "status": "UNAVAILABLE",
        "reason": "INSUFFICIENT_EVIDENCE",
        "retryable": False,
    }
    assert "전용면적" in response["answer"]
    assert response["evidenceSummary"]["capabilities"] == ["price_trend"]


@pytest.mark.parametrize(
    "question",
    (
        "송파구에서 거래량 많은 단지 3곳 추천해줘",
        "잠실엘스와 반포자이 거래량을 비교해줘",
    ),
)
def test_recommendation_and_comparison_are_not_blocked_by_trend_area_preflight(
    question: str,
) -> None:
    assert _price_trend_requires_area(question) is False
