from __future__ import annotations

import asyncio
from datetime import date

import pytest

from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.answer_document import AnswerDocument, AnswerSection
from ai_service.property_chat.models import QueryPlan
from ai_service.property_chat.supervisor import GoalExecutionResult, GoalSpec
from ai_service.property_chat.supervisor_graph import SupervisorGraphEngine
from ai_service.property_chat.supervisor_graph import (
    _dispatch_wave,
    _has_fact_citation_closure,
    _insufficient_evidence_response,
    _schedule_wave,
    _validate_answer,
    _valid_polish,
)


class Planner:
    def __init__(self, plans: tuple[QueryPlan, ...]) -> None:
        self.plans = plans

    async def plan_supervisor_goals(
        self, _request: ChatbotQueryRequest,
    ) -> tuple[QueryPlan, ...]:
        return self.plans


class Executor:
    def __init__(self) -> None:
        self.started: list[str] = []
        self.finished: list[str] = []
        self.concurrent = 0
        self.maximum_concurrent = 0
        self.dependent_preparations = 0

    async def resolve_entities(self, _goals: object) -> tuple[object, ...]:
        return ()

    async def with_verified_recommendation_candidates(self, goal: object, _dependency: object) -> object:
        self.dependent_preparations += 1
        return goal

    async def execute(self, goal: object, request: ChatbotQueryRequest, request_id: str, *, deadline: float):
        del deadline
        goal_id = goal.goal_id
        self.started.append(goal_id)
        self.concurrent += 1
        self.maximum_concurrent = max(self.maximum_concurrent, self.concurrent)
        await asyncio.sleep(0)
        self.concurrent -= 1
        self.finished.append(goal_id)
        document = AnswerDocument(
            request=request,
            request_id=request_id,
            plan=goal.plan,
            sections=(AnswerSection(f"{goal_id} 답변", ()),),
            used_facts=(),
            limitations=(),
            readiness="supported",
            recoverable=False,
        )
        return GoalExecutionResult(goal_id=goal_id, status="SUCCESS", document=document)


class HallucinatingPolisher:
    calls = 0

    async def polish(self, canonical_response: dict[str, object]) -> dict[str, object]:
        self.calls += 1
        return {**canonical_response, "answer": "검증되지 않은 가격은 999억원입니다."}


class QualitativeHallucinatingPolisher:
    async def polish(self, canonical_response: dict[str, object]) -> dict[str, object]:
        return {**canonical_response, "answer": "goal-1 답변이며 역세권입니다."}


class UnavailableExecutor(Executor):
    async def execute(self, goal: object, *_args: object, **_kwargs: object):
        self.started.append(goal.goal_id)
        return GoalExecutionResult.unavailable(goal.goal_id, "근거가 준비되지 않았습니다.", retryable=False)


class DocumentUnavailableExecutor(Executor):
    async def execute(
        self,
        goal: object,
        request: ChatbotQueryRequest,
        request_id: str,
        *,
        deadline: float,
    ) -> GoalExecutionResult:
        del deadline
        self.started.append(goal.goal_id)
        document = AnswerDocument(
            request=request,
            request_id=request_id,
            plan=goal.plan,
            sections=(AnswerSection("필요한 근거가 준비되지 않았습니다.", ()),),
            used_facts=(),
            limitations=("현재 확인 가능한 근거가 없습니다.",),
            readiness="unavailable",
            recoverable=False,
        )
        return GoalExecutionResult(
            goal_id=goal.goal_id,
            status="UNAVAILABLE",
            document=document,
            limitations=document.limitations,
            retryable=False,
        )


class FailingPolisher:
    async def polish(self, _canonical_response: dict[str, object]) -> dict[str, object]:
        raise TimeoutError


class MixedExecutor(Executor):
    async def execute(self, goal: object, request: ChatbotQueryRequest, request_id: str, *, deadline: float):
        if goal.goal_id == "goal-2":
            return GoalExecutionResult.unavailable(goal.goal_id, "두 번째 근거 실패", retryable=True)
        return await super().execute(goal, request, request_id, deadline=deadline)


class ClarificationExecutor(Executor):
    async def execute(
        self, goal: object, request: ChatbotQueryRequest, request_id: str, *, deadline: float,
    ) -> GoalExecutionResult:
        result = await super().execute(goal, request, request_id, deadline=deadline)
        return GoalExecutionResult(
            goal_id=result.goal_id,
            status="CLARIFICATION",
            document=result.document,
            limitations=("동명 단지가 여러 곳입니다.",),
        )


class RejectingBindingExecutor(Executor):
    async def with_verified_recommendation_candidates(
        self, _goal: object, _dependency: object,
    ) -> None:
        self.dependent_preparations += 1
        return None


class EmptyCompiledGraph:
    async def ainvoke(self, *_args: object, **_kwargs: object) -> dict[str, object]:
        return {}


class Metrics:
    def __init__(self) -> None:
        self.events: list[tuple[str, dict[str, object]]] = []

    def increment(self, name: str, labels: dict[str, object]) -> None:
        self.events.append((name, labels))


