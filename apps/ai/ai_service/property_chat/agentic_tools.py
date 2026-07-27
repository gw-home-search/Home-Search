from __future__ import annotations

import asyncio
from collections.abc import Mapping
from datetime import date, timedelta
from typing import Protocol

from .agentic import ToolEvidence
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


class PropertyAgentTools:
    def __init__(self, repository: AgenticPropertyRepository) -> None:
        self._repository = repository

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
            return ToolEvidence(
                payload={"candidates": [
                    {"complexId": complex_id, "availability": "unavailable"}
                    for complex_id in ids
                ], "limitation": "공식 교통·생활 인프라 근거가 현재 snapshot에 없습니다."},
                candidate_ids=frozenset(ids), fact_ids=frozenset(),
            )
        raise ValueError("unsupported agent tool")

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
