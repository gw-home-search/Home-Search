from __future__ import annotations

import asyncio
from collections.abc import Callable
from datetime import date, datetime, timedelta
import hashlib
from typing import Protocol, TypeVar

from .academy_locations import AcademyLocationSearchResult
from .capability_handlers import CapabilityResult, EvidenceFactBuilders
from .childcare_centers import ChildcareSearchResult
from .comparison import CandidatePoint, RecentThreeTradeBasis
from .criteria_recommendation import (
    CriteriaCandidateScope,
    CriteriaRecommendationCandidate,
    CriteriaRecommendationPolicy,
    CriteriaRecommendationRow,
    RecommendationMetric,
    RecommendationTableArtifact,
)
from .lifestyle_metrics import (
    childcare_details,
    childcare_observation_fact,
    childcare_ratio,
    student_details,
    student_observation_fact,
    student_ratio,
)
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
from .rail_stations import (
    RailStationSearchResult,
    StationScopeMatch,
    StationScopeResolution,
)
from .recommendation import (
    RecommendationCandidate,
    RecommendationCard,
    RecommendationCardsArtifact,
    RecommendationPolicy,
    RecommendationScoreItem,
)
from .recommendation_errors import RecommendationExecutionError
from .reference_facilities import FacilitySearchResult, retail_coordinate_ready

_BatchResult = TypeVar("_BatchResult")
_CRITERIA_CANDIDATE_LIMIT = 5_000
_REPOSITORY_BATCH_SIZE = 100


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

    def criteria_candidates(
        self, region_name: str, limit: int
    ) -> CriteriaCandidateScope | None: ...

    def criteria_candidates_near_point(
        self, latitude: float, longitude: float, radius_meters: int, limit: int
    ) -> tuple[ComplexRecord, ...]: ...


class RecommendationRailRepository(Protocol):
    def nearest_batch(
        self, *, points: tuple[CandidatePoint, ...], radius_meters: int
    ) -> dict[int, RailStationSearchResult] | None: ...

    def resolve_station(self, station_name: str) -> StationScopeResolution | None: ...


class RecommendationRetailRepository(Protocol):
    def nearest_batch(
        self,
        *,
        source_id: str,
        category: str,
        points: tuple[CandidatePoint, ...],
        radius_meters: int,
    ) -> dict[int, FacilitySearchResult] | None: ...


class RecommendationSchoolRepository(Protocol):
    def nearest_by_level_batch(
        self, *, points: tuple[CandidatePoint, ...], school_levels: tuple[str, ...],
        radius_meters: int,
    ) -> tuple[SchoolSnapshot, dict[int, SchoolSearchResult]] | None: ...


class RecommendationAcademyRepository(Protocol):
    def nearby_counts_batch(
        self, *, points: tuple[CandidatePoint, ...], radius_meters: int
    ) -> dict[int, AcademyLocationSearchResult] | None: ...


class RecommendationChildcareRepository(Protocol):
    def nearby_batch(
        self, *, points: tuple[CandidatePoint, ...], radius_meters: int
    ) -> dict[int, ChildcareSearchResult] | None: ...


