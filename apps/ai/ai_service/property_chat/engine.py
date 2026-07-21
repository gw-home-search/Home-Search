from __future__ import annotations

import asyncio
import re
from collections.abc import Callable, Iterable
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Protocol

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest

from .models import (
    AdministrativeRegionContext,
    ComplexRecord,
    DraftAnswer,
    EvidenceFact,
    FactClaim,
    MonthlyTrendRecord,
    PropertyCapability,
    QueryPlan,
    ReferenceCapability,
    SchoolRecord,
    SchoolSearchResult,
    SchoolSnapshot,
    TradeRecord,
)
from .academy_registry import AcademyRegistrySummary
from .academy_locations import (
    AcademyLocation,
    AcademyLocationSearchResult,
    RegistryExactMatch,
)
from .reference_facilities import FacilityFact, FacilitySearchResult
from .rail_stations import RailStation, RailStationSearchResult


class PropertyFactRepository(Protocol):
    def find_complexes(
        self, name: str, region_name: str | None, limit: int
    ) -> list[ComplexRecord]: ...

    def recent_trades(
        self,
        complex_id: int,
        start_date: date | None,
        end_date: date | None,
        exclusive_area_square_meters: float | None,
        limit: int,
    ) -> list[TradeRecord]: ...

    def monthly_trends(
        self,
        complex_id: int,
        start_date: date,
        end_date: date,
        exclusive_area_square_meters: float | None,
    ) -> list[MonthlyTrendRecord]: ...

    def latest_trade_date(self) -> date | None: ...

    def resolve_region_context(
        self, region_code: str
    ) -> AdministrativeRegionContext | None: ...


class GroundedLanguageModel(Protocol):
    async def plan_query(self, request: ChatbotQueryRequest) -> QueryPlan: ...

    async def draft_answer(
        self,
        *,
        facts: list[EvidenceFact],
        limitations: list[str],
        question: str,
    ) -> DraftAnswer: ...


class SchoolFactRepository(Protocol):
    def active_snapshot(self) -> SchoolSnapshot | None: ...

    def nearby_schools(
        self,
        *,
        latitude: float,
        longitude: float,
        school_levels: tuple[str, ...],
        radius_meters: int,
        limit: int,
    ) -> SchoolSearchResult: ...


class PointFacilityFactRepository(Protocol):
    def nearby(
        self,
        *,
        source_id: str,
        category: str,
        latitude: float,
        longitude: float,
        radius_meters: int,
        limit: int,
        region_code: str,
        subcategories: tuple[str, ...] = (),
    ) -> FacilitySearchResult: ...


class AcademyRegistryFactRepository(Protocol):
    def summary(
        self, *, education_office_name: str, district_name: str
    ) -> AcademyRegistrySummary | None: ...


class AcademyLocationFactRepository(Protocol):
    def nearby(
        self,
        *,
        latitude: float,
        longitude: float,
        radius_meters: int,
        limit: int,
    ) -> AcademyLocationSearchResult: ...


