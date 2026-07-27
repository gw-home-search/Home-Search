from __future__ import annotations

import asyncio
import re
from dataclasses import replace
from typing import Protocol, Sequence

from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest

from .answer_document import AnswerDocument
from .models import QueryPlan
from .supervisor import GoalExecutionResult, GoalSpec, ResolvedEntity


_CAPABILITY_INTENT_PATTERNS: dict[str, re.Pattern[str]] = {
    "complex_identity": re.compile(r"(?:단지\s*정보|위치|주소|어디)"),
    "recent_trade_lookup": re.compile(r"(?:실거래|최근\s*거래|거래\s*내역)"),
    "price_trend": re.compile(r"(?:가격\s*(?:흐름|추이)|월별|거래량)"),
    "comparison": re.compile(r"(?:비교|차이|대비)"),
    "recommendation": re.compile(r"추천"),
    "school_location": re.compile(r"(?:초등학교|중학교|고등학교|주변\s*학교|학교\s*위치)"),
    "academy_lookup": re.compile(r"(?:학원\s*위치|가까운\s*학원|교습소)"),
    "academy_registry_summary": re.compile(r"(?:학원\s*(?:수|현황|등록))"),
    "rail_station_lookup": re.compile(r"(?:지하철|가까운\s*역|역세권|철도)"),
    "retail_location": re.compile(r"(?:대형마트|백화점|쇼핑센터|복합몰)"),
    "childcare_lookup": re.compile(r"(?:어린이집|유치원)"),
    "kakao_place_search": re.compile(r"(?:카카오\s*장소|장소\s*검색)"),
}


class GroundedEnginePort(Protocol):
    async def execute_goal(
        self,
        plan: QueryPlan,
        request: ChatbotQueryRequest,
        request_id: str,
        *,
        polish_deadline: float | None,
        deterministic_draft: bool,
        resolved_complexes: tuple[object, ...] | None = None,
    ) -> AnswerDocument: ...


class GroundedGoalExecutor:
    def __init__(self, engine: GroundedEnginePort, repository: object) -> None:
        self._engine = engine
        self._repository = repository
        self._resolved_records: dict[tuple[str, str | None], tuple[object, ...]] = {}
        self._resolution_states: dict[
            tuple[str, str | None], str
        ] = {}

    async def execute(
        self,
        goal: GoalSpec,
        request: ChatbotQueryRequest,
        request_id: str,
        *,
        deadline: float | None,
    ) -> GoalExecutionResult:
        try:
            document = await self._engine.execute_goal(
                goal.plan,
                request,
                request_id,
                polish_deadline=deadline,
                deterministic_draft=True,
                resolved_complexes=self._resolved_records.get(
                    (goal.plan.complex_name, goal.plan.region_name)
                ),
            )
        except (TimeoutError, OSError, RuntimeError, ChatbotProviderUnavailable):
            return GoalExecutionResult.unavailable(
                goal.goal_id,
                "일부 데이터를 일시적으로 확인하지 못했습니다.",
                retryable=True,
            )
        status = (
            "UNAVAILABLE"
            if document.readiness == "unavailable"
            else "CLARIFICATION"
            if self._resolution_states.get(
                (goal.plan.complex_name, goal.plan.region_name)
            ) == "AMBIGUOUS"
            else
            "PARTIAL"
            if document.readiness == "partial"
            else "SUCCESS"
        )
        return GoalExecutionResult(
            goal_id=goal.goal_id,
            status=status,
            document=document,
            limitations=document.limitations,
            retryable=document.recoverable and status in {"PARTIAL", "UNAVAILABLE"},
        )

    async def resolve_entities(self, goals: Sequence[GoalSpec]) -> tuple[ResolvedEntity, ...]:
        finder = getattr(self._repository, "find_complexes", None)
        if finder is None:
            return ()
        seen: set[tuple[str, str | None]] = set()
        resolved: list[ResolvedEntity] = []
        for goal in goals:
            if goal.capability in {"recommendation", "comparison"}:
                continue
            identity = (goal.plan.complex_name, goal.plan.region_name)
            if identity in seen:
                continue
            seen.add(identity)
            records = await asyncio.to_thread(
                finder, goal.plan.complex_name, goal.plan.region_name, 3
            )
            self._resolved_records[identity] = tuple(records)
            ids = tuple(record.complex_id for record in records)
            status = (
                "NOT_FOUND"
                if not ids
                else "EXACT"
                if len(ids) == 1
                and records[0].display_name.casefold() == goal.plan.complex_name.casefold()
                else "UNIQUE"
                if len(ids) == 1
                else "AMBIGUOUS"
            )
            self._resolution_states[identity] = status
            resolved.append(ResolvedEntity(
                mention=goal.plan.complex_name,
                candidate_ids=ids,
                selected_id=ids[0] if len(ids) == 1 else None,
                status=status,
            ))
        return tuple(resolved)

    async def with_verified_recommendation_candidates(
        self,
        goal: GoalSpec,
        dependency: GoalExecutionResult,
    ) -> GoalSpec | None:
        if goal.capability != "comparison" or dependency.document is None:
            return None
        patch = dependency.document.to_public_dict().get("conversationMemoryPatch")
        ids = patch.get("complexIds") if isinstance(patch, dict) else None
        if (
            not isinstance(patch, dict)
            or patch.get("version") != 2
            or patch.get("scopeKind") != "RECOMMENDATION"
            or not isinstance(ids, list)
            or not 2 <= len(ids) <= 5
            or any(
                isinstance(complex_id, bool)
                or not isinstance(complex_id, int)
                or complex_id <= 0
                for complex_id in ids
            )
            or len(ids) != len(set(ids))
        ):
            return None
        finder = getattr(self._repository, "find_complex_by_id", None)
        if finder is None:
            return None
        records = await asyncio.gather(*(
            asyncio.to_thread(finder, complex_id)
            for complex_id in ids[:4]
        ))
        names = tuple(record.display_name for record in records if record is not None)
        if len(names) < 2:
            return None
        plan = replace(
            goal.plan,
            complex_name=names[0],
            complex_names=names,
            limit=min(len(names), 4),
        )
        return replace(goal, plan=plan)


def goal_specs(plans: Sequence[QueryPlan], question: str) -> tuple[GoalSpec, ...]:
    normalized_question = re.sub(r"\s+", " ", question)
    entity_reference = next((
        reference
        for reference in ("추천한 후보", "그 후보", "1위와 2위")
        if reference in normalized_question
    ), None)
    return tuple(
        GoalSpec.from_plan(
            goal_id=f"goal-{index}",
            plan=plan,
            appearance_order=_appearance_order(
                normalized_question, plan.capability, index - 1
            ),
            entity_reference=(
                entity_reference if plan.capability == "comparison" else None
            ),
        )
        for index, plan in enumerate(plans, start=1)
    )


def _appearance_order(question: str, capability: str, fallback_order: int) -> int:
    pattern = _CAPABILITY_INTENT_PATTERNS.get(capability)
    match = pattern.search(question) if pattern is not None else None
    return match.start() if match is not None else len(question) + fallback_order