class RecommendationHandler:
    capability: QueryCapability = "recommendation"

    def __init__(
        self,
        repository: RecommendationPropertyRepository,
        rail_repository: RecommendationRailRepository | None,
        retail_repository: RecommendationRetailRepository | None,
        school_repository: RecommendationSchoolRepository | None,
        academy_repository: RecommendationAcademyRepository | None,
        childcare_repository: RecommendationChildcareRepository | None,
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
        if plan.capability != "recommendation":
            raise ValueError("recommendation plan is invalid")
        try:
            return await self._observe(plan)
        except RecommendationExecutionError:
            raise
        except Exception as exception:
            raise RecommendationExecutionError(
                "RECOMMENDATION_OBSERVATION_ASSEMBLY_FAILED"
            ) from exception

    async def _observe(self, plan: QueryPlan) -> CapabilityResult:
        if plan.recommendation_mode == "CRITERIA":
            return await self._observe_criteria(plan)
        clarification = _criteria_clarification(plan)
        if clarification is not None:
            return CapabilityResult([], [clarification], "unavailable")
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
        if len(plan.lifestyle_themes) > 3:
            return CapabilityResult(
                [], ["생활조건은 교통·학생·영유아·쇼핑 중 최대 3개로 지정해 주세요."],
                "unavailable",
            )
        region_name = plan.region_name
        area = plan.exclusive_area_square_meters
        budget = plan.maximum_budget_ten_thousand_krw
        assert region_name is not None and area is not None and budget is not None
        try:
            cutoff = plan.end_date or await asyncio.to_thread(
                self._repository.latest_trade_date
            )
        except Exception as exception:
            raise RecommendationExecutionError(
                "RECOMMENDATION_PROPERTY_CANDIDATE_FAILED"
            ) from exception
        if cutoff is None:
            return CapabilityResult(
                [], ["추천 기준으로 사용할 전역 최신 거래일을 확인하지 못했습니다."],
                "unavailable",
            )
        start_date = cutoff - timedelta(days=364)
        try:
            observations = await asyncio.to_thread(
                self._repository.recommendation_candidates,
                region_name,
                start_date,
                cutoff,
                area,
                _CRITERIA_CANDIDATE_LIMIT,
            )
        except Exception as exception:
            raise RecommendationExecutionError(
                "RECOMMENDATION_PROPERTY_CANDIDATE_FAILED"
            ) from exception
        if observations is None:
            return CapabilityResult(
                [],
                ["지역을 하나로 식별하지 못했습니다. 시·도와 시·군·구를 함께 입력해 주세요."],
                "unavailable",
            )
        if len(observations) > _CRITERIA_CANDIDATE_LIMIT:
            raise ValueError("recommendation candidate cap was exceeded")
        budget_policy = RecommendationPolicy(
            maximum_budget_ten_thousand_krw=budget,
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
            and (
                plan.minimum_unit_count is None
                or record.unit_count is not None
                and record.unit_count >= plan.minimum_unit_count
            )
            and budget_policy.is_budget_qualified(bases[complex_id])
        )
        if not qualified:
            closest = tuple(sorted(
                (
                    (record, bases[complex_id])
                    for complex_id, (record, _) in observations.items()
                    if record.marker_safe
                    and record.latitude is not None
                    and record.longitude is not None
                    and bases[complex_id].sample_count == 3
                    and bases[complex_id].median_amount_ten_thousand_krw is not None
                    and (
                        plan.minimum_unit_count is None
                        or record.unit_count is not None
                    )
                ),
                key=lambda item: (
                    int(
                        plan.minimum_unit_count is not None
                        and (item[0].unit_count or 0) < plan.minimum_unit_count
                    )
                    + int(
                        (item[1].median_amount_ten_thousand_krw or 0) > budget
                    ),
                    max(
                        (plan.minimum_unit_count or 0) - (item[0].unit_count or 0),
                        0,
                    ),
                    max(
                        (item[1].median_amount_ten_thousand_krw or 0) - budget,
                        0,
                    ),
                    item[0].complex_id,
                ),
            ))
            if closest:
                return _degraded_recommendation(
                    await self._observe_budget_criteria(
                        plan,
                        closest,
                        region_name,
                        start_date,
                        cutoff,
                        area,
                        budget,
                        near_constraint_mode=True,
                    ),
                    "정확한 예산·세대수 조건을 모두 충족한 단지가 없어 "
                    "조건 차이가 작은 가까운 후보를 표시했습니다.",
                    fallback_step="NEAREST_CONSTRAINT_CANDIDATES",
                )
            scope_fact = _scope_fact(
                region_name=region_name,
                start_date=start_date,
                cutoff=cutoff,
                area=area,
                budget=budget,
                observed_candidate_count=len(observations),
                minimum_unit_count=plan.minimum_unit_count,
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
        if plan.recommendation_criteria:
            return await self._observe_budget_criteria(
                plan, qualified, region_name, start_date, cutoff, area, budget
            )
        if self._rail_repository is None:
            if self._builders is None:
                return _source_unavailable("철도")
            return _degraded_recommendation(
                await self._observe_budget_criteria(
                    plan, qualified, region_name, start_date, cutoff, area, budget
                ),
                "철도 기준을 확인하지 못해 거래 근거로 후보를 정리했습니다.",
            )
        if self._retail_repository is None:
            if self._builders is None:
                return _source_unavailable("대규모점포")
            return _degraded_recommendation(
                await self._observe_budget_criteria(
                    plan, qualified, region_name, start_date, cutoff, area, budget
                ),
                "대규모점포 기준을 확인하지 못해 거래 근거로 후보를 정리했습니다.",
            )
        effective_themes = list(plan.lifestyle_themes)
        degraded_reasons: list[str] = []
        if "STUDENT" in effective_themes and (
            self._school_repository is None or self._academy_repository is None
        ):
            effective_themes.remove("STUDENT")
            degraded_reasons.append("학교 또는 학원 위치 기준을 제외했습니다.")
        if "YOUNG_CHILD" in effective_themes and self._childcare_repository is None:
            effective_themes.remove("YOUNG_CHILD")
            degraded_reasons.append("어린이집 기준을 제외했습니다.")
        policy = RecommendationPolicy(
            maximum_budget_ten_thousand_krw=budget,
            lifestyle_themes=tuple(effective_themes),
        )
        points = tuple(
            CandidatePoint(
                record.complex_id,
                record.latitude,  # type: ignore[arg-type]
                record.longitude,  # type: ignore[arg-type]
                record.region_code,
            )
            for record, _ in qualified
        )
        try:
            rail_results, retail_results = await asyncio.gather(
                _observe_batch(
                    "RECOMMENDATION_RAIL_BATCH_FAILED",
                    self._rail_repository.nearest_batch,
                    points=points,
                    radius_meters=1500,
                ),
                _observe_batch(
                    "RECOMMENDATION_RETAIL_BATCH_FAILED",
                    self._retail_repository.nearest_batch,
                    source_id="retail.large-store",
                    category="LARGE_STORE",
                    points=points,
                    radius_meters=1000,
                ),
            )
        except RecommendationExecutionError:
            return _degraded_recommendation(
                await self._observe_budget_criteria(
                    plan, qualified, region_name, start_date, cutoff, area, budget
                ),
                "일부 생활 인프라 기준을 확인하지 못해 거래 근거로 후보를 정리했습니다.",
            )
        if rail_results is None:
            return _degraded_recommendation(
                await self._observe_budget_criteria(
                    plan, qualified, region_name, start_date, cutoff, area, budget
                ),
                "철도 기준을 확인하지 못해 거래 근거로 후보를 정리했습니다.",
            )
        if retail_results is None or any(
            not retail_coordinate_ready(result) for result in retail_results.values()
        ):
            return _degraded_recommendation(
                await self._observe_budget_criteria(
                    plan, qualified, region_name, start_date, cutoff, area, budget
                ),
                "대규모점포 기준을 확인하지 못해 거래 근거로 후보를 정리했습니다.",
            )
        student_results = None
        academy_results = None
        if "STUDENT" in effective_themes:
            assert self._school_repository is not None
            assert self._academy_repository is not None
            try:
                student_results, academy_results = await asyncio.gather(
                    asyncio.to_thread(
                        self._school_repository.nearest_by_level_batch,
                        points=points, school_levels=plan.school_levels,
                        radius_meters=1500,
                    ),
                    asyncio.to_thread(
                        self._academy_repository.nearby_counts_batch,
                        points=points, radius_meters=800,
                    ),
                )
            except Exception:
                student_results = None
                academy_results = None
            if student_results is None or academy_results is None:
                degraded_reasons.append("학교 또는 학원 위치 기준을 제외했습니다.")
                student_results = None
                academy_results = None
                school_by_complex = {}
            else:
                snapshot, school_by_complex = student_results
                school_age = (self._today() - snapshot.source_date).days
                if school_age < 0 or school_age > 214 or any(
                    (self._today() - result.observed_at.date()).days < 0
                    or (self._today() - result.observed_at.date()).days > result.freshness_days
                    or result.coordinate_coverage < 0.95
                    for result in academy_results.values()
                ):
                    degraded_reasons.append("학교 또는 학원 위치 기준을 제외했습니다.")
                    student_results = None
                    academy_results = None
                    school_by_complex = {}
        else:
            school_by_complex = {}
        childcare_results = None
        if "YOUNG_CHILD" in effective_themes:
            assert self._childcare_repository is not None
            try:
                childcare_results = await asyncio.to_thread(
                    self._childcare_repository.nearby_batch,
                    points=points, radius_meters=800,
                )
            except Exception:
                childcare_results = None
            if childcare_results is None or any(
                result.coordinate_coverage is None
                or result.coordinate_coverage < 0.9
                or (self._today() - result.observed_at.date()).days < 0
                or (self._today() - result.observed_at.date()).days > result.freshness_days
                for result in childcare_results.values()
            ):
                degraded_reasons.append("어린이집 기준을 제외했습니다.")
                childcare_results = None
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
                student_score_ratio=(
                    student_ratio(
                        school_by_complex[record.complex_id],
                        academy_results[record.complex_id], plan.school_levels,
                    )
                    if academy_results is not None else None
                ),
                young_child_score_ratio=(
                    childcare_ratio(childcare_results[record.complex_id])
                    if childcare_results is not None else None
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
                record.complex_id, rail_result,
                result.breakdown.rail_weight, result.breakdown.rail_points,
            )
            retail_fact = _retail_fact(
                record.complex_id, retail_result,
                result.breakdown.retail_weight, result.breakdown.retail_points,
            )
            extra_facts: list[EvidenceFact] = []
            extra_scores: list[RecommendationScoreItem] = []
            if student_results is not None and academy_results is not None:
                student_fact = student_observation_fact(
                    record.complex_id, school_by_complex[record.complex_id],
                    student_results[0], academy_results[record.complex_id],
                    plan.school_levels, result.breakdown.student_weight,
                    result.breakdown.student_points,
                )
                extra_facts.append(student_fact)
                extra_scores.append(RecommendationScoreItem(
                    "STUDENT", "학생 조건", result.breakdown.student_weight,
                    result.breakdown.student_points, None, (student_fact.fact_id,),
                    student_details(
                        school_by_complex[record.complex_id],
                        academy_results[record.complex_id], plan.school_levels,
                    ),
                ))
            if childcare_results is not None:
                childcare_fact = childcare_observation_fact(
                    record.complex_id, childcare_results[record.complex_id],
                    result.breakdown.young_child_weight,
                    result.breakdown.young_child_points,
                )
                extra_facts.append(childcare_fact)
                extra_scores.append(RecommendationScoreItem(
                    "YOUNG_CHILD", "영유아 조건",
                    result.breakdown.young_child_weight,
                    result.breakdown.young_child_points, None,
                    (childcare_fact.fact_id,),
                    childcare_details(childcare_results[record.complex_id]),
                ))
            facts.extend((complex_fact, trade_fact, rail_fact, retail_fact, *extra_facts))
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
                        "TRANSIT", "철도 접근성", result.breakdown.rail_weight,
                        result.breakdown.rail_points,
                        candidate.rail_distance_meters, (rail_fact.fact_id,),
                    ),
                    RecommendationScoreItem(
                        "SHOPPING", "대규모점포 접근성", result.breakdown.retail_weight,
                        result.breakdown.retail_points,
                        candidate.retail_distance_meters, (retail_fact.fact_id,),
                    ), *extra_scores,
                ),
                limitations=(
                    "최근 365일 동일 면적 거래 3건과 단지 좌표 기준 직선거리입니다.",
                    "예산을 통과한 후보는 가격 점수가 모두 같으며 저렴할수록 가산하지 않습니다.",
                    "대규모점포는 전국 공식 원장 중 좌표가 확인된 범위만 반영했습니다.",
                ),
                fact_ids=(
                    complex_fact.fact_id, trade_fact.fact_id,
                    rail_fact.fact_id, retail_fact.fact_id,
                    *(fact.fact_id for fact in extra_facts),
                ),
                active_themes=tuple(effective_themes),
            ))
        artifact = RecommendationCardsArtifact(
            artifact_id=(
                f"recommendation-{cutoff.isoformat()}-"
                f"{area:g}-{budget}"
            ),
            cards=tuple(cards),
        ).to_public_dict()
        artifact_fact_ids = tuple(dict.fromkeys(_fact_ids(artifact)))
        score = ranked[0].breakdown
        return CapabilityResult(
            _deduplicate_facts(facts),
            [
                f"{region_name} 및 하위 지역에서 {start_date.isoformat()}부터 "
                f"{cutoff.isoformat()}까지 전용면적 "
                f"{area:g}㎡ ±1.0㎡의 최근 거래 "
                "3건을 기준으로 예산을 먼저 적용했습니다.",
                f"조건 충족도 배점은 가격 60점, 철도 {score.rail_weight:g}점, "
                f"대규모점포 {score.retail_weight:g}점, 학생 {score.student_weight:g}점, "
                f"영유아 {score.young_child_weight:g}점입니다.",
                "예산은 후보를 먼저 거르는 조건이며 통과 후보에 추가 가격 가산점이 없습니다.",
                "이 결과는 미래가격·수익성·투자 가치를 평가하지 않습니다.",
                "대규모점포는 전국 공식 원장 중 좌표가 확인된 범위만 반영했습니다.",
                *degraded_reasons,
            ],
            "partial" if degraded_reasons else "supported",
            artifacts=(artifact,),
            artifact_fact_ids=artifact_fact_ids,
            state=("DEGRADED" if degraded_reasons else "EXACT"),
            fallback_steps=("PARTIAL_RECOMMENDATION_METRICS",) if degraded_reasons else (),
        )

    async def _observe_budget_criteria(
        self,
        plan: QueryPlan,
        qualified: tuple[tuple[ComplexRecord, RecentThreeTradeBasis], ...],
        region_name: str,
        start_date: date,
        cutoff: date,
        area: float,
        budget: int,
        near_constraint_mode: bool = False,
    ) -> CapabilityResult:
        points = tuple(
            CandidatePoint(
                record.complex_id,
                record.latitude,  # type: ignore[arg-type]
                record.longitude,  # type: ignore[arg-type]
                record.region_code,
            )
            for record, _ in qualified
        )
        observations = await self._criteria_observations(
            plan.recommendation_criteria, points, plan.school_levels
        )
        if isinstance(observations, CapabilityResult):
            return observations
        metrics_by_complex, metric_facts, unavailable_criteria = observations
        available_criteria = tuple(
            key for key in plan.recommendation_criteria
            if key not in unavailable_criteria
        )
        available_order = tuple(
            key for key in plan.criteria_order if key in available_criteria
        )
        candidates = tuple(
            CriteriaRecommendationCandidate(record, metrics_by_complex[record.complex_id])
            for record, _ in qualified
        )
        basis_by_complex = {basis.complex_id: basis for _, basis in qualified}
        if available_criteria:
            ranked = CriteriaRecommendationPolicy(
                minimum_unit_count=plan.minimum_unit_count,
                criteria=available_criteria,
                criteria_order=available_order,
            ).rank(candidates)[: plan.limit]
        else:
            ranked = tuple(sorted(
                candidates,
                key=lambda candidate: (
                    basis_by_complex[candidate.complex_record.complex_id]
                    .median_amount_ten_thousand_krw or 2_147_483_647,
                    candidate.complex_record.complex_id,
                ),
            ))[: plan.limit]
        scope = CriteriaCandidateScope(
            region_name, tuple(record for record, _ in qualified)
        )
        property_as_of = min(record.data_updated_at.date() for record, _ in qualified)
        scope_fact = _criteria_scope_fact(plan, scope, len(qualified), property_as_of)
        facts: list[EvidenceFact] = [scope_fact]
        rows: list[CriteriaRecommendationRow] = []
        for order, candidate in enumerate(ranked, start=1):
            record = candidate.complex_record
            complex_fact = self._builders.complex_fact(record)
            trade_fact = _trade_basis_fact(basis_by_complex[record.complex_id], budget)
            metric_ids = tuple(
                fact_id
                for key in available_criteria
                for fact_id in candidate.metrics[key].fact_ids
            )
            row_fact_ids = tuple(dict.fromkeys((
                complex_fact.fact_id, trade_fact.fact_id, *metric_ids,
            )))
            facts.extend((complex_fact, trade_fact))
            facts.extend(
                metric_facts[(record.complex_id, key)]
                for key in available_criteria
            )
            rows.append(CriteriaRecommendationRow(
                order, record.complex_id, record.display_name, record.unit_count,
                candidate.metrics, row_fact_ids,
            ))
        artifact = RecommendationTableArtifact(
            artifact_id=(
                f"criteria-budget-{cutoff.isoformat()}-{area:g}-{budget}-"
                f"{_scope_token(region_name)}-{'-'.join(plan.criteria_order)}"
            ),
            scope_type="ADMIN_REGION",
            scope_label=region_name,
            criteria_order=available_order,
            minimum_unit_count=plan.minimum_unit_count,
            radius_meters=800 if "ACADEMY" in available_criteria else None,
            rows=tuple(rows),
        ).to_public_dict()
        return CapabilityResult(
            _deduplicate_facts(facts),
            [
                (
                    f"{start_date.isoformat()}부터 {cutoff.isoformat()}까지 전용면적 "
                    f"{area:g}㎡ ±1.0㎡ 최근 거래 3건을 기준으로, 정확 조건과 "
                    "차이가 작은 후보를 정리했습니다."
                    if near_constraint_mode
                    else f"{start_date.isoformat()}부터 {cutoff.isoformat()}까지 "
                    f"전용면적 {area:g}㎡ ±1.0㎡ 최근 거래 3건과 예산 조건을 "
                    "먼저 적용했습니다."
                ),
                (
                    "가까운 후보를 대상으로 확인 가능한 조건을 비교했습니다."
                    if near_constraint_mode
                    else "예산 통과 후보를 대상으로 단지 중심 800m 내 "
                    "학원 위치 관찰값을 비교했습니다."
                ),
                *(
                    [
                        "일부 기준 자료를 확인하지 못해 거래 근거와 나머지 기준으로 후보를 정리했습니다: "
                        + ", ".join(_criterion_label(key) for key in unavailable_criteria)
                    ]
                    if unavailable_criteria else []
                ),
            ],
            "partial" if unavailable_criteria else "supported",
            artifacts=(artifact,),
            artifact_fact_ids=tuple(dict.fromkeys(_fact_ids(artifact))),
            state=("DEGRADED" if unavailable_criteria else "EXACT"),
            fallback_steps=("PARTIAL_RECOMMENDATION_METRICS",) if unavailable_criteria else (),
        )

    async def _observe_criteria(self, plan: QueryPlan) -> CapabilityResult:
        clarification = _criteria_clarification(plan)
        if clarification is not None:
            return CapabilityResult([], [clarification], "unavailable")
        station_scope_fact = None
        area_bases: dict[int, RecentThreeTradeBasis] = {}
        area_basis_limitation: str | None = None
        if plan.station_name is not None:
            if self._rail_repository is None:
                return _source_unavailable("철도")
            resolution = await asyncio.to_thread(
                self._rail_repository.resolve_station, plan.station_name
            )
            if resolution is None:
                return CapabilityResult(
                    [], ["해당 역을 active 철도 기준에서 확인하지 못했습니다."], "unavailable"
                )
            if (
                len(resolution.matches) != 1
                or resolution.coordinate_coverage < 0.95
                or not 0 <= (self._today() - resolution.source_date).days
                <= resolution.freshness_days
            ):
                return CapabilityResult(
                    [],
                    ["동명 역을 하나로 식별하려면 노선 또는 시·군·구를 함께 알려주세요."],
                    "unavailable",
                )
            match = resolution.matches[0]
            assert plan.radius_meters is not None
            station_candidates = await asyncio.to_thread(
                self._repository.criteria_candidates_near_point,
                match.latitude,
                match.longitude,
                plan.radius_meters,
                _CRITERIA_CANDIDATE_LIMIT,
            )
            scope = CriteriaCandidateScope(
                f"{match.station_name}역 직선거리 {plan.radius_meters}m",
                station_candidates,
            )
            station_scope_fact = _station_scope_fact(plan, resolution, match)
            scope_type = "STATION_RADIUS"
        else:
            if plan.region_name is None:
                return CapabilityResult(
                    [], ["조건 기반 후보를 확인할 지역을 알려주세요."], "unavailable"
                )
            scope = None
            if plan.exclusive_area_square_meters is not None:
                try:
                    cutoff = await asyncio.to_thread(self._repository.latest_trade_date)
                    if cutoff is not None:
                        area_start = cutoff - timedelta(days=364)
                        area_observations = await asyncio.to_thread(
                            self._repository.recommendation_candidates,
                            plan.region_name,
                            area_start,
                            cutoff,
                            plan.exclusive_area_square_meters,
                            _CRITERIA_CANDIDATE_LIMIT,
                        )
                        if area_observations is None:
                            return CapabilityResult(
                                [],
                                ["지역을 하나로 식별하지 못했습니다. 시·도와 시·군·구를 함께 입력해 주세요."],
                                "unavailable",
                            )
                        if area_observations:
                            area_bases = {
                                complex_id: RecentThreeTradeBasis.from_trades(
                                    complex_id=complex_id,
                                    cutoff=cutoff,
                                    exclusive_area_square_meters=plan.exclusive_area_square_meters,
                                    trades=trades,
                                )
                                for complex_id, (_, trades) in area_observations.items()
                            }
                            scope = CriteriaCandidateScope(
                                plan.region_name,
                                tuple(record for record, _ in area_observations.values()),
                            )
                        else:
                            area_basis_limitation = (
                                f"최근 1년 전용면적 {plan.exclusive_area_square_meters:g}㎡ ±1.0㎡ "
                                "거래 3건이 확인되는 단지가 없어 면적을 순위에서 제외했습니다."
                            )
                    else:
                        area_basis_limitation = (
                            "최신 거래 기준일을 확인하지 못해 면적을 순위에서 제외했습니다."
                        )
                except Exception:
                    area_basis_limitation = (
                        "같은 면적의 최근 거래를 확인하지 못해 면적을 순위에서 제외했습니다."
                    )
            if scope is None:
                scope = await asyncio.to_thread(
                    self._repository.criteria_candidates,
                    plan.region_name,
                    _CRITERIA_CANDIDATE_LIMIT,
                )
            scope_type = "ADMIN_REGION"
        if scope is None:
            return CapabilityResult(
                [],
                ["지역을 하나로 식별하지 못했습니다. 시·도와 시·군·구를 함께 입력해 주세요."],
                "unavailable",
            )
        eligible = tuple(
            record
            for record in scope.candidates
            if record.marker_safe
            and record.latitude is not None
            and record.longitude is not None
            and (
                plan.minimum_unit_count is None
                or record.unit_count is not None
                and record.unit_count >= plan.minimum_unit_count
            )
        )
        near_candidate_mode = False
        rankable_records = eligible
        if not rankable_records and plan.minimum_unit_count is not None:
            rankable_records = tuple(
                sorted(
                    (
                        record
                        for record in scope.candidates
                        if record.marker_safe
                        and record.latitude is not None
                        and record.longitude is not None
                        and record.unit_count is not None
                    ),
                    key=lambda record: (
                        max(plan.minimum_unit_count - record.unit_count, 0),
                        record.complex_id,
                    ),
                )
            )
            near_candidate_mode = bool(rankable_records)
        property_as_of = min(
            (record.data_updated_at.date() for record in scope.candidates),
            default=self._today(),
        )
        scope_fact = _criteria_scope_fact(plan, scope, len(eligible), property_as_of)
        if not rankable_records:
            return CapabilityResult(
                [scope_fact, *([station_scope_fact] if station_scope_fact else [])],
                [
                    "요청한 범위와 조건을 모두 적용했지만 확인된 후보가 없습니다.",
                    "세대수 기준이나 지역 범위를 조정해 다시 확인할 수 있습니다.",
                ],
                "supported",
            )
        points = tuple(
            CandidatePoint(
                record.complex_id,
                record.latitude,  # type: ignore[arg-type]
                record.longitude,  # type: ignore[arg-type]
                record.region_code,
            )
            for record in rankable_records
        )
        observations = await self._criteria_observations(
            plan.recommendation_criteria, points, plan.school_levels
        )
        if isinstance(observations, CapabilityResult):
            return observations
        metrics_by_complex, metric_facts, unavailable_criteria = observations
        available_criteria = tuple(
            key for key in plan.recommendation_criteria
            if key not in unavailable_criteria
        )
        available_order = tuple(
            key for key in plan.criteria_order if key in available_criteria
        )
        if len(available_criteria) > 1 and set(available_order) != set(available_criteria):
            available_order = available_criteria
        candidates = tuple(
            CriteriaRecommendationCandidate(
                complex_record=record,
                metrics=metrics_by_complex[record.complex_id],
            )
            for record in rankable_records
        )
        if near_candidate_mode:
            criterion_order = {
                candidate.complex_record.complex_id: index
                for index, candidate in enumerate(
                    (
                        CriteriaRecommendationPolicy(
                            minimum_unit_count=None,
                            criteria=available_criteria,
                            criteria_order=available_order,
                        ).rank(candidates)
                        if available_criteria
                        else candidates
                    )
                )
            }
            ranked = tuple(sorted(
                candidates,
                key=lambda candidate: (
                    max(
                        (plan.minimum_unit_count or 0)
                        - (candidate.complex_record.unit_count or 0),
                        0,
                    ),
                    criterion_order[candidate.complex_record.complex_id],
                    candidate.complex_record.complex_id,
                ),
            ))[: plan.limit]
        elif available_criteria or plan.minimum_unit_count is not None:
            policy = CriteriaRecommendationPolicy(
                minimum_unit_count=plan.minimum_unit_count,
                criteria=available_criteria,
                criteria_order=available_order,
            )
            ranked = policy.rank(candidates)[: plan.limit]
        else:
            ranked = tuple(sorted(
                candidates,
                key=lambda candidate: (
                    candidate.complex_record.unit_count is None,
                    -(candidate.complex_record.unit_count or 0),
                    candidate.complex_record.use_date is None,
                    -(
                        candidate.complex_record.use_date.toordinal()
                        if candidate.complex_record.use_date is not None else 0
                    ),
                    candidate.complex_record.complex_id,
                ),
            ))[: plan.limit]
        facts: list[EvidenceFact] = [scope_fact]
        if station_scope_fact is not None:
            facts.append(station_scope_fact)
        rows: list[CriteriaRecommendationRow] = []
        for order, candidate in enumerate(ranked, start=1):
            complex_fact = self._builders.complex_fact(candidate.complex_record)
            area_basis = area_bases.get(candidate.complex_record.complex_id)
            area_fact = _criteria_area_trade_fact(area_basis) if area_basis is not None else None
            selected_facts = tuple(
                fact_id
                for key in available_criteria
                for fact_id in candidate.metrics[key].fact_ids
            )
            row_fact_ids = tuple(dict.fromkeys((
                complex_fact.fact_id,
                *((area_fact.fact_id,) if area_fact is not None else ()),
                *selected_facts,
            )))
            facts.append(complex_fact)
            if area_fact is not None:
                facts.append(area_fact)
            facts.extend(
                metric_facts[(candidate.complex_record.complex_id, key)]
                for key in available_criteria
            )
            rows.append(CriteriaRecommendationRow(
                order=order,
                complex_id=candidate.complex_record.complex_id,
                complex_name=candidate.complex_record.display_name,
                unit_count=candidate.complex_record.unit_count,
                metrics=candidate.metrics,
                fact_ids=row_fact_ids,
            ))
        artifact = RecommendationTableArtifact(
            artifact_id=(
                f"criteria-recommendation-{self._today().isoformat()}-"
                f"{_scope_token(scope.scope_label)}-{plan.minimum_unit_count or 0}-"
                f"{'-'.join(plan.criteria_order) or 'units'}"
            )[:200],
            scope_type=scope_type,  # type: ignore[arg-type]
            scope_label=scope.scope_label,
            criteria_order=available_order,
            minimum_unit_count=plan.minimum_unit_count,
            radius_meters=(
                plan.radius_meters
                if scope_type == "STATION_RADIUS"
                else 800 if "ACADEMY" in available_criteria else None
            ),
            rows=tuple(rows),
        ).to_public_dict()
        return CapabilityResult(
            _deduplicate_facts(facts),
            [
                f"{scope.scope_label}에서 요청한 조건을 적용한 후보를 정리했습니다.",
                "학원 접근성은 단지 중심 800m 내 위치 관찰값으로 비교했습니다."
                if "ACADEMY" in available_criteria
                else "단지 규모와 확인 가능한 기본정보를 기준으로 먼저 살펴볼 후보를 정리했습니다.",
                *(
                    ["대규모점포는 전국 공식 원장 중 좌표가 확인된 범위만 반영했습니다."]
                    if "SHOPPING" in available_criteria
                    else []
                ),
                *(
                    [
                        "일부 기준 자료를 확인하지 못해 나머지 기준과 단지 정보로 후보를 정리했습니다: "
                        + ", ".join(_criterion_label(key) for key in unavailable_criteria)
                    ]
                    if unavailable_criteria else []
                ),
                *([area_basis_limitation] if area_basis_limitation else []),
                *(
                    [
                        "정확한 세대수 조건을 충족한 단지가 없어 기준 차이가 작은 가까운 후보를 별도로 표시했습니다."
                    ]
                    if near_candidate_mode else []
                ),
            ],
            "partial" if unavailable_criteria or near_candidate_mode or area_basis_limitation else "supported",
            artifacts=(artifact,),
            artifact_fact_ids=tuple(dict.fromkeys(_fact_ids(artifact))),
            state=(
                "DEGRADED"
                if unavailable_criteria or near_candidate_mode or area_basis_limitation
                else "EXACT"
            ),
            fallback_steps=tuple(
                step
                for condition, step in (
                    (bool(unavailable_criteria), "PARTIAL_RECOMMENDATION_METRICS"),
                    (near_candidate_mode, "NEAREST_CONSTRAINT_CANDIDATES"),
                    (bool(area_basis_limitation), "AREA_TRADE_BASIS_UNAVAILABLE"),
                )
                if condition
            ),
        )

    async def _criteria_observations(
        self,
        criteria: tuple[str, ...],
        points: tuple[CandidatePoint, ...],
        school_levels: tuple[str, ...],
    ) -> (
        tuple[
            dict[int, dict[str, RecommendationMetric]],
            dict[tuple[int, str], EvidenceFact],
            tuple[str, ...],
        ]
        | CapabilityResult
    ):
        operations = []
        keys = []
        for key in criteria:
            if key == "ACADEMY":
                if self._academy_repository is None:
                    operations.append(_unavailable_observation())
                else:
                    operations.append(self._observe_criterion_batches(
                        key, points, school_levels
                    ))
            elif key == "TRANSIT":
                if self._rail_repository is None:
                    operations.append(_unavailable_observation())
                else:
                    operations.append(self._observe_criterion_batches(
                        key, points, school_levels
                    ))
            elif key == "SHOPPING":
                if self._retail_repository is None:
                    operations.append(_unavailable_observation())
                else:
                    operations.append(self._observe_criterion_batches(
                        key, points, school_levels
                    ))
            elif key == "SCHOOL":
                if self._school_repository is None:
                    operations.append(_unavailable_observation())
                else:
                    operations.append(self._observe_criterion_batches(
                        key, points, school_levels
                    ))
            keys.append(key)
        results = await asyncio.gather(*operations, return_exceptions=True)
        metrics: dict[int, dict[str, RecommendationMetric]] = {
            point.complex_id: {} for point in points
        }
        facts: dict[tuple[int, str], EvidenceFact] = {}
        unavailable: list[str] = []
        for key, result in zip(keys, results, strict=True):
            if isinstance(result, Exception) or result is None:
                unavailable.append(key)
                continue
            if key == "SCHOOL":
                snapshot, result_by_complex = result
                if not 0 <= (self._today() - snapshot.source_date).days <= 214:
                    unavailable.append(key)
                    continue
            else:
                result_by_complex = result
            if key == "ACADEMY" and any(
                item.coordinate_coverage < 0.95
                or not 0 <= (self._today() - item.observed_at.date()).days
                <= item.freshness_days
                for item in result_by_complex.values()
            ):
                unavailable.append(key)
                continue
            if key == "TRANSIT" and any(
                item.coordinate_coverage < 0.95
                or not 0 <= (self._today() - item.source_date).days
                <= item.freshness_days
                for item in result_by_complex.values()
            ):
                unavailable.append(key)
                continue
            if key == "SHOPPING" and any(
                not retail_coordinate_ready(item) for item in result_by_complex.values()
            ):
                unavailable.append(key)
                continue
            missing_item = False
            for point in points:
                item = result_by_complex.get(point.complex_id)
                if item is None:
                    missing_item = True
                    break
                metric, fact = _criteria_metric(
                    key, point.complex_id, item,
                    snapshot if key == "SCHOOL" else None,
                )
                metrics[point.complex_id][key] = metric
                facts[(point.complex_id, key)] = fact
            if missing_item:
                unavailable.append(key)
                for point in points:
                    metrics[point.complex_id].pop(key, None)
                    facts.pop((point.complex_id, key), None)
        if len(unavailable) == len(criteria) and not any(
            point for point in points
        ):
            return _source_unavailable("추천 기준")
        return metrics, facts, tuple(dict.fromkeys(unavailable))

    async def _observe_criterion_batches(
        self,
        key: str,
        points: tuple[CandidatePoint, ...],
        school_levels: tuple[str, ...],
    ) -> object:
        combined: dict[int, object] = {}
        school_snapshot: SchoolSnapshot | None = None
        for offset in range(0, len(points), _REPOSITORY_BATCH_SIZE):
            batch = points[offset:offset + _REPOSITORY_BATCH_SIZE]
            if key == "ACADEMY":
                assert self._academy_repository is not None
                result = await asyncio.to_thread(
                    self._academy_repository.nearby_counts_batch,
                    points=batch,
                    radius_meters=800,
                )
            elif key == "TRANSIT":
                assert self._rail_repository is not None
                result = await asyncio.to_thread(
                    self._rail_repository.nearest_batch,
                    points=batch,
                    radius_meters=1500,
                )
            elif key == "SHOPPING":
                assert self._retail_repository is not None
                result = await asyncio.to_thread(
                    self._retail_repository.nearest_batch,
                    source_id="retail.large-store",
                    category="LARGE_STORE",
                    points=batch,
                    radius_meters=1000,
                )
            elif key == "SCHOOL":
                assert self._school_repository is not None
                school_result = await asyncio.to_thread(
                    self._school_repository.nearest_by_level_batch,
                    points=batch,
                    school_levels=school_levels,
                    radius_meters=1500,
                )
                if school_result is None:
                    return None
                current_snapshot, result = school_result
                if school_snapshot is not None and school_snapshot != current_snapshot:
                    return None
                school_snapshot = current_snapshot
            else:
                return None
            if result is None:
                return None
            combined.update(result)
        if key == "SCHOOL":
            return (school_snapshot, combined) if school_snapshot is not None else None
        return combined


