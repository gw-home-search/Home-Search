from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.comparison import CandidatePoint
from ai_service.property_chat.engine import (
    GroundedChatbotEngine,
    GroundingValidationError,
    validate_draft,
)
from ai_service.property_chat.models import (
    ComplexRecord,
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    EvidenceFact,
    FactClaim,
    QueryPlan,
    TradeRecord,
)
from ai_service.property_chat.rail_stations import RailStation, RailStationSearchResult
from ai_service.property_chat.rail_stations import StationScopeMatch, StationScopeResolution
from ai_service.property_chat.reference_facilities import FacilityFact, FacilitySearchResult
from ai_service.property_chat.academy_locations import (
    AcademyLocation,
    AcademyLocationSearchResult,
)
from ai_service.property_chat.capability_handlers import CapabilityResult
from ai_service.property_chat.criteria_recommendation import CriteriaCandidateScope
from ai_service.property_chat.childcare_centers import ChildcareCenter, ChildcareSearchResult
from ai_service.property_chat.models import SchoolRecord, SchoolSearchResult, SchoolSnapshot
from ai_service.property_chat.recommendation_handler import RecommendationHandler


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


class SchoolRepository:
    def nearest_by_level_batch(self, *, points, school_levels, radius_meters):
        assert school_levels == ("ELEMENTARY", "MIDDLE", "HIGH")
        assert radius_meters == 1500
        return SchoolSnapshot("school-v1", date(2026, 6, 30), datetime(2026, 7, 1, tzinfo=UTC)), {
            point.complex_id: SchoolSearchResult(tuple(
                SchoolRecord(
                    school_id=f"{point.complex_id}-{level}", school_name=f"{level} 학교",
                    school_level=level, operating_status="운영", road_address=None,
                    lot_address=None, latitude=point.latitude, longitude=point.longitude,
                    distance_meters=0,
                )
                for level in school_levels
            ), 3)
            for point in points
        }


class AcademyRepository:
    def nearby_counts_batch(self, *, points, radius_meters):
        assert radius_meters == 800
        return {
            point.complex_id: AcademyLocationSearchResult(
                locations=(), matched_count=5, coordinate_coverage=1.0,
                dataset_version="academy-v1", observed_at=datetime(2026, 7, 1, tzinfo=UTC),
                verified_zero=False,
            )
            for point in points
        }


