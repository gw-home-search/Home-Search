from __future__ import annotations

import asyncio

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ConfiguredChatbotEngine
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.agentic import (
    AgentDecision,
    AgentRecommendationRow,
    AgentRunResult,
    WebCitation,
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
                web_citations=(WebCitation(
                    fact_id="web:0123456789abcdef0123456789abcdef",
                    title="공식 공고", url="https://www.reb.or.kr/notice?id=1",
                ),),
                research_claims=("최신 공식 공고의 상태를 확인했습니다. [1]",),
            ),
            route="primary", readiness="supported", tool_rounds=2, tool_calls=3,
            scope_label="송파구", web_used=True,
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

    expected_lead = "송파구 조건을 적용해 1곳을 확인했으며 먼저 볼 곳은 나단지입니다."
    assert response["answer"].startswith(expected_lead)
    assert response["uiSummary"]["headline"]["text"] == expected_lead
    assert response["uiReport"]["opening"]["text"] == expected_lead
    assert "최신 공식 공고의 상태를 확인했습니다. [1]" in response["answer"]
    assert response["agentExecution"]["route"] == "primary"
    artifact = response["uiArtifacts"][0]
    assert artifact["version"] == 2
    assert artifact["rows"][0]["complexId"] == 20
    assert response["citations"][-1]["sourceUrl"] == "https://www.reb.or.kr/notice?id=1"


def test_latest_official_question_returns_cited_research_without_candidate_rows(
    monkeypatch,
) -> None:
    monkeypatch.setenv("HOME_AI_AGENTIC_ORCHESTRATION_ENABLED", "true")
    monkeypatch.setattr("ai_service.chat.get_property_fact_repository", object)
    monkeypatch.setattr("ai_service.chat._agent_models", lambda _question: (object(), object()))

    async def run_agent(self, **_kwargs):  # noqa: ANN001, ANN003
        return AgentRunResult(
            decision=AgentDecision(
                answer="공식 근거만 분리해 확인했습니다.", rows=(), fact_ids=(),
                web_citations=(WebCitation(
                    fact_id="web:0123456789abcdef0123456789abcdef",
                    title="송파구 공식 공고",
                    url="https://www.reb.or.kr/notice?id=1",
                ),),
                research_claims=(
                    "잠실 정비사업의 최신 공식 공고에서 현재 상태를 확인했습니다. [1]",
                ),
            ),
            route="primary", readiness="supported", tool_rounds=1, tool_calls=0,
            web_used=True, scope_label="잠실",
        )

    monkeypatch.setattr(
        "ai_service.property_chat.agentic.BoundedAgentOrchestrator.run", run_agent,
    )

    response = asyncio.run(ConfiguredChatbotEngine().query(
        request=ChatbotQueryRequest(question="잠실 정비사업 최신 공고를 알려줘"),
        user=AuthenticatedUser(user_id=42), request_id="official-research-success",
    ))

    expected = "잠실 정비사업의 최신 공식 공고에서 현재 상태를 확인했습니다. [1]"
    assert response["answer"].startswith(expected)
    assert response["uiSummary"]["headline"]["text"] == expected
    assert response["uiReport"] is None
    assert response["evidenceSummary"]["capabilities"] == [
        "redevelopment_official_evidence"
    ]
    assert response["citations"][-1]["evidenceGrade"] == "D"
