from __future__ import annotations

import asyncio
import re
import time
from collections.abc import Callable, Iterable
from dataclasses import replace
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation
from typing import Protocol

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest

from .answer_document import AnswerDocument, CompoundAnswerDocument, FactListPresenter
from .answer_quality import AnswerQualityError, AnswerQualityGate
from .presentation import PresentationAssembler
from .capability_handlers import (
    AcademyLocationFactRepository,
    AcademyLookupHandler,
    AcademyRegistryFactRepository,
    AcademyRegistrySummaryHandler,
    CapabilityCatalog,
    CapabilityResult,
    ChildcareFactRepository,
    ChildcareLookupHandler,
    EvidenceFactBuilders,
    KakaoPlaceSearchHandler,
    PointFacilityFactRepository,
    PriceTrendHandler,
    PropertyFactRepository,
    PropertyIdentityHandler,
    RailStationFactRepository,
    RailStationHandler,
    RecentTradeHandler,
    RetailLocationHandler,
    SchoolFactRepository,
    SchoolLocationHandler,
)
from .comparison_handler import ComparisonHandler
from .deterministic_answer import DeterministicAnswerPresenter
from .deterministic_router import DeterministicQueryRouter
from .recommendation_handler import RecommendationHandler
from .recommendation_errors import RecommendationExecutionError
from .recommendation_presentation import RecommendationTextPresenter
from .lifestyle_themes import detect_explicit_themes, detect_school_levels
from .models import (
    ComplexRecord,
    DraftAnswer,
    EvidenceFact,
    FactClaim,
    MonthlyTrendRecord,
    PropertyCapability,
    QueryCapability,
    QueryPlan,
    QueryPlanBundle,
    RecommendationMode,
    ReferenceCapability,
    SchoolRecord,
    SchoolSearchResult,
    SchoolSnapshot,
    TradeRecord,
    ShowNearbyCategoryAction,
)
from .academy_registry import AcademyRegistrySummary
from .childcare_centers import ChildcareCenter, ChildcareSearchResult
from .academy_locations import (
    AcademyLocation,
    AcademyLocationSearchResult,
    RegistryExactMatch,
)
from .reference_facilities import FacilityFact, FacilitySearchResult
from .rail_stations import RailStation, RailStationSearchResult


class GroundedLanguageModel(Protocol):
    async def plan_query(
        self, request: ChatbotQueryRequest
    ) -> QueryPlan | QueryPlanBundle: ...

    async def draft_answer(
        self,
        *,
        facts: list[EvidenceFact],
        limitations: list[str],
        question: str,
    ) -> DraftAnswer: ...


_GROUNDING_FAILURE_REASONS = frozenset(
    {
        "GROUNDING_CAPABILITY_UNSUPPORTED",
        "GROUNDING_ANSWER_EMPTY",
        "GROUNDING_SENTENCE_BLANK",
        "GROUNDING_FACT_IDS_MISSING",
        "GROUNDING_CLAIMS_MISSING",
        "GROUNDING_FACT_IDS_DUPLICATE",
        "GROUNDING_FACT_UNKNOWN",
        "GROUNDING_FACTS_OMITTED",
        "GROUNDING_CLAIM_NOT_ATTACHED",
        "GROUNDING_CLAIM_MISMATCH",
        "GROUNDING_RESULT_COUNT_OR_LIST_NUMBER",
        "GROUNDING_AMOUNT_UNIT_CONVERSION",
        "GROUNDING_NUMBER_OUTSIDE_OBSERVATION",
        "GROUNDING_SCHOOL_POLICY_VIOLATION",
        "GROUNDING_SCHOOL_TEXT_OUTSIDE_OBSERVATION",
        "GROUNDING_RETAIL_POLICY_VIOLATION",
        "GROUNDING_RETAIL_TEXT_OUTSIDE_OBSERVATION",
        "GROUNDING_ACADEMY_REGISTRY_POLICY_VIOLATION",
        "GROUNDING_ACADEMY_LOOKUP_POLICY_VIOLATION",
        "GROUNDING_ACADEMY_LOOKUP_TEXT_OUTSIDE_OBSERVATION",
        "GROUNDING_RAIL_POLICY_VIOLATION",
        "GROUNDING_RAIL_TEXT_OUTSIDE_OBSERVATION",
        "GROUNDING_CHILDCARE_POLICY_VIOLATION",
        "GROUNDING_CHILDCARE_TEXT_OUTSIDE_OBSERVATION",
        "GROUNDING_MAP_ACTION_POLICY_VIOLATION",
        "GROUNDING_ACTION_FACT_UNKNOWN",
        "GROUNDING_ARTIFACT_FACT_UNKNOWN",
        "GROUNDING_COMPARISON_POLICY_VIOLATION",
        "GROUNDING_RECOMMENDATION_POLICY_VIOLATION",
        "GROUNDING_RECOMMENDATION_TEXT_OUTSIDE_OBSERVATION",
        "GROUNDING_USER_COPY_POLICY_VIOLATION",
    }
)


class GroundingValidationError(ValueError):
    """Non-disclosing validation failure with a stable diagnostic category."""

    def __init__(self, reason_code: str) -> None:
        if reason_code not in _GROUNDING_FAILURE_REASONS:
            raise ValueError("invalid grounding failure reason")
        super().__init__()
        self.reason_code = reason_code


