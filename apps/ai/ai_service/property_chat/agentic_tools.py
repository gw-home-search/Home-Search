from __future__ import annotations

import asyncio
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, timedelta
import hashlib
import math
from typing import Literal
from typing import Protocol

from .agentic import AgentDecision, AgentRecommendationRow, ToolEvidence
from .comparison import CandidatePoint
from .criteria_recommendation import CriteriaCandidateScope
from .models import ComplexRecord, MonthlyTrendRecord, TradeRecord


class AgenticPropertyRepository(Protocol):
    def find_complexes(
        self, name: str, region_name: str | None, limit: int
    ) -> list[ComplexRecord]: ...
    def find_complex_by_id(self, complex_id: int) -> ComplexRecord | None: ...
    def complex_profile(self, complex_id: int) -> Mapping[str, object] | None: ...
    def criteria_candidates(self, region_name: str, limit: int) -> CriteriaCandidateScope | None: ...
    def recommendation_candidates(
        self, region_name: str, start_date: date, end_date: date,
        exclusive_area_square_meters: float, limit: int,
    ) -> dict[int, tuple[ComplexRecord, tuple[TradeRecord, ...]]] | None: ...
    def recent_trades(
        self, complex_id: int, start_date: date | None, end_date: date | None,
        exclusive_area_square_meters: float | None, limit: int,
    ) -> list[TradeRecord]: ...
    def monthly_trends(
        self, complex_id: int, start_date: date, end_date: date,
        exclusive_area_square_meters: float | None,
    ) -> list[MonthlyTrendRecord]: ...
    def latest_trade_date(self) -> date | None: ...
    def latest_trades_for_candidates(
        self, complex_ids: tuple[int, ...], start_date: date, end_date: date,
        exclusive_area_square_meters: float | None,
    ) -> dict[int, TradeRecord | None]: ...


@dataclass(frozen=True)
class VerifiedRecommendationContext:
    question: str
    scope_type: Literal["ADMIN_REGION", "STATION_RADIUS"]
    scope_label: str
    region_name: str | None
    station_name: str | None
    station_lines: tuple[str, ...]
    station_latitude: float | None
    station_longitude: float | None
    station_source_date: date | None
    radius_meters: int | None
    minimum_unit_count: int | None
    maximum_budget_ten_thousand_krw: int | None
    exclusive_area_square_meters: float | None
    criteria_order: tuple[str, ...]
    explicit_criteria: tuple[str, ...]
    school_levels: tuple[str, ...]
    requested_count: int
    area_conversion_note: str | None

    def __post_init__(self) -> None:
        if not 1 <= self.requested_count <= 5 or not self.scope_label.strip():
            raise ValueError("verified recommendation context is invalid")
        if self.scope_type == "ADMIN_REGION":
            if self.region_name is None or any(value is not None for value in (
                self.station_name, self.station_latitude, self.station_longitude,
                self.station_source_date, self.radius_meters,
            )):
                raise ValueError("verified admin scope is invalid")
        elif (
            self.station_name is None or self.station_latitude is None
            or self.station_longitude is None or self.station_source_date is None
            or self.radius_meters is None
        ):
            raise ValueError("verified station scope is invalid")


