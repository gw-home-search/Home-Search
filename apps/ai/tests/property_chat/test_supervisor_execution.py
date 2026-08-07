from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.models import QueryPlan
from ai_service.property_chat.supervisor import GoalExecutionResult, GoalSpec
from ai_service.property_chat.supervisor_execution import GroundedGoalExecutor, goal_specs


class Document:
    def __init__(self, readiness: str, *, recoverable: bool = True, patch: object = None) -> None:
        self.readiness = readiness
        self.recoverable = recoverable
        self.limitations = ("제한",)
        self._patch = patch

    def to_public_dict(self) -> dict[str, object]:
        return {"conversationMemoryPatch": self._patch}


class Engine:
    def __init__(
        self,
        document: Document | None = None,
        error: Exception | None = None,
        *,
        answer_first_enabled: bool = False,
    ) -> None:
        self.document = document
        self.error = error
        self.answer_first_enabled = answer_first_enabled
        self.calls: list[tuple[object, ...]] = []

    async def execute_goal(self, *args: object, **kwargs: object) -> Document:
        self.calls.append((*args, kwargs))
        if self.error is not None:
            raise self.error
        assert self.document is not None
        return self.document


class Repository:
    def find_complexes(self, name: str, _region: str | None, _limit: int):
        return {
            "정확": [SimpleNamespace(complex_id=1, display_name="정확")],
            "별칭": [SimpleNamespace(complex_id=2, display_name="정식명")],
            "모호": [
                SimpleNamespace(complex_id=3, display_name="모호 A"),
                SimpleNamespace(complex_id=4, display_name="모호 B"),
            ],
        }.get(name, [])

    def find_complex_by_id(self, complex_id: int):
        return {
            10: SimpleNamespace(complex_id=10, display_name="후보 A"),
            20: SimpleNamespace(complex_id=20, display_name="후보 B"),
        }.get(complex_id)


def spec(goal_id: str, plan: QueryPlan) -> GoalSpec:
    return GoalSpec.from_plan(goal_id=goal_id, plan=plan, appearance_order=0)


@pytest.mark.parametrize(
    ("readiness", "expected_status", "expected_retryable"),
    [
        ("supported", "SUCCESS", False),
        ("partial", "PARTIAL", False),
        ("unavailable", "UNAVAILABLE", False),
    ],
)
def test_executor_preserves_document_without_marking_evidence_limits_retryable(
    readiness: str, expected_status: str, expected_retryable: bool,
) -> None:
    document = Document(readiness)
    engine = Engine(document)
    executor = GroundedGoalExecutor(engine, Repository())  # type: ignore[arg-type]
    goal = spec("g1", QueryPlan("complex_identity", "정확"))

    result = asyncio.run(executor.execute(
        goal,
        ChatbotQueryRequest(question="정확 단지 정보"),
        "request-1",
        deadline=100.0,
    ))

    assert result.status == expected_status
    assert result.document is document
    assert result.retryable is expected_retryable
    assert engine.calls[0][-1] == {
        "polish_deadline": 100.0,
        "deterministic_draft": True,
        "resolved_complexes": None,
    }


def test_executor_converts_expected_runtime_failure_to_unavailable() -> None:
    executor = GroundedGoalExecutor(Engine(error=TimeoutError()), Repository())  # type: ignore[arg-type]
    result = asyncio.run(executor.execute(
        spec("g1", QueryPlan("complex_identity", "정확")),
        ChatbotQueryRequest(question="정확 단지 정보"),
        "request-1",
        deadline=None,
    ))

    assert result.status == "UNAVAILABLE"
    assert result.retryable is True


def test_executor_resolves_shared_entities_once_with_all_states() -> None:
    engine = Engine(Document("supported"))
    executor = GroundedGoalExecutor(engine, Repository())  # type: ignore[arg-type]
    goals = tuple(
        spec(f"g{index}", QueryPlan("complex_identity", name))
        for index, name in enumerate(("정확", "정확", "별칭", "모호", "없음"), start=1)
    )

    resolved = asyncio.run(executor.resolve_entities(goals))

    assert [(item.mention, item.status, item.selected_id) for item in resolved] == [
        ("정확", "EXACT", 1),
        ("별칭", "UNIQUE", 2),
        ("모호", "AMBIGUOUS", None),
        ("없음", "NOT_FOUND", None),
    ]

    result = asyncio.run(executor.execute(
        goals[3], ChatbotQueryRequest(question="모호 단지 정보"), "request-1",
        deadline=100.0,
    ))
    assert result.status == "CLARIFICATION"
    assert engine.calls[-1][-1]["resolved_complexes"] is None

    asyncio.run(executor.execute(
        goals[0], ChatbotQueryRequest(question="정확 단지 정보"), "request-2",
        deadline=100.0,
    ))
    assert tuple(
        record.complex_id
        for record in engine.calls[-1][-1]["resolved_complexes"]
    ) == (1,)