def _criteria_clarification(plan: QueryPlan) -> str | None:
    return {
        "AMBIGUOUS_EDUCATION": "교육 조건은 학교 위치와 학원 접근성 중 사용할 기준을 알려주세요.",
        "MISSING_PRIORITY": "조건이 여러 개입니다. 먼저 볼 조건과 그다음 조건의 순서를 알려주세요.",
        "NUMERIC_CONDITION_MISMATCH": "요청한 숫자 조건을 정확히 확인하지 못했습니다. 세대수 또는 결과 수를 숫자로 다시 알려주세요.",
        "REGION_NOT_CONFIRMED": "현재 질문에서 지역을 확인하지 못했습니다. 시·도와 시·군·구를 함께 알려주세요.",
        "STATION_RADIUS_REQUIRED": "역 주변 범위는 500m·800m·1km 중 원하는 반경을 알려주세요.",
        "STATION_RADIUS_OUT_OF_RANGE": "역 주변 반경은 300m 이상 2,000m 이하로 알려주세요.",
        "UNSUPPORTED_CHILDCARE": "어린이집·유치원 조건은 현재 핵심 추천에서 제외되어 있습니다.",
    }.get(plan.clarification_code)


async def _unavailable_observation() -> None:
    return None


def _criterion_label(key: str) -> str:
    return {
        "ACADEMY": "학원 위치",
        "TRANSIT": "철도",
        "SCHOOL": "학교",
        "SHOPPING": "대규모점포",
    }.get(key, "필요한")