class PropertyAgentTools:
    def __init__(
        self, repository: AgenticPropertyRepository,
        *, context: VerifiedRecommendationContext | None = None,
        school_repository: object | None = None,
        academy_repository: object | None = None,
        retail_repository: object | None = None,
    ) -> None:
        self._repository = repository
        self._context = context
        self._school_repository = school_repository
        self._academy_repository = academy_repository
        self._retail_repository = retail_repository
        self._verified_pool: ToolEvidence | None = None
        self._verified_records: dict[int, ComplexRecord] = {}
        self._reference_payload_by_id: dict[int, dict[str, object]] | None = None
        self._reference_limitations: tuple[str, ...] = ()

    async def preload_recommendation_evidence(self) -> ToolEvidence:
        pool = await self.execute("get_recommendation_candidate_pool", {"limit": 40})
        requested_sources = {
            criterion for criterion in (self._context.explicit_criteria if self._context else ())
            if criterion in {"SCHOOL", "ACADEMY", "SHOPPING"}
        }
        if not requested_sources or not pool.candidate_ids:
            return pool
        reference = await self._reference_evidence(tuple(sorted(pool.candidate_ids)))
        payload = dict(pool.payload)
        payload["referenceEvidence"] = reference.payload.get("candidates", [])
        payload["limitations"] = reference.payload.get("limitations", [])
        combined = ToolEvidence(
            payload=payload, candidate_ids=pool.candidate_ids,
            candidate_names=pool.candidate_names,
            fact_ids=frozenset((*pool.fact_ids, *reference.fact_ids)),
            scope_label=pool.scope_label, scope_fact_id=pool.scope_fact_id,
        )
        self._verified_pool = combined
        return combined

    async def execute(
        self, name: str, arguments: Mapping[str, object]
    ) -> ToolEvidence:
        if name == "search_complexes":
            return await asyncio.to_thread(
                self._search_complexes, str(arguments["query"]), int(arguments["limit"])
            )
        if name == "get_region_candidate_pool":
            minimum = arguments.get("minimumUnitCount")
            budget = arguments.get("maximumBudgetTenThousandKrw")
            area = arguments.get("exclusiveAreaSquareMeters")
            return await asyncio.to_thread(
                self._candidate_pool, str(arguments["regionName"]),
                int(arguments["limit"]), int(minimum) if minimum is not None else None,
                int(budget) if budget is not None else None,
                float(area) if area is not None else None,
            )
        if name == "get_recommendation_candidate_pool":
            if set(arguments) != {"limit"}:
                raise ValueError("invalid candidate pool arguments")
            limit = arguments["limit"]
            if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 40:
                raise ValueError("invalid candidate pool arguments")
            if self._context is None:
                raise ValueError("verified recommendation context is required")
            return await asyncio.to_thread(self._context_candidate_pool, limit)
        if name == "get_complex_profile":
            return await asyncio.to_thread(self._profile, int(arguments["complexId"]))
        if name == "get_recent_trades":
            return await asyncio.to_thread(self._recent_trades, int(arguments["complexId"]))
        if name == "get_price_trend":
            return await asyncio.to_thread(self._price_trend, int(arguments["complexId"]))
        if name == "get_candidate_evidence":
            return await self._candidate_evidence(tuple(int(value) for value in arguments["complexIds"]))  # type: ignore[union-attr]
        if name == "get_reference_evidence":
            ids = tuple(int(value) for value in arguments["complexIds"])  # type: ignore[union-attr]
            return await self._reference_evidence(ids)
        raise ValueError("unsupported agent tool")

    async def _reference_evidence(self, complex_ids: tuple[int, ...]) -> ToolEvidence:
        if self._context is None:
            return ToolEvidence(
                payload={"candidates": [
                    {"complexId": complex_id, "availability": "unavailable"}
                    for complex_id in complex_ids
                ], "limitation": "공식 교통·생활 인프라 근거가 현재 snapshot에 없습니다."},
                candidate_ids=frozenset(complex_ids), fact_ids=frozenset(),
            )
        if self._reference_payload_by_id is not None:
            if not set(complex_ids).issubset(self._reference_payload_by_id):
                raise ValueError("candidate no longer exists")
            selected = [self._reference_payload_by_id[complex_id] for complex_id in complex_ids]
            return ToolEvidence(
                payload={"candidates": selected, "limitations": list(self._reference_limitations)},
                candidate_ids=frozenset(complex_ids),
                candidate_names={
                    complex_id: self._verified_records[complex_id].display_name
                    for complex_id in complex_ids
                },
                fact_ids=frozenset(
                    fact_id for payload in selected for fact_id in _payload_fact_ids(payload)
                ),
            )
        records = [
            self._verified_records.get(complex_id)
            or self._repository.find_complex_by_id(complex_id)
            for complex_id in complex_ids
        ]
        if any(
            record is None or record.latitude is None or record.longitude is None
            for record in records
        ):
            raise ValueError("candidate no longer exists")
        points = tuple(CandidatePoint(
            record.complex_id, record.latitude, record.longitude, record.region_code,
        ) for record in records if record is not None)  # type: ignore[arg-type]
        payload_by_id: dict[int, dict[str, object]] = {
            complex_id: {"complexId": complex_id, "observations": []}
            for complex_id in complex_ids
        }
        fact_ids: set[str] = set()
        limitations: list[str] = []
        for criterion in self._context.explicit_criteria:
            if criterion == "TRANSIT":
                # Station evidence is bound to the verified candidate-pool scope.
                continue
            try:
                if criterion == "SCHOOL" and self._school_repository is not None:
                    result = await asyncio.to_thread(
                        self._school_repository.nearest_by_level_batch,  # type: ignore[attr-defined]
                        points=points, school_levels=self._context.school_levels,
                        radius_meters=1500,
                    )
                    if result is None:
                        raise ValueError
                    snapshot, by_complex = result
                    if not 0 <= (date.today() - snapshot.source_date).days <= 214:
                        raise ValueError
                    for complex_id in complex_ids:
                        search = by_complex[complex_id]
                        school = min(search.schools, key=lambda item: item.distance_meters, default=None)
                        fact_id = f"school-observation:{complex_id}:{snapshot.source_date.isoformat()}"
                        observation = {
                            "criterion": "SCHOOL", "matchedCount": search.matched_count,
                            "sourceDate": snapshot.source_date.isoformat(), "factId": fact_id,
                        }
                        if school is not None:
                            observation["nearest"] = {
                                "name": school.school_name, "level": school.school_level,
                                "operatingStatus": school.operating_status,
                                "distanceMeters": school.distance_meters,
                            }
                        payload_by_id[complex_id]["observations"].append(observation)  # type: ignore[union-attr]
                        fact_ids.add(fact_id)
                elif criterion == "ACADEMY" and self._academy_repository is not None:
                    by_complex = await asyncio.to_thread(
                        self._academy_repository.nearby_counts_batch,  # type: ignore[attr-defined]
                        points=points, radius_meters=800,
                    )
                    if by_complex is None or any(
                        item.coordinate_coverage < 0.95
                        or not 0 <= (date.today() - item.observed_at.date()).days
                        <= item.freshness_days
                        for item in by_complex.values()
                    ):
                        raise ValueError
                    for complex_id in complex_ids:
                        item = by_complex[complex_id]
                        fact_id = f"academy-observation:{complex_id}:{item.observed_at.date().isoformat()}"
                        payload_by_id[complex_id]["observations"].append({  # type: ignore[union-attr]
                            "criterion": "ACADEMY", "matchedCount": item.matched_count,
                            "nearestDistanceMeters": (
                                item.locations[0].distance_meters if item.locations else None
                            ),
                            "sourceDate": item.observed_at.date().isoformat(),
                            "factId": fact_id,
                        })
                        fact_ids.add(fact_id)
                elif criterion == "SHOPPING" and self._retail_repository is not None:
                    by_complex = await asyncio.to_thread(
                        self._retail_repository.nearest_batch,  # type: ignore[attr-defined]
                        source_id="retail.large-store", category="LARGE_STORE",
                        points=points, radius_meters=1000,
                    )
                    if by_complex is None:
                        raise ValueError
                    for complex_id in complex_ids:
                        item = by_complex[complex_id]
                        nearest = min(
                            item.facilities, key=lambda facility: facility.distance_meters,
                            default=None,
                        )
                        fact_id = f"shopping-observation:{complex_id}:{item.dataset_version}"
                        observation = {
                            "criterion": "SHOPPING", "matchedCount": item.matched_count,
                            "dataAsOf": str(item.data_as_of), "factId": fact_id,
                        }
                        if nearest is not None:
                            observation["nearest"] = {
                                "name": nearest.name, "subcategory": nearest.subcategory,
                                "distanceMeters": nearest.distance_meters,
                            }
                        payload_by_id[complex_id]["observations"].append(observation)  # type: ignore[union-attr]
                        fact_ids.add(fact_id)
                else:
                    raise ValueError
            except Exception:
                limitations.append(f"{criterion} 공식 source를 현재 확인하지 못했습니다.")
        result = ToolEvidence(
            payload={
                "candidates": list(payload_by_id.values()),
                "limitations": list(dict.fromkeys(limitations)),
            },
            candidate_ids=frozenset(complex_ids),
            candidate_names=_candidate_names(self._repository, complex_ids),
            fact_ids=frozenset(fact_ids),
        )
        self._reference_payload_by_id = payload_by_id
        self._reference_limitations = tuple(dict.fromkeys(limitations))
        return result

    def _context_candidate_pool(self, limit: int) -> ToolEvidence:
        if self._verified_pool is not None:
            return self._verified_pool
        assert self._context is not None
        context = self._context
        if context.scope_type == "ADMIN_REGION":
            assert context.region_name is not None
            scope = self._repository.criteria_candidates(context.region_name, 5_000)
            if scope is None:
                return ToolEvidence(payload={"scope": None, "candidates": []})
            records = scope.candidates
        else:
            assert context.station_latitude is not None
            assert context.station_longitude is not None
            assert context.radius_meters is not None
            records = self._repository.criteria_candidates_near_point(  # type: ignore[attr-defined]
                context.station_latitude, context.station_longitude,
                context.radius_meters, 5_000,
            )
        eligible = [
            record for record in records
            if record.marker_safe and record.latitude is not None
            and record.longitude is not None
            and (
                context.minimum_unit_count is None
                or record.unit_count is not None
                and record.unit_count >= context.minimum_unit_count
            )
        ]
        cutoff = self._repository.latest_trade_date() or date.today()
        start_date = cutoff - timedelta(days=364)
        trade_by_complex = (
            self._repository.latest_trades_for_candidates(
                tuple(record.complex_id for record in eligible), start_date, cutoff,
                context.exclusive_area_square_meters,
            )
            if eligible and hasattr(self._repository, "latest_trades_for_candidates")
            else {record.complex_id: None for record in eligible}
        )
        if (
            context.maximum_budget_ten_thousand_krw is not None
            and context.exclusive_area_square_meters is not None
        ):
            eligible = [
                record for record in eligible
                if (trade := trade_by_complex.get(record.complex_id)) is not None
                and trade.deal_amount_ten_thousand_krw
                <= context.maximum_budget_ten_thousand_krw
            ]
        distances = {
            record.complex_id: _distance_meters(
                context.station_latitude, context.station_longitude,
                record.latitude, record.longitude,
            )
            for record in eligible
        } if context.scope_type == "STATION_RADIUS" else {}
        selected = _facet_union(
            eligible, limit=limit, criteria_order=context.criteria_order,
            distances=distances, trade_by_complex=trade_by_complex,
        )
        self._verified_records = {record.complex_id: record for record in selected}
        candidate_payloads = []
        fact_ids: set[str] = set()
        for record in selected:
            payload = _complex_payload(record)
            fact_ids.add(_complex_fact_id(record.complex_id))
            trade = trade_by_complex.get(record.complex_id)
            if trade is None:
                trade_fact_id = (
                    f"trade-window:{record.complex_id}:{start_date.isoformat()}:{cutoff.isoformat()}"
                )
                payload["recentTrade"] = {
                    "availability": "verified_zero", "startDate": start_date.isoformat(),
                    "endDate": cutoff.isoformat(), "factId": trade_fact_id,
                }
            else:
                trade_fact_id = f"trade:{trade.trade_id}"
                payload["recentTrade"] = {
                    "availability": "available", "dealDate": trade.deal_date.isoformat(),
                    "amountTenThousandKrw": trade.deal_amount_ten_thousand_krw,
                    "exclusiveAreaSquareMeters": trade.exclusive_area_square_meters,
                    "floor": trade.floor, "factId": trade_fact_id,
                }
            fact_ids.add(trade_fact_id)
            if context.scope_type == "STATION_RADIUS":
                assert context.station_name is not None
                assert context.radius_meters is not None
                assert context.station_source_date is not None
                distance_fact_id = (
                    f"station-distance:{record.complex_id}:{context.station_name}"
                )
                payload["station"] = {
                    "name": context.station_name,
                    "lines": list(context.station_lines),
                    "distanceMeters": distances[record.complex_id],
                    "radiusMeters": context.radius_meters,
                    "sourceDate": context.station_source_date.isoformat(),
                    "factId": distance_fact_id,
                }
                fact_ids.add(distance_fact_id)
            candidate_payloads.append(payload)
        result = ToolEvidence(
            payload={
                "scope": {
                    "type": context.scope_type, "label": context.scope_label,
                    "factId": _scope_fact_id(context),
                },
                "selection": "FACET_ROUND_ROBIN_NOT_FINAL_RANK",
                "hardFilters": {
                    "minimumUnitCount": context.minimum_unit_count,
                    "maximumBudgetTenThousandKrw": context.maximum_budget_ten_thousand_krw,
                    "exclusiveAreaSquareMeters": context.exclusive_area_square_meters,
                },
                "criteriaOrder": list(context.criteria_order),
                "candidates": candidate_payloads,
            },
            candidate_ids=frozenset(record.complex_id for record in selected),
            candidate_names={record.complex_id: record.display_name for record in selected},
            fact_ids=frozenset((*fact_ids, _scope_fact_id(context))),
            scope_label=context.scope_label, scope_fact_id=_scope_fact_id(context),
        )
        self._verified_pool = result
        return result

    def deterministic_fallback(self) -> AgentDecision | None:
        if self._context is None or self._verified_pool is None:
            return None
        candidates = self._verified_pool.payload.get("candidates")
        if not isinstance(candidates, list) or not candidates:
            return None
        rows = []
        for payload in candidates[:self._context.requested_count]:
            if not isinstance(payload, dict):
                continue
            complex_id = int(payload["complexId"])
            complex_name = str(payload["complexName"])
            complex_fact_id = str(payload["factId"])
            strengths: list[tuple[str, tuple[str, ...]]] = []
            station = payload.get("station")
            if isinstance(station, dict):
                strengths.append((
                    f"{station['name']}역 직선거리 {int(station['distanceMeters']):,}m입니다.",
                    (str(station["factId"]),),
                ))
            if payload.get("unitCount") is not None and payload.get("useDate") is not None:
                strengths.append((
                    f"{int(payload['unitCount']):,}세대이며 사용승인일은 "
                    f"{payload['useDate']}입니다.",
                    (complex_fact_id,),
                ))
            elif payload.get("unitCount") is not None:
                strengths.append((
                    f"{int(payload['unitCount']):,}세대 규모가 확인됩니다.",
                    (complex_fact_id,),
                ))
            elif payload.get("useDate") is not None:
                strengths.append((
                    f"사용승인일은 {payload['useDate']}입니다.", (complex_fact_id,),
                ))
            recent_trade = payload.get("recentTrade")
            if isinstance(recent_trade, dict) and recent_trade.get("availability") == "available":
                strengths.append((
                    "최근 거래 "
                    f"{recent_trade['dealDate']} · 전용 "
                    f"{float(recent_trade['exclusiveAreaSquareMeters']):g}㎡ · "
                    f"{int(recent_trade['amountTenThousandKrw']):,}만원을 확인했습니다.",
                    (str(recent_trade["factId"]),),
                ))
            strengths = strengths[:3] or [
                ("단지 기본정보를 확인할 수 있는 후보입니다.", (complex_fact_id,))
            ]
            if isinstance(recent_trade, dict) and recent_trade.get("availability") == "verified_zero":
                tradeoffs = ((
                    "최근 1년 거래는 확인되지 않아 가격 판단에 사용할 수 없습니다.",
                    (str(recent_trade["factId"]),),
                ),)
            elif (
                self._context.maximum_budget_ten_thousand_krw is None
                or self._context.exclusive_area_square_meters is None
            ):
                tradeoffs = ((
                    "예산과 전용면적이 모두 지정되지 않아 거래금액은 후보 간 순위로 비교하지 않았습니다.",
                    (complex_fact_id,),
                ),)
            else:
                tradeoffs = ((
                    "공식 단지·거래 자료 밖의 주거 품질은 추가 확인이 필요합니다.",
                    (complex_fact_id,),
                ),)
            row_fact_ids = tuple(dict.fromkeys((
                complex_fact_id,
                *(fact_id for _, ids in (*strengths, *tradeoffs) for fact_id in ids),
            )))
            role = _fallback_role(self._context.criteria_order, station, recent_trade)
            rows.append(AgentRecommendationRow(
                complex_id=complex_id, complex_name=complex_name, role=role,
                summary=" ".join(text for text, _ in strengths[:2]),
                strengths=tuple(strengths), tradeoffs=tradeoffs,
                metrics={}, fact_ids=row_fact_ids,
            ))
        fact_ids = tuple(dict.fromkeys((
            _scope_fact_id(self._context),
            *(fact_id for row in rows for fact_id in row.fact_ids),
        )))
        source_limitations = self._verified_pool.payload.get("limitations", [])
        return AgentDecision(
            answer=(
                f"{self._context.scope_label}에서 검증 후보 중 {len(rows)}곳을 확인했습니다. "
                "AI 생성 경로를 완료하지 못해 같은 근거의 고정 규칙으로 후보를 정리했습니다."
            ),
            rows=tuple(rows), fact_ids=fact_ids,
            limitations=tuple(dict.fromkeys((
                "AI provider 결과 대신 검증 근거 기반 fallback을 사용했습니다.",
                *(str(item) for item in source_limitations if isinstance(item, str)),
            ))),
        )

    def _search_complexes(self, query: str, limit: int) -> ToolEvidence:
        records = self._repository.find_complexes(query, None, min(limit, 6))
        facts = {_complex_fact_id(record.complex_id) for record in records}
        return ToolEvidence(
            payload={"matches": [_complex_payload(record) for record in records]},
            candidate_ids=frozenset(record.complex_id for record in records),
            candidate_names={record.complex_id: record.display_name for record in records},
            fact_ids=frozenset(facts),
        )

    def _candidate_pool(
        self, region_name: str, limit: int, minimum_unit_count: int | None,
        maximum_budget: int | None, exclusive_area: float | None,
    ) -> ToolEvidence:
        scope = self._repository.criteria_candidates(region_name, 5_000)
        if scope is None:
            return ToolEvidence(payload={"scope": None, "candidates": []})
        base_candidates = scope.candidates
        if maximum_budget is not None and exclusive_area is not None:
            end = self._repository.latest_trade_date() or date.today()
            budget_candidates = self._repository.recommendation_candidates(
                region_name, end - timedelta(days=365), end, exclusive_area, 5_000
            )
            if budget_candidates is None:
                return ToolEvidence(payload={"scope": None, "candidates": []})
            allowed_ids = {
                record.complex_id
                for record, trades in budget_candidates.values()
                if trades and max(
                    trade.deal_amount_ten_thousand_krw for trade in trades
                ) <= maximum_budget
            }
            base_candidates = tuple(
                record for record in base_candidates
                if record.complex_id in allowed_ids
            )
        qualified = [
            record for record in base_candidates
            if minimum_unit_count is None
            or record.unit_count is not None and record.unit_count >= minimum_unit_count
        ]
        # Facet union only: no final recommendation score or order is assigned here.
        by_scale = sorted(
            qualified, key=lambda record: (-(record.unit_count or -1), record.complex_id)
        )[:limit]
        by_newer = sorted(
            qualified,
            key=lambda record: (-(record.use_date.toordinal() if record.use_date else -1), record.complex_id),
        )[:limit]
        selected: list[ComplexRecord] = []
        for records in (by_scale, by_newer, qualified):
            for record in records:
                if record.complex_id not in {item.complex_id for item in selected}:
                    selected.append(record)
                if len(selected) >= limit:
                    break
            if len(selected) >= limit:
                break
        fact_ids = frozenset(_complex_fact_id(record.complex_id) for record in selected)
        return ToolEvidence(
            payload={
                "scope": {"type": "ADMIN_REGION", "label": scope.scope_label},
                "selection": "FACET_UNION_NOT_FINAL_RANK",
                "hardFilters": {
                    "minimumUnitCount": minimum_unit_count,
                    "maximumBudgetTenThousandKrw": maximum_budget,
                    "exclusiveAreaSquareMeters": exclusive_area,
                },
                "candidates": [_complex_payload(record) for record in selected],
            },
            candidate_ids=frozenset(record.complex_id for record in selected),
            candidate_names={record.complex_id: record.display_name for record in selected},
            fact_ids=fact_ids,
            scope_label=scope.scope_label,
        )

    def _profile(self, complex_id: int) -> ToolEvidence:
        profile = self._repository.complex_profile(complex_id)
        fact_id = f"profile:{complex_id}"
        return ToolEvidence(
            payload={"complexId": complex_id, "profile": profile, "factId": fact_id}
            if profile is not None else {"complexId": complex_id, "profile": None},
            candidate_ids=frozenset({complex_id}),
            candidate_names=_candidate_name(self._repository, complex_id),
            fact_ids=frozenset({fact_id}) if profile is not None else frozenset(),
        )

    def _recent_trades(self, complex_id: int) -> ToolEvidence:
        end = self._repository.latest_trade_date() or date.today()
        trades = self._repository.recent_trades(
            complex_id, end - timedelta(days=365), end, None, 10
        )
        fact_ids = frozenset(f"trade:{trade.trade_id}" for trade in trades)
        return ToolEvidence(
            payload={"complexId": complex_id, "windowDays": 365, "trades": [
                {"dealDate": trade.deal_date.isoformat(),
                 "amountTenThousandKrw": trade.deal_amount_ten_thousand_krw,
                 "exclusiveAreaSquareMeters": trade.exclusive_area_square_meters,
                 "floor": trade.floor, "factId": f"trade:{trade.trade_id}"}
                for trade in trades
            ]},
            candidate_ids=frozenset({complex_id}), fact_ids=fact_ids,
            candidate_names=_candidate_name(self._repository, complex_id),
        )

    def _price_trend(self, complex_id: int) -> ToolEvidence:
        end = self._repository.latest_trade_date() or date.today()
        records = self._repository.monthly_trends(
            complex_id, end - timedelta(days=365), end, None
        )
        fact_ids = frozenset(
            f"trend:{complex_id}:{record.month.isoformat()}" for record in records
        )
        return ToolEvidence(
            payload={"complexId": complex_id, "months": [
                {"month": record.month.isoformat(),
                 "averageAmountTenThousandKrw": record.average_amount_ten_thousand_krw,
                 "tradeCount": record.trade_count,
                 "minimumAmountTenThousandKrw": record.minimum_amount_ten_thousand_krw,
                 "maximumAmountTenThousandKrw": record.maximum_amount_ten_thousand_krw,
                 "factId": f"trend:{complex_id}:{record.month.isoformat()}"}
                for record in records
            ]},
            candidate_ids=frozenset({complex_id}), fact_ids=fact_ids,
            candidate_names=_candidate_name(self._repository, complex_id),
        )

    async def _candidate_evidence(self, complex_ids: tuple[int, ...]) -> ToolEvidence:
        if self._verified_pool is not None:
            candidates = self._verified_pool.payload.get("candidates")
            if isinstance(candidates, list):
                by_id = {
                    int(payload["complexId"]): payload
                    for payload in candidates
                    if isinstance(payload, dict) and "complexId" in payload
                }
                if not set(complex_ids).issubset(by_id):
                    raise ValueError("candidate no longer exists")
                selected = [by_id[complex_id] for complex_id in complex_ids]
                selected_fact_ids = frozenset(
                    fact_id for payload in selected
                    for fact_id in _payload_fact_ids(payload)
                )
                return ToolEvidence(
                    payload={"candidates": selected},
                    candidate_ids=frozenset(complex_ids),
                    candidate_names={
                        complex_id: str(by_id[complex_id]["complexName"])
                        for complex_id in complex_ids
                    },
                    fact_ids=selected_fact_ids,
                )
        results = await asyncio.gather(*(
            asyncio.to_thread(self._candidate_bundle, complex_id)
            for complex_id in complex_ids
        ))
        fact_ids = frozenset(
            fact_id for _payload, ids in results for fact_id in ids
        )
        return ToolEvidence(
            payload={"candidates": [payload for payload, _ids in results]},
            candidate_ids=frozenset(complex_ids), fact_ids=fact_ids,
            candidate_names={
                int(payload["complexId"]): str(payload["complexName"])
                for payload, _ids in results
            },
        )

    def _candidate_bundle(self, complex_id: int) -> tuple[dict[str, object], frozenset[str]]:
        record = self._repository.find_complex_by_id(complex_id)
        if record is None:
            raise ValueError("candidate no longer exists")
        trade_evidence = self._recent_trades(complex_id)
        fact_ids = set(trade_evidence.fact_ids)
        fact_ids.add(_complex_fact_id(complex_id))
        return {
            **_complex_payload(record),
            "recentTradeEvidence": trade_evidence.payload,
        }, frozenset(fact_ids)