def test_answer_first_executor_never_selects_the_first_ambiguous_candidate() -> None:
    engine = Engine(Document("supported"), answer_first_enabled=True)
    executor = GroundedGoalExecutor(engine, Repository())  # type: ignore[arg-type]
    goal = spec("g1", QueryPlan("complex_identity", "모호"))

    resolved = asyncio.run(executor.resolve_entities((goal,)))

    assert [(item.status, item.selected_id, item.candidate_ids) for item in resolved] == [
        ("AMBIGUOUS", None, (3, 4)),
    ]


def test_executor_prioritizes_unavailable_readiness_over_ambiguous_entity() -> None:
    executor = GroundedGoalExecutor(Engine(Document("unavailable")), Repository())  # type: ignore[arg-type]
    goal = spec("g1", QueryPlan("complex_identity", "모호"))
    asyncio.run(executor.resolve_entities((goal,)))

    result = asyncio.run(executor.execute(
        goal,
        ChatbotQueryRequest(question="모호 단지 정보"),
        "request-1",
        deadline=100.0,
    ))

    assert result.status == "UNAVAILABLE"


def test_dependent_comparison_uses_only_verified_recommendation_ids() -> None:
    executor = GroundedGoalExecutor(Engine(Document("supported")), Repository())  # type: ignore[arg-type]
    comparison = spec(
        "compare",
        QueryPlan("comparison", "기존 A", complex_names=("기존 A", "기존 B")),
    )
    dependency = GoalExecutionResult(
        goal_id="recommend",
        status="SUCCESS",
        document=Document("supported", patch={
            "version": 2, "scopeKind": "RECOMMENDATION", "complexIds": [10, 20, 30],
        }),
    )

    updated = asyncio.run(executor.with_verified_recommendation_candidates(
        comparison, dependency
    ))

    assert updated.plan.complex_names == ("후보 A", "후보 B")
    rejected = asyncio.run(executor.with_verified_recommendation_candidates(
        comparison,
        GoalExecutionResult("recommend", "SUCCESS", document=Document("supported")),
    ))
    assert rejected is None


def test_goal_specs_keep_order_and_only_mark_explicit_comparison_reference() -> None:
    goals = goal_specs((
        QueryPlan("recommendation", "송파구", region_name="송파구", limit=2),
        QueryPlan("comparison", "A", complex_names=("A", "B")),
    ), "송파구 추천한 후보를 비교해줘")

    assert [goal.goal_id for goal in goals] == ["goal-1", "goal-2"]
    assert goals[0].entity_reference is None
    assert goals[1].entity_reference == "추천한 후보"


def test_goal_specs_restore_question_appearance_order_after_bundle_canonicalization() -> None:
    goals = goal_specs((
        QueryPlan("comparison", "A", complex_names=("A", "B")),
        QueryPlan("recommendation", "송파구", region_name="송파구", limit=2),
        QueryPlan("rail_station_lookup", "A"),
    ), "송파구 후보를 추천하고 그 후보를 비교한 다음 가까운 역도 알려줘")

    appearance = {goal.capability: goal.appearance_order for goal in goals}
    assert appearance["recommendation"] < appearance["comparison"]
    assert appearance["comparison"] < appearance["rail_station_lookup"]


def test_executor_fail_closed_branches_leave_goal_unchanged() -> None:
    no_repository = GroundedGoalExecutor(Engine(Document("supported")), object())  # type: ignore[arg-type]
    assert asyncio.run(no_repository.resolve_entities(())) == ()
    recommendation = spec(
        "recommend", QueryPlan("recommendation", "송파구", region_name="송파구")
    )
    assert asyncio.run(GroundedGoalExecutor(
        Engine(Document("supported")), Repository(),  # type: ignore[arg-type]
    ).resolve_entities((recommendation,))) == ()

    dependency = GoalExecutionResult(
        "recommend", "SUCCESS", document=Document("supported", patch={
            "complexIds": [10, 999],
        }),
    )
    comparison = spec("compare", QueryPlan("comparison", "A", complex_names=("A", "B")))
    assert asyncio.run(no_repository.with_verified_recommendation_candidates(
        comparison, dependency
    )) is None
    assert asyncio.run(GroundedGoalExecutor(
        Engine(Document("supported")), Repository(),  # type: ignore[arg-type]
    ).with_verified_recommendation_candidates(comparison, dependency)) is None
    identity = spec("identity", QueryPlan("complex_identity", "정확"))
    assert asyncio.run(no_repository.with_verified_recommendation_candidates(
        identity, dependency
    )) is None
