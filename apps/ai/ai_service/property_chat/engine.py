from __future__ import annotations

import asyncio
import re
from collections.abc import Callable, Iterable
from dataclasses import replace
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Protocol

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest

from .answer_document import AnswerDocument, CompoundAnswerDocument, FactListPresenter
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
from .recommendation_handler import RecommendationHandler
from .lifestyle_themes import detect_explicit_themes, detect_school_levels
from .models import (
    ComplexRecord,
    DraftAnswer,
    EvidenceFact,
    FactClaim,
    MonthlyTrendRecord,
    PropertyCapability,
    QueryPlan,
    QueryPlanBundle,
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
        today: Callable[[], date] = date.today,
    ) -> None:
        self._repository = repository
        self._language_model = language_model
        self._enabled_capabilities = enabled_capabilities
        self._enabled_reference_capabilities = enabled_reference_capabilities
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
        try:
            planned = await self._language_model.plan_query(request)
            bundle = (
                planned if isinstance(planned, QueryPlanBundle)
                else QueryPlanBundle((planned,))
            )
            plans = tuple(
                _verify_lifestyle_plan(plan, request.question)
                for plan in bundle.fragments
            )
            documents = tuple(await asyncio.gather(*(
                self._execute_fragment(plan, request, request_id) for plan in plans
            )))
            if len(documents) == 1:
                return documents[0].to_public_dict()
            return CompoundAnswerDocument(
                request, request_id, documents
            ).to_public_dict()
        except ChatbotProviderUnavailable:
            raise
        except Exception as exception:
            raise ChatbotProviderUnavailable() from exception

    async def _execute_fragment(
        self,
        plan: QueryPlan,
        request: ChatbotQueryRequest,
        request_id: str,
    ) -> AnswerDocument:
        if plan.capability in self._enabled_capabilities or (
            plan.capability in self._enabled_reference_capabilities
        ):
            plan_handler = self._catalog.plan_handler_for(plan.capability)
            if plan_handler is not None:
                async with asyncio.timeout(3):
                    result = await plan_handler.observe(plan)
            else:
                facts, limitations, readiness, actions = await self._observe(plan)
                result = CapabilityResult(facts, limitations, readiness, actions)
        else:
            result = CapabilityResult(
                [],
                ["해당 질문 기능은 현재 데이터 준비와 검증이 진행 중입니다."],
                "unavailable",
            )
        draft = await self._language_model.draft_answer(
            facts=result.facts,
            limitations=result.limitations,
            question=request.question,
        )
        used_facts = validate_draft(
            draft,
            result.facts,
            result.readiness,
            limitations=result.limitations,
            enforce_school_policy=plan.capability == "school_location",
            enforce_childcare_policy=plan.capability == "childcare_lookup",
            enforce_map_action_policy=plan.capability == "kakao_place_search",
            enforce_comparison_policy=plan.capability == "comparison",
            enforce_recommendation_policy=plan.capability == "recommendation",
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
        artifacts = list(result.artifacts) or FactListPresenter().present(
            plan=plan, used_facts=used_facts, readiness=result.readiness
        )
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
        )

    async def _observe(
        self, plan: QueryPlan
    ) -> tuple[
        list[EvidenceFact],
        list[str],
        str,
        tuple[ShowNearbyCategoryAction, ...],
    ]:
        complexes = await asyncio.to_thread(
            self._repository.find_complexes,
            plan.complex_name,
            plan.region_name,
            6,
        )
        if not complexes:
            return (
                [],
                ["지정한 이름과 지역 조건으로 단지를 식별하지 못했습니다."],
                "unavailable",
                (),
            )
        if len(complexes) > 1:
            return (
                [_complex_fact(record) for record in complexes],
                ["동명 단지가 여러 곳이므로 지역이나 주소 조건을 추가해야 합니다."],
                "partial",
                (),
            )
        handler = self._catalog.handler_for(plan.capability)
        if handler is None:
            raise GroundingValidationError("GROUNDING_CAPABILITY_UNSUPPORTED")
        result = await handler.observe(plan, complexes[0])
        return result.facts, result.limitations, result.readiness, result.actions


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
    for academy_name in re.findall(r"[가-힣A-Za-z0-9 ]+(?:학원|교습소)", text):
        candidate = academy_name.strip()
        if candidate and candidate not in observed_text:
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
    for station_name in re.findall(r"[\w가-힣()]+역", text):
        if station_name not in allowed_station_names:
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
    unsupported = re.search(
        r"(?:우승|승자|최고|최상|더\s*좋|가장\s*좋|추천|투자\s*가치|수익)", text
    )
    negative = re.search(
        r"(?:판단|선정|추천|순위|우승|투자\s*가치).{0,15}"
        r"(?:않|아니|없|제공하지)",
        text,
    )
    if unsupported and not negative:
        raise GroundingValidationError("GROUNDING_COMPARISON_POLICY_VIOLATION")


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
