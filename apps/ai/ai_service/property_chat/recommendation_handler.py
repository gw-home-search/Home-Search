from __future__ import annotations

import asyncio
from datetime import date, timedelta
from typing import Protocol

from .capability_handlers import CapabilityResult, EvidenceFactBuilders
from .comparison import CandidatePoint, RecentThreeTradeBasis
from .models import (
    ComplexRecord,
    EvidenceFact,
    FactClaim,
    QueryCapability,
    QueryPlan,
    TradeRecord,
)
from .rail_stations import RailStationSearchResult
from .recommendation import (
    RecommendationCandidate,
    RecommendationCard,
    RecommendationCardsArtifact,
    RecommendationPolicy,
    RecommendationScoreItem,
)
from .reference_facilities import FacilitySearchResult


class RecommendationPropertyRepository(Protocol):
    def recommendation_candidates(
        self,
        region_name: str,
        start_date: date,
        end_date: date,
        exclusive_area_square_meters: float,
        limit: int,
    ) -> dict[int, tuple[ComplexRecord, tuple[TradeRecord, ...]]] | None: ...

    def latest_trade_date(self) -> date | None: ...


class RecommendationRailRepository(Protocol):
    def nearest_batch(
        self, *, points: tuple[CandidatePoint, ...], radius_meters: int
    ) -> dict[int, RailStationSearchResult] | None: ...


class RecommendationRetailRepository(Protocol):
    def nearest_batch(
        self,
        *,
        source_id: str,
        category: str,
        points: tuple[CandidatePoint, ...],
        radius_meters: int,
    ) -> dict[int, FacilitySearchResult] | None: ...