class GroundedChatbotEngine:
    def __init__(
        self,
        *,
        repository: PropertyFactRepository,
        language_model: GroundedLanguageModel,
        enabled_capabilities: frozenset[PropertyCapability],
        school_repository: SchoolFactRepository | None = None,
        point_facility_repository: PointFacilityFactRepository | None = None,
        academy_registry_repository: AcademyRegistryFactRepository | None = None,
        academy_location_repository: AcademyLocationFactRepository | None = None,
        rail_station_repository: RailStationFactRepository | None = None,
        childcare_repository: ChildcareFactRepository | None = None,
        enabled_reference_capabilities: frozenset[ReferenceCapability] = frozenset(),
        enabled_recommendation_modes: frozenset[RecommendationMode] = frozenset(
            {"BUDGET", "CRITERIA"}
        ),
        answer_first_enabled: bool = False,
        property_overview_enabled: bool = False,
        answer_first_fallback_capabilities: frozenset[QueryCapability] | None = None,
        semantic_goal_planner_enabled: bool = True,
        dependent_workflow_enabled: bool = False,
        polish_budget_seconds: float | None = None,
        today: Callable[[], date] = date.today,
    ) -> None:
        self._repository = repository
        self._language_model = language_model
        self._enabled_capabilities = enabled_capabilities
        self._enabled_reference_capabilities = enabled_reference_capabilities
        self._enabled_recommendation_modes = enabled_recommendation_modes
        self._answer_first_enabled = answer_first_enabled
        self._property_overview_enabled = property_overview_enabled
        self._answer_first_fallback_capabilities = (
            answer_first_fallback_capabilities
            if answer_first_fallback_capabilities is not None
            else frozenset({
                "complex_identity",
                "recent_trade_lookup",
                "price_trend",
                "school_location",
                "retail_location",
                "academy_registry_summary",
                "academy_lookup",
                "rail_station_lookup",
                "childcare_lookup",
                "kakao_place_search",
                "comparison",
                "recommendation",
            })
        )
        self._semantic_goal_planner_enabled = semantic_goal_planner_enabled
        self._dependent_workflow_enabled = dependent_workflow_enabled
        self._polish_budget_seconds = polish_budget_seconds
        self._today = today
        builders = EvidenceFactBuilders(
            complex_fact=_complex_fact,
            trade_fact=_trade_fact,
            trend_fact=_trend_fact,
            school_fact=_school_fact,
            school_scope_fact=_school_scope_fact,
            academy_registry_fact=_academy_registry_fact,
            academy_location_fact=_academy_location_fact,
            academy_exact_match_fact=_academy_exact_match_fact,
            academy_lookup_scope_fact=_academy_lookup_scope_fact,
            retail_fact=_retail_fact,
            retail_scope_fact=_retail_scope_fact,
            rail_station_fact=_rail_station_fact,
            rail_scope_fact=_rail_scope_fact,
            childcare_fact=_childcare_fact,
            childcare_scope_fact=_childcare_scope_fact,
        )
        self._catalog = CapabilityCatalog(
            (
                PropertyIdentityHandler(builders),
                RecentTradeHandler(repository, builders),
                PriceTrendHandler(repository, builders),
                SchoolLocationHandler(school_repository, builders, today),
                AcademyLookupHandler(academy_location_repository, builders, today),
                AcademyRegistrySummaryHandler(
                    repository, academy_registry_repository, builders, today
                ),
                RailStationHandler(rail_station_repository, builders, today),
                RetailLocationHandler(point_facility_repository, builders),
                ChildcareLookupHandler(childcare_repository, builders, today),
                KakaoPlaceSearchHandler(builders),
            ),
            plan_handlers=(
                ComparisonHandler(
                    repository,  # type: ignore[arg-type]
                    rail_station_repository,  # type: ignore[arg-type]
                    point_facility_repository,  # type: ignore[arg-type]
                    school_repository,  # type: ignore[arg-type]
                    academy_location_repository,  # type: ignore[arg-type]
                    childcare_repository,  # type: ignore[arg-type]
                    builders,
                    today,
                ),
                RecommendationHandler(
                    repository,  # type: ignore[arg-type]
                    rail_station_repository,  # type: ignore[arg-type]
                    point_facility_repository,  # type: ignore[arg-type]
                    school_repository,  # type: ignore[arg-type]
                    academy_location_repository,  # type: ignore[arg-type]
                    childcare_repository,  # type: ignore[arg-type]
                    builders,
                    today,
                ),
            ),
        )

    async def query(
        self,
        *,
        request: ChatbotQueryRequest,
        user: AuthenticatedUser,
        request_id: str,
    ) -> dict[str, object]:
        del user
        polish_deadline = (
            time.monotonic() + self._polish_budget_seconds
            if self._polish_budget_seconds is not None
            else None
        )
        try:
            try:
                planned = await self._language_model.plan_query(request)
            except ChatbotProviderUnavailable:
                if not self._answer_first_enabled:
                    raise
                planned = DeterministicQueryRouter(today=self._today()).plan(request)
                if planned is None:
                    raise
            if self._dependent_workflow_enabled:
                planned = await self._apply_dependent_context(request, planned)
            if self._answer_first_enabled and self._property_overview_enabled:
                planned = await self._apply_overview_context(request, planned)
            bundle = (
                planned if isinstance(planned, QueryPlanBundle)
                else QueryPlanBundle((planned,))
            )
            plans = tuple(
                _verify_plan(
                    _apply_answer_first_defaults(plan, self._today())
                    if self._answer_first_enabled else plan,
                    request.question,
                    semantic_goal_planner_enabled=self._semantic_goal_planner_enabled,
                )
                for plan in bundle.fragments
            )
            documents = tuple(await asyncio.gather(*(
                self._execute_fragment(
                    plan, request, request_id, polish_deadline=polish_deadline
                )
                for plan in plans
            )))
            if len(documents) == 1:
                try:
                    return documents[0].to_public_dict()
                except RecommendationExecutionError:
                    raise
                except Exception as exception:
                    if documents[0].plan.capability == "recommendation":
                        raise RecommendationExecutionError(
                            "RECOMMENDATION_RESPONSE_SERIALIZATION_FAILED"
                        ) from exception
                    raise
            try:
                return CompoundAnswerDocument(
                    request, request_id, documents
                ).to_public_dict()
            except RecommendationExecutionError:
                raise
            except Exception as exception:
                if any(
                    document.plan.capability == "recommendation"
                    for document in documents
                ):
                    raise RecommendationExecutionError(
                        "RECOMMENDATION_RESPONSE_SERIALIZATION_FAILED"
                    ) from exception
                raise
        except ChatbotProviderUnavailable:
            raise
        except Exception as exception:
            raise ChatbotProviderUnavailable() from exception

    async def _apply_dependent_context(
        self,
        request: ChatbotQueryRequest,
        planned: QueryPlan | QueryPlanBundle,
    ) -> QueryPlan | QueryPlanBundle:
        context = request.conversationContext
        memory = context.memory if context is not None else None
        if (
            memory is None
            or memory.version != 2
            or memory.scopeKind != "RECOMMENDATION"
            or not memory.complexIds
            or re.search(r"(?:비교|차이|대조)", request.question) is None
        ):
            return planned
        selected_indexes = tuple(
            index
            for index in _referenced_candidate_indexes(request.question)
            if index < len(memory.complexIds)
        )
        candidate_ids = (
            tuple(memory.complexIds[index] for index in selected_indexes)
            if len(selected_indexes) >= 2
            else tuple(memory.complexIds[:4])
        )
        records = await asyncio.gather(*(
            asyncio.to_thread(self._repository.find_complex_by_id, complex_id)
            for complex_id in candidate_ids
        ))
        verified = tuple(record for record in records if record is not None)
        if len(verified) < 2:
            return planned
        first_plan = (
            planned.fragments[0] if isinstance(planned, QueryPlanBundle) else planned
        )
        return QueryPlan(
            capability="comparison",
            complex_name=verified[0].display_name,
            complex_names=tuple(record.display_name for record in verified),
            region_name=first_plan.region_name,
            exclusive_area_square_meters=first_plan.exclusive_area_square_meters,
            start_date=first_plan.start_date,
            end_date=first_plan.end_date,
            lifestyle_themes=first_plan.lifestyle_themes,
            school_levels=first_plan.school_levels,
            limit=min(len(verified), 4),
        )

    async def _apply_overview_context(
        self,
        request: ChatbotQueryRequest,
        planned: QueryPlan | QueryPlanBundle,
    ) -> QueryPlan | QueryPlanBundle:
        if re.search(r"(?:전체적|전반적|이\s*단지\s*어때|여기\s*(?:어때|괜찮)|살기\s*괜찮)", request.question) is None:
            return planned
        complex_id = None
        conversation_context = request.conversationContext
        if conversation_context is not None and conversation_context.memory is not None:
            complex_id = conversation_context.memory.complexId
        ui_context = request.uiContext
        if ui_context is not None and ui_context.selectedComplex is not None:
            complex_id = ui_context.selectedComplex.complexId
        record = None
        if complex_id is not None:
            finder = getattr(self._repository, "find_complex_by_id", None)
            if finder is not None:
                record = await asyncio.to_thread(finder, complex_id)
        if record is not None:
            return DeterministicQueryRouter(today=self._today()).overview(
                record.display_name,
                record.region_name,
            )
        first_plan = (
            planned.fragments[0]
            if isinstance(planned, QueryPlanBundle)
            else planned
        )
        if first_plan.complex_name not in {"이 단지", "여기", "이곳"}:
            return DeterministicQueryRouter(today=self._today()).overview(
                first_plan.complex_name,
                first_plan.region_name,
            )
        return planned

    async def _execute_fragment(
        self,
        plan: QueryPlan,
        request: ChatbotQueryRequest,
        request_id: str,
        *,
        polish_deadline: float | None,
    ) -> AnswerDocument:
        try:
            if (
                plan.capability == "recommendation"
                and plan.recommendation_mode not in self._enabled_recommendation_modes
            ):
                result = CapabilityResult(
                    [],
                    ["이 추천 방식은 현재 데이터 준비와 검증이 진행 중입니다."],
                    "unavailable",
                )
            elif plan.capability in self._enabled_capabilities or (
                plan.capability in self._enabled_reference_capabilities
            ):
                plan_handler = self._catalog.plan_handler_for(plan.capability)
                if plan_handler is not None:
                    async with asyncio.timeout(_fragment_timeout_seconds(plan.capability)):
                        result = await plan_handler.observe(plan)
                else:
                    result = await self._observe(plan)
            else:
                result = CapabilityResult(
                    [],
                    ["해당 질문 기능은 현재 데이터 준비와 검증이 진행 중입니다."],
                    "unavailable",
                )
        except RecommendationExecutionError:
            raise
        except (TimeoutError, OSError, RuntimeError, ChatbotProviderUnavailable):
            if not self._fallback_enabled(plan.capability):
                raise
            result = CapabilityResult(
                [],
                ["일부 데이터를 확인하지 못해 이 항목은 답변에서 제외했습니다."],
                "unavailable",
            )
        if self._answer_first_enabled:
            result = _annotate_answer_first_assumptions(
                result, plan, request.question
            )
        if plan.capability == "recommendation":
            try:
                draft = RecommendationTextPresenter().present(
                    facts=result.facts,
                    limitations=result.limitations,
                    readiness=result.readiness,
                )
            except Exception as exception:
                raise RecommendationExecutionError(
                    "RECOMMENDATION_TEXT_PRESENTATION_FAILED"
                ) from exception
        else:
            try:
                if polish_deadline is None:
                    draft = await self._language_model.draft_answer(
                        facts=result.facts,
                        limitations=result.limitations,
                        question=request.question,
                    )
                else:
                    remaining = polish_deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError
                    async with asyncio.timeout(remaining):
                        draft = await self._language_model.draft_answer(
                            facts=result.facts,
                            limitations=result.limitations,
                            question=request.question,
                        )
            except (TimeoutError, OSError, RuntimeError, ChatbotProviderUnavailable):
                if not self._fallback_enabled(plan.capability):
                    raise
                draft = DeterministicAnswerPresenter().present(
                    plan=plan,
                    facts=result.facts,
                    limitations=result.limitations,
                    readiness=result.readiness,
                )
        validation_options = {
            "limitations": result.limitations,
            "enforce_school_policy": plan.capability == "school_location",
            "enforce_childcare_policy": plan.capability == "childcare_lookup",
            "enforce_map_action_policy": plan.capability == "kakao_place_search",
            "enforce_comparison_policy": plan.capability == "comparison",
            "enforce_recommendation_policy": plan.capability == "recommendation",
        }
        try:
            used_facts = validate_draft(
                draft, result.facts, result.readiness, **validation_options
            )
            AnswerQualityGate().validate(
                draft=draft,
                facts=result.facts,
                readiness=result.readiness,
            )
        except (GroundingValidationError, AnswerQualityError):
            if not self._fallback_enabled(plan.capability):
                raise
            draft = DeterministicAnswerPresenter().present(
                plan=plan,
                facts=result.facts,
                limitations=result.limitations,
                readiness=result.readiness,
            )
            used_facts = validate_draft(
                draft, result.facts, result.readiness, **validation_options
            )
            AnswerQualityGate().validate(
                draft=draft,
                facts=result.facts,
                readiness=result.readiness,
            )
        if result.artifact_fact_ids:
            fact_by_id = {fact.fact_id: fact for fact in result.facts}
            if any(fact_id not in fact_by_id for fact_id in result.artifact_fact_ids):
                raise GroundingValidationError("GROUNDING_ARTIFACT_FACT_UNKNOWN")
            used_ids = {fact.fact_id for fact in used_facts}
            used_facts.extend(
                fact_by_id[fact_id]
                for fact_id in result.artifact_fact_ids
                if fact_id not in used_ids
            )
        used_fact_ids = {fact.fact_id for fact in used_facts}
        if any(
            not set(action.fact_ids).issubset(used_fact_ids)
            for action in result.actions
        ):
            raise GroundingValidationError("GROUNDING_ACTION_FACT_UNKNOWN")
        try:
            artifacts = list(result.artifacts) or FactListPresenter().present(
                plan=plan, used_facts=used_facts, readiness=result.readiness
            )
            presentation, artifacts = PresentationAssembler().present(
                plan=plan,
                used_facts=used_facts,
                readiness=result.readiness,
                artifacts=artifacts,
            )
        except Exception as exception:
            if plan.capability == "recommendation":
                raise RecommendationExecutionError(
                    "RECOMMENDATION_STRUCTURED_PRESENTATION_FAILED"
                ) from exception
            raise
        try:
            return AnswerDocument.from_grounded_result(
                request=request,
                request_id=request_id,
                plan=plan,
                draft=draft,
                used_facts=used_facts,
                limitations=result.limitations,
                readiness=result.readiness,
                artifacts=artifacts,
                actions=[action.to_public_dict(request_id) for action in result.actions],
                presentation=presentation,
                outcome_state=result.state or "EXACT",
                assumptions=result.assumptions,
                fallback_steps=result.fallback_steps,
                recoverable=result.recoverable,
            )
        except Exception as exception:
            if plan.capability == "recommendation":
                raise RecommendationExecutionError(
                    "RECOMMENDATION_DOCUMENT_FAILED"
                ) from exception
            raise

    async def _observe(self, plan: QueryPlan) -> CapabilityResult:
        complexes = await asyncio.to_thread(
            self._repository.find_complexes,
            plan.complex_name,
            plan.region_name,
            6,
        )
        if not complexes:
            return CapabilityResult(
                [],
                ["지정한 이름과 지역 조건으로 단지를 식별하지 못했습니다."],
                "unavailable",
            )
        if len(complexes) > 1:
            return CapabilityResult(
                [_complex_fact(record) for record in complexes],
                ["동명 단지가 여러 곳이어서 지역과 주소를 함께 표시했습니다."],
                "partial",
                state="DEGRADED",
                fallback_steps=("AMBIGUOUS_COMPLEX_CANDIDATES",),
            )
        handler = self._catalog.handler_for(plan.capability)
        if handler is None:
            raise GroundingValidationError("GROUNDING_CAPABILITY_UNSUPPORTED")
        try:
            result = await handler.observe(plan, complexes[0])
        except (TimeoutError, OSError, RuntimeError, ChatbotProviderUnavailable):
            if not self._fallback_enabled(plan.capability):
                raise
            return CapabilityResult(
                [_complex_fact(complexes[0])],
                ["요청한 세부 데이터는 현재 확인하지 못해 단지 기본정보만 정리했습니다."],
                "partial",
                state="DEGRADED",
                fallback_steps=("PRESERVED_COMPLEX_IDENTITY",),
                recoverable=True,
            )
        if self._fallback_enabled(plan.capability) and result.state == "EMPTY" and not result.facts:
            return replace(
                result,
                facts=[_complex_fact(complexes[0])],
                readiness="partial",
            )
        return result

    def _fallback_enabled(self, capability: QueryCapability) -> bool:
        return (
            self._answer_first_enabled
            and capability in self._answer_first_fallback_capabilities
        )


