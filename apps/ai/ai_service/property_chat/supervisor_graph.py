from __future__ import annotations

import asyncio
import copy
import re
import time
from dataclasses import dataclass, field
from typing import Annotated, Any, Protocol, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from langgraph.types import Send

from ai_service.models import ChatbotQueryRequest
from ai_service.terminal_response import (
    safe_final_response,
    terminal_outcome,
    with_terminal_outcome,
)

from .answer_document import CompoundAnswerDocument
from .models import QueryPlan
from .supervisor import (
    DependencyPlanner,
    GoalExecutionResult,
    GoalOrderingPolicy,
    GoalSpec,
    GraphInvariantError,
    ResolvedEntity,
    SupervisorPlan,
    WaveScheduler,
    reduce_goal_results,
)
from .supervisor_execution import GroundedGoalExecutor, goal_specs


class GoalPlanner(Protocol):
    async def plan_goals(self, request: ChatbotQueryRequest) -> tuple[QueryPlan, ...]: ...


class MetricsSink(Protocol):
    def increment(self, name: str, labels: dict[str, str | int | bool]) -> None: ...


class FinalPolisher(Protocol):
    async def polish(self, canonical_response: dict[str, object]) -> dict[str, object]: ...


class NullMetricsSink:
    def increment(self, _name: str, _labels: dict[str, str | int | bool]) -> None:
        return


@dataclass
class RuntimeContext:
    planner: GoalPlanner
    executor: GroundedGoalExecutor
    request_id: str
    deadline: float
    semaphore: asyncio.Semaphore = field(default_factory=lambda: asyncio.Semaphore(2))
    metrics: MetricsSink = field(default_factory=NullMetricsSink)
    polisher: FinalPolisher | None = None


class SupervisorState(TypedDict, total=False):
    request: ChatbotQueryRequest
    plan: SupervisorPlan
    goals: tuple[GoalSpec, ...]
    resolved_entities: tuple[ResolvedEntity, ...]
    scheduled_goal_ids: tuple[str, ...]
    wave_count: int
    goal_results: Annotated[list[GoalExecutionResult], reduce_goal_results]
    goal: GoalSpec
    dependency_results: list[GoalExecutionResult]
    canonical_response: dict[str, object]
    candidate_response: dict[str, object]
    final_response: dict[str, object]


class SupervisorGraphEngine:
    def __init__(
        self,
        *,
        planner: GoalPlanner,
        executor: GroundedGoalExecutor,
        timeout_seconds: float,
        metrics: MetricsSink | None = None,
        polisher: FinalPolisher | None = None,
    ) -> None:
        if not 1 <= timeout_seconds <= 60:
            raise ValueError("supervisor graph timeout must be within 1..60 seconds")
        self._planner = planner
        self._executor = executor
        self._timeout_seconds = timeout_seconds
        self._metrics = metrics or NullMetricsSink()
        self._polisher = polisher
        self._graph = _compile_graph()

    async def query(
        self,
        *,
        request: ChatbotQueryRequest,
        request_id: str,
    ) -> dict[str, object]:
        started_at = time.monotonic()
        deadline = time.monotonic() + self._timeout_seconds
        context = RuntimeContext(
            planner=self._planner,
            executor=self._executor,
            request_id=request_id,
            deadline=deadline,
            metrics=self._metrics,
            polisher=self._polisher,
        )
        try:
            async with asyncio.timeout(self._timeout_seconds):
                output = await self._graph.ainvoke(
                    {"request": request, "goal_results": [], "wave_count": 0},
                    context=context,
                    config={"recursion_limit": 32},
                )
            response = output.get("final_response")
            if not isinstance(response, dict):
                raise GraphInvariantError("graph did not produce a final response")
            outcome = response.get("terminalOutcome")
            self._metrics.increment("supervisor_graph_completed", {
                "goal_count": len(output.get("goals", ())),
                "wave_count": output.get("wave_count", 0),
                "terminal_status": (
                    outcome.get("status", "UNKNOWN")
                    if isinstance(outcome, dict) else "UNKNOWN"
                ),
                "terminal_reason": (
                    outcome.get("reason", "UNKNOWN")
                    if isinstance(outcome, dict) else "UNKNOWN"
                ),
                "elapsed_milliseconds": round((time.monotonic() - started_at) * 1000),
            })
            return response
        except Exception:
            self._metrics.increment("supervisor_graph_safe_final", {"reason": "invariant_or_runtime"})
            return safe_final_response(request_id)


async def _supervise(
    state: SupervisorState, runtime: Runtime[RuntimeContext],
) -> dict[str, object]:
    plans = await runtime.context.planner.plan_goals(state["request"])
    goals = goal_specs(plans, state["request"].question)
    return {"plan": SupervisorPlan(goals), "goals": goals}


def _validate_plan(state: SupervisorState) -> dict[str, object]:
    plan = SupervisorPlan(state["goals"])
    return {"plan": plan, "goals": plan.goals}


