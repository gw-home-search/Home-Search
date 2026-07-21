from __future__ import annotations

import asyncio
from collections.abc import Callable
from dataclasses import replace
from datetime import date, timedelta
from typing import Protocol

from .academy_locations import AcademyLocationSearchResult
from .capability_handlers import CapabilityResult, EvidenceFactBuilders
from .childcare_centers import ChildcareSearchResult
from .comparison import (
    CandidatePoint,
    ComparisonCell,
    ComparisonColumn,
    ComparisonRow,
    ComparisonTableArtifact,
    RecentThreeTradeBasis,
)
from .lifestyle_metrics import childcare_observation_fact, student_observation_fact
from .models import (
    ComplexRecord,
    EvidenceFact,
    FactClaim,
    QueryCapability,
    QueryPlan,
    SchoolSearchResult,
    SchoolSnapshot,
    TradeRecord,
)
from .rail_stations import RailStationSearchResult
from .reference_facilities import FacilitySearchResult


class ComparisonPropertyRepository(Protocol):
    def find_complexes_batch(
        self, names: tuple[str, ...], region_name: str | None, limit_per_name: int
    ) -> dict[str, tuple[ComplexRecord, ...]]: ...

    def recent_trades_batch(
        self,
        complex_ids: tuple[int, ...],
        start_date: date,
        end_date: date,
        exclusive_area_square_meters: float,
        limit_per_complex: int,
    ) -> dict[int, tuple[TradeRecord, ...]]: ...

    def latest_trade_date(self) -> date | None: ...


class ComparisonRailRepository(Protocol):
    def nearest_batch(
        self, *, points: tuple[CandidatePoint, ...], radius_meters: int
    ) -> dict[int, RailStationSearchResult] | None: ...


class ComparisonRetailRepository(Protocol):
    def nearest_batch(
        self,
        *,
        source_id: str,
        category: str,
        points: tuple[CandidatePoint, ...],
        radius_meters: int,
    ) -> dict[int, FacilitySearchResult] | None: ...


class ComparisonSchoolRepository(Protocol):
    def nearest_by_level_batch(
        self, *, points: tuple[CandidatePoint, ...], school_levels: tuple[str, ...],
        radius_meters: int,
    ) -> tuple[SchoolSnapshot, dict[int, SchoolSearchResult]] | None: ...


class ComparisonAcademyRepository(Protocol):
    def nearby_counts_batch(
        self, *, points: tuple[CandidatePoint, ...], radius_meters: int,
    ) -> dict[int, AcademyLocationSearchResult] | None: ...


class ComparisonChildcareRepository(Protocol):
    def nearby_batch(
        self, *, points: tuple[CandidatePoint, ...], radius_meters: int,
    ) -> dict[int, ChildcareSearchResult] | None: ...