def _fragment_timeout_seconds(capability: QueryCapability) -> float:
    # Recommendation handlers combine several independently bounded read queries.
    return 8.0 if capability == "recommendation" else 3.0


def _complex_fact(record: ComplexRecord) -> EvidenceFact:
    claims = [
        FactClaim(str(record.complex_id), "COMPLEX_ID"),
        FactClaim(record.display_name, "TEXT"),
    ]
    for value in (record.region_code, record.region_name, record.address):
        if value:
            claims.append(FactClaim(value, "TEXT"))
    payload: dict[str, object] = {
        "complexId": record.complex_id,
        "displayName": record.display_name,
        "regionCode": record.region_code,
        "regionName": record.region_name,
        "address": record.address,
        "markerSafe": record.marker_safe,
    }
    if record.unit_count is not None:
        claims.append(FactClaim(str(record.unit_count), "HOUSEHOLD_COUNT"))
        payload["unitCount"] = record.unit_count
    if record.use_date is not None:
        claims.append(FactClaim(record.use_date.isoformat(), "DATE"))
        payload["useDate"] = record.use_date.isoformat()
    claims.append(FactClaim(str(record.marker_safe).lower(), "BOOLEAN"))
    if record.marker_safe and record.latitude is not None and record.longitude is not None:
        claims.extend(
            [
                FactClaim(_number(record.latitude), "DEGREES_LATITUDE"),
                FactClaim(_number(record.longitude), "DEGREES_LONGITUDE"),
            ]
        )
        payload["latitude"] = record.latitude
        payload["longitude"] = record.longitude
    return EvidenceFact(
        fact_id=f"property-complex-{record.complex_id}",
        claims=tuple(claims),
        data_as_of=record.data_updated_at.date(),
        payload=payload,
    )


def _trade_fact(record: TradeRecord, data_as_of: date) -> EvidenceFact:
    claims = (
        FactClaim(record.deal_date.isoformat(), "DATE"),
        FactClaim(str(record.deal_amount_ten_thousand_krw), "10_000_KRW"),
        FactClaim(
            _korean_krw_display(record.deal_amount_ten_thousand_krw),
            "KOREAN_KRW_DISPLAY",
        ),
        FactClaim(_number(record.exclusive_area_square_meters), "SQUARE_METERS"),
        *(() if record.floor is None else (FactClaim(str(record.floor), "FLOOR"),)),
    )
    return EvidenceFact(
        fact_id=f"property-trade-{record.trade_id}",
        claims=claims,
        data_as_of=data_as_of,
        payload={
            "tradeId": record.trade_id,
            "complexId": record.complex_id,
            "dealDate": record.deal_date.isoformat(),
            "dealAmountTenThousandKrw": record.deal_amount_ten_thousand_krw,
            "exclusiveAreaSquareMeters": record.exclusive_area_square_meters,
            "floor": record.floor,
        },
    )


