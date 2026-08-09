from __future__ import annotations

import asyncio
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date
from typing import Literal, Protocol, cast

from .academy_locations import AcademyLocation, AcademyLocationSearchResult, RegistryExactMatch
from .academy_registry import AcademyRegistrySummary
from .childcare_centers import ChildcareCenter, ChildcareSearchResult
from .models import (
    AdministrativeRegionContext,
    CAPABILITY_EXECUTION_ORDER,
    ComplexRecord,
    EvidenceFact,
    FocusComplexAction,
    MonthlyTrendRecord,
    QueryCapability,
    QueryPlan,
    SchoolRecord,
    SchoolSearchResult,
    SchoolSnapshot,
    ShowNearbyCategoryAction,
    TradeRecord,
)
from .rail_stations import RailStation, RailStationSearchResult
from .reference_facilities import (
    FacilityFact,
    FacilitySearchResult,
    RETAIL_MIN_COORDINATE_COVERAGE,
    retail_coordinate_ready,
)


class PropertyFactRepository(Protocol):
    def find_complex_by_id(self, complex_id: int) -> ComplexRecord | None: ...

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

    def candidate_observation_summaries(
        self,
        complex_ids: tuple[int, ...],
        start_date: date | None,
        end_date: date | None,
        exclusive_area_square_meters: float | None,
        capability: QueryCapability,
    ): ...

    def resolve_region_context(
        self, region_code: str
    ) -> AdministrativeRegionContext | None: ...


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


class ChildcareFactRepository(Protocol):
    def nearby(
        self,
        *,
        latitude: float,
        longitude: float,
        radius_meters: int,
        limit: int,
        region_code: str | None,
    ) -> ChildcareSearchResult | None: ...


CapabilityOutcomeState = Literal[
    "EXACT", "DEGRADED", "EMPTY", "UNAVAILABLE", "ERROR"
]


@dataclass(frozen=True)
class CapabilityOutcome:
    facts: list[EvidenceFact]
    limitations: list[str]
    readiness: str
    actions: tuple[ShowNearbyCategoryAction | FocusComplexAction, ...] = ()
    artifacts: tuple[dict[str, object], ...] = ()
    artifact_fact_ids: tuple[str, ...] = ()
    state: CapabilityOutcomeState | None = None
    assumptions: tuple[str, ...] = ()
    fallback_steps: tuple[str, ...] = ()
    recoverable: bool = True
    result_facts: tuple[EvidenceFact, ...] | None = None
    selection_facts: tuple[EvidenceFact, ...] = ()
    alternative_facts: tuple[EvidenceFact, ...] = ()
    primary_artifact_id: str | None = None
    no_exact_result: bool = False
    suggested_questions: tuple[str, ...] = ()
    selection_reason: str | None = None
    selection_reason_fact_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.readiness not in {"supported", "partial", "unavailable"}:
            raise ValueError("capability outcome readiness is invalid")
        if self.state is None:
            state: CapabilityOutcomeState = (
                "UNAVAILABLE"
                if self.readiness == "unavailable"
                else "DEGRADED"
                if self.readiness == "partial"
                else "EXACT"
                if self.facts
                else "EMPTY"
            )
            object.__setattr__(self, "state", state)


# Temporary source-compatible name while handlers migrate independently.
CapabilityResult = CapabilityOutcome


class CapabilityHandler(Protocol):
    capability: QueryCapability

    async def observe(
        self, plan: QueryPlan, complex_record: ComplexRecord
    ) -> CapabilityResult: ...


class PlanCapabilityHandler(Protocol):
    capability: QueryCapability

    async def observe(self, plan: QueryPlan) -> CapabilityResult: ...


