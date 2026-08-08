from __future__ import annotations

import asyncio
from datetime import UTC, datetime

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ConfiguredChatbotEngine
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.agentic import (
    AgentDecision,
    AgentRecommendationRow,
    AgentRunResult,
    WebCitation,
)
from ai_service.property_chat.agentic_response import build_agentic_response
from ai_service.property_chat.models import ComplexRecord, QueryPlan
from ai_service.property_chat.criteria_recommendation import CriteriaCandidateScope
from ai_service.terminal_response import with_terminal_outcome


def test_successful_agent_path_does_not_construct_legacy_presenters(
    monkeypatch,
) -> None:
    selected_record = ComplexRecord(
        complex_id=20, display_name="나단지", region_code="11710",
        region_name="송파구", address="서울 송파구", latitude=37.5,
        longitude=127.1, marker_safe=True,
        data_updated_at=datetime(2026, 7, 1, tzinfo=UTC), parcel_id=20,
    )

    class Repository:
        def find_complex_by_id(self, complex_id):
            return selected_record if complex_id == 20 else None

        def criteria_candidates(self, _region_name, _limit):
            return CriteriaCandidateScope("송파구", (selected_record,))

        def latest_trade_date(self):
            return None

        def latest_trades_for_candidates(self, complex_ids, _start, _end, _area):
            return {complex_id: None for complex_id in complex_ids}

    class Planner:
        async def plan_query(self, _request):
            return QueryPlan(
                "recommendation", "송파 아파트", region_name="송파구",
                recommendation_mode="CRITERIA", limit=1,
            )

    monkeypatch.setenv("HOME_AI_AGENTIC_ORCHESTRATION_ENABLED", "true")
    monkeypatch.setattr("ai_service.chat.get_property_fact_repository", Repository)
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", Planner)
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

    expected_lead = "송파구에서 검증 후보 중 1곳을 확인했습니다."
    assert response["answer"].startswith(expected_lead)
    assert response["uiSummary"]["headline"]["text"] == expected_lead
    assert response["uiReport"]["opening"]["text"].startswith(expected_lead)
    assert "최신 공식 공고의 상태를 확인했습니다. [1]" in response["answer"]
    assert response["agentExecution"]["route"] == "primary"
    artifact = response["uiArtifacts"][0]
    assert artifact["version"] == 2
    assert artifact["rows"][0]["complexId"] == 20
    assert response["citations"][-1]["sourceUrl"] == "https://www.reb.or.kr/notice?id=1"


def test_agentic_station_response_emits_verified_scope_and_focus_action() -> None:
    request = ChatbotQueryRequest(question="망포역 아파트 1개 추천")
    result = AgentRunResult(
        decision=AgentDecision(
            answer="망포역 직선거리 1500m 안의 후보를 확인했습니다. 역 거리와 단지 규모를 함께 비교했습니다.",
            rows=(AgentRecommendationRow(
                complex_id=20, complex_name="나단지", role="TRANSIT",
                summary="망포역 접근성과 단지 규모를 함께 확인한 후보입니다.",
                strengths=(("망포역 직선거리 620m입니다.", ("station-distance:20:7f0f88f9e08ad156",)),),
                tradeoffs=(("예산과 전용면적은 지정되지 않았습니다.", ("complex:20",)),),
                metrics={}, fact_ids=("complex:20", "station-distance:20:7f0f88f9e08ad156"),
            ),),
            fact_ids=("complex:20", "station-distance:20:7f0f88f9e08ad156"),
        ),
        route="primary", readiness="supported", tool_rounds=1, tool_calls=1,
        scope_label="망포역 직선거리 1500m",
    )
    selected = (ComplexRecord(
        complex_id=20, display_name="나단지", region_code="41117",
        region_name="수원시 영통구", address="수원시 영통구 망포동",
        latitude=37.245, longitude=127.056, marker_safe=True,
        data_updated_at=datetime(2026, 7, 1, tzinfo=UTC), parcel_id=7753,
    ),)

    response = build_agentic_response(
        request=request, request_id="station-agentic", result=result,
        requested_count=1, scope_label="망포역 직선거리 1500m",
        scope_type="STATION_RADIUS", criteria_order=("TRANSIT",),
        basis_notes=("예산 미지정", "전용면적 미지정 · 거래금액 후보 간 비교 제외"),
        selected_complexes=selected,
    )

    assert response["uiArtifacts"][0]["basis"]["scopeType"] == "STATION_RADIUS"
    assert response["uiActions"] == [{
        "type": "focusComplex", "version": 1,
        "actionId": "action-station-agentic-focus-complex-20",
        "label": "나단지 지도에서 보기", "parcelId": 7753, "complexId": 20,
        "center": {"lat": 37.245, "lng": 127.056}, "level": 4,
        "openDetail": True, "autoRun": False, "factIds": ["complex:20"],
    }]
    assert response["uiReport"]["actionIds"] == [
        "action-station-agentic-focus-complex-20"
    ]
    assert [item["text"] for item in response["uiReport"]["basis"]] == [
        "선정 범위: 망포역 직선거리 1500m", "예산 미지정",
        "전용면적 미지정 · 거래금액 후보 간 비교 제외",
    ]


def test_partial_evidence_keeps_grounded_rows_and_focus_action() -> None:
    result = AgentRunResult(
        decision=AgentDecision(
            answer="역 거리 근거로 후보를 골랐습니다.",
            rows=(AgentRecommendationRow(
                complex_id=20, complex_name="나단지", role="TRANSIT",
                summary="역과 가까운 후보입니다.",
                strengths=(("역 거리 확인", ("complex:20",)),),
                tradeoffs=(("학교 source 미확인", ("complex:20",)),),
                metrics={}, fact_ids=("complex:20",),
            ),), fact_ids=("complex:20",), limitations=("학교 source 미확인",),
        ),
        route="primary", readiness="partial", tool_rounds=2, tool_calls=2,
        scope_label="망포역 직선거리 1500m", scope_fact_id="scope:station",
    )
    selected = (ComplexRecord(
        complex_id=20, display_name="나단지", region_code="41117",
        region_name="수원시 영통구", address="수원시 영통구 망포동",
        latitude=37.245, longitude=127.056, marker_safe=True,
        data_updated_at=datetime(2026, 7, 1, tzinfo=UTC), parcel_id=7753,
    ),)

    response = build_agentic_response(
        request=ChatbotQueryRequest(question="망포역과 초등학교가 가까운 아파트 추천"),
        request_id="partial-station", result=result, requested_count=1,
        scope_label="망포역 직선거리 1500m", scope_type="STATION_RADIUS",
        criteria_order=("TRANSIT", "EDUCATION"), selected_complexes=selected,
    )

    assert response["success"] is True
    assert response["status"] == "partial_success"
    assert with_terminal_outcome(response)["terminalOutcome"] == {
        "version": 1, "status": "PARTIAL", "reason": "PARTIAL_EVIDENCE",
        "retryable": False,
    }
    assert response["uiArtifacts"]
    assert response["uiActions"]


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