class ComparisonHandler:
    capability: QueryCapability = "comparison"

    def __init__(
        self,
        repository: ComparisonPropertyRepository,
        rail_repository: ComparisonRailRepository | None,
        retail_repository: ComparisonRetailRepository | None,
        school_repository: ComparisonSchoolRepository | None,
        academy_repository: ComparisonAcademyRepository | None,
        childcare_repository: ComparisonChildcareRepository | None,
        builders: EvidenceFactBuilders,
        today: Callable[[], date],
    ) -> None:
        self._repository = repository
        self._rail_repository = rail_repository
        self._retail_repository = retail_repository
        self._school_repository = school_repository
        self._academy_repository = academy_repository
        self._childcare_repository = childcare_repository
        self._builders = builders
        self._today = today

    async def observe(self, plan: QueryPlan) -> CapabilityResult:
        if plan.capability != "comparison" or plan.exclusive_area_square_meters is None:
            raise ValueError("comparison plan is invalid")
        matches = await asyncio.to_thread(
            self._repository.find_complexes_batch,
            plan.complex_names,
            plan.region_name,
            6,
        )
        unresolved = [name for name in plan.complex_names if len(matches.get(name, ())) != 1]
        if unresolved:
            candidate_facts = [
                self._builders.complex_fact(record)
                for name in plan.complex_names
                for record in matches.get(name, ())
            ]
            return CapabilityResult(
                candidate_facts,
                [
                    "비교 단지를 하나로 식별하지 못했습니다: " + ", ".join(unresolved),
                    "동명 단지는 지역이나 주소 조건을 추가해야 합니다.",
                ],
                "partial" if candidate_facts else "unavailable",
            )
        complexes = tuple(matches[name][0] for name in plan.complex_names)
        if len({record.complex_id for record in complexes}) != len(complexes):
            return CapabilityResult(
                _deduplicate_facts([
                    self._builders.complex_fact(record) for record in complexes
                ]),
                ["같은 단지가 둘 이상의 비교 이름으로 식별되어 다른 단지명을 입력해야 합니다."],
                "partial",
            )
        cutoff = plan.end_date or await asyncio.to_thread(self._repository.latest_trade_date)
        if cutoff is None:
            return CapabilityResult(
                [self._builders.complex_fact(record) for record in complexes],
                ["비교에 사용할 전역 최신 거래일을 확인하지 못했습니다."],
                "unavailable",
            )
        start_date = cutoff - timedelta(days=364)
        complex_ids = tuple(record.complex_id for record in complexes)
        trades = await asyncio.to_thread(
            self._repository.recent_trades_batch,
            complex_ids,
            start_date,
            cutoff,
            plan.exclusive_area_square_meters,
            3,
        )
        bases = {
            record.complex_id: RecentThreeTradeBasis.from_trades(
                complex_id=record.complex_id,
                cutoff=cutoff,
                exclusive_area_square_meters=plan.exclusive_area_square_meters,
                trades=trades.get(record.complex_id, ()),
            )
            for record in complexes
        }
        points = tuple(
            CandidatePoint(record.complex_id, record.latitude, record.longitude, record.region_code)
            for record in complexes
            if record.marker_safe and record.latitude is not None and record.longitude is not None
        )
        rail_results, retail_results = await asyncio.gather(
            self._rail(points),
            self._retail(points),
        )
        complex_facts = {record.complex_id: self._builders.complex_fact(record) for record in complexes}
        basis_facts = {key: _trade_basis_fact(value) for key, value in bases.items()}
        rail_facts: dict[int, EvidenceFact] = {}
        retail_facts: dict[int, EvidenceFact] = {}
        student_facts: dict[int, EvidenceFact] = {}
        childcare_facts: dict[int, EvidenceFact] = {}
        for record in complexes:
            rail_result = rail_results.get(record.complex_id) if rail_results is not None else None
            if rail_result is not None and rail_result.stations:
                rail_facts[record.complex_id] = _for_complex(
                    self._builders.rail_station_fact(
                        rail_result.stations[0], rail_result
                    ),
                    record.complex_id,
                )
            retail_result = (
                retail_results.get(record.complex_id) if retail_results is not None else None
            )
            if retail_result is not None and retail_result.facilities:
                retail_facts[record.complex_id] = _for_complex(
                    self._builders.retail_fact(retail_result.facilities[0]),
                    record.complex_id,
                )
        student_ready = False
        if "STUDENT" in plan.lifestyle_themes and points:
            if self._school_repository is not None and self._academy_repository is not None:
                school_bundle, academy_results = await asyncio.gather(
                    asyncio.to_thread(
                        self._school_repository.nearest_by_level_batch,
                        points=points, school_levels=plan.school_levels, radius_meters=1500,
                    ),
                    asyncio.to_thread(
                        self._academy_repository.nearby_counts_batch,
                        points=points, radius_meters=800,
                    ),
                )
                if school_bundle is not None and academy_results is not None:
                    snapshot, school_results = school_bundle
                    student_ready = (
                        0 <= (self._today() - snapshot.source_date).days <= 214
                        and all(
                            result.coordinate_coverage >= 0.95
                            and 0 <= (self._today() - result.observed_at.date()).days
                            <= result.freshness_days
                            for result in academy_results.values()
                        )
                    )
                    if student_ready:
                        student_facts = {
                            record.complex_id: student_observation_fact(
                                record.complex_id, school_results[record.complex_id], snapshot,
                                academy_results[record.complex_id], plan.school_levels, 0, 0,
                            ) for record in complexes if record.complex_id in school_results
                        }
        childcare_ready = False
        if "YOUNG_CHILD" in plan.lifestyle_themes and points and self._childcare_repository is not None:
            childcare_results = await asyncio.to_thread(
                self._childcare_repository.nearby_batch, points=points, radius_meters=800,
            )
            if childcare_results is not None:
                childcare_ready = all(
                    result.coordinate_coverage is not None
                    and result.coordinate_coverage >= 0.9
                    and 0 <= (self._today() - result.observed_at.date()).days
                    <= result.freshness_days
                    for result in childcare_results.values()
                )
                if childcare_ready:
                    childcare_facts = {
                        record.complex_id: childcare_observation_fact(
                            record.complex_id, childcare_results[record.complex_id], 0, 0,
                        ) for record in complexes if record.complex_id in childcare_results
                    }
        rows = _rows(
            complexes,
            bases,
            complex_facts,
            basis_facts,
            rail_facts,
            retail_facts,
            rail_results is not None,
            retail_results is not None,
            {point.complex_id for point in points},
        )
        if "STUDENT" in plan.lifestyle_themes:
            rows += (ComparisonRow("studentAccess", "학교 위치·800m 교육업소", tuple(
                _lifestyle_cell(student_facts.get(item.complex_id), student_ready, "학생 조건 데이터")
                for item in complexes
            )),)
        if "YOUNG_CHILD" in plan.lifestyle_themes:
            rows += (ComparisonRow("youngChildAccess", "800m 공식 어린이집", tuple(
                _lifestyle_cell(childcare_facts.get(item.complex_id), childcare_ready, "어린이집 데이터")
                for item in complexes
            )),)
        artifact = ComparisonTableArtifact(
            artifact_id="comparison-" + "-".join(str(value) for value in complex_ids),
            columns=tuple(
                ComparisonColumn(
                    str(record.complex_id), record.display_name,
                    (complex_facts[record.complex_id].fact_id,),
                )
                for record in complexes
            ),
            rows=rows,
            cutoff=cutoff,
            start_date=start_date,
            exclusive_area_square_meters=plan.exclusive_area_square_meters,
        ).to_public_dict()
        artifact_fact_ids = tuple(dict.fromkeys(_fact_ids(artifact)))
        all_facts = _deduplicate_facts([
            *complex_facts.values(),
            *basis_facts.values(),
            *rail_facts.values(),
            *retail_facts.values(),
            *student_facts.values(),
            *childcare_facts.values(),
        ])
        has_unavailable = any(
            cell.availability == "unavailable" for row in rows for cell in row.cells
        )
        return CapabilityResult(
            all_facts,
            [
                f"모든 단지는 {start_date.isoformat()}부터 {cutoff.isoformat()}까지 "
                f"전용면적 {plan.exclusive_area_square_meters:g}㎡ ±1.0㎡ 기준입니다.",
                "거래가 3건 미만인 단지의 가격 항목은 확인 불가로 표시합니다.",
                "철도와 대규모점포 거리는 단지 표시 좌표 기준 직선거리입니다.",
                "조건별 관찰값을 나란히 보여드리며, 중요하게 보는 조건에 따라 선택이 달라질 수 있습니다.",
            ],
            "partial" if has_unavailable else "supported",
            artifacts=(artifact,),
            artifact_fact_ids=artifact_fact_ids,
        )

    async def _rail(
        self, points: tuple[CandidatePoint, ...]
    ) -> dict[int, RailStationSearchResult] | None:
        if self._rail_repository is None or not points:
            return None
        return await asyncio.to_thread(
            self._rail_repository.nearest_batch, points=points, radius_meters=1500
        )

    async def _retail(
        self, points: tuple[CandidatePoint, ...]
    ) -> dict[int, FacilitySearchResult] | None:
        if self._retail_repository is None or not points:
            return None
        return await asyncio.to_thread(
            self._retail_repository.nearest_batch,
            source_id="retail.large-store",
            category="LARGE_STORE",
            points=points,
            radius_meters=1000,
        )