class CapabilityCatalog:
    def __init__(
        self,
        handlers: tuple[CapabilityHandler, ...],
        *,
        plan_handlers: tuple[PlanCapabilityHandler, ...] = (),
    ) -> None:
        capabilities = tuple(handler.capability for handler in (*handlers, *plan_handlers))
        if len(capabilities) != len(set(capabilities)):
            raise ValueError("duplicate capability handler")
        self._handlers = handlers
        self._by_capability = {handler.capability: handler for handler in handlers}
        self._plan_by_capability = {
            handler.capability: handler for handler in plan_handlers
        }

    @property
    def capabilities(self) -> tuple[QueryCapability, ...]:
        return tuple(
            capability
            for capability in CAPABILITY_EXECUTION_ORDER
            if capability in self._by_capability
            or capability in self._plan_by_capability
        )

    def handler_for(self, capability: str) -> CapabilityHandler | None:
        return self._by_capability.get(cast(QueryCapability, capability))

    def plan_handler_for(self, capability: str) -> PlanCapabilityHandler | None:
        return self._plan_by_capability.get(cast(QueryCapability, capability))


@dataclass(frozen=True)
class EvidenceFactBuilders:
    complex_fact: Callable[[ComplexRecord], EvidenceFact]
    trade_fact: Callable[[TradeRecord, date], EvidenceFact]
    trend_fact: Callable[[MonthlyTrendRecord, date], EvidenceFact]
    school_fact: Callable[[SchoolRecord, SchoolSnapshot], EvidenceFact]
    school_scope_fact: Callable[
        [QueryPlan, ComplexRecord, SchoolSearchResult, SchoolSnapshot], EvidenceFact
    ]
    academy_registry_fact: Callable[[AcademyRegistrySummary], EvidenceFact]
    academy_location_fact: Callable[[AcademyLocation], EvidenceFact]
    academy_exact_match_fact: Callable[[AcademyLocation, RegistryExactMatch], EvidenceFact]
    academy_lookup_scope_fact: Callable[
        [QueryPlan, ComplexRecord, AcademyLocationSearchResult], EvidenceFact
    ]
    retail_fact: Callable[[FacilityFact], EvidenceFact]
    retail_scope_fact: Callable[
        [QueryPlan, ComplexRecord, FacilitySearchResult], EvidenceFact
    ]
    rail_station_fact: Callable[[RailStation, RailStationSearchResult], EvidenceFact]
    rail_scope_fact: Callable[
        [QueryPlan, ComplexRecord, RailStationSearchResult], EvidenceFact
    ]
    childcare_fact: Callable[[ChildcareCenter], EvidenceFact]
    childcare_scope_fact: Callable[
        [QueryPlan, ComplexRecord, ChildcareSearchResult], EvidenceFact
    ]


class PropertyIdentityHandler:
    capability: QueryCapability = "complex_identity"

    def __init__(self, builders: EvidenceFactBuilders) -> None:
        self._builders = builders

    async def observe(self, plan: QueryPlan, complex_record: ComplexRecord) -> CapabilityResult:
        del plan
        limitations = []
        if not complex_record.marker_safe:
            limitations.append("검증된 표시 좌표가 없어 위치 좌표는 제공하지 않습니다.")
        return CapabilityResult(
            [self._builders.complex_fact(complex_record)], limitations, "supported"
        )