async def _resolve_entities(
    state: SupervisorState, runtime: Runtime[RuntimeContext],
) -> dict[str, object]:
    return {"resolved_entities": await runtime.context.executor.resolve_entities(state["goals"])}


def _build_dependencies(state: SupervisorState) -> dict[str, object]:
    goals = DependencyPlanner().apply(state["goals"])
    return {"goals": goals, "plan": SupervisorPlan(goals)}


def _schedule_wave(state: SupervisorState) -> dict[str, object]:
    completed = frozenset(result.goal_id for result in state.get("goal_results", []))
    wave = WaveScheduler(GoalOrderingPolicy()).next_wave(
        state["goals"], completed=completed
    )
    wave_count = state.get("wave_count", 0) + (1 if wave else 0)
    if wave_count > 4:
        raise GraphInvariantError("supervisor graph exceeded four waves")
    return {"scheduled_goal_ids": wave, "wave_count": wave_count}


def _dispatch_wave(state: SupervisorState) -> list[Send] | str:
    scheduled = state.get("scheduled_goal_ids", ())
    if not scheduled:
        return "compose_canonical"
    by_id = {goal.goal_id: goal for goal in state["goals"]}
    completed = state.get("goal_results", [])
    return [
        Send("execute_goal", {
            "goal": by_id[goal_id],
            "dependency_results": completed,
            "request": state["request"],
        })
        for goal_id in scheduled
    ]


async def _execute_goal(
    state: SupervisorState, runtime: Runtime[RuntimeContext],
) -> dict[str, object]:
    goal = state["goal"]
    dependencies = {
        result.goal_id: result for result in state.get("dependency_results", [])
    }
    if goal.depends_on:
        dependency = dependencies.get(goal.depends_on[0])
        if dependency is None or dependency.status == "UNAVAILABLE":
            return {"goal_results": [GoalExecutionResult.unavailable(
                goal.goal_id,
                "선행 추천 후보를 검증하지 못해 비교를 실행하지 않았습니다.",
                retryable=dependency.retryable if dependency else False,
            )]}
        verified_goal = await runtime.context.executor.with_verified_recommendation_candidates(
            goal, dependency
        )
        if verified_goal is None:
            return {"goal_results": [GoalExecutionResult.unavailable(
                goal.goal_id,
                "추천 결과에서 검증된 비교 후보를 확인하지 못했습니다.",
                retryable=dependency.retryable,
            )]}
        goal = verified_goal
    async with runtime.context.semaphore:
        result = await runtime.context.executor.execute(
            goal,
            state["request"],
            runtime.context.request_id,
            deadline=runtime.context.deadline - 5,
        )
    return {"goal_results": [result]}


def _join_wave(_state: SupervisorState) -> dict[str, object]:
    return {"scheduled_goal_ids": ()}


def _route_join(state: SupervisorState) -> str:
    completed = {result.goal_id for result in state.get("goal_results", [])}
    return "compose_canonical" if len(completed) == len(state["goals"]) else "schedule_wave"


def _compose_canonical(
    state: SupervisorState, runtime: Runtime[RuntimeContext],
) -> dict[str, object]:
    by_id = {result.goal_id: result for result in state.get("goal_results", [])}
    ordered = [by_id[goal.goal_id] for goal in GoalOrderingPolicy().order(state["goals"])]
    documents = [result.document for result in ordered if result.document is not None]
    if not documents:
        response = _insufficient_evidence_response(
            runtime.context.request_id,
            [limitation for result in ordered for limitation in result.limitations],
        )
    elif len(documents) == 1:
        response = documents[0].to_public_dict()
    else:
        response = CompoundAnswerDocument(
            state["request"], runtime.context.request_id, tuple(documents)
        ).to_public_dict()
    if documents and all(result.status == "CLARIFICATION" for result in ordered):
        response = dict(response)
        response["success"] = False
        response["status"] = "failed"
        resolution = response.get("conversationResolution")
        if isinstance(resolution, dict):
            response["conversationResolution"] = {**resolution, "answerMode": "NO_RESULT"}
        response["conversationMemoryPatch"] = None
        response["terminalOutcome"] = terminal_outcome(
            "CLARIFICATION", "AMBIGUOUS_ENTITY"
        )
    elif any(result.status != "SUCCESS" for result in ordered) and documents:
        response = dict(response)
        response["success"] = True
        response["status"] = "partial_success"
        response["terminalOutcome"] = terminal_outcome(
            "PARTIAL", "PARTIAL_EVIDENCE",
            retryable=any(result.retryable for result in ordered),
        )
    else:
        response = with_terminal_outcome(response)
    return {"canonical_response": response}


async def _polish_once(
    state: SupervisorState, runtime: Runtime[RuntimeContext],
) -> dict[str, object]:
    canonical = state["canonical_response"]
    if runtime.context.polisher is None or runtime.context.deadline - time.monotonic() < 5:
        return {"candidate_response": canonical}
    try:
        candidate = await runtime.context.polisher.polish(copy.deepcopy(canonical))
    except Exception:
        candidate = canonical
    return {"candidate_response": candidate}