def _complex_fact_id(complex_id: int) -> str:
    return f"complex:{complex_id}"


def _scope_fact_id(context: VerifiedRecommendationContext) -> str:
    token = hashlib.sha256(
        f"{context.scope_type}\0{context.scope_label}".encode()
    ).hexdigest()[:16]
    return f"recommendation-scope:{token}"


def _complex_payload(record: ComplexRecord) -> dict[str, object]:
    return {
        "complexId": record.complex_id, "complexName": record.display_name,
        "regionName": record.region_name, "address": record.address,
        "unitCount": record.unit_count,
        "useDate": record.use_date.isoformat() if record.use_date else None,
        "markerSafe": record.marker_safe,
        "dataUpdatedAt": record.data_updated_at.isoformat(),
        "factId": _complex_fact_id(record.complex_id),
    }


def _candidate_name(
    repository: AgenticPropertyRepository, complex_id: int,
) -> dict[int, str]:
    record = repository.find_complex_by_id(complex_id)
    if record is None:
        raise ValueError("candidate no longer exists")
    return {complex_id: record.display_name}


def _candidate_names(
    repository: AgenticPropertyRepository, complex_ids: tuple[int, ...],
) -> dict[int, str]:
    return {
        complex_id: _candidate_name(repository, complex_id)[complex_id]
        for complex_id in complex_ids
    }