class RecentTradeHandler:
    capability: QueryCapability = "recent_trade_lookup"

    def __init__(
        self,
        repository: PropertyFactRepository,
        builders: EvidenceFactBuilders,
        *,
        allow_reference_fallback: bool = True,
    ) -> None:
        self._repository = repository
        self._builders = builders
        self._allow_reference_fallback = allow_reference_fallback

    async def observe(self, plan: QueryPlan, complex_record: ComplexRecord) -> CapabilityResult:
        trades = await asyncio.to_thread(
            self._repository.recent_trades,
            complex_record.complex_id,
            plan.start_date,
            plan.end_date,
            plan.exclusive_area_square_meters,
            plan.limit,
        )
        if not trades:
            if not self._allow_reference_fallback:
                return CapabilityOutcome(
                    [],
                    ["요청한 기간과 전용면적 조건에 맞는 실거래가 없습니다."],
                    "unavailable",
                    state="EMPTY",
                    no_exact_result=True,
                )
            same_area_reference = await asyncio.to_thread(
                self._repository.recent_trades,
                complex_record.complex_id,
                None,
                None,
                plan.exclusive_area_square_meters,
                plan.limit,
            )
            if same_area_reference:
                latest_trade_date = await asyncio.to_thread(
                    self._repository.latest_trade_date
                )
                data_as_of = latest_trade_date or max(
                    record.deal_date for record in same_area_reference
                )
                return CapabilityOutcome(
                    [
                        self._builders.trade_fact(record, data_as_of)
                        for record in same_area_reference
                    ],
                    [
                        "정확 조건에서는 0건이어서 같은 면적의 확인 가능한 최근 거래를 참고 거래로 표시했습니다.",
                        "신고 취소 또는 지연 신고가 이후 반영될 수 있습니다.",
                    ],
                    "partial",
                    state="DEGRADED",
                    fallback_steps=("SAME_AREA_ANY_PERIOD",),
                )
            return CapabilityOutcome(
                [],
                ["정확 조건과 같은 면적의 참고 범위에서도 확인된 실거래가 없습니다."],
                "unavailable",
                state="EMPTY",
                fallback_steps=("SAME_AREA_ANY_PERIOD",),
            )
        latest_trade_date = await asyncio.to_thread(self._repository.latest_trade_date)
        data_as_of = latest_trade_date or max(record.deal_date for record in trades)
        limitations = ["신고 취소 또는 지연 신고가 이후 반영될 수 있습니다."]
        if len(trades) < plan.limit:
            limitations.append(
                f"요청한 {plan.limit}건 중 실제 확인된 거래는 {len(trades)}건입니다."
            )
        if plan.exclusive_area_square_meters is not None:
            limitations.append("전용면적은 요청값 기준 ±1.0㎡ 범위로 조회했습니다.")
        return CapabilityResult(
            [self._builders.trade_fact(record, data_as_of) for record in trades],
            limitations,
            "supported",
        )


class PriceTrendHandler:
    capability: QueryCapability = "price_trend"

    def __init__(self, repository: PropertyFactRepository, builders: EvidenceFactBuilders) -> None:
        self._repository = repository
        self._builders = builders

    async def observe(self, plan: QueryPlan, complex_record: ComplexRecord) -> CapabilityResult:
        assert plan.start_date is not None and plan.end_date is not None
        trends = await asyncio.to_thread(
            self._repository.monthly_trends,
            complex_record.complex_id,
            plan.start_date,
            plan.end_date,
            plan.exclusive_area_square_meters,
        )
        if not trends:
            reference_trades = await asyncio.to_thread(
                self._repository.recent_trades,
                complex_record.complex_id,
                plan.start_date,
                plan.end_date,
                plan.exclusive_area_square_meters,
                min(plan.limit, 5),
            )
            if reference_trades:
                latest_trade_date = await asyncio.to_thread(
                    self._repository.latest_trade_date
                )
                data_as_of = latest_trade_date or max(
                    record.deal_date for record in reference_trades
                )
                return CapabilityOutcome(
                    [
                        self._builders.trade_fact(record, data_as_of)
                        for record in reference_trades
                    ],
                    [
                        "월별 추이를 만들 표본이 없어 같은 조건의 최근 개별 거래를 참고로 표시했습니다.",
                        "개별 거래만으로 가격 방향이나 미래 흐름을 판단할 수 없습니다.",
                    ],
                    "partial",
                    state="DEGRADED",
                    fallback_steps=("RECENT_INDIVIDUAL_TRADES",),
                )
            return CapabilityOutcome(
                [],
                ["월별 추이와 같은 조건의 최근 개별 거래를 모두 확인하지 못했습니다."],
                "unavailable",
                state="EMPTY",
                fallback_steps=("RECENT_INDIVIDUAL_TRADES",),
            )
        latest_trade_date = await asyncio.to_thread(self._repository.latest_trade_date)
        data_as_of = latest_trade_date or min(
            plan.end_date, max(_month_end(record.month) for record in trends)
        )
        limitations = [
            "월별 수치는 실제 거래 관찰값이며 미래 가격을 의미하지 않습니다.",
            "거래가 없는 월은 0건으로 만들지 않고 표에서 생략했습니다.",
        ]
        if plan.exclusive_area_square_meters is not None:
            limitations.append("전용면적은 요청값 기준 ±1.0㎡ 범위로 집계했습니다.")
        return CapabilityResult(
            [self._builders.trend_fact(record, data_as_of) for record in trends],
            limitations,
            "supported",
        )