class RailStationFactRepository(Protocol):
    def nearby(
        self,
        *,
        latitude: float,
        longitude: float,
        radius_meters: int,
        limit: int,
    ) -> RailStationSearchResult: ...


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
        enabled_reference_capabilities: frozenset[ReferenceCapability] = frozenset(),
        today: Callable[[], date] = date.today,
    ) -> None:
        self._repository = repository
        self._school_repository = school_repository
        self._point_facility_repository = point_facility_repository
        self._academy_registry_repository = academy_registry_repository
        self._academy_location_repository = academy_location_repository
        self._rail_station_repository = rail_station_repository
        self._language_model = language_model
        self._enabled_capabilities = enabled_capabilities
        self._enabled_reference_capabilities = enabled_reference_capabilities
        self._today = today
        self._reference_observers = {
            "school_location": self._observe_schools,
            "retail_location": self._observe_retail,
            "academy_registry_summary": self._observe_academy_registry,
            "academy_lookup": self._observe_academy_lookup,
            "rail_station_lookup": self._observe_rail_stations,
        }

    async def query(
        self,
        *,
        request: ChatbotQueryRequest,
        user: AuthenticatedUser,
        request_id: str,
    ) -> dict[str, object]:
        del user
        try:
            plan = await self._language_model.plan_query(request)
            if plan.capability in self._enabled_capabilities or (
                plan.capability in self._enabled_reference_capabilities
            ):
                facts, limitations, readiness = await self._observe(plan)
            else:
                facts, limitations, readiness = (
                    [],
                    ["해당 질문 기능은 현재 데이터 준비와 검증이 진행 중입니다."],
                    "unavailable",
                )
            draft = await self._language_model.draft_answer(
                facts=facts,
                limitations=limitations,
                question=request.question,
            )
            used_facts = validate_draft(
                draft,
                facts,
                readiness,
                limitations=limitations,
                enforce_school_policy=plan.capability == "school_location",
            )
            return _response(
                request=request,
                request_id=request_id,
                plan=plan,
                draft=draft,
                used_facts=used_facts,
                limitations=limitations,
                readiness=readiness,
            )
        except ChatbotProviderUnavailable:
            raise
        except Exception as exception:
            raise ChatbotProviderUnavailable() from exception

    async def _observe(
        self, plan: QueryPlan
    ) -> tuple[list[EvidenceFact], list[str], str]:
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
            )
        if len(complexes) > 1:
            return (
                [_complex_fact(record) for record in complexes],
                ["동명 단지가 여러 곳이므로 지역이나 주소 조건을 추가해야 합니다."],
                "partial",
            )

        complex_record = complexes[0]
        reference_observer = self._reference_observers.get(plan.capability)
        if reference_observer is not None:
            return await reference_observer(plan, complex_record)
        if plan.capability == "complex_identity":
            limitations = []
            if not complex_record.marker_safe:
                limitations.append("검증된 표시 좌표가 없어 위치 좌표는 제공하지 않습니다.")
            return [_complex_fact(complex_record)], limitations, "supported"
        if plan.capability == "recent_trade_lookup":
            trades = await asyncio.to_thread(
                self._repository.recent_trades,
                complex_record.complex_id,
                plan.start_date,
                plan.end_date,
                plan.exclusive_area_square_meters,
                plan.limit,
            )
            if not trades:
                return (
                    [],
                    ["지정한 기간과 면적 조건에서 확인된 실거래가 없습니다."],
                    "unavailable",
                )
            latest_trade_date = await asyncio.to_thread(self._repository.latest_trade_date)
            data_as_of = latest_trade_date or max(record.deal_date for record in trades)
            limitations = ["신고 취소 또는 지연 신고가 이후 반영될 수 있습니다."]
            if plan.exclusive_area_square_meters is not None:
                limitations.append("전용면적은 요청값 기준 ±1.0㎡ 범위로 조회했습니다.")
            return [_trade_fact(record, data_as_of) for record in trades], limitations, "supported"
        if plan.capability == "price_trend":
            assert plan.start_date is not None and plan.end_date is not None
            trends = await asyncio.to_thread(
                self._repository.monthly_trends,
                complex_record.complex_id,
                plan.start_date,
                plan.end_date,
                plan.exclusive_area_square_meters,
            )
            if not trends:
                return (
                    [],
                    ["지정한 기간과 면적 조건으로 월별 추이를 계산할 거래가 없습니다."],
                    "unavailable",
                )
            latest_trade_date = await asyncio.to_thread(self._repository.latest_trade_date)
            data_as_of = latest_trade_date or min(
                plan.end_date, max(_month_end(record.month) for record in trends)
            )
            limitations = ["월별 수치는 실제 거래 관찰값이며 미래 가격을 의미하지 않습니다."]
            if plan.exclusive_area_square_meters is not None:
                limitations.append("전용면적은 요청값 기준 ±1.0㎡ 범위로 집계했습니다.")
            return [_trend_fact(record, data_as_of) for record in trends], limitations, "supported"
        raise GroundingValidationError("GROUNDING_CAPABILITY_UNSUPPORTED")

    async def _observe_schools(
        self, plan: QueryPlan, complex_record: ComplexRecord
    ) -> tuple[list[EvidenceFact], list[str], str]:
        if not 100 <= plan.radius_meters <= 2000:
            return (
                [],
                ["학교 검색 반경은 100m에서 2000m 사이로 지정해야 합니다."],
                "unavailable",
            )
        if (
            not complex_record.marker_safe
            or complex_record.latitude is None
            or complex_record.longitude is None
        ):
            return (
                [],
                ["검증된 단지 표시 좌표가 없어 주변 학교를 조회할 수 없습니다."],
                "unavailable",
            )
        if self._school_repository is None:
            return (
                [],
                ["공식 학교 위치 snapshot이 준비되지 않았습니다."],
                "unavailable",
            )
        snapshot = await asyncio.to_thread(self._school_repository.active_snapshot)
        if snapshot is None:
            return (
                [],
                ["공식 학교 위치 active snapshot이 없습니다."],
                "unavailable",
            )
        age_days = (self._today() - snapshot.source_date).days
        if age_days < 0 or age_days > 214:
            return (
                [],
                ["공식 학교 위치 snapshot의 기준일이 freshness 범위를 벗어났습니다."],
                "unavailable",
            )
        result = await asyncio.to_thread(
            self._school_repository.nearby_schools,
            latitude=complex_record.latitude,
            longitude=complex_record.longitude,
            school_levels=plan.school_levels,
            radius_meters=plan.radius_meters,
            limit=plan.limit,
        )
        facts = [
            _complex_fact(complex_record),
            *(_school_fact(record, snapshot) for record in result.schools),
            _school_scope_fact(plan, complex_record, result, snapshot),
        ]
        return (
            facts,
            [
                "거리는 단지 표시 좌표 기준 직선거리이며 실제 보행 경로가 아닙니다.",
                "통학구역 근거가 아니므로 배정학교를 의미하지 않습니다.",
                "데이터 기준일 이후 학교 운영상태가 변경될 수 있습니다.",
            ],
            "supported",
        )

    async def _observe_retail(
        self, plan: QueryPlan, complex_record: ComplexRecord
    ) -> tuple[list[EvidenceFact], list[str], str]:
        assert plan.radius_meters is not None
        if not 100 <= plan.radius_meters <= 3000:
            return (
                [],
                ["대규모점포 검색 반경은 100m에서 3000m 사이로 지정해야 합니다."],
                "unavailable",
            )
        if (
            not complex_record.marker_safe
            or complex_record.latitude is None
            or complex_record.longitude is None
            or complex_record.region_code is None
        ):
            return (
                [],
                ["검증된 단지 좌표와 지역 코드가 없어 주변 대규모점포를 조회할 수 없습니다."],
                "unavailable",
            )
        if self._point_facility_repository is None:
            return (
                [],
                ["공식 대규모점포 active snapshot이 준비되지 않았습니다."],
                "unavailable",
            )
        result = await asyncio.to_thread(
            self._point_facility_repository.nearby,
            source_id="retail.large-store",
            category="RETAIL",
            latitude=complex_record.latitude,
            longitude=complex_record.longitude,
            radius_meters=plan.radius_meters,
            limit=plan.limit,
            region_code=complex_record.region_code,
            subcategories=plan.facility_subtypes,
        )
        facts = [
            _complex_fact(complex_record),
            *(_retail_fact(record) for record in result.facilities),
            _retail_scope_fact(plan, complex_record, result),
        ]
        limitations = [
            "거리는 단지 표시 좌표 기준 직선거리이며 실제 보행 경로가 아닙니다.",
            "공식 snapshot 이후 운영상태가 변경될 수 있습니다.",
        ]
        if not result.facilities and not result.verified_zero:
            limitations.append(
                "좌표가 확인된 공식 자료의 결과이며 좌표가 없는 원장이 포함될 수 있어 시설이 전혀 없다고 단정할 수 없습니다."
            )
        return facts, limitations, "supported"

    async def _observe_academy_registry(
        self, plan: QueryPlan, complex_record: ComplexRecord
    ) -> tuple[list[EvidenceFact], list[str], str]:
        if complex_record.region_code is None:
            return [], ["단지의 행정구역을 확인할 수 없습니다."], "unavailable"
        if self._academy_registry_repository is None:
            return [], ["공식 학원·교습소 등록 집계가 준비되지 않았습니다."], "unavailable"
        region = await asyncio.to_thread(
            self._repository.resolve_region_context, complex_record.region_code
        )
        if region is None:
            return [], ["단지의 시도·시군구 행정구역을 확인할 수 없습니다."], "unavailable"
        summary = await asyncio.to_thread(
            self._academy_registry_repository.summary,
            education_office_name=region.education_office_name,
            district_name=region.district_name,
        )
        if summary is None:
            return [], ["해당 시군구의 공식 등록 집계를 확인하지 못했습니다."], "unavailable"
        age_days = (self._today() - summary.observed_at.date()).days
        if age_days < 0 or age_days > summary.freshness_days:
            return [], ["공식 등록 집계의 관측일이 freshness 범위를 벗어났습니다."], "unavailable"
        return (
            [_academy_registry_fact(summary)],
            [
                "시군구 단위 공식 등록 원장 집계이며 위치 검색이나 교육 품질을 의미하지 않습니다.",
                "관측일 이후 등록·운영상태가 변경될 수 있습니다.",
            ],
            "supported",
        )

    async def _observe_academy_lookup(
        self, plan: QueryPlan, complex_record: ComplexRecord
    ) -> tuple[list[EvidenceFact], list[str], str]:
        assert plan.radius_meters is not None
        if not 100 <= plan.radius_meters <= 2000:
            return (
                [],
                ["교육업소 검색 반경은 100m에서 2000m 사이로 지정해야 합니다."],
                "unavailable",
            )
        if (
            not complex_record.marker_safe
            or complex_record.latitude is None
            or complex_record.longitude is None
        ):
            return (
                [],
                ["검증된 단지 표시 좌표가 없어 교육업소를 조회할 수 없습니다."],
                "unavailable",
            )
        if self._academy_location_repository is None:
            return [], ["Sbiz 교육업소 active snapshot이 준비되지 않았습니다."], "unavailable"
        result = await asyncio.to_thread(
            self._academy_location_repository.nearby,
            latitude=complex_record.latitude,
            longitude=complex_record.longitude,
            radius_meters=plan.radius_meters,
            limit=plan.limit,
        )
        age_days = (self._today() - result.observed_at.date()).days
        if (
            result.coordinate_coverage < 0.95
            or age_days < 0
            or age_days > result.freshness_days
        ):
            return (
                [],
                ["Sbiz 교육업소 snapshot의 좌표 coverage 또는 관측일이 기준을 충족하지 못했습니다."],
                "unavailable",
            )
        facts: list[EvidenceFact] = []
        for location in result.locations:
            facts.append(_academy_location_fact(location))
            if location.registry_match is not None:
                facts.append(
                    _academy_exact_match_fact(location, location.registry_match)
                )
        facts.append(_academy_lookup_scope_fact(plan, complex_record, result))
        limitations = [
            "거리는 단지 표시 좌표 기준 직선거리이며 실제 보행 경로가 아닙니다.",
            "미결합 교육업소는 Sbiz 위치 근거이며 NEIS 공식 등록 여부를 의미하지 않습니다.",
        ]
        if not result.locations and not result.verified_zero:
            limitations.append(
                "행정코드 체계의 지역 coverage가 검증되지 않아 교육업소가 전혀 없다고 단정할 수 없습니다."
            )
        return facts, limitations, "supported"

    async def _observe_rail_stations(
        self, plan: QueryPlan, complex_record: ComplexRecord
    ) -> tuple[list[EvidenceFact], list[str], str]:
        assert plan.radius_meters is not None
        if not 100 <= plan.radius_meters <= 3000:
            return (
                [],
                ["철도역 검색 반경은 100m에서 3000m 사이로 지정해야 합니다."],
                "unavailable",
            )
        if (
            not complex_record.marker_safe
            or complex_record.latitude is None
            or complex_record.longitude is None
        ):
            return (
                [],
                ["검증된 단지 표시 좌표가 없어 주변 철도역을 조회할 수 없습니다."],
                "unavailable",
            )
        if self._rail_station_repository is None:
            return [], ["공식 철도역 active snapshot이 준비되지 않았습니다."], "unavailable"
        result = await asyncio.to_thread(
            self._rail_station_repository.nearby,
            latitude=complex_record.latitude,
            longitude=complex_record.longitude,
            radius_meters=plan.radius_meters,
            limit=plan.limit,
        )
        age_days = (self._today() - result.source_date).days
        if (
            result.coordinate_coverage < 1.0
            or age_days < 0
            or age_days > result.freshness_days
        ):
            return (
                [],
                ["철도역 snapshot의 좌표 coverage 또는 기준일이 활성화 기준을 충족하지 못했습니다."],
                "unavailable",
            )
        facts = [
            *(_rail_station_fact(station, result) for station in result.stations),
            _rail_scope_fact(plan, complex_record, result),
        ]
        return (
            facts,
            [
                "거리는 단지 표시 좌표 기준 직선거리이며 실제 보행 경로가 아닙니다.",
                "통근시간·배차·혼잡도는 현재 근거에 포함되지 않습니다.",
            ],
            "supported",
        )


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