def _trade_basis_fact(basis: RecentThreeTradeBasis) -> EvidenceFact:
    claims = [
        FactClaim(str(basis.sample_count), "COUNT"),
        FactClaim(basis.start_date.isoformat(), "DATE"),
        FactClaim(basis.cutoff.isoformat(), "DATE"),
        FactClaim(f"{basis.exclusive_area_square_meters:g}", "SQUARE_METERS"),
    ]
    payload: dict[str, object] = {
        "complexId": basis.complex_id,
        "sampleCount": basis.sample_count,
        "startDate": basis.start_date.isoformat(),
        "cutoffDate": basis.cutoff.isoformat(),
        "exclusiveAreaSquareMeters": basis.exclusive_area_square_meters,
        "tradeIds": [trade.trade_id for trade in basis.trades],
    }
    if basis.latest_trade is not None and basis.median_amount_ten_thousand_krw is not None:
        claims.extend([
            FactClaim(basis.latest_trade.deal_date.isoformat(), "LATEST_TRADE_DATE"),
            FactClaim(
                str(basis.latest_trade.deal_amount_ten_thousand_krw),
                "LATEST_10_000_KRW",
            ),
            FactClaim(
                str(basis.median_amount_ten_thousand_krw),
                "MEDIAN_10_000_KRW",
            ),
        ])
        payload.update({
            "latestTradeDate": basis.latest_trade.deal_date.isoformat(),
            "latestTradeAmountTenThousandKrw": (
                basis.latest_trade.deal_amount_ten_thousand_krw
            ),
            "medianAmountTenThousandKrw": basis.median_amount_ten_thousand_krw,
        })
    return EvidenceFact(
        fact_id=(
            f"comparison-trade-basis-{basis.complex_id}-{basis.cutoff.isoformat()}-"
            f"{basis.exclusive_area_square_meters:g}"
        ),
        claims=tuple(claims),
        data_as_of=basis.cutoff,
        payload=payload,
    )