class SchoolLocationHandler:
    capability: QueryCapability = "school_location"

    def __init__(
        self,
        repository: SchoolFactRepository | None,
        builders: EvidenceFactBuilders,
        today: Callable[[], date],
    ) -> None:
        self._repository = repository
        self._builders = builders
        self._today = today

    async def observe(self, plan: QueryPlan, complex_record: ComplexRecord) -> CapabilityResult:
        assert plan.radius_meters is not None
        if not 100 <= plan.radius_meters <= 2000:
            return CapabilityResult(
                [], ["학교 검색 반경은 100m에서 2000m 사이로 지정해야 합니다."], "unavailable"
            )
        if not _has_marker_coordinates(complex_record):
            return CapabilityResult(
                [], ["검증된 단지 표시 좌표가 없어 주변 학교를 조회할 수 없습니다."], "unavailable"
            )
        if self._repository is None:
            return CapabilityResult([], ["공식 학교 위치 snapshot이 준비되지 않았습니다."], "unavailable")
        snapshot = await asyncio.to_thread(self._repository.active_snapshot)
        if snapshot is None:
            return CapabilityResult([], ["공식 학교 위치 active snapshot이 없습니다."], "unavailable")
        age_days = (self._today() - snapshot.source_date).days
        if age_days < 0 or age_days > 214:
            return CapabilityResult(
                [], ["공식 학교 위치 snapshot의 기준일이 freshness 범위를 벗어났습니다."], "unavailable"
            )
        result = await asyncio.to_thread(
            self._repository.nearby_schools,
            latitude=complex_record.latitude,
            longitude=complex_record.longitude,
            school_levels=plan.school_levels,
            radius_meters=plan.radius_meters,
            limit=plan.limit,
        )
        return CapabilityResult(
            [
                self._builders.complex_fact(complex_record),
                *(self._builders.school_fact(record, snapshot) for record in result.schools),
                self._builders.school_scope_fact(plan, complex_record, result, snapshot),
            ],
            [
                "거리는 단지 표시 좌표 기준 직선거리이며 실제 보행 경로가 아닙니다.",
                "통학구역 근거가 아니므로 배정학교를 의미하지 않습니다.",
                "데이터 기준일 이후 학교 운영상태가 변경될 수 있습니다.",
            ],
            "supported",
        )


class AcademyLookupHandler:
    capability: QueryCapability = "academy_lookup"

    def __init__(
        self,
        repository: AcademyLocationFactRepository | None,
        builders: EvidenceFactBuilders,
        today: Callable[[], date],
    ) -> None:
        self._repository = repository
        self._builders = builders
        self._today = today

    async def observe(self, plan: QueryPlan, complex_record: ComplexRecord) -> CapabilityResult:
        assert plan.radius_meters is not None
        if not 100 <= plan.radius_meters <= 2000:
            return CapabilityResult(
                [], ["교육업소 검색 반경은 100m에서 2000m 사이로 지정해야 합니다."], "unavailable"
            )
        if not _has_marker_coordinates(complex_record):
            return CapabilityResult(
                [], ["검증된 단지 표시 좌표가 없어 교육업소를 조회할 수 없습니다."], "unavailable"
            )
        if self._repository is None:
            return CapabilityResult([], ["학원 위치 데이터가 준비되지 않았습니다."], "unavailable")
        result = await asyncio.to_thread(
            self._repository.nearby,
            latitude=complex_record.latitude,
            longitude=complex_record.longitude,
            radius_meters=plan.radius_meters,
            limit=plan.limit,
        )
        age_days = (self._today() - result.observed_at.date()).days
        if result.coordinate_coverage < 0.95 or age_days < 0 or age_days > result.freshness_days:
            return CapabilityResult(
                [], ["학원 위치 데이터의 좌표 범위 또는 관측일이 기준을 충족하지 못했습니다."], "unavailable"
            )
        facts: list[EvidenceFact] = []
        for location in result.locations:
            facts.append(self._builders.academy_location_fact(location))
            if location.registry_match is not None:
                facts.append(
                    self._builders.academy_exact_match_fact(location, location.registry_match)
                )
        facts.append(self._builders.academy_lookup_scope_fact(plan, complex_record, result))
        limitations = [
            "거리는 단지 표시 좌표 기준 직선거리이며 실제 보행 경로가 아닙니다.",
            "표시된 학원 위치는 공식 학원 등록 여부와 별도로 관찰된 위치 정보입니다.",
        ]
        if not result.locations and not result.verified_zero:
            limitations.append(
                "지역 범위가 충분히 검증되지 않아 학원이 전혀 없다고 단정할 수 없습니다."
            )
        return CapabilityResult(facts, limitations, "supported")