def _payload_fact_ids(value: object) -> tuple[str, ...]:
    if isinstance(value, dict):
        own = (
            (value["factId"],)
            if isinstance(value.get("factId"), str) else ()
        )
        return tuple(dict.fromkeys((
            *own,
            *(fact_id for nested in value.values() for fact_id in _payload_fact_ids(nested)),
        )))
    if isinstance(value, list):
        return tuple(dict.fromkeys(
            fact_id for nested in value for fact_id in _payload_fact_ids(nested)
        ))
    return ()


def _distance_meters(
    origin_latitude: float | None, origin_longitude: float | None,
    latitude: float | None, longitude: float | None,
) -> int:
    if None in (origin_latitude, origin_longitude, latitude, longitude):
        raise ValueError("distance coordinates are unavailable")
    assert origin_latitude is not None and origin_longitude is not None
    assert latitude is not None and longitude is not None
    earth_radius = 6_371_008.8
    lat1, lat2 = math.radians(origin_latitude), math.radians(latitude)
    delta_lat = lat2 - lat1
    delta_lng = math.radians(longitude - origin_longitude)
    value = math.sin(delta_lat / 2) ** 2 + (
        math.cos(lat1) * math.cos(lat2) * math.sin(delta_lng / 2) ** 2
    )
    return round(earth_radius * 2 * math.atan2(math.sqrt(value), math.sqrt(1 - value)))