def _criteria_scope_fact(
    plan: QueryPlan,
    scope: CriteriaCandidateScope,
    eligible_count: int,
    observed_on: date,
) -> EvidenceFact:
    claims = [
        FactClaim(scope.scope_label, "TEXT"),
        FactClaim(str(len(scope.candidates)), "OBSERVED_CANDIDATE_COUNT"),
        FactClaim(str(eligible_count), "QUALIFIED_CANDIDATE_COUNT"),
    ]
    if plan.minimum_unit_count is not None:
        claims.append(FactClaim(str(plan.minimum_unit_count), "MINIMUM_HOUSEHOLD_COUNT"))
    return EvidenceFact(
        fact_id=(
            f"criteria-scope-{_scope_token(scope.scope_label)}-{observed_on.isoformat()}-"
            f"{plan.minimum_unit_count or 0}-{len(scope.candidates)}-{eligible_count}"
        ),
        claims=tuple(claims),
        data_as_of=observed_on,
        payload={
            "scopeType": "STATION_RADIUS" if plan.station_name else "ADMIN_REGION",
            "scopeLabel": scope.scope_label,
            "stationName": plan.station_name,
            "radiusMeters": plan.radius_meters if plan.station_name else None,
            "minimumUnitCount": plan.minimum_unit_count,
            "observedCandidateCount": len(scope.candidates),
            "qualifiedCandidateCount": eligible_count,
            "criteriaOrder": list(plan.criteria_order),
        },
        source_id="property.ai_read",
        source_name="Home Search 단지 정보",
        evidence_grade="A",
        dataset_version_value=f"property-{observed_on.isoformat()}",
    )


