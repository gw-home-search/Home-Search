from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Any, Literal, Sequence

from .models import QueryCapability, QueryPlan


GoalStatus = Literal["SUCCESS", "PARTIAL", "CLARIFICATION", "UNAVAILABLE"]
ExecutionClass = Literal["LIGHT", "HEAVY"]

_CANONICAL_CAPABILITIES: tuple[QueryCapability, ...] = (
    "complex_identity",
    "recent_trade_lookup",
    "price_trend",
    "comparison",
    "recommendation",
    "school_location",
    "academy_lookup",
    "academy_registry_summary",
    "rail_station_lookup",
    "retail_location",
    "childcare_lookup",
    "kakao_place_search",
)
_HEAVY_CAPABILITIES = frozenset({"recommendation", "comparison"})
_FOLLOW_UP_ENTITY_REFERENCES = frozenset({"추천한 후보", "그 후보", "1위와 2위"})


class GraphInvariantError(RuntimeError):
    pass


@dataclass(frozen=True)
class GoalSpec:
    goal_id: str
    capability: QueryCapability
    plan: QueryPlan
    appearance_order: int
    execution_class: ExecutionClass
    entity_reference: str | None = None
    depends_on: tuple[str, ...] = ()
    requested_dependencies: tuple[str, ...] = ()

    @classmethod
    def from_plan(
        cls,
        *,
        goal_id: str,
        plan: QueryPlan,
        appearance_order: int,
        entity_reference: str | None = None,
        requested_dependencies: tuple[str, ...] = (),
    ) -> GoalSpec:
        if not goal_id or len(goal_id) > 64 or appearance_order < 0:
            raise ValueError("invalid goal identity")
        return cls(
            goal_id=goal_id,
            capability=plan.capability,
            plan=plan,
            appearance_order=appearance_order,
            execution_class="HEAVY" if plan.capability in _HEAVY_CAPABILITIES else "LIGHT",
            entity_reference=entity_reference,
            requested_dependencies=requested_dependencies,
        )


@dataclass(frozen=True)
class SupervisorPlan:
    goals: tuple[GoalSpec, ...]
    assumptions: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not 1 <= len(self.goals) <= 4:
            raise GraphInvariantError("supervisor plan must contain 1..4 goals")
        ids = tuple(goal.goal_id for goal in self.goals)
        if len(ids) != len(set(ids)):
            raise GraphInvariantError("supervisor goal ids must be unique")


@dataclass(frozen=True)
class ResolvedEntity:
    mention: str
    candidate_ids: tuple[int, ...]
    selected_id: int | None
    status: Literal["EXACT", "UNIQUE", "AMBIGUOUS", "NOT_FOUND"]


@dataclass(frozen=True)
class GoalExecutionResult:
    goal_id: str
    status: GoalStatus
    document: Any | None = None
    limitations: tuple[str, ...] = ()
    retryable: bool = False

    @classmethod
    def unavailable(
        cls, goal_id: str, limitation: str, *, retryable: bool,
    ) -> GoalExecutionResult:
        return cls(
            goal_id=goal_id,
            status="UNAVAILABLE",
            limitations=(limitation,),
            retryable=retryable,
        )


class DependencyPlanner:
    def apply(self, goals: Sequence[GoalSpec]) -> tuple[GoalSpec, ...]:
        if not 1 <= len(goals) <= 4:
            raise GraphInvariantError("goal count exceeds supervisor limit")
        by_id = {goal.goal_id: goal for goal in goals}
        recommendation_ids = tuple(
            goal.goal_id for goal in goals if goal.capability == "recommendation"
        )
        planned: list[GoalSpec] = []
        for goal in goals:
            allowed: tuple[str, ...] = ()
            if (
                goal.capability == "comparison"
                and goal.entity_reference in _FOLLOW_UP_ENTITY_REFERENCES
                and len(recommendation_ids) == 1
            ):
                allowed = recommendation_ids
            if goal.requested_dependencies and goal.requested_dependencies != allowed:
                raise GraphInvariantError("supervisor requested a forbidden dependency")
            dependencies = goal.requested_dependencies or allowed
            if any(dependency not in by_id or dependency == goal.goal_id for dependency in dependencies):
                raise GraphInvariantError("goal dependency is missing or self-referential")
            planned.append(replace(goal, depends_on=dependencies))
        self._validate_depth_and_cycles(planned)
        return tuple(planned)

    @staticmethod
    def _validate_depth_and_cycles(goals: Sequence[GoalSpec]) -> None:
        by_id = {goal.goal_id: goal for goal in goals}
        for goal in goals:
            for dependency in goal.depends_on:
                if by_id[dependency].depends_on:
                    raise GraphInvariantError("goal dependency depth exceeds one")
                if goal.goal_id in by_id[dependency].depends_on:
                    raise GraphInvariantError("goal dependency cycle detected")


class GoalOrderingPolicy:
    def order(self, goals: Sequence[GoalSpec]) -> tuple[GoalSpec, ...]:
        rank = {capability: index for index, capability in enumerate(_CANONICAL_CAPABILITIES)}
        return tuple(sorted(
            goals,
            key=lambda goal: (goal.appearance_order, rank[goal.capability], goal.goal_id),
        ))


class WaveScheduler:
    def __init__(self, ordering: GoalOrderingPolicy | None = None) -> None:
        self._ordering = ordering or GoalOrderingPolicy()

    def next_wave(
        self, goals: Sequence[GoalSpec], *, completed: frozenset[str],
    ) -> tuple[str, ...]:
        pending = [goal for goal in goals if goal.goal_id not in completed]
        if not pending:
            return ()
        ready = self._ordering.order(tuple(
            goal for goal in pending if set(goal.depends_on).issubset(completed)
        ))
        if not ready:
            raise GraphInvariantError("pending goals exist but no goal is ready")
        if ready[0].execution_class == "HEAVY":
            return (ready[0].goal_id,)
        return tuple(
            goal.goal_id for goal in ready if goal.execution_class == "LIGHT"
        )[:2]


def reduce_goal_results(
    current: list[GoalExecutionResult], updates: list[GoalExecutionResult],
) -> list[GoalExecutionResult]:
    merged = {result.goal_id: result for result in current}
    order = [result.goal_id for result in current]
    for result in updates:
        existing = merged.get(result.goal_id)
        if existing is not None:
            if existing != result:
                raise GraphInvariantError("conflicting duplicate goal result")
            continue
        merged[result.goal_id] = result
        order.append(result.goal_id)
    return [merged[goal_id] for goal_id in order]