class ChildcareRepository:
    def nearby_batch(self, *, points, radius_meters):
        assert radius_meters == 800
        return {point.complex_id: ChildcareSearchResult(
            centers=(ChildcareCenter(
                f"center-{point.complex_id}", "해뜰어린이집", "국공립", 50, 0,
                date(2026, 7, 1), "child-v1",
            ),), matched_count=5, returned_count=1, has_more=True,
            verified_zero=False, coordinate_coverage=1.0, dataset_version="child-v1",
            observed_at=datetime(2026, 7, 1, tzinfo=UTC), freshness_days=45,
        ) for point in points}


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

    with pytest.raises(ChatbotProviderUnavailable) as raised:
        asyncio.run(engine.query(
            request=ChatbotQueryRequest(question="송파구 20억 이하 전용 84㎡ 추천"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-recommendation-invalid",
        ))

    assert isinstance(raised.value.__cause__, GroundingValidationError)
    assert raised.value.__cause__.reason_code == "GROUNDING_RECOMMENDATION_POLICY_VIOLATION"


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


def test_criteria_recommendation_uses_units_and_academy_without_budget_or_area() -> None:
    class CriteriaPropertyRepository(PropertyRepository):
        def criteria_candidates(self, region_name, limit):
            self.calls += 1
            assert (region_name, limit) == ("영등포구", 101)
            return CriteriaCandidateScope(
                "영등포구",
                (
                    replace(_complex(501), region_code="11560", region_name="영등포구", unit_count=499),
                    replace(_complex(502), region_code="11560", region_name="영등포구", unit_count=800),
                    replace(_complex(503), region_code="11560", region_name="영등포구", unit_count=1200),
                ),
            )

    class CriteriaAcademyRepository:
        def __init__(self):
            self.calls = 0

        def nearby_counts_batch(self, *, points, radius_meters):
            self.calls += 1
            assert radius_meters == 800
            assert [point.complex_id for point in points] == [502, 503]
            return {
                point.complex_id: AcademyLocationSearchResult(
                    locations=(AcademyLocation(
                        store_id=f"academy-{point.complex_id}",
                        name=f"학원 {point.complex_id}",
                        small_category_code="P10501",
                        status="OPEN",
                        address="서울 영등포구",
                        distance_meters=100,
                        dataset_version="academy-v1",
                        observed_at=datetime(2026, 7, 1, tzinfo=UTC),
                        registry_match=None,
                    ),),
                    matched_count=10 if point.complex_id == 503 else 5,
                    coordinate_coverage=1.0,
                    dataset_version="academy-v1",
                    observed_at=datetime(2026, 7, 1, tzinfo=UTC),
                    verified_zero=False,
                )
                for point in points
            }

    class CriteriaLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="recommendation",
                complex_name="영등포구",
                region_name="영등포구",
                recommendation_mode="CRITERIA",
                minimum_unit_count=500,
                recommendation_criteria=("ACADEMY",),
                criteria_order=("ACADEMY",),
            )

    property_repository = CriteriaPropertyRepository()
    academy_repository = CriteriaAcademyRepository()
    engine = GroundedChatbotEngine(
        repository=property_repository,
        language_model=CriteriaLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        academy_location_repository=academy_repository,
        today=lambda: date(2026, 7, 20),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question="영등포구 500세대 이상 중 학원 접근성 우선으로 추천해줘"
        ),
        user=AuthenticatedUser(user_id=1),
        request_id="request-criteria-recommendation",
    ))

    assert response["status"] == "success"
    assert property_repository.calls == 1
    assert academy_repository.calls == 1
    table = response["uiArtifacts"][0]
    assert table["type"] == "recommendationTable"
    assert table["policyVersion"] == "criteria-recommendation-policy-v1"
    assert table["basis"] == {
        "scopeType": "ADMIN_REGION",
        "scopeLabel": "영등포구",
        "criteriaOrder": ["ACADEMY"],
        "minimumUnitCount": 500,
        "radiusMeters": 800,
    }
    assert [row["complexId"] for row in table["rows"]] == [503, 502]
    assert all(row["unitCount"] >= 500 for row in table["rows"])
    assert [item["key"] for item in response["uiSummary"]["criteria"]] == [
        "REGION", "MIN_UNIT_COUNT", "ACADEMY",
    ]
    assert response["uiSummary"]["scopeNotice"]["text"] == (
        "‘영등포구’ 기준으로 해석했습니다."
    )


def test_criteria_recommendation_supports_an_exact_station_radius_scope() -> None:
    class StationPropertyRepository(PropertyRepository):
        def criteria_candidates_near_point(
            self, latitude, longitude, radius_meters, limit
        ):
            self.calls += 1
            assert (latitude, longitude, radius_meters, limit) == (
                37.521, 126.924, 800, 101,
            )
            return (replace(
                _complex(502), region_code="11560", region_name="영등포구",
                unit_count=800,
            ),)

    class StationRailRepository(RailRepository):
        def resolve_station(self, station_name):
            assert station_name == "여의도"
            return StationScopeResolution(
                matches=(StationScopeMatch(
                    station_name="여의도",
                    latitude=37.521,
                    longitude=126.924,
                    lines=("5호선", "9호선"),
                    occurrence_ids=("rail-5-yeouido", "rail-9-yeouido"),
                ),),
                dataset_version="rail-v1",
                source_date=date(2026, 6, 30),
                coordinate_coverage=1.0,
                freshness_days=410,
            )

    class StationLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="recommendation",
                complex_name="여의도역",
                recommendation_mode="CRITERIA",
                station_name="여의도",
                radius_meters=800,
                minimum_unit_count=500,
                recommendation_criteria=("ACADEMY",),
                criteria_order=("ACADEMY",),
            )

    engine = GroundedChatbotEngine(
        repository=StationPropertyRepository(),
        language_model=StationLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        rail_station_repository=StationRailRepository(),
        academy_location_repository=AcademyRepository(),
        today=lambda: date(2026, 7, 20),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question="여의도역 800m 안 500세대 이상 중 학원 우선으로 추천해줘"
        ),
        user=AuthenticatedUser(user_id=1),
        request_id="request-station-criteria",
    ))

    table = response["uiArtifacts"][0]
    assert table["basis"]["scopeType"] == "STATION_RADIUS"
    assert table["basis"]["scopeLabel"] == "여의도역 직선거리 800m"
    assert any(
        citation["sourceId"] == "transport.rail-station"
        for citation in response["citations"]
    )