def _trend_fact(record: MonthlyTrendRecord, data_as_of: date) -> EvidenceFact:
    return EvidenceFact(
        fact_id=f"property-trend-{record.complex_id}-{record.month:%Y-%m}",
        claims=(
            FactClaim(record.month.strftime("%Y-%m"), "MONTH"),
            FactClaim(str(record.average_amount_ten_thousand_krw), "10_000_KRW"),
            FactClaim(
                _korean_krw_display(record.average_amount_ten_thousand_krw),
                "KOREAN_KRW_AVERAGE_DISPLAY",
            ),
            FactClaim(str(record.trade_count), "COUNT"),
            FactClaim(str(record.minimum_amount_ten_thousand_krw), "10_000_KRW_MIN"),
            FactClaim(
                _korean_krw_display(record.minimum_amount_ten_thousand_krw),
                "KOREAN_KRW_MIN_DISPLAY",
            ),
            FactClaim(str(record.maximum_amount_ten_thousand_krw), "10_000_KRW_MAX"),
            FactClaim(
                _korean_krw_display(record.maximum_amount_ten_thousand_krw),
                "KOREAN_KRW_MAX_DISPLAY",
            ),
        ),
        data_as_of=data_as_of,
        payload={
            "complexId": record.complex_id,
            "month": record.month.strftime("%Y-%m"),
            "averageAmountTenThousandKrw": record.average_amount_ten_thousand_krw,
            "tradeCount": record.trade_count,
            "minimumAmountTenThousandKrw": record.minimum_amount_ten_thousand_krw,
            "maximumAmountTenThousandKrw": record.maximum_amount_ten_thousand_krw,
        },
    )


def _school_fact(record: SchoolRecord, snapshot: SchoolSnapshot) -> EvidenceFact:
    level_display = {
        "ELEMENTARY": "초등학교",
        "MIDDLE": "중학교",
        "HIGH": "고등학교",
    }[record.school_level]
    address = record.road_address or record.lot_address
    claims = [
        FactClaim(record.school_id, "SCHOOL_ID"),
        FactClaim(record.school_name, "TEXT"),
        FactClaim(record.school_level, "SCHOOL_LEVEL"),
        FactClaim(level_display, "SCHOOL_LEVEL_DISPLAY"),
        FactClaim(record.operating_status, "OPERATING_STATUS"),
        FactClaim(str(record.distance_meters), "METERS"),
    ]
    if address:
        claims.append(FactClaim(address, "TEXT"))
    return EvidenceFact(
        fact_id=f"school-location-{record.school_id}",
        claims=tuple(claims),
        data_as_of=snapshot.source_date,
        payload={
            "schoolId": record.school_id,
            "schoolName": record.school_name,
            "schoolLevel": record.school_level,
            "operatingStatus": record.operating_status,
            "distanceMeters": record.distance_meters,
            "address": address,
            "datasetVersion": snapshot.dataset_version,
            "dataAsOf": snapshot.source_date.isoformat(),
        },
        source_id="edu.school-location",
        source_name="전국초중등학교위치표준데이터",
        source_url="https://www.data.go.kr/data/15021148/standard.do",
        evidence_grade="A",
        dataset_version_value=snapshot.dataset_version,
    )


def _school_scope_fact(
    plan: QueryPlan,
    complex_record: ComplexRecord,
    result: SchoolSearchResult,
    snapshot: SchoolSnapshot,
) -> EvidenceFact:
    school_levels = ",".join(plan.school_levels)
    return EvidenceFact(
        fact_id=f"school-location-scope-{complex_record.complex_id}-{plan.radius_meters}",
        claims=(
            FactClaim(str(complex_record.complex_id), "COMPLEX_ID"),
            FactClaim(str(plan.radius_meters), "RADIUS_METERS"),
            FactClaim(school_levels, "SCHOOL_LEVELS"),
            FactClaim(str(result.matched_count), "COUNT"),
            FactClaim(str(result.returned_count), "RETURNED_COUNT"),
            FactClaim(str(result.has_more).lower(), "BOOLEAN"),
        ),
        data_as_of=snapshot.source_date,
        payload={
            "complexId": complex_record.complex_id,
            "radiusMeters": plan.radius_meters,
            "schoolLevels": list(plan.school_levels),
            "matchedCount": result.matched_count,
            "returnedCount": result.returned_count,
            "hasMore": result.has_more,
        },
        source_id="edu.school-location",
        source_name="전국초중등학교위치표준데이터",
        source_url="https://www.data.go.kr/data/15021148/standard.do",
        evidence_grade="A",
        dataset_version_value=snapshot.dataset_version,
    )


def _academy_registry_fact(summary: AcademyRegistrySummary) -> EvidenceFact:
    observed_date = summary.observed_at.date()
    return EvidenceFact(
        fact_id=(
            f"academy-registry-{summary.education_office_code}-"
            f"{summary.district_name}"
        ),
        claims=(
            FactClaim(summary.education_office_code, "EDUCATION_OFFICE_CODE"),
            FactClaim(summary.education_office_name, "EDUCATION_OFFICE"),
            FactClaim(summary.district_name, "DISTRICT"),
            FactClaim(str(summary.total_count), "COUNT"),
            FactClaim(str(summary.open_count), "OPEN_COUNT"),
            FactClaim(observed_date.isoformat(), "DATE"),
        ),
        data_as_of=observed_date,
        payload={
            "educationOfficeCode": summary.education_office_code,
            "educationOfficeName": summary.education_office_name,
            "districtName": summary.district_name,
            "registeredCount": summary.total_count,
            "openCount": summary.open_count,
            "observedDate": observed_date.isoformat(),
            "datasetVersion": summary.dataset_version,
        },
        source_id="edu.academy-registry",
        source_name="전국학원및교습소표준데이터",
        source_url="https://www.data.go.kr/data/15096277/standard.do",
        evidence_grade="A",
        dataset_version_value=summary.dataset_version,
    )


def _academy_location_fact(location: AcademyLocation) -> EvidenceFact:
    claims = [
        FactClaim(location.store_id, "FACILITY_ID"),
        FactClaim(location.name, "TEXT"),
        FactClaim(location.small_category_code, "FACILITY_SUBTYPE"),
        FactClaim(location.status, "OPERATING_STATUS"),
        FactClaim(str(location.distance_meters), "METERS"),
    ]
    if location.address:
        claims.append(FactClaim(location.address, "TEXT"))
    return EvidenceFact(
        fact_id=f"sbiz-academy-location-{location.store_id}",
        claims=tuple(claims),
        data_as_of=location.observed_at.date(),
        payload={
            "facilityId": location.store_id,
            "facilityName": location.name,
            "smallCategoryCode": location.small_category_code,
            "operatingStatus": location.status,
            "distanceMeters": location.distance_meters,
            "address": location.address,
            "registryMatch": (
                "EXACT" if location.registry_match is not None else "UNMATCHED"
            ),
            "datasetVersion": location.dataset_version,
        },
        source_id="place.sbiz-academy",
        source_name="상가(상권)정보 API 교육업종",
        source_url="https://www.data.go.kr/data/15012005/openapi.do",
        evidence_grade="B",
        dataset_version_value=location.dataset_version,
    )


def _academy_exact_match_fact(
    location: AcademyLocation, match: RegistryExactMatch
) -> EvidenceFact:
    return EvidenceFact(
        fact_id=f"academy-registry-exact-{match.registry_fact_id}",
        claims=(
            FactClaim(match.registry_fact_id, "REGISTRY_FACT_ID"),
            FactClaim(match.academy_name, "TEXT"),
            FactClaim(match.status, "REGISTRY_STATUS"),
            FactClaim("EXACT", "MATCH_TYPE"),
        ),
        data_as_of=match.observed_at.date(),
        payload={
            "facilityId": location.store_id,
            "registryFactId": match.registry_fact_id,
            "academyName": match.academy_name,
            "registryStatus": match.status,
            "matchType": "EXACT",
            "datasetVersion": match.dataset_version,
        },
        source_id="edu.academy-registry",
        source_name="전국학원및교습소표준데이터",
        source_url="https://www.data.go.kr/data/15096277/standard.do",
        evidence_grade="A",
        dataset_version_value=match.dataset_version,
    )