class AcademyRegistrySummaryHandler:
    capability: QueryCapability = "academy_registry_summary"

    def __init__(
        self,
        property_repository: PropertyFactRepository,
        repository: AcademyRegistryFactRepository | None,
        builders: EvidenceFactBuilders,
        today: Callable[[], date],
    ) -> None:
        self._property_repository = property_repository
        self._repository = repository
        self._builders = builders
        self._today = today

    async def observe(self, plan: QueryPlan, complex_record: ComplexRecord) -> CapabilityResult:
        del plan
        if complex_record.region_code is None:
            return CapabilityResult([], ["단지의 행정구역을 확인할 수 없습니다."], "unavailable")
        if self._repository is None:
            return CapabilityResult([], ["공식 학원·교습소 등록 집계가 준비되지 않았습니다."], "unavailable")
        region = await asyncio.to_thread(
            self._property_repository.resolve_region_context, complex_record.region_code
        )
        if region is None:
            return CapabilityResult([], ["단지의 시도·시군구 행정구역을 확인할 수 없습니다."], "unavailable")
        summary = await asyncio.to_thread(
            self._repository.summary,
            education_office_name=region.education_office_name,
            district_name=region.district_name,
        )
        if summary is None:
            return CapabilityResult([], ["해당 시군구의 공식 등록 집계를 확인하지 못했습니다."], "unavailable")
        age_days = (self._today() - summary.observed_at.date()).days
        if age_days < 0 or age_days > summary.freshness_days:
            return CapabilityResult([], ["공식 등록 집계의 관측일이 freshness 범위를 벗어났습니다."], "unavailable")
        return CapabilityResult(
            [self._builders.academy_registry_fact(summary)],
            [
                "시군구 단위 공식 등록 원장 집계이며 위치 검색이나 교육 품질을 의미하지 않습니다.",
                "관측일 이후 등록·운영상태가 변경될 수 있습니다.",
            ],
            "supported",
        )


class RailStationHandler:
    capability: QueryCapability = "rail_station_lookup"

    def __init__(
        self,
        repository: RailStationFactRepository | None,
        builders: EvidenceFactBuilders,
        today: Callable[[], date],
    ) -> None:
        self._repository = repository
        self._builders = builders
        self._today = today

    async def observe(self, plan: QueryPlan, complex_record: ComplexRecord) -> CapabilityResult:
        assert plan.radius_meters is not None
        if not 100 <= plan.radius_meters <= 3000:
            return CapabilityResult(
                [], ["철도역 검색 반경은 100m에서 3000m 사이로 지정해야 합니다."], "unavailable"
            )
        if not _has_marker_coordinates(complex_record):
            return CapabilityResult(
                [], ["검증된 단지 표시 좌표가 없어 주변 철도역을 조회할 수 없습니다."], "unavailable"
            )
        if self._repository is None:
            return CapabilityResult([], ["공식 철도역 active snapshot이 준비되지 않았습니다."], "unavailable")
        result = await asyncio.to_thread(
            self._repository.nearby,
            latitude=complex_record.latitude,
            longitude=complex_record.longitude,
            radius_meters=plan.radius_meters,
            limit=plan.limit,
        )
        age_days = (self._today() - result.source_date).days
        if result.coordinate_coverage < 1.0 or age_days < 0 or age_days > result.freshness_days:
            return CapabilityResult(
                [], ["철도역 snapshot의 좌표 coverage 또는 기준일이 활성화 기준을 충족하지 못했습니다."], "unavailable"
            )
        return CapabilityResult(
            [
                *(self._builders.rail_station_fact(station, result) for station in result.stations),
                self._builders.rail_scope_fact(plan, complex_record, result),
            ],
            [
                "거리는 단지 표시 좌표 기준 직선거리이며 실제 보행 경로가 아닙니다.",
                "통근시간·배차·혼잡도는 현재 근거에 포함되지 않습니다.",
            ],
            "supported",
        )