def test_criteria_recommendation_does_not_use_unmentioned_school_condition() -> None:
    class CriteriaPropertyRepository(PropertyRepository):
        def criteria_candidates(self, _region_name, _limit):
            return CriteriaCandidateScope(
                "영등포구", (replace(_complex(502), unit_count=800),)
            )

    class OverProposingLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="recommendation", complex_name="영등포구",
                region_name="영등포구", recommendation_mode="CRITERIA",
                minimum_unit_count=500,
                recommendation_criteria=("ACADEMY", "SCHOOL"),
                criteria_order=("ACADEMY", "SCHOOL"),
            )

    engine = GroundedChatbotEngine(
        repository=CriteriaPropertyRepository(),
        language_model=OverProposingLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        academy_location_repository=AcademyRepository(),
        school_repository=None,
        today=lambda: date(2026, 7, 20),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question="영등포구 500세대 이상 중 학원 접근성 우선 추천"
        ),
        user=AuthenticatedUser(user_id=1),
        request_id="request-academy-only",
    ))

    assert response["status"] == "success"
    assert response["uiArtifacts"][0]["basis"]["criteriaOrder"] == ["ACADEMY"]
    assert set(response["uiArtifacts"][0]["rows"][0]["metrics"]) == {"ACADEMY"}


def test_criteria_recommendation_observes_all_active_sources_once_in_priority_order() -> None:
    class AllCriteriaPropertyRepository(PropertyRepository):
        def criteria_candidates(self, region_name, limit):
            self.calls += 1
            assert (region_name, limit) == ("송파구", 101)
            return CriteriaCandidateScope(
                "송파구", (replace(_complex(501), unit_count=800),)
            )

    class AllCriteriaLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="recommendation", complex_name="송파구",
                region_name="송파구", recommendation_mode="CRITERIA",
                minimum_unit_count=500,
                recommendation_criteria=("ACADEMY", "SCHOOL", "TRANSIT", "SHOPPING"),
                criteria_order=("ACADEMY", "TRANSIT", "SCHOOL", "SHOPPING"),
            )

    rail = RailRepository()
    retail = RetailRepository()
    engine = GroundedChatbotEngine(
        repository=AllCriteriaPropertyRepository(),
        language_model=AllCriteriaLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        academy_location_repository=AcademyRepository(),
        school_repository=SchoolRepository(),
        rail_station_repository=rail,
        point_facility_repository=retail,
        today=lambda: date(2026, 7, 20),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question=(
                "송파구 500세대 이상 중 학원 먼저, 그다음 교통, 학교, "
                "쇼핑 순서로 추천"
            )
        ),
        user=AuthenticatedUser(user_id=1),
        request_id="request-all-criteria",
    ))

    assert response["status"] == "success"
    assert rail.calls == retail.calls == 1
    table = response["uiArtifacts"][0]
    assert table["basis"]["criteriaOrder"] == [
        "ACADEMY", "TRANSIT", "SCHOOL", "SHOPPING",
    ]
    assert set(table["rows"][0]["metrics"]) == {
        "ACADEMY", "SCHOOL", "TRANSIT", "SHOPPING",
    }
    assert table["rows"][0]["metrics"]["ACADEMY"]["unit"] == "COUNT"
    assert table["rows"][0]["metrics"]["TRANSIT"]["unit"] == "METERS"


def test_criteria_recommendation_requests_priority_instead_of_guessing_weights() -> None:
    class MultipleLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="recommendation", complex_name="영등포구",
                region_name="영등포구", recommendation_mode="CRITERIA",
                recommendation_criteria=("ACADEMY", "TRANSIT"),
                criteria_order=(),
            )

    repository = PropertyRepository()
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=MultipleLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="영등포구 학원과 교통 조건으로 추천"),
        user=AuthenticatedUser(user_id=1),
        request_id="request-priority-clarification",
    ))

    assert response["status"] == "failed"
    assert repository.calls == 0
    assert any("순서" in limitation for limitation in response["limitations"])


