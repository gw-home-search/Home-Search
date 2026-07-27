from __future__ import annotations

import asyncio
from collections.abc import Mapping, Sequence

import pytest

from ai_service.property_chat.agentic import (
    AgentDecision,
    AgentRecommendationRow,
    AgentToolCall,
    AgentTurn,
    BoundedAgentOrchestrator,
    ToolEvidence,
)


class ScriptedModel:
    def __init__(self, turns: Sequence[AgentTurn | Exception]) -> None:
        self.turns = list(turns)
        self.calls = 0

    async def respond(self, **_kwargs: object) -> AgentTurn:
        self.calls += 1
        result = self.turns.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


class RecordingTools:
    def __init__(self) -> None:
        self.calls: list[str] = []

    async def execute(
        self, name: str, arguments: Mapping[str, object]
    ) -> ToolEvidence:
        self.calls.append(name)
        if name == "get_region_candidate_pool":
            return ToolEvidence(
                payload={"candidates": [
                    {"complexId": 10, "complexName": "가단지"},
                    {"complexId": 20, "complexName": "나단지"},
                    {"complexId": 30, "complexName": "다단지"},
                ]},
                candidate_ids=frozenset({10, 20, 30}),
                fact_ids=frozenset({"complex:10", "complex:20", "complex:30"}),
            )
        return ToolEvidence(
            payload={"complexId": arguments["complexId"]},
            candidate_ids=frozenset({int(arguments["complexId"])}),
            fact_ids=frozenset({f"trade:{arguments['complexId']}"}),
        )


def _decision(*ids: int, fact_id: str = "complex:10") -> AgentDecision:
    return AgentDecision(
        answer="검증된 근거를 비교한 결과입니다.",
        rows=tuple(
            AgentRecommendationRow(
                complex_id=complex_id,
                complex_name=f"{complex_id}단지",
                role="BALANCED",
                summary="검증된 후보 비교",
                strengths=(("확인된 특성", (fact_id,)),),
                tradeoffs=(("추가 확인 필요", (fact_id,)),),
                metrics={},
                fact_ids=(fact_id,),
            )
            for complex_id in ids
        ),
        fact_ids=(fact_id,),
    )


def test_agent_can_call_tools_in_multiple_rounds_and_own_final_order() -> None:
    primary = ScriptedModel([
        AgentTurn(tool_calls=(AgentToolCall(
            call_id="pool", name="get_region_candidate_pool",
            arguments={"regionName": "송파구", "limit": 40},
        ),)),
        AgentTurn(tool_calls=(
            AgentToolCall(call_id="trade-20", name="get_recent_trades", arguments={"complexId": 20}),
            AgentToolCall(call_id="trade-10", name="get_recent_trades", arguments={"complexId": 10}),
        )),
        AgentTurn(decision=_decision(20, 10, fact_id="trade:20")),
    ])
    tools = RecordingTools()

    result = asyncio.run(BoundedAgentOrchestrator(
        primary=primary,
        secondary=ScriptedModel([]),
        tools=tools,
    ).run(question="송파 아파트 2개 추천", requested_count=2))

    assert [row.complex_id for row in result.decision.rows] == [20, 10]
    assert result.route == "primary"
    assert tools.calls == [
        "get_region_candidate_pool", "get_recent_trades", "get_recent_trades"
    ]


@pytest.mark.parametrize("decision", [
    _decision(10, 99),
    _decision(10, 10),
    _decision(10, fact_id="invented:fact"),
])
def test_invalid_primary_is_repaired_with_same_evidence(decision: AgentDecision) -> None:
    primary = ScriptedModel([
        AgentTurn(tool_calls=(AgentToolCall(
            call_id="pool", name="get_region_candidate_pool",
            arguments={"regionName": "송파구", "limit": 40},
        ),)),
        AgentTurn(decision=decision),
        AgentTurn(decision=_decision(10)),
    ])

    result = asyncio.run(BoundedAgentOrchestrator(
        primary=primary,
        secondary=ScriptedModel([]),
        tools=RecordingTools(),
    ).run(question="송파 아파트 추천", requested_count=1))

    assert result.route == "repair"
    assert primary.calls == 3


def test_provider_failure_uses_secondary_and_all_failure_is_transparent_fallback() -> None:
    tools = RecordingTools()
    secondary = ScriptedModel([
        AgentTurn(tool_calls=(AgentToolCall(
            call_id="pool", name="get_region_candidate_pool",
            arguments={"regionName": "송파구", "limit": 40},
        ),)),
        AgentTurn(decision=_decision(10)),
    ])
    secondary_result = asyncio.run(BoundedAgentOrchestrator(
        primary=ScriptedModel([RuntimeError("provider")]),
        secondary=secondary,
        tools=tools,
    ).run(question="송파 아파트 추천", requested_count=1))
    assert secondary_result.route == "secondary"

    fallback_result = asyncio.run(BoundedAgentOrchestrator(
        primary=ScriptedModel([RuntimeError("primary")]),
        secondary=ScriptedModel([RuntimeError("secondary")]),
        tools=tools,
    ).run(question="송파 아파트 추천", requested_count=1))
    assert fallback_result.route == "minimal_fallback"
    assert fallback_result.readiness == "partial"
    assert "AI 비교 분석을 완료하지 못해" in fallback_result.decision.answer