def _validate_answer(state: SupervisorState) -> dict[str, object]:
    canonical = state["canonical_response"]
    if not _has_fact_citation_closure(canonical):
        raise GraphInvariantError("canonical response fact/citation closure failed")
    candidate = state.get("candidate_response", canonical)
    response = candidate if _valid_polish(canonical, candidate) else canonical
    return {"final_response": response}


def _finalize_response(state: SupervisorState) -> dict[str, object]:
    return {"final_response": state["final_response"]}


def _has_fact_citation_closure(response: dict[str, object]) -> bool:
    citations = response.get("citations")
    if not isinstance(citations, list):
        return False
    fact_ids = {
        fact_id
        for citation in citations
        if isinstance(citation, dict)
        for fact_id in citation.get("factIds", [])
        if isinstance(fact_id, str)
    }
    fragments = response.get("fragments", [])
    return isinstance(fragments, list) and all(
        isinstance(fragment, dict)
        and isinstance(fragment.get("factIds", []), list)
        and set(fragment.get("factIds", [])).issubset(fact_ids)
        for fragment in fragments
    )


def _valid_polish(
    canonical: dict[str, object], candidate: dict[str, object],
) -> bool:
    if not isinstance(candidate, dict) or not _has_fact_citation_closure(candidate):
        return False
    canonical_answer = canonical.get("answer")
    candidate_answer = candidate.get("answer")
    if (
        not isinstance(canonical_answer, str)
        or not isinstance(candidate_answer, str)
        or not candidate_answer.strip()
    ):
        return False
    canonical_metadata = {key: value for key, value in canonical.items() if key != "answer"}
    candidate_metadata = {key: value for key, value in candidate.items() if key != "answer"}
    if canonical_metadata != candidate_metadata:
        return False
    canonical_numbers = set(re.findall(r"(?<![A-Za-z])\d+(?:[.,]\d+)?", canonical_answer))
    candidate_numbers = set(re.findall(r"(?<![A-Za-z])\d+(?:[.,]\d+)?", candidate_answer))
    return candidate_numbers.issubset(canonical_numbers)


def _insufficient_evidence_response(
    request_id: str, limitations: list[str],
) -> dict[str, object]:
    limitation = next((item for item in limitations if item.strip()), "필요한 근거가 준비되지 않았습니다.")
    return {
        "success": False,
        "status": "failed",
        "fragments": [],
        "result": {},
        "message": "",
        "executionSummary": {"total": 0, "succeeded": 0, "failed": 0},
        "answer": limitation,
        "resolvedQuestion": "",
        "conversationResolution": {
            "version": 1, "answerMode": "NO_RESULT", "goals": [],
            "assumptions": [], "omissions": [],
        },
        "conversationMemoryPatch": None,
        "uiActions": [],
        "uiArtifacts": [],
        "uiSummary": None,
        "uiReport": None,
        "requestId": request_id,
        "citations": [],
        "dataAsOf": None,
        "limitations": [limitation],
        "evidenceSummary": {
            "status": "unavailable", "capabilities": [],
            "factCount": 0, "citationCount": 0,
        },
        "terminalOutcome": terminal_outcome(
            "UNAVAILABLE", "INSUFFICIENT_EVIDENCE"
        ),
    }


def _compile_graph() -> Any:
    graph = StateGraph(SupervisorState, context_schema=RuntimeContext)
    graph.add_node("supervise", _supervise)
    graph.add_node("validate_plan", _validate_plan)
    graph.add_node("resolve_entities", _resolve_entities)
    graph.add_node("build_dependencies", _build_dependencies)
    graph.add_node("schedule_wave", _schedule_wave)
    graph.add_node("execute_goal", _execute_goal)
    graph.add_node("join_wave", _join_wave)
    graph.add_node("compose_canonical", _compose_canonical)
    graph.add_node("polish_once", _polish_once)
    graph.add_node("validate_answer", _validate_answer)
    graph.add_node("finalize_response", _finalize_response)
    graph.add_edge(START, "supervise")
    graph.add_edge("supervise", "validate_plan")
    graph.add_edge("validate_plan", "resolve_entities")
    graph.add_edge("resolve_entities", "build_dependencies")
    graph.add_edge("build_dependencies", "schedule_wave")
    graph.add_conditional_edges("schedule_wave", _dispatch_wave)
    graph.add_edge("execute_goal", "join_wave")
    graph.add_conditional_edges("join_wave", _route_join)
    graph.add_edge("compose_canonical", "polish_once")
    graph.add_edge("polish_once", "validate_answer")
    graph.add_edge("validate_answer", "finalize_response")
    graph.add_edge("finalize_response", END)
    return graph.compile(checkpointer=None, store=None)
