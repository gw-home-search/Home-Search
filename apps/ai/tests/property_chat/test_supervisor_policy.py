from __future__ import annotations

import pytest

from ai_service.property_chat.models import QueryPlan
from ai_service.property_chat.supervisor import (
    DependencyPlanner,
    GoalExecutionResult,
    GoalOrderingPolicy,
    GoalSpec,
    GraphInvariantError,
    SupervisorPlan,
    WaveScheduler,
    reduce_goal_results,
)


def goal(goal_id: str, capability: str, order: int) -> GoalSpec:
    return GoalSpec.from_plan(
        goal_id=goal_id,
        plan=QueryPlan(capability, "잠실엘스"),  # type: ignore[arg-type]
        appearance_order=order,
    )


def test_wave_scheduler_caps_light_goals_and_isolates_heavy_goals() -> None:
    scheduler = WaveScheduler(GoalOrderingPolicy())
    goals = (
        goal("g1", "complex_identity", 0),
        goal("g2", "recent_trade_lookup", 1),
        goal("g3", "school_location", 2),
    )
    first = scheduler.next_wave(goals, completed=frozenset())
    second = scheduler.next_wave(goals, completed=frozenset(first))

    assert first == ("g1", "g2")
    assert second == ("g3",)

    mixed = (goal("g1", "recommendation", 0), goal("g2", "complex_identity", 1))
    assert scheduler.next_wave(mixed, completed=frozenset()) == ("g1",)


def test_dependency_planner_allows_only_explicit_recommendation_comparison_edge() -> None:
    planner = DependencyPlanner()
    recommendation = goal("recommend", "recommendation", 0)
    comparison = GoalSpec.from_plan(
        goal_id="compare",
        plan=QueryPlan("comparison", "잠실엘스", complex_names=("잠실엘스", "헬리오시티")),
        appearance_order=1,
        entity_reference="그 후보",
    )
    planned = planner.apply((recommendation, comparison))

    assert planned[1].depends_on == ("recommend",)

    forbidden = GoalSpec.from_plan(
        goal_id="compare",
        plan=comparison.plan,
        appearance_order=1,
        requested_dependencies=("unknown",),
    )
    with pytest.raises(GraphInvariantError):
        planner.apply((recommendation, forbidden))


def test_result_reducer_is_idempotent_and_rejects_conflicting_duplicate() -> None:
    result = GoalExecutionResult.unavailable("g1", "temporary", retryable=True)
    assert reduce_goal_results([result], [result]) == [result]

    conflicting = GoalExecutionResult.unavailable("g1", "different", retryable=False)
    with pytest.raises(GraphInvariantError):
        reduce_goal_results([result], [conflicting])


def test_policy_rejects_invalid_goal_identity_missing_dependency_and_no_ready_cycle() -> None:
    with pytest.raises(ValueError):
        GoalSpec.from_plan(
            goal_id="", plan=QueryPlan("complex_identity", "잠실엘스"), appearance_order=0,
        )
    first = goal("g1", "complex_identity", 0)
    missing = GoalSpec.from_plan(
        goal_id="g2",
        plan=QueryPlan("complex_identity", "헬리오시티"),
        appearance_order=1,
        requested_dependencies=("missing",),
    )
    with pytest.raises(GraphInvariantError):
        DependencyPlanner().apply((first, missing))
    self_dependency = GoalSpec.from_plan(
        goal_id="self",
        plan=QueryPlan("complex_identity", "자기참조"),
        appearance_order=2,
        requested_dependencies=("self",),
    )
    with pytest.raises(GraphInvariantError):
        DependencyPlanner().apply((self_dependency,))

    blocked = GoalSpec(
        goal_id="g2", capability="complex_identity", plan=missing.plan,
        appearance_order=1, execution_class="LIGHT", depends_on=("g1",),
    )
    with pytest.raises(GraphInvariantError):
        WaveScheduler().next_wave((blocked,), completed=frozenset())


def test_reducer_appends_new_goal_in_arrival_order() -> None:
    first = GoalExecutionResult.unavailable("g1", "one", retryable=False)
    second = GoalExecutionResult.unavailable("g2", "two", retryable=False)
    assert reduce_goal_results([first], [second]) == [first, second]


def test_plan_and_dependency_invariants_cover_duplicate_depth_cycle_and_empty_wave() -> None:
    first = goal("g1", "complex_identity", 0)
    with pytest.raises(GraphInvariantError):
        SupervisorPlan(())
    with pytest.raises(GraphInvariantError):
        SupervisorPlan((first, first))
    with pytest.raises(GraphInvariantError):
        DependencyPlanner().apply(())

    dependent = GoalSpec(
        goal_id="g2", capability="complex_identity", plan=first.plan,
        appearance_order=1, execution_class="LIGHT", depends_on=("g1",),
    )
    depth_two = GoalSpec(
        goal_id="g3", capability="complex_identity", plan=first.plan,
        appearance_order=2, execution_class="LIGHT", depends_on=("g2",),
    )
    with pytest.raises(GraphInvariantError):
        DependencyPlanner._validate_depth_and_cycles((first, dependent, depth_two))
    cycle_left = GoalSpec(
        goal_id="g1", capability="complex_identity", plan=first.plan,
        appearance_order=0, execution_class="LIGHT", depends_on=("g2",),
    )
    with pytest.raises(GraphInvariantError):
        DependencyPlanner._validate_depth_and_cycles((cycle_left, dependent))
    assert WaveScheduler().next_wave((first,), completed=frozenset({"g1"})) == ()