def _academy_lookup_scope_fact(
    plan: QueryPlan,
    complex_record: ComplexRecord,
    result: AcademyLocationSearchResult,
) -> EvidenceFact:
    return EvidenceFact(
        fact_id=f"sbiz-academy-scope-{complex_record.complex_id}-{plan.radius_meters}",
        claims=(
            FactClaim(str(plan.radius_meters), "RADIUS_METERS"),
            FactClaim(str(result.matched_count), "COUNT"),
            FactClaim(str(result.returned_count), "RETURNED_COUNT"),
            FactClaim(str(result.has_more).lower(), "BOOLEAN"),
            FactClaim(str(result.verified_zero).lower(), "VERIFIED_ZERO"),
            FactClaim(_number(result.coordinate_coverage), "COVERAGE_RATIO"),
        ),
        data_as_of=result.observed_at.date(),
        payload={
            "complexId": complex_record.complex_id,
            "radiusMeters": plan.radius_meters,
            "matchedCount": result.matched_count,
            "returnedCount": result.returned_count,
            "hasMore": result.has_more,
            "verifiedZero": result.verified_zero,
            "coordinateCoverage": result.coordinate_coverage,
        },
        source_id="place.sbiz-academy",
        source_name="상가(상권)정보 API 교육업종",
        source_url="https://www.data.go.kr/data/15012005/openapi.do",
        evidence_grade="B",
        dataset_version_value=result.dataset_version,
    )


def _retail_fact(record: FacilityFact) -> EvidenceFact:
    data_as_of = (
        record.data_as_of.date()
        if hasattr(record.data_as_of, "date") and not isinstance(record.data_as_of, date)
        else record.data_as_of
    )
    claims = [
        FactClaim(record.fact_id, "FACILITY_ID"),
        FactClaim(record.name, "TEXT"),
        FactClaim(record.category, "FACILITY_CATEGORY"),
        FactClaim(record.status, "OPERATING_STATUS"),
        FactClaim(str(record.distance_meters), "METERS"),
    ]
    if record.subcategory:
        claims.append(FactClaim(record.subcategory, "FACILITY_SUBTYPE"))
    if record.address:
        claims.append(FactClaim(record.address, "TEXT"))
    return EvidenceFact(
        fact_id=f"reference-retail-{record.fact_id}",
        claims=tuple(claims),
        data_as_of=data_as_of,  # type: ignore[arg-type]
        payload={
            "factId": record.fact_id,
            "facilityId": record.fact_id,
            "facilityName": record.name,
            "category": record.category,
            "subcategory": record.subcategory,
            "operatingStatus": record.status,
            "distanceMeters": record.distance_meters,
            "address": record.address,
            "datasetVersion": record.dataset_version,
            "dataAsOf": data_as_of.isoformat(),
        },
        source_id="retail.large-store",
        source_name="전국대규모및준대규모점포표준데이터",
        source_url="https://www.data.go.kr/data/15045013/fileData.do",
        evidence_grade="A",
        dataset_version_value=record.dataset_version,
    )


def _retail_scope_fact(
    plan: QueryPlan,
    complex_record: ComplexRecord,
    result: FacilitySearchResult,
) -> EvidenceFact:
    assert plan.radius_meters is not None
    data_as_of = (
        result.data_as_of.date()
        if hasattr(result.data_as_of, "date") and not isinstance(result.data_as_of, date)
        else result.data_as_of
    )
    subtypes = ",".join(plan.facility_subtypes)
    claims = [
        FactClaim(str(complex_record.complex_id), "COMPLEX_ID"),
        FactClaim(str(plan.radius_meters), "RADIUS_METERS"),
        FactClaim(subtypes or "ALL", "FACILITY_SUBTYPES"),
        FactClaim(str(result.matched_count), "COUNT"),
        FactClaim(str(result.returned_count), "RETURNED_COUNT"),
        FactClaim(str(result.has_more).lower(), "BOOLEAN"),
        FactClaim(str(result.verified_zero).lower(), "VERIFIED_ZERO"),
    ]
    if result.coordinate_coverage is not None:
        claims.append(FactClaim(_number(result.coordinate_coverage), "COORDINATE_COVERAGE"))
    return EvidenceFact(
        fact_id=f"reference-retail-scope-{complex_record.complex_id}-{plan.radius_meters}",
        claims=tuple(claims),
        data_as_of=data_as_of,  # type: ignore[arg-type]
        payload={
            "complexId": complex_record.complex_id,
            "radiusMeters": plan.radius_meters,
            "requestedSubtypes": list(plan.facility_subtypes),
            "matchedCount": result.matched_count,
            "returnedCount": result.returned_count,
            "hasMore": result.has_more,
            "verifiedZero": result.verified_zero,
            "coordinateCoverage": result.coordinate_coverage,
        },
        source_id="retail.large-store",
        source_name="전국대규모및준대규모점포표준데이터",
        source_url="https://www.data.go.kr/data/15045013/fileData.do",
        evidence_grade="A",
        dataset_version_value=result.dataset_version,
    )


def _rail_station_fact(
    station: RailStation, result: RailStationSearchResult
) -> EvidenceFact:
    lines = ",".join(station.lines)
    return EvidenceFact(
        fact_id=f"rail-station-{station.occurrence_ids[0]}",
        claims=(
            FactClaim(station.station_name, "TEXT"),
            FactClaim(lines, "RAIL_LINES"),
            *(FactClaim(line, "RAIL_LINE") for line in station.lines),
            FactClaim(str(station.distance_meters), "METERS"),
            FactClaim(str(len(station.occurrence_ids)), "OCCURRENCE_COUNT"),
        ),
        data_as_of=result.source_date,
        payload={
            "stationName": station.station_name,
            "lines": list(station.lines),
            "occurrenceIds": list(station.occurrence_ids),
            "distanceMeters": station.distance_meters,
            "datasetVersion": result.dataset_version,
        },
        source_id="transport.rail-station",
        source_name="전국도시철도역사정보표준데이터",
        source_url="https://www.data.go.kr/data/15013205/standard.do",
        evidence_grade="A",
        dataset_version_value=result.dataset_version,
    )


def _rail_scope_fact(
    plan: QueryPlan,
    complex_record: ComplexRecord,
    result: RailStationSearchResult,
) -> EvidenceFact:
    assert plan.radius_meters is not None
    return EvidenceFact(
        fact_id=f"rail-scope-{complex_record.complex_id}-{plan.radius_meters}",
        claims=(
            FactClaim(str(plan.radius_meters), "RADIUS_METERS"),
            FactClaim(str(len(result.stations)), "COUNT"),
            FactClaim(str(result.occurrence_count), "OCCURRENCE_COUNT"),
            FactClaim(_number(result.coordinate_coverage), "COORDINATE_COVERAGE"),
        ),
        data_as_of=result.source_date,
        payload={
            "complexId": complex_record.complex_id,
            "radiusMeters": plan.radius_meters,
            "stationCount": len(result.stations),
            "occurrenceCount": result.occurrence_count,
            "coordinateCoverage": result.coordinate_coverage,
        },
        source_id="transport.rail-station",
        source_name="전국도시철도역사정보표준데이터",
        source_url="https://www.data.go.kr/data/15013205/standard.do",
        evidence_grade="A",
        dataset_version_value=result.dataset_version,
    )


def _childcare_fact(center: ChildcareCenter) -> EvidenceFact:
    return EvidenceFact(
        fact_id=f"childcare-center-{center.center_id}",
        claims=(
            FactClaim(center.center_id, "FACILITY_ID"),
            FactClaim(center.center_name, "TEXT"),
            FactClaim(center.center_type, "CHILDCARE_TYPE"),
            FactClaim(str(center.capacity), "CAPACITY_PERSONS"),
            FactClaim(str(center.distance_meters), "METERS"),
            FactClaim(center.reference_date.isoformat(), "DATE"),
        ),
        data_as_of=center.reference_date,
        payload={
            "centerId": center.center_id,
            "centerName": center.center_name,
            "centerType": center.center_type,
            "capacity": center.capacity,
            "distanceMeters": center.distance_meters,
            "referenceDate": center.reference_date.isoformat(),
            "datasetVersion": center.dataset_version,
        },
        source_id="childcare.center",
        source_name="어린이집별 기본정보 조회",
        source_url="https://www.data.go.kr/data/15013108/standard.do",
        evidence_grade="A",
        dataset_version_value=center.dataset_version,
    )