def _scope_token(label: str) -> str:
    return hashlib.sha256(label.strip().encode("utf-8")).hexdigest()[:12]


def _station_scope_fact(
    plan: QueryPlan,
    resolution: StationScopeResolution,
    match: StationScopeMatch,
) -> EvidenceFact:
    assert plan.radius_meters is not None
    return EvidenceFact(
        fact_id=(
            f"criteria-station-scope-{match.occurrence_ids[0]}-"
            f"{plan.radius_meters}"
        ),
        claims=(
            FactClaim(match.station_name, "TEXT"),
            FactClaim(str(plan.radius_meters), "METERS"),
            *(FactClaim(line, "RAIL_LINE") for line in match.lines),
        ),
        data_as_of=resolution.source_date,
        payload={
            "stationName": match.station_name,
            "lines": list(match.lines),
            "latitude": match.latitude,
            "longitude": match.longitude,
            "radiusMeters": plan.radius_meters,
            "distanceBasis": "STRAIGHT_LINE",
        },
        source_id="transport.rail-station",
        source_name="철도역 위치",
        evidence_grade="A",
        dataset_version_value=resolution.dataset_version,
    )


def _criteria_metric(
    key: str,
    complex_id: int,
    result: object,
    school_snapshot: SchoolSnapshot | None,
) -> tuple[RecommendationMetric, EvidenceFact]:
    if key == "ACADEMY" and isinstance(result, AcademyLocationSearchResult):
        nearest = result.locations[0].distance_meters if result.locations else None
        fact = _criteria_observation_fact(
            key=key,
            complex_id=complex_id,
            value=result.matched_count,
            nearest_distance=nearest,
            radius_meters=800,
            observed_on=result.observed_at.date(),
            source_id="place.sbiz-academy",
            source_name="소상공인시장진흥공단 교육업소 위치",
            dataset_version=result.dataset_version,
        )
        return RecommendationMetric(
            "available", result.matched_count, nearest,
            result.observed_at.date(), (fact.fact_id,),
        ), fact
    if key == "TRANSIT" and isinstance(result, RailStationSearchResult):
        nearest = result.stations[0].distance_meters if result.stations else None
        fact = _criteria_observation_fact(
            key=key,
            complex_id=complex_id,
            value=nearest,
            nearest_distance=nearest,
            radius_meters=1500,
            observed_on=result.source_date,
            source_id="transport.rail-station",
            source_name="철도역 위치",
            dataset_version=result.dataset_version,
        )
        return _distance_metric(nearest, result.source_date, fact, 1500), fact
    if key == "SHOPPING" and isinstance(result, FacilitySearchResult):
        nearest = result.facilities[0].distance_meters if result.facilities else None
        observed_on = _as_date(result.data_as_of)
        fact = _criteria_observation_fact(
            key=key,
            complex_id=complex_id,
            value=nearest,
            nearest_distance=nearest,
            radius_meters=1000,
            observed_on=observed_on,
            source_id="retail.large-store",
            source_name="대규모점포 위치",
            dataset_version=result.dataset_version,
        )
        return _distance_metric(nearest, observed_on, fact, 1000), fact
    if (
        key == "SCHOOL"
        and isinstance(result, SchoolSearchResult)
        and school_snapshot is not None
    ):
        nearest = min(
            (school.distance_meters for school in result.schools), default=None
        )
        fact = _criteria_observation_fact(
            key=key,
            complex_id=complex_id,
            value=nearest,
            nearest_distance=nearest,
            radius_meters=1500,
            observed_on=school_snapshot.source_date,
            source_id="edu.school-location",
            source_name="학교 위치",
            dataset_version=school_snapshot.dataset_version,
        )
        return _distance_metric(nearest, school_snapshot.source_date, fact, 1500), fact
    raise ValueError("criteria observation shape is invalid")