@pytest.mark.parametrize(
    ("scope_value", "expected_status", "expected_text"),
    (
        (None, "failed", "하나로 식별"),
        (CriteriaCandidateScope("영등포구", ()), "success", "확인된 후보가 없습니다"),
        (
            CriteriaCandidateScope(
                "영등포구",
                tuple(replace(_complex(index), unit_count=800) for index in range(1, 102)),
            ),
            "failed",
            "100개를 넘어",
        ),
    ),
)
def test_criteria_recommendation_handles_region_scope_boundaries(
    scope_value, expected_status: str, expected_text: str,
) -> None:
    class BoundaryRepository(PropertyRepository):
        def criteria_candidates(self, _region_name, _limit):
            self.calls += 1
            return scope_value

    class BoundaryLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="recommendation", complex_name="영등포구",
                region_name="영등포구", recommendation_mode="CRITERIA",
                minimum_unit_count=500, recommendation_criteria=("ACADEMY",),
                criteria_order=("ACADEMY",),
            )

    response = asyncio.run(GroundedChatbotEngine(
        repository=BoundaryRepository(), language_model=BoundaryLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
    ).query(
        request=ChatbotQueryRequest(
            question="영등포구 500세대 이상 중 학원 접근성 우선 추천"
        ),
        user=AuthenticatedUser(user_id=1), request_id="request-scope-boundary",
    ))

    assert response["status"] == expected_status
    assert any(expected_text in text for text in response["limitations"])


@pytest.mark.parametrize(
    ("criterion", "question", "source_label"),
    (
        ("ACADEMY", "학원", "Sbiz 교육업소"),
        ("TRANSIT", "교통", "철도"),
        ("SCHOOL", "학교", "학교"),
        ("SHOPPING", "쇼핑", "대규모점포"),
    ),
)
def test_criteria_recommendation_reports_each_missing_active_source(
    criterion: str, question: str, source_label: str,
) -> None:
    class SourceRepository(PropertyRepository):
        def criteria_candidates(self, _region_name, _limit):
            return CriteriaCandidateScope(
                "송파구", (replace(_complex(501), unit_count=800),)
            )

    class SourceLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="recommendation", complex_name="송파구",
                region_name="송파구", recommendation_mode="CRITERIA",
                minimum_unit_count=500,
                recommendation_criteria=(criterion,),  # type: ignore[arg-type]
                criteria_order=(criterion,),  # type: ignore[arg-type]
            )

    response = asyncio.run(GroundedChatbotEngine(
        repository=SourceRepository(), language_model=SourceLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        today=lambda: date(2026, 7, 20),
    ).query(
        request=ChatbotQueryRequest(
            question=f"송파구 500세대 이상 중 {question} 우선 추천"
        ),
        user=AuthenticatedUser(user_id=1), request_id=f"request-missing-{criterion}",
    ))

    assert response["status"] == "failed"
    assert any(source_label in text for text in response["limitations"])