class RecommendationHandler:
    capability: QueryCapability = "recommendation"

    def __init__(
        self,
        repository: RecommendationPropertyRepository,
        rail_repository: RecommendationRailRepository | None,
        retail_repository: RecommendationRetailRepository | None,
        builders: EvidenceFactBuilders,
    ) -> None:
        self._repository = repository
        self._rail_repository = rail_repository
        self._retail_repository = retail_repository
        self._builders = builders

    async def observe(self, plan: QueryPlan) -> CapabilityResult:
        if plan.capability != "recommendation":
            raise ValueError("recommendation plan is invalid")
        missing = [
            label
            for value, label in (
                (plan.region_name, "지역"),
                (plan.maximum_budget_ten_thousand_krw, "최대 예산"),
                (plan.exclusive_area_square_meters, "전용면적"),
            )
            if value is None
        ]
        if missing:
            return CapabilityResult(
                [],
                ["추천을 실행하려면 다음 조건이 필요합니다: " + ", ".join(missing)],
                "unavailable",
            )
        region_name = plan.region_name
        area = plan.exclusive_area_square_meters
        budget = plan.maximum_budget_ten_thousand_krw
        assert region_name is not None and area is not None and budget is not None
        cutoff = plan.end_date or await asyncio.to_thread(
            self._repository.latest_trade_date
        )
        if cutoff is None:
            return CapabilityResult(
                [], ["추천 기준으로 사용할 전역 최신 거래일을 확인하지 못했습니다."],
                "unavailable",
            )
        start_date = cutoff - timedelta(days=364)
        observations = await asyncio.to_thread(
            self._repository.recommendation_candidates,
            region_name,
            start_date,
            cutoff,
            area,
            100,
        )
        if observations is None:
            return CapabilityResult(
                [],
                ["지역을 하나로 식별하지 못했습니다. 시·도와 시·군·구를 함께 입력해 주세요."],
                "unavailable",
            )
        if len(observations) > 100:
            raise ValueError("recommendation candidate cap was exceeded")
        policy = RecommendationPolicy(
            maximum_budget_ten_thousand_krw=(
                budget
            )
        )
        bases = {
            complex_id: RecentThreeTradeBasis.from_trades(
                complex_id=complex_id,
                cutoff=cutoff,
                exclusive_area_square_meters=area,
                trades=trades,
            )
            for complex_id, (_, trades) in observations.items()
        }
        qualified = tuple(
            (record, bases[complex_id])
            for complex_id, (record, _) in observations.items()
            if record.marker_safe
            and record.latitude is not None
            and record.longitude is not None
            and policy.is_budget_qualified(bases[complex_id])
        )
        if not qualified:
            scope_fact = _scope_fact(
                region_name=region_name,
                start_date=start_date,
                cutoff=cutoff,
                area=area,
                budget=budget,
                observed_candidate_count=len(observations),
            )
            return CapabilityResult(
                [scope_fact],
                [
                    f"{start_date.isoformat()}부터 {cutoff.isoformat()}까지 전용면적 "
                    f"{area:g}㎡ ±1.0㎡ 최근 거래 3건과 "
                    "예산 조건을 모두 통과한 단지를 확인하지 못했습니다."
                ],
                "supported",
            )
        if self._rail_repository is None:
            return _source_unavailable("철도")
        if self._retail_repository is None:
            return _source_unavailable("대규모점포")
        points = tuple(
            CandidatePoint(
                record.complex_id,
                record.latitude,  # type: ignore[arg-type]
                record.longitude,  # type: ignore[arg-type]
                record.region_code,
            )
            for record, _ in qualified
        )
        rail_results, retail_results = await asyncio.gather(
            asyncio.to_thread(
                self._rail_repository.nearest_batch,
                points=points,
                radius_meters=1500,
            ),
            asyncio.to_thread(
                self._retail_repository.nearest_batch,
                source_id="retail.large-store",
                category="LARGE_STORE",
                points=points,
                radius_meters=1000,
            ),
        )
        if rail_results is None:
            return _source_unavailable("철도")
        if retail_results is None:
            return _source_unavailable("대규모점포")
        candidates = tuple(
            RecommendationCandidate(
                complex_record=record,
                trade_basis=basis,
                rail_distance_meters=_rail_distance(
                    rail_results.get(record.complex_id)
                ),
                retail_distance_meters=_retail_distance(
                    retail_results.get(record.complex_id)
                ),
            )
            for record, basis in qualified
        )
        ranked = policy.rank(candidates)[: plan.limit]
        facts: list[EvidenceFact] = []
        cards: list[RecommendationCard] = []
        for rank, result in enumerate(ranked, start=1):
            candidate = result.candidate
            record = candidate.complex_record
            rail_result = rail_results[record.complex_id]
            retail_result = retail_results[record.complex_id]
            complex_fact = self._builders.complex_fact(record)
            trade_fact = _trade_basis_fact(
                candidate.trade_basis, budget
            )
            rail_fact = _rail_fact(
                record.complex_id, rail_result, result.breakdown.rail_points
            )
            retail_fact = _retail_fact(
                record.complex_id, retail_result, result.breakdown.retail_points
            )
            facts.extend((complex_fact, trade_fact, rail_fact, retail_fact))
            cards.append(RecommendationCard(
                rank=rank,
                complex_id=record.complex_id,
                complex_name=record.display_name,
                total_score=result.total_score,
                latest_trade_date=candidate.trade_basis.latest_trade.deal_date,  # type: ignore[union-attr]
                latest_trade_amount_ten_thousand_krw=(
                    candidate.trade_basis.latest_trade.deal_amount_ten_thousand_krw  # type: ignore[union-attr]
                ),
                median_amount_ten_thousand_krw=(
                    candidate.trade_basis.median_amount_ten_thousand_krw  # type: ignore[arg-type]
                ),
                latest_trade_fact_ids=(trade_fact.fact_id,),
                median_fact_ids=(trade_fact.fact_id,),
                score_breakdown=(
                    RecommendationScoreItem(
                        "PRICE", "예산 조건", 60.0,
                        result.breakdown.price_points, None, (trade_fact.fact_id,),
                    ),
                    RecommendationScoreItem(
                        "TRANSIT", "철도 접근성", 25.0,
                        result.breakdown.rail_points,
                        candidate.rail_distance_meters, (rail_fact.fact_id,),
                    ),
                    RecommendationScoreItem(
                        "SHOPPING", "대규모점포 접근성", 15.0,
                        result.breakdown.retail_points,
                        candidate.retail_distance_meters, (retail_fact.fact_id,),
                    ),
                ),
                limitations=(
                    "최근 365일 동일 면적 거래 3건과 단지 좌표 기준 직선거리입니다.",
                    "예산을 통과한 후보는 가격 점수가 모두 같으며 저렴할수록 가산하지 않습니다.",
                ),
                fact_ids=(
                    complex_fact.fact_id, trade_fact.fact_id,
                    rail_fact.fact_id, retail_fact.fact_id,
                ),
            ))
        artifact = RecommendationCardsArtifact(
            artifact_id=(
                f"recommendation-{cutoff.isoformat()}-"
                f"{area:g}-{budget}"
            ),
            cards=tuple(cards),
        ).to_public_dict()
        artifact_fact_ids = tuple(dict.fromkeys(_fact_ids(artifact)))
        return CapabilityResult(
            _deduplicate_facts(facts),
            [
                f"{region_name} 및 하위 지역에서 {start_date.isoformat()}부터 "
                f"{cutoff.isoformat()}까지 전용면적 "
                f"{area:g}㎡ ±1.0㎡의 최근 거래 "
                "3건을 기준으로 예산을 먼저 적용했습니다.",
                "조건 충족도는 가격 60점, 철도 25점, 대규모점포 15점입니다.",
                "가격은 예산 hard filter이며 통과 후보에 추가 가격 가산점이 없습니다.",
                "이 결과는 미래가격·수익성·투자 가치를 평가하지 않습니다.",
            ],
            "supported",
            artifacts=(artifact,),
            artifact_fact_ids=artifact_fact_ids,
        )