def _facet_union(
    records: list[ComplexRecord], *, limit: int, criteria_order: tuple[str, ...],
    distances: Mapping[int, int], trade_by_complex: Mapping[int, TradeRecord | None],
) -> list[ComplexRecord]:
    facets: list[list[ComplexRecord]] = []
    for criterion in criteria_order:
        if criterion == "TRANSIT" and distances:
            facets.append(sorted(records, key=lambda item: (
                distances.get(item.complex_id, math.inf), item.complex_id,
            )))
        elif criterion == "SCALE":
            facets.append(sorted(records, key=lambda item: (
                item.unit_count is None, -(item.unit_count or 0), item.complex_id,
            )))
        elif criterion == "NEWER":
            facets.append(sorted(records, key=lambda item: (
                item.use_date is None,
                -(item.use_date.toordinal() if item.use_date else 0), item.complex_id,
            )))
        elif criterion == "TRADE_ACTIVITY":
            facets.append(sorted(records, key=lambda item: (
                trade_by_complex.get(item.complex_id) is None,
                -(
                    trade_by_complex[item.complex_id].deal_date.toordinal()
                    if trade_by_complex.get(item.complex_id) is not None else 0
                ),
                item.complex_id,
            )))
    if distances and not any(criterion == "TRANSIT" for criterion in criteria_order):
        facets.append(sorted(records, key=lambda item: (
            distances.get(item.complex_id, math.inf), item.complex_id,
        )))
    facets.extend((
        sorted(records, key=lambda item: (
            trade_by_complex.get(item.complex_id) is None,
            -(
                trade_by_complex[item.complex_id].deal_date.toordinal()
                if trade_by_complex.get(item.complex_id) is not None else 0
            ),
            item.complex_id,
        )),
        sorted(records, key=lambda item: (
            item.unit_count is None, -(item.unit_count or 0), item.complex_id,
        )),
        sorted(records, key=lambda item: (
            item.use_date is None,
            -(item.use_date.toordinal() if item.use_date else 0), item.complex_id,
        )),
        sorted(records, key=lambda item: item.complex_id),
    ))
    selected: list[ComplexRecord] = []
    seen: set[int] = set()
    for index in range(len(records)):
        for facet in facets:
            if index >= len(facet):
                continue
            record = facet[index]
            if record.complex_id in seen:
                continue
            selected.append(record)
            seen.add(record.complex_id)
            if len(selected) == limit:
                return selected
    return selected


def _fallback_role(
    criteria_order: tuple[str, ...], station: object, recent_trade: object,
):
    if criteria_order:
        first = criteria_order[0]
        if first in {"TRANSIT", "EDUCATION", "LIFESTYLE", "SCALE", "NEWER", "TRADE_ACTIVITY"}:
            return first
    if isinstance(station, dict):
        return "TRANSIT"
    if isinstance(recent_trade, dict) and recent_trade.get("availability") == "available":
        return "TRADE_ACTIVITY"
    return "BALANCED"
