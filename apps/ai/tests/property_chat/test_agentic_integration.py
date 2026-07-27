from __future__ import annotations

import asyncio

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ConfiguredChatbotEngine
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.agentic import (
    AgentDecision,
    AgentRecommendationRow,
    AgentRunResult,
)


def test_successful_agent_path_does_not_construct_legacy_presenters(
    monkeypatch,
) -> None:
    monkeypatch.setenv("HOME_AI_AGENTIC_ORCHESTRATION_ENABLED", "true")
    monkeypatch.setattr("ai_service.chat.get_property_fact_repository", object)
    monkeypatch.setattr("ai_service.chat._agent_models", lambda _question: (object(), object()))

    async def run_agent(self, **_kwargs):  # noqa: ANN001, ANN003
        return AgentRunResult(
            decision=AgentDecision(
                answer="예산·면적 미지정 상태에서 검증 근거를 균형 비교했습니다.",
                rows=(AgentRecommendationRow(
                    complex_id=20, complex_name="나단지", role="BALANCED",
                    summary="거래와 규모를 함께 비교했습니다.",
                    strengths=(("규모가 확인됩니다.", ("complex:20",)),),
                    tradeoffs=(("예산 적합성은 추가 확인이 필요합니다.", ("complex:20",)),),
                    metrics={}, fact_ids=("complex:20",),
                ),),
                fact_ids=("complex:20",), limitations=("예산·면적 미지정",),
            ),
            route="primary", readiness="supported", tool_rounds=2, tool_calls=3,
            scope_label="송파구",
        )

    monkeypatch.setattr(
        "ai_service.property_chat.agentic.BoundedAgentOrchestrator.run", run_agent,
    )

    def fail_legacy(*_args, **_kwargs):
        raise AssertionError("legacy engine must not be constructed on agent success")

    monkeypatch.setattr(
        "ai_service.property_chat.engine.GroundedChatbotEngine", fail_legacy,
    )

    response = asyncio.run(ConfiguredChatbotEngine().query(
        request=ChatbotQueryRequest(question="송파 아파트 1개 추천"),
        user=AuthenticatedUser(user_id=42), request_id="agentic-success",
    ))

    assert response["answer"].startswith("예산·면적 미지정")
    assert response["agentExecution"]["route"] == "primary"
    artifact = response["uiArtifacts"][0]
    assert artifact["version"] == 2
    assert artifact["rows"][0]["complexId"] == 20