def _source_unavailable(label: str) -> CapabilityResult:
    return CapabilityResult(
        [], [f"{label} source가 준비되지 않아 조건 충족도를 계산하지 못했습니다."],
        "unavailable",
    )


def _rail_distance(result: RailStationSearchResult | None) -> int | None:
    if result is None or not result.stations:
        return None
    return result.stations[0].distance_meters


def _retail_distance(result: FacilitySearchResult | None) -> int | None:
    if result is None or not result.facilities:
        return None
    return result.facilities[0].distance_meters


def _trade_basis_fact(
    basis: RecentThreeTradeBasis, maximum_budget_ten_thousand_krw: int
) -> EvidenceFact:
    latest = basis.latest_trade
    median = basis.median_amount_ten_thousand_krw
    if latest is None or median is None or basis.sample_count != 3:
        raise ValueError("qualified recommendation trade basis is invalid")
    return EvidenceFact(
        fact_id=(
            f"recommendation-trade-basis-{basis.complex_id}-"
            f"{basis.cutoff.isoformat()}-{basis.exclusive_area_square_meters:g}"
        ),
        claims=(
            FactClaim("3", "COUNT"),
            FactClaim(basis.cutoff.isoformat(), "DATE"),
            FactClaim(str(latest.deal_amount_ten_thousand_krw), "LATEST_10_000_KRW"),
            FactClaim(str(median), "MEDIAN_10_000_KRW"),
            FactClaim(str(maximum_budget_ten_thousand_krw), "MAXIMUM_10_000_KRW"),
            FactClaim("60", "POINTS"),
        ),
        data_as_of=basis.cutoff,
        payload={
            "complexId": basis.complex_id,
            "sampleCount": 3,
            "cutoffDate": basis.cutoff.isoformat(),
            "startDate": basis.start_date.isoformat(),
            "exclusiveAreaSquareMeters": basis.exclusive_area_square_meters,
            "latestTradeDate": latest.deal_date.isoformat(),
            "latestTradeAmountTenThousandKrw": latest.deal_amount_ten_thousand_krw,
            "medianAmountTenThousandKrw": median,
            "maximumBudgetTenThousandKrw": maximum_budget_ten_thousand_krw,
            "pricePoints": 60,
        },
    )