def validate_draft(
    draft: DraftAnswer,
    facts: list[EvidenceFact],
    readiness: str,
    *,
    limitations: list[str] | None = None,
    enforce_school_policy: bool = False,
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


def _response(
    *,
    request: ChatbotQueryRequest,
    request_id: str,
    plan: QueryPlan,
    draft: DraftAnswer,
    used_facts: list[EvidenceFact],
    limitations: list[str],
    readiness: str,
) -> dict[str, object]:
    answer = " ".join(sentence.text.strip() for sentence in draft.sentences)
    citations = _citations(used_facts)
    data_as_of = min((fact.data_as_of for fact in used_facts), default=None)
    success = readiness != "unavailable"
    legacy_status = (
        "failed" if readiness == "unavailable" else "partial_success" if readiness == "partial" else "success"
    )
    return {
        "success": success,
        "status": legacy_status,
        "question": request.question,
        "fragments": [],
        "result": {},
        "message": "",
        "executionSummary": {"total": 1, "succeeded": int(success), "failed": int(not success)},
        "answer": answer,
        "resolvedQuestion": request.question,
        "conversationResolution": None,
        "conversationMemoryPatch": None,
        "uiActions": [],
        "uiArtifacts": [],
        "uiSummary": None,
        "requestId": request_id,
        "citations": citations,
        "dataAsOf": data_as_of.isoformat() if data_as_of else None,
        "limitations": limitations,
        "evidenceSummary": {
            "status": readiness,
            "capabilities": [plan.capability],
            "factCount": len(used_facts),
            "citationCount": len(citations),
        },
    }


def _citations(facts: list[EvidenceFact]) -> list[dict[str, object]]:
    grouped: dict[tuple[str, str, str | None, str, str, date], list[str]] = {}
    for fact in facts:
        key = (
            fact.source_id,
            fact.source_name,
            fact.source_url,
            fact.evidence_grade,
            fact.dataset_version,
            fact.data_as_of,
        )
        grouped.setdefault(key, []).append(fact.fact_id)
    return [
        {
            "citationId": f"citation-{index}",
            "sourceId": source_id,
            "sourceName": source_name,
            "sourceUrl": source_url,
            "evidenceGrade": evidence_grade,
            "datasetVersion": version,
            "dataAsOf": data_as_of.isoformat(),
            "observedAt": None,
            "factIds": fact_ids,
        }
        for index, (
            (source_id, source_name, source_url, evidence_grade, version, data_as_of),
            fact_ids,
        ) in enumerate(grouped.items(), start=1)
    ]


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


def _month_end(month: date) -> date:
    if month.month == 12:
        return date(month.year, 12, 31)
    return date.fromordinal(date(month.year, month.month + 1, 1).toordinal() - 1)