def test_criteria_observation_rejects_stale_incomplete_and_missing_batches() -> None:
    point = CandidatePoint(501, 37.5, 127.1, "11710")

    class BatchStub:
        def __init__(self, value):
            self.value = value

        def nearby_counts_batch(self, **_kwargs):
            return self.value

        def nearest_batch(self, **_kwargs):
            return self.value

        def nearest_by_level_batch(self, **_kwargs):
            return self.value

    invalid_values = {
        "ACADEMY": {
            501: AcademyLocationSearchResult(
                (), 0, 0.5, "academy-v1",
                datetime(2026, 7, 1, tzinfo=UTC), True,
            )
        },
        "TRANSIT": {
            501: RailStationSearchResult(
                (), 0, "rail-v1", date(2026, 6, 30), coordinate_coverage=0.5
            )
        },
        "SHOPPING": {
            501: FacilitySearchResult(
                (), 0, 0, False, True, 0.5, "retail-v1", date(2026, 6, 30)
            )
        },
        "SCHOOL": (
            SchoolSnapshot(
                "school-v1", date(2025, 1, 1), datetime(2025, 1, 2, tzinfo=UTC)
            ),
            {501: SchoolSearchResult((), 0)},
        ),
    }
    for criterion, value in invalid_values.items():
        stub = BatchStub(value)
        handler = RecommendationHandler(
            repository=PropertyRepository(), rail_repository=stub,
            retail_repository=stub, school_repository=stub,
            academy_repository=stub, childcare_repository=None,
            builders=None,  # type: ignore[arg-type]
            today=lambda: date(2026, 7, 20),
        )
        result = asyncio.run(handler._criteria_observations(  # noqa: SLF001
            (criterion,), (point,), ("ELEMENTARY", "MIDDLE", "HIGH")
        ))
        assert isinstance(result, CapabilityResult)
        assert result.readiness == "unavailable"

    for value in (None, {}):
        stub = BatchStub(value)
        handler = RecommendationHandler(
            repository=PropertyRepository(), rail_repository=None,
            retail_repository=None, school_repository=None,
            academy_repository=stub, childcare_repository=None,
            builders=None,  # type: ignore[arg-type]
            today=lambda: date(2026, 7, 20),
        )
        result = asyncio.run(handler._criteria_observations(  # noqa: SLF001
            ("ACADEMY",), (point,), ("ELEMENTARY", "MIDDLE", "HIGH")
        ))
        assert isinstance(result, CapabilityResult)
        assert result.readiness == "unavailable"


def test_recommendation_handler_guards_invalid_and_unresolved_scopes() -> None:
    handler = RecommendationHandler(
        repository=PropertyRepository(), rail_repository=None,
        retail_repository=None, school_repository=None, academy_repository=None,
        childcare_repository=None, builders=None,  # type: ignore[arg-type]
        today=lambda: date(2026, 7, 20),
    )
    with pytest.raises(ValueError, match="plan"):
        asyncio.run(handler.observe(QueryPlan(
            capability="complex_identity", complex_name="후보"
        )))

    base = QueryPlan(
        capability="recommendation", complex_name="여의도역",
        recommendation_mode="CRITERIA", station_name="여의도", radius_meters=800,
        recommendation_criteria=("ACADEMY",), criteria_order=("ACADEMY",),
    )
    missing_source = asyncio.run(handler.observe(base))
    assert any("철도" in text for text in missing_source.limitations)

    class StationStub:
        def __init__(self, resolution):
            self.resolution = resolution

        def resolve_station(self, _station_name):
            return self.resolution

    not_found_handler = RecommendationHandler(
        repository=PropertyRepository(), rail_repository=StationStub(None),
        retail_repository=None, school_repository=None, academy_repository=None,
        childcare_repository=None, builders=None,  # type: ignore[arg-type]
        today=lambda: date(2026, 7, 20),
    )
    not_found = asyncio.run(not_found_handler.observe(base))
    assert any("확인하지 못했습니다" in text for text in not_found.limitations)

    ambiguous_resolution = StationScopeResolution(
        matches=(
            StationScopeMatch("중앙", 37.5, 127.0, ("1호선",), ("rail-1",)),
            StationScopeMatch("중앙", 37.6, 127.1, ("2호선",), ("rail-2",)),
        ),
        dataset_version="rail-v1", source_date=date(2026, 6, 30),
        coordinate_coverage=1.0, freshness_days=410,
    )
    ambiguous_handler = RecommendationHandler(
        repository=PropertyRepository(),
        rail_repository=StationStub(ambiguous_resolution), retail_repository=None,
        school_repository=None, academy_repository=None, childcare_repository=None,
        builders=None,  # type: ignore[arg-type]
        today=lambda: date(2026, 7, 20),
    )
    ambiguous = asyncio.run(ambiguous_handler.observe(replace(base, station_name="중앙")))
    assert any("노선" in text for text in ambiguous.limitations)

    no_scope = asyncio.run(handler.observe(replace(
        base, station_name=None, radius_meters=None, region_name=None
    )))
    assert any("지역" in text for text in no_scope.limitations)