class RetailLocationHandler:
    capability: QueryCapability = "retail_location"

    def __init__(
        self,
        repository: PointFacilityFactRepository | None,
        builders: EvidenceFactBuilders,
    ) -> None:
        self._repository = repository
        self._builders = builders

    async def observe(self, plan: QueryPlan, complex_record: ComplexRecord) -> CapabilityResult:
        assert plan.radius_meters is not None
        if not 100 <= plan.radius_meters <= 3000:
            return CapabilityResult(
                [], ["대규모점포 검색 반경은 100m에서 3000m 사이로 지정해야 합니다."], "unavailable"
            )
        if not _has_marker_coordinates(complex_record) or complex_record.region_code is None:
            return CapabilityResult(
                [], ["검증된 단지 좌표와 지역 코드가 없어 주변 대규모점포를 조회할 수 없습니다."], "unavailable"
            )
        if self._repository is None:
            return CapabilityResult([], ["공식 대규모점포 active snapshot이 준비되지 않았습니다."], "unavailable")
        result = await asyncio.to_thread(
            self._repository.nearby,
            source_id="retail.large-store",
            category="RETAIL",
            latitude=complex_record.latitude,
            longitude=complex_record.longitude,
            radius_meters=plan.radius_meters,
            limit=plan.limit,
            region_code=complex_record.region_code,
            subcategories=plan.facility_subtypes,
        )
        if not retail_coordinate_ready(result):
            return CapabilityResult(
                [],
                [
                    "대규모점포 원장의 좌표 확인 범위가 "
                    f"활성화 기준 {RETAIL_MIN_COORDINATE_COVERAGE * 100:g}%에 "
                    "미치지 못했습니다."
                ],
                "unavailable",
            )
        limitations = [
            "거리는 단지 표시 좌표 기준 직선거리이며 실제 보행 경로가 아닙니다.",
            "공식 snapshot 이후 운영상태가 변경될 수 있습니다.",
            "전국 대규모점포 원장 중 좌표가 확인된 "
            f"{result.coordinate_coverage * 100:.2f}% 범위만 반영했습니다.",
        ]
        if not result.facilities and not result.verified_zero:
            limitations.append(
                "좌표가 확인된 공식 자료의 결과이며 좌표가 없는 원장이 포함될 수 있어 시설이 전혀 없다고 단정할 수 없습니다."
            )
        return CapabilityResult(
            [
                self._builders.complex_fact(complex_record),
                *(self._builders.retail_fact(record) for record in result.facilities),
                self._builders.retail_scope_fact(plan, complex_record, result),
            ],
            limitations,
            "supported",
        )