def _childcare_scope_fact(
    plan: QueryPlan,
    complex_record: ComplexRecord,
    result: ChildcareSearchResult,
) -> EvidenceFact:
    assert plan.radius_meters is not None
    assert result.coordinate_coverage is not None
    return EvidenceFact(
        fact_id=f"childcare-scope-{complex_record.complex_id}-{plan.radius_meters}",
        claims=(
            FactClaim(str(plan.radius_meters), "RADIUS_METERS"),
            FactClaim(str(result.matched_count), "COUNT"),
            FactClaim(str(result.returned_count), "RETURNED_COUNT"),
            FactClaim(str(result.has_more).lower(), "BOOLEAN"),
            FactClaim(str(result.verified_zero).lower(), "VERIFIED_ZERO"),
            FactClaim(_number(result.coordinate_coverage), "COORDINATE_COVERAGE"),
        ),
        data_as_of=result.observed_at.date(),
        payload={
            "complexId": complex_record.complex_id,
            "radiusMeters": plan.radius_meters,
            "matchedCount": result.matched_count,
            "returnedCount": result.returned_count,
            "hasMore": result.has_more,
            "verifiedZero": result.verified_zero,
            "coordinateCoverage": result.coordinate_coverage,
            "observedAt": result.observed_at.isoformat(),
        },
        source_id="childcare.center",
        source_name="어린이집별 기본정보 조회",
        source_url="https://www.data.go.kr/data/15013108/standard.do",
        evidence_grade="A",
        dataset_version_value=result.dataset_version,
    )


def _verify_lifestyle_plan(plan: QueryPlan, question: str) -> QueryPlan:
    if plan.capability not in {"recommendation", "comparison"}:
        return plan
    themes = detect_explicit_themes(question, plan.lifestyle_themes)
    return replace(
        plan,
        lifestyle_themes=themes,
        school_levels=detect_school_levels(question, themes),
    )


def _apply_answer_first_defaults(plan: QueryPlan, today: date) -> QueryPlan:
    if (
        plan.capability == "recent_trade_lookup"
        and plan.start_date is None
        and plan.end_date is None
    ):
        return replace(
            plan,
            start_date=today - timedelta(days=365),
            end_date=today,
        )
    return plan


def _annotate_answer_first_assumptions(
    result: CapabilityResult,
    plan: QueryPlan,
    question: str,
) -> CapabilityResult:
    if result.readiness == "unavailable":
        return result
    assumptions = list(result.assumptions)
    fallback_steps = list(result.fallback_steps)
    if plan.capability in {"recent_trade_lookup", "price_trend"} and re.search(
        r"(?:[0-9]+\s*(?:년|개월|일)|[0-9]{4}[.년-]|부터|까지)", question
    ) is None:
        fallback_steps.append("DEFAULT_PERIOD_ONE_YEAR")
    if plan.capability in {
        "school_location",
        "academy_lookup",
        "rail_station_lookup",
        "retail_location",
        "childcare_lookup",
    } and re.search(r"[0-9]+(?:\.[0-9]+)?\s*(?:km|킬로미터|킬로|m|미터)", question) is None:
        assumptions.append(
            f"검색 반경을 지정하지 않아 직선거리 {plan.radius_meters}m를 적용했습니다."
        )
    if not assumptions:
        return result
    return replace(
        result,
        state="DEGRADED" if result.state == "EXACT" else result.state,
        assumptions=tuple(dict.fromkeys(assumptions)),
        fallback_steps=tuple(dict.fromkeys(fallback_steps)),
    )


def _verify_plan(
    plan: QueryPlan,
    question: str,
    *,
    semantic_goal_planner_enabled: bool = True,
) -> QueryPlan:
    if plan.capability != "recommendation":
        return _verify_lifestyle_plan(plan, question)
    try:
        return _verify_recommendation_plan(
            _verify_lifestyle_plan(plan, question),
            question,
            semantic_goal_planner_enabled=semantic_goal_planner_enabled,
        )
    except Exception as exception:
        raise RecommendationExecutionError(
            "RECOMMENDATION_PLAN_VALIDATION_FAILED"
        ) from exception


def _verify_recommendation_plan(
    plan: QueryPlan,
    question: str,
    *,
    semantic_goal_planner_enabled: bool = True,
) -> QueryPlan:
    if plan.capability != "recommendation":
        return plan
    normalized = " ".join(question.split())
    unit_match = re.search(r"([0-9][0-9,]*)\s*세대\s*이상", normalized)
    minimum_unit_count = (
        int(unit_match.group(1).replace(",", "")) if unit_match else None
    )
    limit_match = re.search(
        r"(?<![0-9])([0-9]{1,2})\s*(?:곳|개)(?:을|를)?\s*"
        r"(?:추천|골라|선정|보여|알려)",
        normalized,
    )
    requested_limit = int(limit_match.group(1)) if limit_match else 5
    verified_limit = requested_limit if 1 <= requested_limit <= 5 else plan.limit
    use_criteria_mode = (
        plan.recommendation_mode == "CRITERIA"
        or plan.maximum_budget_ten_thousand_krw is None
        or plan.exclusive_area_square_meters is None
    )
    if not use_criteria_mode:
        budget_criteria = tuple(dict.fromkeys((
            *plan.recommendation_criteria,
            *(("ACADEMY",) if re.search(r"(?:학원가|학원|교습소)", normalized) else ()),
        )))
        return replace(
            plan,
            limit=verified_limit,
            minimum_unit_count=minimum_unit_count,
            recommendation_criteria=budget_criteria,  # type: ignore[arg-type]
            criteria_order=budget_criteria,  # type: ignore[arg-type]
            clarification_code=None,
        )
    criterion_patterns = {
        "ACADEMY": r"(?:학원가|학원|교습소)",
        "SCHOOL": r"(?:초등학교|중학교|고등학교|학교)",
        "TRANSIT": r"(?:역세권|지하철|철도|교통|역\s*(?:접근|거리|가까))",
        "SHOPPING": r"(?:대형마트|백화점|쇼핑센터|복합몰|쇼핑)",
    }
    criteria_positions = [
        (match.start(), key)
        for key, pattern in criterion_patterns.items()
        if (match := re.search(pattern, normalized)) is not None
    ]
    criteria = [key for _, key in sorted(criteria_positions)]
    clarification = None
    if re.search(r"(?:학생|교육)(?!업소)", normalized) and not {
        "ACADEMY", "SCHOOL"
    }.intersection(criteria):
        criteria.extend(("SCHOOL", "ACADEMY"))
    # The model selects from a closed runtime metric catalog. Preserve those
    # semantic choices for broad preferences, while an explicit narrow metric
    # request must not inherit unrelated model-proposed criteria.
    broad_preference = re.search(
        r"(?:학군|교육|아이|자녀|키우|생활|편의|가성비|좋은|괜찮)", normalized
    ) is not None
    semantic_criteria = (
        plan.recommendation_criteria
        if broad_preference and semantic_goal_planner_enabled
        else ()
    )
    typed_criteria = tuple(dict.fromkeys((*criteria, *semantic_criteria)))
    explicit_priority = bool(re.search(r"(?:우선|먼저|그다음|다음으로)", normalized))
    criteria_order = tuple(
        key for key in plan.criteria_order if key in typed_criteria
    )
    if len(typed_criteria) == 1:
        criteria_order = typed_criteria
    elif len(typed_criteria) > 1:
        if not explicit_priority or set(criteria_order) != set(typed_criteria):
            criteria_order = typed_criteria
    region_name = plan.region_name
    if region_name is not None:
        region_token = re.sub(
            r"(?:특별시|광역시|특별자치시|특별자치도|시|군|구)$", "", region_name
        )
        if region_name not in normalized and region_token not in normalized:
            clarification = clarification or "REGION_NOT_CONFIRMED"
    radius_meters = plan.radius_meters
    if plan.station_name is not None:
        station_token = plan.station_name.removesuffix("역").strip()
        if station_token not in normalized:
            clarification = clarification or "REGION_NOT_CONFIRMED"
        radius_match = re.search(
            r"([0-9]+(?:\.[0-9]+)?)\s*(km|킬로미터|킬로|m|미터)",
            normalized,
            re.IGNORECASE,
        )
        if radius_match is None:
            radius_meters = 1500
        else:
            raw_radius = float(radius_match.group(1))
            extracted_radius = round(
                raw_radius * 1000
                if radius_match.group(2).lower() in {"km", "킬로미터", "킬로"}
                else raw_radius
            )
            if not 300 <= extracted_radius <= 2_000:
                radius_meters = 1500
            else:
                radius_meters = extracted_radius
    return replace(
        plan,
        recommendation_mode="CRITERIA",
        limit=verified_limit,
        minimum_unit_count=minimum_unit_count,
        recommendation_criteria=typed_criteria,  # type: ignore[arg-type]
        criteria_order=criteria_order,  # type: ignore[arg-type]
        radius_meters=radius_meters,
        clarification_code=clarification,  # type: ignore[arg-type]
    )