def recent_trade(name: str) -> QueryPlan:
    return QueryPlan(
        "recent_trade_lookup",
        name,
        start_date=date(2025, 7, 27),
        end_date=date(2026, 7, 27),
    )


def test_graph_executes_three_independent_goals_in_bounded_waves() -> None:
    executor = Executor()
    metrics = Metrics()
    engine = SupervisorGraphEngine(
        planner=Planner((
            QueryPlan("complex_identity", "잠실엘스"),
            recent_trade("잠실엘스"),
            QueryPlan("school_location", "잠실엘스"),
        )),
        executor=executor,  # type: ignore[arg-type]
        timeout_seconds=10,
        metrics=metrics,  # type: ignore[arg-type]
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스 단지 정보, 거래, 학교 알려줘"),
        request_id="request-1",
    ))

    assert response["terminalOutcome"]["status"] == "ANSWERED"
    assert executor.maximum_concurrent == 2
    assert executor.started[:2] == ["goal-1", "goal-2"]
    assert executor.started[2:] == ["goal-3"]
    assert metrics.events[0][0] == "supervisor_graph_completed"
    assert metrics.events[0][1]["goal_count"] == 3
    assert metrics.events[0][1]["wave_count"] == 2
    assert metrics.events[0][1]["terminal_status"] == "ANSWERED"


def test_graph_composes_goals_in_canonical_capability_order() -> None:
    engine = SupervisorGraphEngine(
        planner=Planner((
            QueryPlan("school_location", "잠실엘스"),
            QueryPlan("complex_identity", "잠실엘스"),
        )),
        executor=Executor(),  # type: ignore[arg-type]
        timeout_seconds=10,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스 학교와 기본정보를 알려줘"),
        request_id="request-1",
    ))

    assert [
        goal["capability"]
        for goal in response["conversationResolution"]["goals"]
    ] == ["complex_identity", "school_location"]


def test_graph_runs_explicit_recommendation_before_dependent_comparison() -> None:
    executor = Executor()
    engine = SupervisorGraphEngine(
        planner=Planner((
            QueryPlan("recommendation", "송파구", region_name="송파구", limit=2),
            QueryPlan(
                "comparison", "잠실엘스",
                complex_names=("잠실엘스", "헬리오시티"),
            ),
        )),
        executor=executor,  # type: ignore[arg-type]
        timeout_seconds=10,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="송파구 후보를 추천하고 그 후보를 비교해줘"),
        request_id="request-1",
    ))

    assert response["terminalOutcome"]["status"] == "ANSWERED"
    assert executor.started == ["goal-1", "goal-2"]
    assert executor.dependent_preparations == 1


def test_graph_invariant_failure_returns_non_disclosing_safe_final() -> None:
    plans = tuple(QueryPlan("complex_identity", f"단지-{index}") for index in range(5))
    engine = SupervisorGraphEngine(
        planner=Planner(plans),
        executor=Executor(),  # type: ignore[arg-type]
        timeout_seconds=10,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="내부 오류 문자열을 숨겨줘"),
        request_id="request-1",
    ))

    assert response["terminalOutcome"] == {
        "version": 1, "status": "UNAVAILABLE",
        "reason": "TEMPORARY_FAILURE", "retryable": True,
    }
    assert "내부 오류" not in response["answer"]


def test_graph_rejects_polish_with_unverified_number_and_uses_canonical_answer() -> None:
    polisher = HallucinatingPolisher()
    engine = SupervisorGraphEngine(
        planner=Planner((QueryPlan("complex_identity", "잠실엘스"),)),
        executor=Executor(),  # type: ignore[arg-type]
        timeout_seconds=10,
        polisher=polisher,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스 정보를 알려줘"),
        request_id="request-1",
    ))

    assert polisher.calls == 1
    assert response["answer"] == "goal-1 답변"
    assert "999" not in response["answer"]


def test_graph_rejects_polish_with_new_qualitative_claim() -> None:
    engine = SupervisorGraphEngine(
        planner=Planner((QueryPlan("complex_identity", "잠실엘스"),)),
        executor=Executor(),  # type: ignore[arg-type]
        timeout_seconds=10,
        polisher=QualitativeHallucinatingPolisher(),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스 정보를 알려줘"),
        request_id="request-1",
    ))

    assert response["answer"] == "goal-1 답변"


def test_graph_returns_insufficient_evidence_and_skips_dependent_goal_after_failure() -> None:
    executor = UnavailableExecutor()
    engine = SupervisorGraphEngine(
        planner=Planner((
            QueryPlan("recommendation", "송파구", region_name="송파구", limit=2),
            QueryPlan("comparison", "A", complex_names=("A", "B")),
        )),
        executor=executor,  # type: ignore[arg-type]
        timeout_seconds=10,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="송파구 추천한 후보를 비교해줘"),
        request_id="request-1",
    ))

    assert executor.started == ["goal-1"]
    assert response["terminalOutcome"]["reason"] == "INSUFFICIENT_EVIDENCE"
    assert response["terminalOutcome"]["retryable"] is False