class ChildcareLookupHandler:
    capability: QueryCapability = "childcare_lookup"

    def __init__(
        self,
        repository: ChildcareFactRepository | None,
        builders: EvidenceFactBuilders,
        today: Callable[[], date],
    ) -> None:
        self._repository = repository
        self._builders = builders
        self._today = today

    async def observe(
        self, plan: QueryPlan, complex_record: ComplexRecord
    ) -> CapabilityResult:
        assert plan.radius_meters is not None
        if not 100 <= plan.radius_meters <= 2000:
            return CapabilityResult(
                [],
                ["어린이집 검색 반경은 100m에서 2000m 사이로 지정해야 합니다."],
                "unavailable",
            )
        if not _has_marker_coordinates(complex_record):
            return CapabilityResult(
                [],
                ["검증된 단지 표시 좌표가 없어 주변 어린이집을 조회할 수 없습니다."],
                "unavailable",
            )
        if self._repository is None:
            return CapabilityResult(
                [],
                ["공식 어린이집 active snapshot이 준비되지 않았습니다."],
                "unavailable",
            )
        region_code = _district_region_code(complex_record.region_code)
        result = await asyncio.to_thread(
            self._repository.nearby,
            latitude=complex_record.latitude,
            longitude=complex_record.longitude,
            radius_meters=plan.radius_meters,
            limit=plan.limit,
            region_code=region_code,
        )
        if result is None:
            return CapabilityResult(
                [],
                ["공식 어린이집 active snapshot이 없습니다."],
                "unavailable",
            )
        age_days = (self._today() - result.observed_at.date()).days
        if (
            age_days < 0
            or age_days > result.freshness_days
            or result.coordinate_coverage is None
            or result.coordinate_coverage < 0.9
        ):
            return CapabilityResult(
                [],
                ["공식 어린이집 snapshot의 관측일 또는 지역 좌표 coverage가 활성화 기준을 충족하지 못했습니다."],
                "unavailable",
            )
        facts = [
            *(self._builders.childcare_fact(center) for center in result.centers),
            self._builders.childcare_scope_fact(plan, complex_record, result),
        ]
        limitations = [
            "거리는 단지 표시 좌표 기준 직선거리이며 실제 보행 경로가 아닙니다.",
            "정원은 시설의 수용 정원이며 현재 이용 가능 여부를 의미하지 않습니다.",
            "입소 대기와 보육 품질은 현재 근거에 포함되지 않습니다.",
            "데이터 기준일 이후 운영상태가 변경될 수 있습니다.",
        ]
        readiness = (
            "supported"
            if result.centers or result.verified_zero
            else "partial"
        )
        if not result.centers and not result.verified_zero:
            limitations.append(
                "지정 반경의 0건 여부를 지역 coverage로 확정할 수 없습니다."
            )
        return CapabilityResult(facts, limitations, readiness)


class KakaoPlaceSearchHandler:
    capability: QueryCapability = "kakao_place_search"

    def __init__(self, builders: EvidenceFactBuilders) -> None:
        self._builders = builders

    async def observe(
        self, plan: QueryPlan, complex_record: ComplexRecord
    ) -> CapabilityResult:
        if not _has_marker_coordinates(complex_record):
            return CapabilityResult(
                [],
                ["검증된 단지 표시 좌표가 없어 Kakao 지도 검색을 열 수 없습니다."],
                "unavailable",
            )
        assert plan.place_category is not None
        assert complex_record.latitude is not None
        assert complex_record.longitude is not None
        fact = self._builders.complex_fact(complex_record)
        label = (
            "지도에서 병원 보기"
            if plan.place_category == "HOSPITAL"
            else "지도에서 어린이집 보기"
        )
        return CapabilityResult(
            [fact],
            [
                "Kakao 장소 검색은 버튼을 누른 뒤 실행되며 공식 시설 현황 근거가 아닙니다.",
                "Kakao 검색 결과는 chatbot 답변이나 대화 archive에 저장하지 않습니다.",
            ],
            "supported",
            (
                ShowNearbyCategoryAction(
                    label=label,
                    category=plan.place_category,
                    latitude=complex_record.latitude,
                    longitude=complex_record.longitude,
                    fact_ids=(fact.fact_id,),
                ),
            ),
        )


def _has_marker_coordinates(record: ComplexRecord) -> bool:
    return record.marker_safe and record.latitude is not None and record.longitude is not None


def _district_region_code(value: str | None) -> str | None:
    if value is None:
        return None
    candidate = value[:5]
    if len(candidate) == 5 and candidate.isascii() and candidate.isdigit():
        return candidate
    return None


def _month_end(month: date) -> date:
    if month.month == 12:
        return date(month.year, 12, 31)
    return date.fromordinal(date(month.year, month.month + 1, 1).toordinal() - 1)