def test_budget_recommendation_handler_guards_source_and_candidate_boundaries() -> None:
    plan = QueryPlan(
        capability="recommendation", complex_name="송파구", region_name="송파구",
        recommendation_mode="BUDGET", exclusive_area_square_meters=84,
        maximum_budget_ten_thousand_krw=200_000,
    )

    class LatestMissing(PropertyRepository):
        def latest_trade_date(self):
            return None

    class RegionMissing(PropertyRepository):
        def recommendation_candidates(self, *_args):
            return None

    class TooMany(PropertyRepository):
        def recommendation_candidates(self, *_args):
            return {
                index: (_complex(index), ()) for index in range(1, 102)
            }

    def observe(repository, *, rail=None, retail=None):
        return asyncio.run(RecommendationHandler(
            repository=repository, rail_repository=rail,
            retail_repository=retail, school_repository=None,
            academy_repository=None, childcare_repository=None,
            builders=None,  # type: ignore[arg-type]
            today=lambda: date(2026, 7, 20),
        ).observe(plan))

    assert any("최신 거래일" in text for text in observe(LatestMissing()).limitations)
    assert any("하나로 식별" in text for text in observe(RegionMissing()).limitations)
    with pytest.raises(ValueError, match="cap"):
        observe(TooMany())
    assert any("철도" in text for text in observe(PropertyRepository()).limitations)
    assert any(
        "대규모점포" in text
        for text in observe(PropertyRepository(), rail=RailRepository()).limitations
    )

def test_childcare_and_kindergarten_stay_out_of_active_criteria_recommendation() -> None:
    class ChildcareLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="recommendation", complex_name="영등포구",
                region_name="영등포구", recommendation_mode="CRITERIA",
                minimum_unit_count=500,
            )

    repository = PropertyRepository()
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=ChildcareLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="영등포구 500세대 이상 어린이집 추천"),
        user=AuthenticatedUser(user_id=1),
        request_id="request-childcare-deferred",
    ))

    assert response["status"] == "failed"
    assert repository.calls == 0
    assert any("핵심 추천에서 제외" in limitation for limitation in response["limitations"])


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


def test_budget_recommendation_applies_minimum_unit_count_before_sources() -> None:
    class UnitPropertyRepository(PropertyRepository):
        def recommendation_candidates(self, *args):
            observations = super().recommendation_candidates(*args)
            return {
                complex_id: (replace(record, unit_count=400), trades)
                for complex_id, (record, trades) in observations.items()
            }

    class UnitLanguageModel(LanguageModel):
        async def plan_query(self, request):
            return replace(
                await super().plan_query(request), minimum_unit_count=500
            )

    repository = UnitPropertyRepository()
    rail = RailRepository()
    retail = RetailRepository()
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=UnitLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        rail_station_repository=rail,
        point_facility_repository=retail,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question="송파구 20억 이하 전용 84㎡ 500세대 이상 추천"
        ),
        user=AuthenticatedUser(user_id=1),
        request_id="request-budget-unit-count",
    ))

    assert response["status"] == "success"
    assert response["uiArtifacts"] == []
    assert rail.calls == retail.calls == 0
    assert response["uiSummary"]["criteria"][-1]["key"] == "MIN_UNIT_COUNT"


def test_budget_recommendation_uses_explicit_academy_as_separate_criteria_policy() -> None:
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(),
        language_model=LanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        academy_location_repository=AcademyRepository(),
        rail_station_repository=None,
        point_facility_repository=None,
        today=lambda: date(2026, 7, 20),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question="송파구 20억 이하 전용 84㎡ 중 학원 접근성 우선 추천"
        ),
        user=AuthenticatedUser(user_id=1),
        request_id="request-budget-academy",
    ))

    assert response["status"] == "success"
    artifact = response["uiArtifacts"][0]
    assert artifact["type"] == "recommendationTable"
    assert artifact["policyVersion"] == "criteria-recommendation-policy-v1"
    assert artifact["basis"]["criteriaOrder"] == ["ACADEMY"]
    assert [item["key"] for item in response["uiSummary"]["criteria"]] == [
        "REGION", "MAX_BUDGET", "EXCLUSIVE_AREA", "ACADEMY",
    ]


