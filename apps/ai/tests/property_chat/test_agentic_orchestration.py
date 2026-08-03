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
    WebCitation,
    _validate_decision,
    _validate_tool_call,
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
                candidate_names={10: "10단지", 20: "20단지", 30: "30단지"},
                fact_ids=frozenset({"complex:10", "complex:20", "complex:30"}),
            )
        return ToolEvidence(
            payload={"complexId": arguments["complexId"]},
            candidate_ids=frozenset({int(arguments["complexId"])}),
            candidate_names={int(arguments["complexId"]): f"{arguments['complexId']}단지"},
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


@pytest.mark.parametrize("answer", [
    "이 단지가 무조건 최고입니다.",
    "투자 추천 단지이며 통근시간은 짧습니다.",
    "관측되지 않은 999억원을 기록했습니다.",
])
def test_forbidden_or_unobserved_claims_are_repaired(answer: str) -> None:
    invalid = AgentDecision(
        answer=answer, rows=_decision(10).rows, fact_ids=("complex:10",),
    )
    primary = ScriptedModel([
        AgentTurn(tool_calls=(AgentToolCall(
            call_id="pool", name="get_region_candidate_pool",
            arguments={"regionName": "송파구", "limit": 40},
        ),)),
        AgentTurn(decision=invalid),
        AgentTurn(decision=_decision(10)),
    ])

    result = asyncio.run(BoundedAgentOrchestrator(
        primary=primary, secondary=ScriptedModel([]), tools=RecordingTools(),
    ).run(question="송파 아파트 추천", requested_count=1))

    assert result.route == "repair"


def test_candidate_name_must_match_the_verified_identity() -> None:
    wrong_name = AgentDecision(
        answer="검증된 근거를 비교했습니다.",
        rows=(AgentRecommendationRow(
            complex_id=10, complex_name="다른단지", role="BALANCED",
            summary="검증된 후보 비교", strengths=(("확인된 특성", ("complex:10",)),),
            tradeoffs=(("추가 확인 필요", ("complex:10",)),), metrics={},
            fact_ids=("complex:10",),
        ),),
        fact_ids=("complex:10",),
    )
    primary = ScriptedModel([
        AgentTurn(tool_calls=(AgentToolCall(
            call_id="pool", name="get_region_candidate_pool",
            arguments={"regionName": "송파구", "limit": 40},
        ),)),
        AgentTurn(decision=wrong_name),
        AgentTurn(decision=_decision(10)),
    ])

    result = asyncio.run(BoundedAgentOrchestrator(
        primary=primary, secondary=ScriptedModel([]), tools=RecordingTools(),
    ).run(question="송파 아파트 추천", requested_count=1))

    assert result.route == "repair"


@pytest.mark.parametrize("turn", [AgentTurn, lambda: AgentTurn(
    tool_calls=(AgentToolCall("id", "search_complexes", {}),),
    decision=_decision(10),
)])
def test_agent_turn_requires_exactly_one_output_kind(turn) -> None:
    with pytest.raises(ValueError, match="either tools"):
        turn()


@pytest.mark.parametrize("count", [0, 6])
def test_requested_count_is_bounded(count: int) -> None:
    with pytest.raises(ValueError, match="requested count"):
        asyncio.run(BoundedAgentOrchestrator(
            primary=ScriptedModel([]), secondary=ScriptedModel([]), tools=RecordingTools(),
        ).run(question="추천", requested_count=count))


@pytest.mark.parametrize("call,candidates", [
    (AgentToolCall("bad id!", "search_complexes", {"query": "잠실", "limit": 3}), set()),
    (AgentToolCall("id", "unknown", {}), set()),
    (AgentToolCall("id", "get_complex_profile", {"complexId": True}), set()),
    (AgentToolCall("id", "get_recent_trades", {"complexId": 2}), {1}),
    (AgentToolCall("id", "get_candidate_evidence", {"bad": [1]}), {1}),
    (AgentToolCall("id", "get_reference_evidence", {"complexIds": [1, 1]}), {1}),
    (AgentToolCall("id", "search_complexes", {"query": "", "limit": 0}), set()),
    (AgentToolCall("id", "get_region_candidate_pool", {"regionName": "", "limit": 3}), set()),
    (AgentToolCall("id", "get_region_candidate_pool", {
        "regionName": "송파구", "limit": 3, "minimumUnitCount": 0,
    }), set()),
    (AgentToolCall("id", "get_region_candidate_pool", {
        "regionName": "송파구", "limit": 3, "maximumBudgetTenThousandKrw": 0,
    }), set()),
    (AgentToolCall("id", "get_region_candidate_pool", {
        "regionName": "송파구", "limit": 3, "exclusiveAreaSquareMeters": True,
    }), set()),
    (AgentToolCall("id", "get_region_candidate_pool", {
        "regionName": "송파구", "limit": 3, "maximumBudgetTenThousandKrw": 100_000,
    }), set()),
])
def test_tool_arguments_fail_closed(call: AgentToolCall, candidates: set[int]) -> None:
    with pytest.raises(ValueError):
        _validate_tool_call(call, candidates)


def test_valid_tool_argument_variants_are_accepted() -> None:
    _validate_tool_call(AgentToolCall(
        "pool", "get_region_candidate_pool", {
            "regionName": "송파구", "limit": 3, "minimumUnitCount": 500,
            "maximumBudgetTenThousandKrw": 150_000, "exclusiveAreaSquareMeters": 84.0,
        },
    ), set())
    _validate_tool_call(AgentToolCall(
        "batch", "get_candidate_evidence", {"complexIds": [1, 2]},
    ), {1, 2})


def _validate_for_test(decision: AgentDecision) -> None:
    _validate_decision(
        decision, candidate_ids={10}, candidate_names={10: "10단지"},
        fact_ids={"complex:10"}, allowed_numbers=set(), requested_count=1,
    )


@pytest.mark.parametrize("decision", [
    AgentDecision(" ", _decision(10).rows, ("complex:10",)),
    AgentDecision("답변", (), ("complex:10",)),
    AgentDecision("답변", (AgentRecommendationRow(
        10, "10단지", "INVALID", "요약", (("강점", ("complex:10",)),),
        (("한계", ("complex:10",)),), {}, ("complex:10",),
    ),), ("complex:10",)),
    AgentDecision("답변", (AgentRecommendationRow(
        10, "10단지", "BALANCED", "요약", (("강점", ("complex:10",)),),
        (("한계", ("complex:10",)),), {}, (),
    ),), ("complex:10",)),
    AgentDecision("답변", (AgentRecommendationRow(
        10, "10단지", "BALANCED", "요약", (("", ()),),
        (("한계", ("complex:10",)),), {}, ("complex:10",),
    ),), ("complex:10",)),
    AgentDecision("답변", _decision(10).rows, ("complex:10",), web_citations=(
        WebCitation("web:bad", "공식", "https://www.reb.or.kr/notice"),
    )),
])
def test_decision_shape_and_web_citations_fail_closed(decision: AgentDecision) -> None:
    with pytest.raises(ValueError):
        _validate_for_test(decision)


def test_official_only_decision_requires_cited_research_claim_without_rows() -> None:
    decision = AgentDecision(
        answer="공식 근거를 확인했습니다.", rows=(), fact_ids=(),
        web_citations=(WebCitation(
            "web:0123456789abcdef0123456789abcdef", "공식 공고",
            "https://www.reb.or.kr/notice?id=1",
        ),),
        research_claims=("최신 공식 공고의 현재 상태를 확인했습니다. [1]",),
    )

    _validate_for_test(decision)


@pytest.mark.parametrize(
    "decision",
    [
        AgentDecision(
            answer="답변", rows=_decision(10).rows, fact_ids=("complex:10",),
            web_citations=(WebCitation(
                "web:0123456789abcdef0123456789abcdef", "공식 공고",
                "https://www.reb.or.kr/notice?id=1",
            ),),
        ),
        AgentDecision(
            answer="답변", rows=_decision(10).rows, fact_ids=("complex:10",),
            research_claims=("공식 상태를 확인했습니다. [1]",),
        ),
        AgentDecision(
            answer="답변", rows=_decision(10).rows, fact_ids=("complex:10",),
            web_citations=(WebCitation(
                "web:0123456789abcdef0123456789abcdef", "공식 공고",
                "https://www.reb.or.kr/notice?id=1",
            ),),
            research_claims=("공식 상태를 확인했습니다. [2]",),
        ),
    ],
)
def test_decision_rejects_missing_or_mismatched_research_citations(
    decision: AgentDecision,
) -> None:
    with pytest.raises(ValueError):
        _validate_for_test(decision)


class FixedTools:
    def __init__(self, result: ToolEvidence | Exception) -> None:
        self.result = result

    async def execute(self, _name: str, _arguments: Mapping[str, object]) -> ToolEvidence:
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


def _search_call(index: int = 1) -> AgentToolCall:
    return AgentToolCall(f"search-{index}", "search_complexes", {"query": "잠실", "limit": 3})


def test_secondary_invalid_decision_and_failed_repair_fall_through() -> None:
    evidence = ToolEvidence(
        payload={"complexId": 10}, candidate_ids=frozenset({10}),
        candidate_names={10: "10단지"}, fact_ids=frozenset({"complex:10"}),
    )
    result = asyncio.run(BoundedAgentOrchestrator(
        primary=ScriptedModel([AgentTurn(tool_calls=(_search_call(),)),
                               AgentTurn(decision=_decision(99)),
                               AgentTurn(tool_calls=(_search_call(2),))]),
        secondary=ScriptedModel([AgentTurn(tool_calls=(_search_call(),)),
                                 AgentTurn(decision=_decision(99))]),
        tools=FixedTools(evidence),
    ).run(question="추천", requested_count=1))
    assert result.route == "minimal_fallback"


@pytest.mark.parametrize("tool_result", [
    RuntimeError("read failed"),
    ToolEvidence(payload={"value": "x" * (32 * 1024)}),
])
def test_tool_failure_or_oversized_output_uses_secondary_fallback(tool_result) -> None:
    result = asyncio.run(BoundedAgentOrchestrator(
        primary=ScriptedModel([AgentTurn(tool_calls=(_search_call(),))]),
        secondary=ScriptedModel([RuntimeError("secondary")]),
        tools=FixedTools(tool_result),
    ).run(question="추천", requested_count=1))
    assert result.route == "minimal_fallback"


def test_call_count_total_bytes_and_conflicting_identity_are_bounded() -> None:
    many_calls = tuple(_search_call(index) for index in range(13))
    large = ToolEvidence(payload={"value": "x" * 32_700})
    conflict_model = ScriptedModel([
        AgentTurn(tool_calls=(_search_call(1),)), AgentTurn(tool_calls=(_search_call(2),)),
    ])

    for model, tools in (
        (ScriptedModel([AgentTurn(tool_calls=many_calls)]), FixedTools(ToolEvidence(payload={}))),
        (ScriptedModel([AgentTurn(tool_calls=tuple(_search_call(i) for i in range(1, 6)))]), FixedTools(large)),
    ):
        result = asyncio.run(BoundedAgentOrchestrator(
            primary=model, secondary=ScriptedModel([RuntimeError("secondary")]), tools=tools,
        ).run(question="추천", requested_count=1))
        assert result.route == "minimal_fallback"

    class ConflictingTools:
        calls = 0

        async def execute(self, _name, _arguments):
            self.calls += 1
            return ToolEvidence(
                payload={"ok": True}, candidate_ids=frozenset({10}),
                candidate_names={10: "가단지" if self.calls == 1 else "나단지"},
                scope_label="송파구",
            )

    result = asyncio.run(BoundedAgentOrchestrator(
        primary=conflict_model, secondary=ScriptedModel([RuntimeError("secondary")]),
        tools=ConflictingTools(),
    ).run(question="추천", requested_count=1))
    assert result.route == "minimal_fallback"


def test_four_tool_rounds_without_decision_end_in_fallback() -> None:
    result = asyncio.run(BoundedAgentOrchestrator(
        primary=ScriptedModel([AgentTurn(tool_calls=(_search_call(i),)) for i in range(1, 5)]),
        secondary=ScriptedModel([RuntimeError("secondary")]),
        tools=FixedTools(ToolEvidence(payload={}, scope_label="송파구")),
    ).run(question="추천", requested_count=1))
    assert result.route == "minimal_fallback"