def _distance_metric(
    nearest: int | None,
    observed_on: date,
    fact: EvidenceFact,
    radius_meters: int,
) -> RecommendationMetric:
    if nearest is None:
        return RecommendationMetric(
            "unavailable", None, None, observed_on, (fact.fact_id,),
            f"직선거리 {radius_meters}m 안에서 확인되지 않았습니다.",
        )
    return RecommendationMetric(
        "available", nearest, nearest, observed_on, (fact.fact_id,)
    )


def _criteria_observation_fact(
    *,
    key: str,
    complex_id: int,
    value: int | None,
    nearest_distance: int | None,
    radius_meters: int,
    observed_on: date,
    source_id: str,
    source_name: str,
    dataset_version: str,
) -> EvidenceFact:
    claims = [FactClaim(str(radius_meters), "METERS")]
    if value is not None:
        claims.append(FactClaim(str(value), "COUNT" if key == "ACADEMY" else "METERS"))
    return EvidenceFact(
        fact_id=f"criteria-{key.lower()}-{complex_id}-{observed_on.isoformat()}",
        claims=tuple(claims),
        data_as_of=observed_on,
        payload={
            "complexId": complex_id,
            "criterion": key,
            "value": value,
            "nearestDistanceMeters": nearest_distance,
            "radiusMeters": radius_meters,
        },
        source_id=source_id,
        source_name=source_name,
        evidence_grade="A",
        dataset_version_value=dataset_version,
    )