def _referenced_candidate_indexes(question: str) -> tuple[int, ...]:
    references: list[tuple[int, int]] = []
    patterns = (
        (0, r"(?:1\s*위|첫\s*번째|첫째)"),
        (1, r"(?:2\s*위|두\s*번째|둘째)"),
        (2, r"(?:3\s*위|세\s*번째|셋째)"),
        (3, r"(?:4\s*위|네\s*번째|넷째)"),
        (4, r"(?:5\s*위|다섯\s*번째|다섯째)"),
    )
    for index, pattern in patterns:
        match = re.search(pattern, question)
        if match is not None:
            references.append((match.start(), index))
    return tuple(index for _, index in sorted(references))


def validate_draft(
    draft: DraftAnswer,
    facts: list[EvidenceFact],
    readiness: str,
    *,
    limitations: list[str] | None = None,
    enforce_school_policy: bool = False,
    enforce_childcare_policy: bool = False,
    enforce_map_action_policy: bool = False,
    enforce_comparison_policy: bool = False,
    enforce_recommendation_policy: bool = False,
) -> list[EvidenceFact]:
    if not draft.sentences:
        raise GroundingValidationError("GROUNDING_ANSWER_EMPTY")
    fact_by_id = {fact.fact_id: fact for fact in facts}
    school_facts = [fact for fact in facts if fact.source_id == "edu.school-location"]
    retail_facts = [fact for fact in facts if fact.source_id == "retail.large-store"]
    academy_registry_facts = [
        fact
        for fact in facts
        if fact.source_id == "edu.academy-registry"
        and not fact.fact_id.startswith("academy-registry-exact-")
    ]
    academy_location_facts = [
        fact for fact in facts if fact.source_id == "place.sbiz-academy"
    ]
    rail_facts = [
        fact for fact in facts if fact.source_id == "transport.rail-station"
    ]
    childcare_facts = [
        fact for fact in facts if fact.source_id == "childcare.center"
    ]
    used_ids: list[str] = []
    for sentence in draft.sentences:
        if not sentence.text.strip():
            raise GroundingValidationError("GROUNDING_SENTENCE_BLANK")
        if readiness != "unavailable" and not sentence.fact_ids:
            raise GroundingValidationError("GROUNDING_FACT_IDS_MISSING")
        if readiness != "unavailable" and not sentence.claims:
            raise GroundingValidationError("GROUNDING_CLAIMS_MISSING")
        if len(sentence.fact_ids) != len(set(sentence.fact_ids)):
            raise GroundingValidationError("GROUNDING_FACT_IDS_DUPLICATE")
        referenced: list[EvidenceFact] = []
        for fact_id in sentence.fact_ids:
            fact = fact_by_id.get(fact_id)
            if fact is None:
                raise GroundingValidationError("GROUNDING_FACT_UNKNOWN")
            referenced.append(fact)
            if fact_id not in used_ids:
                used_ids.append(fact_id)
        for claim in sentence.claims:
            if claim.fact_id not in sentence.fact_ids:
                raise GroundingValidationError("GROUNDING_CLAIM_NOT_ATTACHED")
            fact = fact_by_id[claim.fact_id]
            if FactClaim(claim.value, claim.unit) not in fact.claims:
                raise GroundingValidationError("GROUNDING_CLAIM_MISMATCH")
        if school_facts or enforce_school_policy:
            _validate_school_sentence(sentence.text, referenced)
        if retail_facts:
            _validate_retail_sentence(sentence.text, referenced)
        if academy_registry_facts:
            _validate_academy_registry_sentence(sentence.text)
        if academy_location_facts:
            _validate_academy_lookup_sentence(sentence.text, referenced)
        if rail_facts:
            _validate_rail_sentence(sentence.text, referenced)
        if childcare_facts or enforce_childcare_policy:
            _validate_childcare_sentence(sentence.text, referenced)
        if enforce_map_action_policy:
            _validate_map_action_sentence(sentence.text)
        if enforce_comparison_policy:
            _validate_comparison_sentence(sentence.text)
        if enforce_recommendation_policy:
            _validate_recommendation_sentence(sentence.text, referenced)
        _validate_user_facing_copy(sentence.text)
        allowed_numbers = _number_tokens(
            claim.value for fact in referenced for claim in fact.claims
        )
        if readiness == "unavailable" and limitations:
            allowed_numbers.update(_number_tokens(limitations))
        unexpected_numbers = _number_tokens([sentence.text]) - allowed_numbers
        if unexpected_numbers:
            ordinal_candidates = {
                str(index) for index in range(1, len(facts) + 1)
            }
            if unexpected_numbers.issubset(ordinal_candidates):
                reason_code = "GROUNDING_RESULT_COUNT_OR_LIST_NUMBER"
            elif any(
                claim.unit.startswith("10_000_KRW")
                for fact in referenced
                for claim in fact.claims
            ):
                reason_code = "GROUNDING_AMOUNT_UNIT_CONVERSION"
            else:
                reason_code = "GROUNDING_NUMBER_OUTSIDE_OBSERVATION"
            raise GroundingValidationError(reason_code)
    if readiness != "unavailable" and set(used_ids) != set(fact_by_id):
        raise GroundingValidationError("GROUNDING_FACTS_OMITTED")
    return [fact_by_id[fact_id] for fact_id in used_ids]


def _validate_school_sentence(text: str, referenced: list[EvidenceFact]) -> None:
    positive_assignment = "배정" in text and not re.search(
        r"배정.{0,30}(?:의미하지|아니|않|근거가 없|확인할 수 없|판단할 수 없|알 수 없)",
        text,
    )
    positive_attendance_zone = "통학구역" in text and not re.search(
        r"통학구역.{0,30}(?:아니|근거가 없|확인할 수 없|판단할 수 없|알 수 없)",
        text,
    )
    if positive_assignment or positive_attendance_zone or re.search(
        r"통학시간은|(?:도보|통학)\s*(?:거리|시간|\d+\s*분)|걸어서|초품아(?:입니다|이며|라고)|명문(?:입니다|학교)|좋은\s*학교|교육\s*수준(?:이)?\s*(?:높|좋)|진학(?:이)?\s*(?:가능|보장)|폐교|휴교|운영\s*중단|개교\s*예정|(?:새로운|신규)\s*학교(?:가|는)?\s*(?:더\s*)?없",
        text,
    ):
        raise GroundingValidationError("GROUNDING_SCHOOL_POLICY_VIOLATION")
    observed_text = {
        claim.value
        for fact in referenced
        for claim in fact.claims
        if claim.unit in {"TEXT", "SCHOOL_LEVEL_DISPLAY"}
    }
    for school_name in re.findall(r"[가-힣A-Za-z0-9]+(?:초등학교|중학교|고등학교)", text):
        if school_name not in observed_text:
            raise GroundingValidationError("GROUNDING_SCHOOL_TEXT_OUTSIDE_OBSERVATION")


def _validate_retail_sentence(text: str, referenced: list[EvidenceFact]) -> None:
    if re.search(
        r"(?:생활권|학군|투자가치|투자s*가치|추천|살기s*좋|상권).{0,20}(?:좋|높|우수|편리|가치|추천)|"
        r"(?:좋|높|우수|편리)\S*.{0,20}(?:생활권|학군|투자가치|상권)|"
        r"폐업|휴업|영업\s*중단|신규\s*(?:시설|점포).{0,20}(?:없|아니)",
        text,
    ):
        raise GroundingValidationError("GROUNDING_RETAIL_POLICY_VIOLATION")
    observed_text = {
        claim.value
        for fact in referenced
        for claim in fact.claims
        if claim.unit == "TEXT"
    }
    for facility_name in re.findall(
        r"[가-힣A-Za-z0-9]+(?:대형마트|백화점|쇼핑센터|쇼핑몰|몰)", text
    ):
        if facility_name not in observed_text:
            raise GroundingValidationError("GROUNDING_RETAIL_TEXT_OUTSIDE_OBSERVATION")


def _validate_academy_registry_sentence(text: str) -> None:
    if re.search(r"반경|거리|주변|인근|가까", text):
        raise GroundingValidationError(
            "GROUNDING_ACADEMY_REGISTRY_POLICY_VIOLATION"
        )