def test_graph_keeps_document_backed_unavailable_result_failed() -> None:
    engine = SupervisorGraphEngine(
        planner=Planner((QueryPlan("complex_identity", "잠실엘스"),)),
        executor=DocumentUnavailableExecutor(),  # type: ignore[arg-type]
        timeout_seconds=10,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스 정보를 알려줘"),
        request_id="request-1",
    ))

    assert response["success"] is False
    assert response["status"] == "failed"
    assert response["conversationResolution"]["answerMode"] == "NO_RESULT"
    assert response["terminalOutcome"] == {
        "version": 1,
        "status": "UNAVAILABLE",
        "reason": "INSUFFICIENT_EVIDENCE",
        "retryable": False,
    }


def test_graph_skips_comparison_without_verified_recommendation_ids() -> None:
    executor = RejectingBindingExecutor()
    engine = SupervisorGraphEngine(
        planner=Planner((
            QueryPlan("recommendation", "송파구", region_name="송파구", limit=2),
            QueryPlan("comparison", "임의 A", complex_names=("임의 A", "임의 B")),
        )),
        executor=executor,  # type: ignore[arg-type]
        timeout_seconds=10,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="송파구 추천한 후보를 비교해줘"),
        request_id="request-1",
    ))

    assert executor.started == ["goal-1"]
    assert executor.dependent_preparations == 1
    assert response["terminalOutcome"]["status"] == "PARTIAL"


def test_graph_uses_canonical_when_polisher_raises_and_rejects_invalid_timeout() -> None:
    with pytest.raises(ValueError):
        SupervisorGraphEngine(
            planner=Planner((QueryPlan("complex_identity", "잠실엘스"),)),
            executor=Executor(),  # type: ignore[arg-type]
            timeout_seconds=61,
        )
    engine = SupervisorGraphEngine(
        planner=Planner((QueryPlan("complex_identity", "잠실엘스"),)),
        executor=Executor(),  # type: ignore[arg-type]
        timeout_seconds=10,
        polisher=FailingPolisher(),
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스 정보"), request_id="request-1",
    ))
    assert response["answer"] == "goal-1 답변"


def test_graph_partial_result_and_direct_validation_fail_closed_branches() -> None:
    engine = SupervisorGraphEngine(
        planner=Planner((
            QueryPlan("complex_identity", "잠실엘스"),
            QueryPlan("school_location", "잠실엘스"),
        )),
        executor=MixedExecutor(),  # type: ignore[arg-type]
        timeout_seconds=10,
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스 정보와 학교"), request_id="request-1",
    ))
    assert response["terminalOutcome"] == {
        "version": 1, "status": "PARTIAL", "reason": "PARTIAL_EVIDENCE", "retryable": True,
    }

    state_goal = QueryPlan("complex_identity", "잠실엘스")
    spec = GoalSpec.from_plan(goal_id="g1", plan=state_goal, appearance_order=0)
    with pytest.raises(Exception):
        _schedule_wave({"goals": (spec,), "goal_results": [], "wave_count": 4})  # type: ignore[arg-type]
    assert _dispatch_wave({"scheduled_goal_ids": ()}) == "compose_canonical"  # type: ignore[arg-type]
    assert _has_fact_citation_closure({"citations": "invalid"}) is False
    with pytest.raises(Exception):
        _validate_answer({"canonical_response": {"citations": "invalid"}})  # type: ignore[arg-type]
    canonical = {"answer": "검증 답변 1", "citations": [], "fragments": []}
    assert _valid_polish(canonical, None) is False  # type: ignore[arg-type]
    assert _valid_polish(canonical, {**canonical, "answer": ""}) is False
    assert _valid_polish(canonical, {**canonical, "status": "changed"}) is False
    assert _valid_polish(canonical, {**canonical, "answer": "검증 답변 1"}) is True
    assert _insufficient_evidence_response("request-1", [""])["answer"] == "필요한 근거가 준비되지 않았습니다."


def test_graph_ambiguous_only_response_requires_clarification() -> None:
    engine = SupervisorGraphEngine(
        planner=Planner((QueryPlan("complex_identity", "동명 단지"),)),
        executor=ClarificationExecutor(),  # type: ignore[arg-type]
        timeout_seconds=10,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="동명 단지 정보"), request_id="request-1",
    ))

    assert response["success"] is False
    assert response["status"] == "failed"
    assert response["conversationResolution"]["answerMode"] == "NO_RESULT"
    assert response["conversationMemoryPatch"] is None
    assert response["terminalOutcome"] == {
        "version": 1,
        "status": "CLARIFICATION",
        "reason": "AMBIGUOUS_ENTITY",
        "retryable": False,
    }


def test_graph_missing_final_response_is_safe() -> None:
    engine = SupervisorGraphEngine(
        planner=Planner((QueryPlan("complex_identity", "잠실엘스"),)),
        executor=Executor(),  # type: ignore[arg-type]
        timeout_seconds=10,
    )
    engine._graph = EmptyCompiledGraph()  # type: ignore[assignment]
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스 정보"), request_id="request-1",
    ))
    assert response["terminalOutcome"]["reason"] == "TEMPORARY_FAILURE"
