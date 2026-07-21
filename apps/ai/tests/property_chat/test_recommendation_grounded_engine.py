from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.comparison import CandidatePoint
from ai_service.property_chat.engine import GroundedChatbotEngine
from ai_service.property_chat.models import (
    ComplexRecord,
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    QueryPlan,
    TradeRecord,
)
from ai_service.property_chat.rail_stations import RailStation, RailStationSearchResult
from ai_service.property_chat.reference_facilities import FacilityFact, FacilitySearchResult


def _complex(complex_id: int) -> ComplexRecord:
    return ComplexRecord(
        complex_id=complex_id,
        display_name=f"후보 {complex_id}",
        region_code="11710",
        region_name="송파구",
        address=f"서울 송파구 후보 {complex_id}",
        latitude=37.5 + complex_id / 100_000,
        longitude=127.1,
        marker_safe=True,
        data_updated_at=datetime(2026, 7, 20, tzinfo=UTC),
    )


def _trades(complex_id: int, amounts: tuple[int, ...]) -> tuple[TradeRecord, ...]:
    return tuple(
        TradeRecord(
            complex_id * 10 + index,
            complex_id,
            date(2026, 7, 21 - index),
            amount,
            84.0,
            10,
        )
        for index, amount in enumerate(amounts, start=1)
    )


class PropertyRepository:
    def __init__(self) -> None:
        self.calls = 0

    def recommendation_candidates(
        self, region_name, start_date, end_date, area, limit
    ):
        self.calls += 1
        assert (region_name, start_date, end_date, area, limit) == (
            "송파구", date(2025, 7, 21), date(2026, 7, 20), 84.0, 100,
        )
        return {
            501: (_complex(501), _trades(501, (195_000, 190_000, 185_000))),
            502: (_complex(502), _trades(502, (150_000, 155_000))),
            503: (_complex(503), _trades(503, (205_000, 210_000, 220_000))),
        }

    def latest_trade_date(self):
        return date(2026, 7, 20)

    def find_complexes(self, *_args):
        raise AssertionError("recommendation must not run per-candidate identity queries")

    def recent_trades(self, *_args):
        raise AssertionError("recommendation must not run per-candidate trade queries")


class RailRepository:
    def __init__(self, *, ready: bool = True) -> None:
        self.ready = ready
        self.calls = 0

    def nearest_batch(self, *, points: tuple[CandidatePoint, ...], radius_meters: int):
        self.calls += 1
        assert [point.complex_id for point in points] == [501]
        assert radius_meters == 1500
        if not self.ready:
            return None
        return {
            point.complex_id: RailStationSearchResult(
                stations=(RailStation(
                    station_name="송파역",
                    lines=("8호선",),
                    occurrence_ids=(f"rail-{point.complex_id}",),
                    distance_meters=300,
                ),),
                occurrence_count=1,
                dataset_version="rail-v1",
                source_date=date(2026, 6, 30),
            )
            for point in points
        }


class RetailRepository:
    def __init__(self) -> None:
        self.calls = 0

    def nearest_batch(self, *, source_id, category, points, radius_meters):
        self.calls += 1
        assert (source_id, category, radius_meters) == (
            "retail.large-store", "LARGE_STORE", 1000,
        )
        return {
            point.complex_id: FacilitySearchResult(
                facilities=(FacilityFact(
                    fact_id=f"retail-{point.complex_id}",
                    name="롯데마트",
                    category="LARGE_STORE",
                    subcategory="LARGE_MART",
                    status="OPEN",
                    address="서울 송파구",
                    distance_meters=500,
                    dataset_version="retail-v1",
                    data_as_of=date(2026, 6, 30),
                ),),
                matched_count=1,
                returned_count=1,
                has_more=False,
                verified_zero=False,
                coordinate_coverage=1.0,
                dataset_version="retail-v1",
                data_as_of=date(2026, 6, 30),
            )
            for point in points
        }


class LanguageModel:
    async def plan_query(self, _request):
        return QueryPlan(
            capability="recommendation",
            complex_name="송파구",
            region_name="송파구",
            exclusive_area_square_meters=84.0,
            maximum_budget_ten_thousand_krw=200_000,
        )

    async def draft_answer(self, *, facts, limitations, question):
        del limitations, question
        return DraftAnswer([DraftSentence(
            "입력 조건을 통과한 후보를 확인했습니다.",
            [fact.fact_id for fact in facts],
            [
                DraftClaim(fact.fact_id, fact.claims[0].value, fact.claims[0].unit)
                for fact in facts
            ],
        )])