def _rows(
    complexes: tuple[ComplexRecord, ...],
    bases: dict[int, RecentThreeTradeBasis],
    complex_facts: dict[int, EvidenceFact],
    basis_facts: dict[int, EvidenceFact],
    rail_facts: dict[int, EvidenceFact],
    retail_facts: dict[int, EvidenceFact],
    rail_ready: bool,
    retail_ready: bool,
    coordinate_complex_ids: set[int],
) -> tuple[ComparisonRow, ...]:
    return (
        ComparisonRow("latestTrade", "가장 최근 거래", tuple(
            _latest_cell(bases[item.complex_id], basis_facts[item.complex_id])
            for item in complexes
        )),
        ComparisonRow("recentThreeMedian", "최근 3건 중앙값", tuple(
            _median_cell(bases[item.complex_id], basis_facts[item.complex_id])
            for item in complexes
        )),
        ComparisonRow("tradeSampleCount", "거래 표본 수", tuple(
            ComparisonCell(
                "available", f"{bases[item.complex_id].sample_count}건", "COUNT", None,
                (basis_facts[item.complex_id].fact_id,),
            ) for item in complexes
        )),
        ComparisonRow("unitCount", "세대수", tuple(
            _static_cell(item.unit_count, "세대", "HOUSEHOLD_COUNT", complex_facts[item.complex_id])
            for item in complexes
        )),
        ComparisonRow("useDate", "사용승인일", tuple(
            _static_cell(item.use_date, "", "DATE", complex_facts[item.complex_id])
            for item in complexes
        )),
        ComparisonRow("nearestRail", "최근접 철도역", tuple(
            _facility_cell(
                rail_facts.get(item.complex_id), rail_ready,
                "1,500m 내 철도역", item.complex_id in coordinate_complex_ids,
            )
            for item in complexes
        )),
        ComparisonRow("nearestRetail", "최근접 대규모점포", tuple(
            _facility_cell(
                retail_facts.get(item.complex_id), retail_ready,
                "1,000m 내 대규모점포", item.complex_id in coordinate_complex_ids,
            )
            for item in complexes
        )),
    )


def _latest_cell(basis: RecentThreeTradeBasis, fact: EvidenceFact) -> ComparisonCell:
    if basis.latest_trade is None:
        return _price_unavailable(fact)
    return ComparisonCell(
        "available",
        f"{basis.latest_trade.deal_date.isoformat()} · "
        f"{_krw(basis.latest_trade.deal_amount_ten_thousand_krw)}",
        "10_000_KRW", None, (fact.fact_id,),
    )


def _median_cell(basis: RecentThreeTradeBasis, fact: EvidenceFact) -> ComparisonCell:
    if basis.median_amount_ten_thousand_krw is None:
        return _price_unavailable(fact)
    return ComparisonCell(
        "available", _krw(basis.median_amount_ten_thousand_krw),
        "10_000_KRW", None, (fact.fact_id,),
    )