def _validate_academy_lookup_sentence(
    text: str, referenced: list[EvidenceFact]
) -> None:
    if re.search(
        r"(?:공식\s*등록\s*(?:학원|업소)?\s*(?:총수|수는|개수|건수))|"
        r"(?:등록\s*학원\s*(?:총수|수는|개수|건수))|"
        r"유사|비슷|추정|퍼지|fuzzy",
        text,
        re.IGNORECASE,
    ):
        raise GroundingValidationError("GROUNDING_ACADEMY_LOOKUP_POLICY_VIOLATION")
    observed_text = {
        claim.value
        for fact in referenced
        for claim in fact.claims
        if claim.unit == "TEXT"
    }
    observed_academy_names = {
        value
        for value in observed_text
        if value.endswith(("학원", "교습소"))
    }
    compact_observed_names = {
        re.sub(r"\s+", "", value) for value in observed_academy_names
    }
    for academy_name in re.findall(r"[가-힣A-Za-z0-9()]+(?:학원|교습소)", text):
        candidate = academy_name.strip()
        if (
            candidate not in {"학원", "교습소"}
            and candidate not in compact_observed_names
            and not any(name.endswith(candidate) for name in observed_academy_names)
        ):
            raise GroundingValidationError(
                "GROUNDING_ACADEMY_LOOKUP_TEXT_OUTSIDE_OBSERVATION"
            )
    for match in re.finditer(
        r"([가-힣A-Za-z0-9()]+)\s+(학원|교습소)", text
    ):
        prefix, kind = match.groups()
        candidate = f"{prefix} {kind}"
        if candidate in observed_academy_names or any(
            name.endswith(candidate) for name in observed_academy_names
        ):
            continue
        if prefix in {"주변", "인근", "근처"} or re.search(
            r"(?:은|는|이|가|을|를|의|과|와|도|에서|으로|보다|중)$", prefix
        ):
            continue
        raise GroundingValidationError(
            "GROUNDING_ACADEMY_LOOKUP_TEXT_OUTSIDE_OBSERVATION"
        )


def _validate_rail_sentence(
    text: str, referenced: list[EvidenceFact]
) -> None:
    unsupported = re.search(
        r"통근\s*시간|소요\s*시간|배차|혼잡|운행\s*간격|걸어서|"
        r"(?:도보|통근)\s*(?:거리|시간)",
        text,
    )
    explicit_negative = re.search(
        r"(?:통근\s*시간|소요\s*시간|배차|혼잡도?|운행\s*간격|걸어서|"
        r"(?:도보|통근)\s*(?:거리|시간)).{0,50}"
        r"(?:포함되지\s*않|제공되지\s*않|확인할\s*수\s*없|"
        r"알\s*수\s*없|근거가\s*없|지원하지\s*않)",
        text,
    )
    positive_value = re.search(
        r"(?:통근|소요).{0,15}\d+\s*분|"
        r"배차.{0,20}(?:\d+\s*(?:분|회)|자주|드물)|"
        r"혼잡.{0,20}(?:높|낮|보통|심하)",
        text,
    )
    if positive_value or (unsupported and not explicit_negative):
        raise GroundingValidationError("GROUNDING_RAIL_POLICY_VIOLATION")
    observed_text = {
        claim.value
        for fact in referenced
        for claim in fact.claims
        if claim.unit == "TEXT"
    }
    allowed_station_names = observed_text | {
        name if name.endswith("역") else f"{name}역" for name in observed_text
    }
    generic_station_labels = {
        "가까운역",
        "도시철도역",
        "인근역",
        "전철역",
        "주변역",
        "지하철역",
        "철도역",
        "최근접역",
        "최근접지하철역",
        "해당역",
    }
    for station_name in re.findall(r"[\w가-힣()]+역", text):
        if (
            station_name not in allowed_station_names
            and station_name not in generic_station_labels
        ):
            raise GroundingValidationError(
                "GROUNDING_RAIL_TEXT_OUTSIDE_OBSERVATION"
            )


def _validate_childcare_sentence(
    text: str, referenced: list[EvidenceFact]
) -> None:
    availability = re.search(
        r"(?:입소|등록|자리).{0,20}(?:가능|여유|남아|받을\s*수|할\s*수)",
        text,
    )
    availability_negative = re.search(
        r"(?:입소|등록|자리).{0,30}(?:의미하지\s*않|확인할\s*수\s*없|"
        r"알\s*수\s*없|근거가\s*없|포함되지\s*않)",
        text,
    )
    unsupported = re.search(
        r"입소\s*대기|대기\s*(?:기간|순번)|보육\s*(?:품질|수준)|추천\s*순위",
        text,
    )
    unsupported_negative = re.search(
        r"(?:입소\s*대기|대기\s*(?:기간|순번)|보육\s*(?:품질|수준)|추천\s*순위)"
        r".{0,50}(?:의미하지\s*않|확인할\s*수\s*없|알\s*수\s*없|"
        r"근거가\s*없|포함되지\s*않|지원하지\s*않)",
        text,
    )
    if (
        (availability and not availability_negative)
        or (unsupported and not unsupported_negative)
        or re.search(r"정원.{0,20}(?:빈자리|여유|남아)", text)
    ):
        raise GroundingValidationError("GROUNDING_CHILDCARE_POLICY_VIOLATION")
    observed_text = {
        claim.value
        for fact in referenced
        for claim in fact.claims
        if claim.unit == "TEXT"
    }
    for center_name in re.findall(r"[\w가-힣()]+어린이집", text):
        if center_name not in observed_text:
            raise GroundingValidationError(
                "GROUNDING_CHILDCARE_TEXT_OUTSIDE_OBSERVATION"
            )


def _validate_map_action_sentence(text: str) -> None:
    if re.search(
        r"(?:공식|공인|인증|검증).{0,20}(?:병원|의료기관|어린이집|유치원)",
        text,
    ) or re.search(
        r"(?:병원|의료기관|어린이집|유치원).{0,30}"
        r"(?:\d+\s*개|있(?:습니다|다|는)|가까|거리|운영|입소|추천|좋|우수)",
        text,
    ):
        raise GroundingValidationError("GROUNDING_MAP_ACTION_POLICY_VIOLATION")


def _validate_comparison_sentence(text: str) -> None:
    if re.search(r"(?:추천|투자\s*가치|수익)", text):
        raise GroundingValidationError("GROUNDING_COMPARISON_POLICY_VIOLATION")


def _validate_user_facing_copy(text: str) -> None:
    if re.search(
        r"(?:우승|승자|압도적|최고|최상|무조건|더\s*좋은\s*단지|"
        r"살기\s*좋은\s*단지|명문\s*학군|교육\s*수준이?\s*높|"
        r"투자\s*가치가?\s*높|오를\s*가능성이?\s*높|"
        r"\bweight\b|hard\s*filter|source\s+unavailable)",
        text,
        re.IGNORECASE,
    ):
        raise GroundingValidationError("GROUNDING_USER_COPY_POLICY_VIOLATION")


def _validate_recommendation_sentence(
    text: str, referenced: list[EvidenceFact]
) -> None:
    unsupported = re.search(
        r"(?:투자\s*가치|수익(?:성|률)?|미래\s*가격|오를|상승\s*예상|"
        r"저렴.{0,20}(?:좋|우수|추천)|싼.{0,20}(?:좋|우수|추천)|"
        r"학군|교육\s*수준|보육\s*(?:품질|수준)|입소\s*가능)",
        text,
    )
    negative = re.search(
        r"(?:투자\s*가치|수익(?:성|률)?|미래\s*가격|학군|교육\s*수준|"
        r"보육\s*(?:품질|수준)|입소\s*가능).{0,30}"
        r"(?:판단하지|평가하지|의미하지|포함되지|확인할\s*수\s*없|아니|않|없)",
        text,
    )
    if unsupported and not negative:
        raise GroundingValidationError("GROUNDING_RECOMMENDATION_POLICY_VIOLATION")
    observed_text = {
        claim.value for fact in referenced for claim in fact.claims
        if claim.unit == "TEXT"
    }
    observed_names = (
        re.findall(r"[가-힣A-Za-z0-9]+(?:초등학교|중학교|고등학교)", text)
        + re.findall(r"[\w가-힣()]+어린이집", text)
    )
    for name in observed_names:
        if name not in observed_text:
            raise GroundingValidationError("GROUNDING_RECOMMENDATION_TEXT_OUTSIDE_OBSERVATION")


def _number(value: int | float) -> str:
    return format(Decimal(str(value)).normalize(), "f")


def _korean_krw_display(amount_ten_thousand_krw: int) -> str:
    eok, man_won = divmod(amount_ten_thousand_krw, 10_000)
    if eok and man_won:
        return f"{eok:,}억 {man_won:,}만원"
    if eok:
        return f"{eok:,}억원"
    return f"{man_won:,}만원"


def _number_tokens(values: Iterable[str]) -> set[str]:
    tokens: set[str] = set()
    for value in values:
        for raw in re.findall(r"[0-9]+(?:[.,][0-9]+)*", value):
            try:
                tokens.add(format(Decimal(raw.replace(",", "")).normalize(), "f"))
            except InvalidOperation:
                continue
    return tokens