def _scope_fact(
    *,
    region_name: str,
    start_date: date,
    cutoff: date,
    area: float,
    budget: int,
    observed_candidate_count: int,
) -> EvidenceFact:
    return EvidenceFact(
        fact_id=(
            f"recommendation-scope-{cutoff.isoformat()}-{area:g}-{budget}"
        ),
        claims=(
            FactClaim(region_name, "TEXT"),
            FactClaim(start_date.isoformat(), "DATE"),
            FactClaim(cutoff.isoformat(), "DATE"),
            FactClaim(f"{area:g}", "SQUARE_METERS"),
            FactClaim(str(budget), "MAXIMUM_10_000_KRW"),
            FactClaim(str(observed_candidate_count), "OBSERVED_CANDIDATE_COUNT"),
            FactClaim("0", "QUALIFIED_CANDIDATE_COUNT"),
        ),
        data_as_of=cutoff,
        payload={
            "regionName": region_name,
            "startDate": start_date.isoformat(),
            "cutoffDate": cutoff.isoformat(),
            "exclusiveAreaSquareMeters": area,
            "maximumBudgetTenThousandKrw": budget,
            "observedCandidateCount": observed_candidate_count,
            "qualifiedCandidateCount": 0,
        },
    )


def _rail_fact(
    complex_id: int, result: RailStationSearchResult, points: float
) -> EvidenceFact:
    station = result.stations[0] if result.stations else None
    claims = [
        FactClaim("1500", "METERS"),
        FactClaim(result.source_date.isoformat(), "DATE"),
        FactClaim("25", "WEIGHT_POINTS"),
        FactClaim(format(points, ".15g"), "POINTS"),
    ]
    if station is None:
        claims.append(FactClaim("0", "COUNT"))
    else:
        claims.extend((
            FactClaim(station.station_name, "TEXT"),
            FactClaim(str(station.distance_meters), "METERS"),
        ))
    return EvidenceFact(
        fact_id=f"recommendation-rail-{complex_id}-{result.dataset_version}",
        claims=tuple(claims),
        data_as_of=result.source_date,
        payload={
            "complexId": complex_id,
            "radiusMeters": 1500,
            "nearestDistanceMeters": None if station is None else station.distance_meters,
            "stationName": None if station is None else station.station_name,
            "datasetVersion": result.dataset_version,
            "weight": 25,
            "points": points,
        },
        source_id="transport.rail-station",
        source_name="전국 도시철도역사 정보",
        evidence_grade="A",
        dataset_version_value=result.dataset_version,
    )


def _retail_fact(
    complex_id: int, result: FacilitySearchResult, points: float
) -> EvidenceFact:
    facility = result.facilities[0] if result.facilities else None
    claims = [
        FactClaim("1000", "METERS"),
        FactClaim("15", "WEIGHT_POINTS"),
        FactClaim(format(points, ".15g"), "POINTS"),
    ]
    if facility is None:
        claims.append(FactClaim("0", "COUNT"))
    else:
        claims.extend((
            FactClaim(facility.name, "TEXT"),
            FactClaim(str(facility.distance_meters), "METERS"),
        ))
    return EvidenceFact(
        fact_id=f"recommendation-retail-{complex_id}-{result.dataset_version}",
        claims=tuple(claims),
        data_as_of=result.data_as_of,
        payload={
            "complexId": complex_id,
            "radiusMeters": 1000,
            "nearestDistanceMeters": None if facility is None else facility.distance_meters,
            "facilityName": None if facility is None else facility.name,
            "datasetVersion": result.dataset_version,
            "weight": 15,
            "points": points,
        },
        source_id="retail.large-store",
        source_name="전국 대규모점포 인허가 정보",
        evidence_grade="A",
        dataset_version_value=result.dataset_version,
    )


def _fact_ids(value: object) -> list[str]:
    if isinstance(value, dict):
        return [
            item
            for key, child in value.items()
            for item in (
                child if key == "factIds" and isinstance(child, list)
                else _fact_ids(child)
            )
            if isinstance(item, str)
        ]
    if isinstance(value, list):
        return [item for child in value for item in _fact_ids(child)]
    return []


def _deduplicate_facts(facts: list[EvidenceFact]) -> list[EvidenceFact]:
    return list({fact.fact_id: fact for fact in facts}.values())