def _price_unavailable(fact: EvidenceFact) -> ComparisonCell:
    return ComparisonCell(
        "unavailable", None, "10_000_KRW",
        "동일 면적의 최근 거래 표본이 3건 미만입니다.", (fact.fact_id,),
    )


def _lifestyle_cell(
    fact: EvidenceFact | None, source_ready: bool, source_label: str
) -> ComparisonCell:
    if fact is None:
        return ComparisonCell(
            "unavailable", None, "LIFESTYLE_ACCESS",
            (
                "검증된 단지 표시 좌표가 없습니다."
                if source_ready else f"{source_label}가 준비되지 않았습니다."
            ),
            (),
        )
    if fact.source_id == "lifestyle.student-observation":
        nearest = fact.payload.get("nearestSchools")
        school_values = []
        if isinstance(nearest, dict):
            labels = {"ELEMENTARY": "초등학교", "MIDDLE": "중학교", "HIGH": "고등학교"}
            for level in ("ELEMENTARY", "MIDDLE", "HIGH"):
                value = nearest.get(level)
                if isinstance(value, dict):
                    school_values.append(
                        f"{labels[level]} {value.get('name')} {value.get('distanceMeters')}m"
                    )
        count = fact.payload.get("sbizEducationCountWithin800m")
        rendered = " · ".join((*school_values, f"Sbiz 교육업소 {count}곳"))
    else:
        count = fact.payload.get("countWithin800m")
        name = fact.payload.get("nearestCenterName")
        distance = fact.payload.get("nearestDistanceMeters")
        nearest_text = "최근접 없음" if name is None else f"최근접 {name} {distance}m"
        rendered = f"공식 운영 어린이집 {count}곳 · {nearest_text}"
    return ComparisonCell(
        "available", rendered, "LIFESTYLE_ACCESS", None, (fact.fact_id,)
    )


def _static_cell(
    value: int | date | None, suffix: str, unit: str, fact: EvidenceFact
) -> ComparisonCell:
    if value is None:
        return ComparisonCell(
            "unavailable", None, unit, "단지 원장에서 확인하지 못했습니다.", ()
        )
    rendered = value.isoformat() if isinstance(value, date) else f"{value:,}{suffix}"
    return ComparisonCell("available", rendered, unit, None, (fact.fact_id,))


def _facility_cell(
    fact: EvidenceFact | None,
    source_ready: bool,
    missing_label: str,
    coordinate_available: bool,
) -> ComparisonCell:
    if fact is None:
        reason = (
            "검증된 단지 표시 좌표가 없습니다."
            if not coordinate_available
            else f"{missing_label}을 확인하지 못했습니다."
            if source_ready
            else "필요한 시설 데이터가 아직 준비되지 않았습니다."
        )
        return ComparisonCell("unavailable", None, "STRAIGHT_LINE_METER", reason, ())
    payload = fact.payload
    name = payload.get("stationName", payload.get("facilityName"))
    distance = payload.get("distanceMeters")
    lines = payload.get("lines")
    if not isinstance(name, str) or not isinstance(distance, int):
        raise ValueError("comparison facility fact is invalid")
    line_text = f" · {', '.join(lines)}" if isinstance(lines, list | tuple) and lines else ""
    return ComparisonCell(
        "available", f"{name}{line_text} · {distance:,}m",
        "STRAIGHT_LINE_METER", None, (fact.fact_id,),
    )


def _krw(amount: int) -> str:
    eok, man_won = divmod(amount, 10_000)
    if eok and man_won:
        return f"{eok:,}억 {man_won:,}만원"
    if eok:
        return f"{eok:,}억원"
    return f"{man_won:,}만원"


def _fact_ids(value: object):
    if isinstance(value, dict):
        for key, nested in value.items():
            if key == "factIds" and isinstance(nested, list):
                yield from (item for item in nested if isinstance(item, str))
            else:
                yield from _fact_ids(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from _fact_ids(nested)


def _deduplicate_facts(facts: list[EvidenceFact]) -> list[EvidenceFact]:
    return list({fact.fact_id: fact for fact in facts}.values())


def _for_complex(fact: EvidenceFact, complex_id: int) -> EvidenceFact:
    return replace(
        fact,
        fact_id=f"comparison-{complex_id}-{fact.fact_id}",
        payload={**fact.payload, "comparisonComplexId": complex_id},
    )