def _source_unavailable(label: str) -> CapabilityResult:
    return CapabilityResult(
        [], [f"{label} 데이터가 아직 준비되지 않아 조건 충족도를 계산하지 못했습니다."],
        "unavailable",
    )


def _degraded_recommendation(
    result: CapabilityResult,
    reason: str,
    *,
    fallback_step: str = "PARTIAL_RECOMMENDATION_METRICS",
) -> CapabilityResult:
    return CapabilityResult(
        facts=result.facts,
        limitations=[*result.limitations, reason],
        readiness="partial",
        actions=result.actions,
        artifacts=result.artifacts,
        artifact_fact_ids=result.artifact_fact_ids,
        state="DEGRADED",
        assumptions=result.assumptions,
        fallback_steps=tuple(dict.fromkeys((
            *result.fallback_steps,
            fallback_step,
        ))),
        recoverable=result.recoverable,
    )


async def _observe_batch(
    reason_code: str,
    operation: Callable[..., _BatchResult],
    **kwargs: object,
) -> _BatchResult:
    try:
        return await asyncio.to_thread(operation, **kwargs)
    except Exception as exception:
        raise RecommendationExecutionError(reason_code) from exception


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


def _criteria_area_trade_fact(basis: RecentThreeTradeBasis) -> EvidenceFact:
    latest = basis.latest_trade
    median = basis.median_amount_ten_thousand_krw
    if latest is None or median is None or basis.sample_count != 3:
        raise ValueError("criteria recommendation trade basis is invalid")
    return EvidenceFact(
        fact_id=(
            f"criteria-trade-basis-{basis.complex_id}-{basis.cutoff.isoformat()}-"
            f"{basis.exclusive_area_square_meters:g}"
        ),
        claims=(
            FactClaim("3", "COUNT"),
            FactClaim(basis.cutoff.isoformat(), "DATE"),
            FactClaim(f"{basis.exclusive_area_square_meters:g}", "SQUARE_METERS"),
            FactClaim(latest.deal_date.isoformat(), "LATEST_TRADE_DATE"),
            FactClaim(str(latest.deal_amount_ten_thousand_krw), "LATEST_10_000_KRW"),
            FactClaim(str(median), "MEDIAN_10_000_KRW"),
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
    minimum_unit_count: int | None,
) -> EvidenceFact:
    unit_suffix = f"-{minimum_unit_count}" if minimum_unit_count is not None else ""
    return EvidenceFact(
        fact_id=(
            f"recommendation-scope-{cutoff.isoformat()}-{area:g}-{budget}{unit_suffix}"
        ),
        claims=(
            FactClaim(region_name, "TEXT"),
            FactClaim(start_date.isoformat(), "DATE"),
            FactClaim(cutoff.isoformat(), "DATE"),
            FactClaim(f"{area:g}", "SQUARE_METERS"),
            FactClaim(str(budget), "MAXIMUM_10_000_KRW"),
            FactClaim(str(observed_candidate_count), "OBSERVED_CANDIDATE_COUNT"),
            FactClaim("0", "QUALIFIED_CANDIDATE_COUNT"),
            *(
                (FactClaim(str(minimum_unit_count), "MINIMUM_HOUSEHOLD_COUNT"),)
                if minimum_unit_count is not None else ()
            ),
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
            "minimumUnitCount": minimum_unit_count,
        },
    )


def _rail_fact(
    complex_id: int, result: RailStationSearchResult, weight: float, points: float
) -> EvidenceFact:
    station = result.stations[0] if result.stations else None
    claims = [
        FactClaim("1500", "METERS"),
        FactClaim(result.source_date.isoformat(), "DATE"),
        FactClaim(format(weight, ".15g"), "WEIGHT_POINTS"),
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
            "weight": weight,
            "points": points,
        },
        source_id="transport.rail-station",
        source_name="전국 도시철도역사 정보",
        evidence_grade="A",
        dataset_version_value=result.dataset_version,
    )


def _retail_fact(
    complex_id: int, result: FacilitySearchResult, weight: float, points: float
) -> EvidenceFact:
    facility = result.facilities[0] if result.facilities else None
    claims = [
        FactClaim("1000", "METERS"),
        FactClaim(format(weight, ".15g"), "WEIGHT_POINTS"),
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
        data_as_of=_as_date(result.data_as_of),
        payload={
            "complexId": complex_id,
            "radiusMeters": 1000,
            "nearestDistanceMeters": None if facility is None else facility.distance_meters,
            "facilityName": None if facility is None else facility.name,
            "datasetVersion": result.dataset_version,
            "weight": weight,
            "points": points,
        },
        source_id="retail.large-store",
        source_name="전국 대규모점포 인허가 정보",
        evidence_grade="A",
        dataset_version_value=result.dataset_version,
    )


def _as_date(value: date | datetime) -> date:
    return value.date() if isinstance(value, datetime) else value


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