def _query(*, rail_ready: bool = True):
    property_repository = PropertyRepository()
    rail_repository = RailRepository(ready=rail_ready)
    retail_repository = RetailRepository()
    engine = GroundedChatbotEngine(
        repository=property_repository,
        language_model=LanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        rail_station_repository=rail_repository,
        point_facility_repository=retail_repository,
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="송파구 20억 이하 전용 84㎡ 추천"),
        user=AuthenticatedUser(user_id=1),
        request_id="request-recommendation",
    ))
    return response, property_repository, rail_repository, retail_repository


def test_recommendation_filters_before_deterministic_scoring_and_uses_batch_queries() -> None:
    response, property_repository, rail_repository, retail_repository = _query()

    assert response["success"] is True
    assert property_repository.calls == 1
    assert rail_repository.calls == 1
    assert retail_repository.calls == 1
    cards = response["uiArtifacts"][0]
    assert cards["type"] == "recommendationCards"
    assert cards["policyVersion"] == "recommendation-policy-v1"
    assert [card["complexId"] for card in cards["cards"]] == [501]
    card = cards["cards"][0]
    assert card["latestTrade"] == {
        "date": "2026-07-20",
        "amountTenThousandKrw": 195_000,
        "factIds": ["recommendation-trade-basis-501-2026-07-20-84"],
    }
    assert card["recentThreeMedian"] == {
        "amountTenThousandKrw": 190_000,
        "factIds": ["recommendation-trade-basis-501-2026-07-20-84"],
    }
    assert [(item["key"], item["weight"]) for item in card["scoreBreakdown"]] == [
        ("PRICE", 60.0), ("TRANSIT", 25.0), ("SHOPPING", 15.0),
    ]
    assert card["scoreBreakdown"][0]["points"] == 60.0
    citation_fact_ids = {
        fact_id for citation in response["citations"] for fact_id in citation["factIds"]
    }
    assert set(card["factIds"]).issubset(citation_fact_ids)


def test_recommendation_is_unavailable_when_a_required_source_is_unavailable() -> None:
    response, _, _, _ = _query(rail_ready=False)

    assert response["status"] == "failed"
    assert response["uiArtifacts"] == []
    assert any("철도" in item and "준비" in item for item in response["limitations"])


def test_recommendation_rejects_investment_and_low_price_quality_claims() -> None:
    model = LanguageModel()

    async def invalid_draft(*, facts, limitations, question):
        del limitations, question
        return DraftAnswer([DraftSentence(
            "가장 저렴해서 좋은 투자 가치가 있는 후보입니다.",
            [fact.fact_id for fact in facts],
            [
                DraftClaim(fact.fact_id, fact.claims[0].value, fact.claims[0].unit)
                for fact in facts
            ],
        )])

    model.draft_answer = invalid_draft  # type: ignore[method-assign]
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(),
        language_model=model,
        enabled_capabilities=frozenset({"recommendation"}),
        rail_station_repository=RailRepository(),
        point_facility_repository=RetailRepository(),
    )

    with pytest.raises(Exception):
        asyncio.run(engine.query(
            request=ChatbotQueryRequest(question="송파구 20억 이하 전용 84㎡ 추천"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-recommendation-invalid",
        ))


def test_recommendation_lists_missing_required_inputs_without_observation() -> None:
    class MissingInputLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(capability="recommendation", complex_name="추천 조건 확인")

    repository = PropertyRepository()
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=MissingInputLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        rail_station_repository=RailRepository(),
        point_facility_repository=RetailRepository(),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="아파트 후보를 추천해줘"),
        user=AuthenticatedUser(user_id=1),
        request_id="request-recommendation-missing",
    ))

    assert response["status"] == "failed"
    assert repository.calls == 0
    assert any(
        all(label in limitation for label in ("지역", "최대 예산", "전용면적"))
        for limitation in response["limitations"]
    )


def test_recommendation_returns_grounded_verified_zero_without_facility_queries() -> None:
    class EmptyPropertyRepository(PropertyRepository):
        def recommendation_candidates(self, *_args):
            self.calls += 1
            return {}

    repository = EmptyPropertyRepository()
    rail_repository = RailRepository()
    retail_repository = RetailRepository()
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=LanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        rail_station_repository=rail_repository,
        point_facility_repository=retail_repository,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="송파구 20억 이하 전용 84㎡ 추천"),
        user=AuthenticatedUser(user_id=1),
        request_id="request-recommendation-zero",
    ))

    assert response["status"] == "success"
    assert response["uiArtifacts"] == []
    assert response["citations"][0]["factIds"] == [
        "recommendation-scope-2026-07-20-84-200000"
    ]
    assert rail_repository.calls == 0
    assert retail_repository.calls == 0
    assert any("통과한 단지를 확인하지 못했습니다" in item for item in response["limitations"])