def test_recommendation_applies_only_verified_student_and_transit_themes() -> None:
    class ThemedLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="recommendation", complex_name="송파구", region_name="송파구",
                exclusive_area_square_meters=84.0,
                maximum_budget_ten_thousand_krw=200_000,
                lifestyle_themes=("TRANSIT", "STUDENT", "YOUNG_CHILD"),
            )

    engine = GroundedChatbotEngine(
        repository=PropertyRepository(), language_model=ThemedLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        rail_station_repository=RailRepository(), point_facility_repository=RetailRepository(),
        school_repository=SchoolRepository(), academy_location_repository=AcademyRepository(),
        today=lambda: date(2026, 7, 20),
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="학생이 있고 역도 가까운 송파구 후보 추천"),
        user=AuthenticatedUser(user_id=1), request_id="request-themed-recommendation",
    ))

    card = response["uiArtifacts"][0]["cards"][0]
    assert card["activeThemes"] == ["TRANSIT", "STUDENT"]
    assert [(item["key"], item["weight"]) for item in card["scoreBreakdown"]] == [
        ("PRICE", 60.0), ("TRANSIT", 22.5), ("SHOPPING", 5.0),
        ("STUDENT", 12.5),
    ]
    assert "YOUNG_CHILD" not in [item["key"] for item in card["scoreBreakdown"]]


def test_recommendation_does_not_zero_score_a_missing_childcare_source() -> None:
    class ChildThemeLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return replace(
                await super().plan_query(_request),
                lifestyle_themes=("YOUNG_CHILD",),
            )

    engine = GroundedChatbotEngine(
        repository=PropertyRepository(), language_model=ChildThemeLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        rail_station_repository=RailRepository(), point_facility_repository=RetailRepository(),
        childcare_repository=None,
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="어린아이가 있는 집을 추천해줘"),
        user=AuthenticatedUser(user_id=1), request_id="request-child-source-missing",
    ))

    assert response["status"] == "failed"
    assert any("어린이집" in value for value in response["limitations"])


def test_recommendation_keeps_childcare_deferred_even_when_repository_exists() -> None:
    class ChildThemeLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return replace(
                await super().plan_query(_request), lifestyle_themes=("YOUNG_CHILD",)
            )

    engine = GroundedChatbotEngine(
        repository=PropertyRepository(), language_model=ChildThemeLanguageModel(),
        enabled_capabilities=frozenset({"recommendation"}),
        rail_station_repository=RailRepository(), point_facility_repository=RetailRepository(),
        childcare_repository=ChildcareRepository(), today=lambda: date(2026, 7, 20),
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="어린아이가 있는 집을 추천해줘"),
        user=AuthenticatedUser(user_id=1), request_id="request-child-theme",
    ))

    assert response["status"] == "failed"
    assert response["uiArtifacts"] == []
    assert any("핵심 추천에서 제외" in value for value in response["limitations"])


def test_recommendation_allows_grounded_name_in_a_negative_quality_limitation() -> None:
    fact = EvidenceFact(
        fact_id="recommendation-childcare-501",
        claims=(FactClaim("해뜰어린이집", "TEXT"),),
        data_as_of=date(2026, 7, 1), payload={}, source_id="lifestyle.childcare",
    )
    draft = DraftAnswer([DraftSentence(
        "해뜰어린이집 정보는 입소 가능 여부나 보육 품질을 의미하지 않습니다.",
        [fact.fact_id], [DraftClaim(fact.fact_id, "해뜰어린이집", "TEXT")],
    )])

    assert validate_draft(
        draft, [fact], "supported", enforce_recommendation_policy=True,
    ) == [fact]


def test_recommendation_rejects_an_unobserved_lifestyle_facility_name() -> None:
    fact = EvidenceFact(
        fact_id="recommendation-childcare-501",
        claims=(FactClaim("해뜰어린이집", "TEXT"),),
        data_as_of=date(2026, 7, 1), payload={}, source_id="lifestyle.childcare",
    )
    draft = DraftAnswer([DraftSentence(
        "확인되지않은어린이집이 가깝습니다.",
        [fact.fact_id], [DraftClaim(fact.fact_id, "해뜰어린이집", "TEXT")],
    )])

    with pytest.raises(GroundingValidationError) as raised:
        validate_draft(
            draft, [fact], "supported", enforce_recommendation_policy=True,
        )

    assert raised.value.reason_code == "GROUNDING_RECOMMENDATION_TEXT_OUTSIDE_OBSERVATION"
