from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime

import pytest

from ai_service.property_chat.agentic_tools import PropertyAgentTools
from ai_service.property_chat.criteria_recommendation import CriteriaCandidateScope
from ai_service.property_chat.models import ComplexRecord, MonthlyTrendRecord, TradeRecord


def _complex(
    complex_id: int, *, units: int | None = 500, use_date: date | None = date(2020, 1, 1),
) -> ComplexRecord:
    return ComplexRecord(
        complex_id=complex_id, display_name=f"{complex_id}단지", region_code="11710",
        region_name="송파구", address=f"송파구 {complex_id}", latitude=37.5,
        longitude=127.1, marker_safe=True,
        data_updated_at=datetime(2026, 7, 1, tzinfo=UTC), unit_count=units,
        use_date=use_date,
    )


class Repository:
    def __init__(self) -> None:
        self.records = {1: _complex(1, units=1000, use_date=date(2010, 1, 1)),
                        2: _complex(2, units=600, use_date=date(2024, 1, 1)),
                        3: _complex(3, units=None, use_date=None)}
        self.scope: CriteriaCandidateScope | None = CriteriaCandidateScope(
            "송파구", tuple(self.records.values())
        )
        self.budget_available = True

    def find_complexes(self, _name: str, _region: str | None, limit: int):
        return list(self.records.values())[:limit]

    def find_complex_by_id(self, complex_id: int):
        return self.records.get(complex_id)

    def complex_profile(self, complex_id: int):
        return {"unitCount": 1000} if complex_id == 1 else None

    def criteria_candidates(self, _region: str, _limit: int):
        return self.scope

    def recommendation_candidates(self, _region, _start, _end, _area, _limit):
        if not self.budget_available:
            return None
        return {
            1: (self.records[1], (self._trade(11, 1, 120_000),)),
            2: (self.records[2], (self._trade(22, 2, 90_000),)),
            3: (self.records[3], ()),
        }

    def recent_trades(self, complex_id, _start, _end, _area, _limit):
        return [self._trade(complex_id * 10, complex_id, 100_000 + complex_id)]

    def monthly_trends(self, complex_id, _start, _end, _area):
        return [MonthlyTrendRecord(complex_id, date(2026, 6, 1), 100_000, 2, 90_000, 110_000)]

    def latest_trade_date(self):
        return date(2026, 7, 1)

    @staticmethod
    def _trade(trade_id: int, complex_id: int, amount: int) -> TradeRecord:
        return TradeRecord(trade_id, complex_id, date(2026, 6, 1), amount, 84.0, 10)


def _run(tools: PropertyAgentTools, name: str, arguments: dict[str, object]):
    return asyncio.run(tools.execute(name, arguments))


def test_all_read_only_tool_dispatches_publish_bounded_evidence() -> None:
    tools = PropertyAgentTools(Repository())

    search = _run(tools, "search_complexes", {"query": "단지", "limit": 40})
    profile = _run(tools, "get_complex_profile", {"complexId": 1})
    missing_profile = _run(tools, "get_complex_profile", {"complexId": 2})
    trades = _run(tools, "get_recent_trades", {"complexId": 1})
    trend = _run(tools, "get_price_trend", {"complexId": 1})
    candidates = _run(tools, "get_candidate_evidence", {"complexIds": [1, 2]})
    references = _run(tools, "get_reference_evidence", {"complexIds": [1, 2]})

    assert len(search.payload["matches"]) == 3
    assert search.candidate_ids == frozenset({1, 2, 3})
    assert profile.fact_ids == frozenset({"profile:1"})
    assert missing_profile.fact_ids == frozenset()
    assert trades.fact_ids == frozenset({"trade:10"})
    assert trend.fact_ids == frozenset({"trend:1:2026-06-01"})
    assert candidates.candidate_names == {1: "1단지", 2: "2단지"}
    assert references.fact_ids == frozenset()
    assert references.payload["limitation"].startswith("공식")


def test_candidate_pool_is_facet_union_with_server_hard_filters() -> None:
    tools = PropertyAgentTools(Repository())

    result = _run(tools, "get_region_candidate_pool", {
        "regionName": "송파구", "limit": 2, "minimumUnitCount": 500,
        "maximumBudgetTenThousandKrw": None, "exclusiveAreaSquareMeters": None,
    })
    budget = _run(tools, "get_region_candidate_pool", {
        "regionName": "송파구", "limit": 3, "minimumUnitCount": None,
        "maximumBudgetTenThousandKrw": 100_000, "exclusiveAreaSquareMeters": 84.0,
    })

    assert result.scope_label == "송파구"
    assert result.candidate_ids == frozenset({1, 2})
    assert result.payload["selection"] == "FACET_UNION_NOT_FINAL_RANK"
    assert budget.candidate_ids == frozenset({2})


def test_candidate_pool_returns_empty_when_scope_or_budget_observation_is_unavailable() -> None:
    repository = Repository()
    tools = PropertyAgentTools(repository)
    repository.scope = None
    assert _run(tools, "get_region_candidate_pool", {
        "regionName": "송파구", "limit": 3, "minimumUnitCount": None,
        "maximumBudgetTenThousandKrw": None, "exclusiveAreaSquareMeters": None,
    }).candidate_ids == frozenset()

    repository.scope = CriteriaCandidateScope("송파구", tuple(repository.records.values()))
    repository.budget_available = False
    assert _run(tools, "get_region_candidate_pool", {
        "regionName": "송파구", "limit": 3, "minimumUnitCount": None,
        "maximumBudgetTenThousandKrw": 100_000, "exclusiveAreaSquareMeters": 84.0,
    }).candidate_ids == frozenset()


def test_unknown_tool_and_disappeared_candidate_fail_closed() -> None:
    tools = PropertyAgentTools(Repository())
    with pytest.raises(ValueError, match="unsupported"):
        _run(tools, "write_sql", {})
    with pytest.raises(ValueError, match="no longer exists"):
        _run(tools, "get_candidate_evidence", {"complexIds": [99]})
    with pytest.raises(ValueError, match="no longer exists"):
        _run(tools, "get_recent_trades", {"complexId": 99})
