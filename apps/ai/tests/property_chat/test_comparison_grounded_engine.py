from __future__ import annotations

import asyncio
from dataclasses import replace
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
from ai_service.property_chat.academy_locations import AcademyLocationSearchResult
from ai_service.property_chat.childcare_centers import ChildcareCenter, ChildcareSearchResult
from ai_service.property_chat.models import SchoolRecord, SchoolSearchResult, SchoolSnapshot


def _complex(complex_id: int, name: str, lat: float, lng: float) -> ComplexRecord:
    return ComplexRecord(
        complex_id=complex_id,
        display_name=name,
        region_code="11710",
        region_name="송파구",
        address=f"서울 송파구 {name}",
        latitude=lat,
        longitude=lng,
        marker_safe=True,
        data_updated_at=datetime(2026, 7, 20, tzinfo=UTC),
        unit_count=5_000 + complex_id,
        use_date=date(2008, 9, 30),
    )


class PropertyRepository:
    def __init__(self) -> None:
        self.batch_lookup_calls = 0
        self.batch_trade_calls = 0
        self.complexes = {
            "잠실엘스": (_complex(501, "잠실엘스", 37.513, 127.082),),
            "헬리오시티": (_complex(502, "헬리오시티", 37.497, 127.107),),
        }

    def find_complexes_batch(self, names, region_name, limit_per_name):
        self.batch_lookup_calls += 1
        assert names == ("잠실엘스", "헬리오시티")
        assert region_name is None
        assert limit_per_name == 6
        return self.complexes

    def recent_trades_batch(self, ids, start, end, area, limit):
        self.batch_trade_calls += 1
        assert ids == (501, 502)
        assert start == date(2025, 7, 21)
        assert end == date(2026, 7, 20)
        assert area == 84.0
        assert limit == 3
        return {
            501: tuple(
                TradeRecord(index, 501, date(2026, 7, 21 - index), amount, 84.0, 10)
                for index, amount in enumerate((205_000, 210_000, 195_000), start=1)
            ),
            502: (
                TradeRecord(4, 502, date(2026, 7, 20), 190_000, 84.1, 9),
                TradeRecord(5, 502, date(2026, 7, 19), 185_000, 83.9, 8),
            ),
        }

    def latest_trade_date(self):
        return date(2026, 7, 20)

    def find_complexes(self, *_args):
        raise AssertionError("comparison must use one batch complex query")

    def recent_trades(self, *_args):
        raise AssertionError("comparison must use one batch trade query")

    def monthly_trends(self, *_args):
        return []


class RailRepository:
    def __init__(self) -> None:
        self.calls = 0

    def nearest_batch(self, *, points: tuple[CandidatePoint, ...], radius_meters: int):
        self.calls += 1
        assert 1 <= len(points) <= 2
        assert radius_meters == 1500
        return {
            point.complex_id: RailStationSearchResult(
                stations=(RailStation(
                    station_name="잠실역" if point.complex_id == 501 else "송파역",
                    lines=("2호선",),
                    occurrence_ids=("shared-rail",),
                    distance_meters=420,
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
                    fact_id="shared-retail",
                    name="롯데마트",
                    category="LARGE_STORE",
                    subcategory="LARGE_MART",
                    status="OPEN",
                    address="서울 송파구",
                    distance_meters=650,
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
            capability="comparison",
            complex_name="잠실엘스",
            complex_names=("잠실엘스", "헬리오시티"),
            exclusive_area_square_meters=84.0,
        )

    async def draft_answer(self, *, facts, limitations, question):
        del limitations, question
        return DraftAnswer([
            DraftSentence(
                "두 단지를 동일한 기준으로 비교했습니다.",
                [fact.fact_id for fact in facts],
                [
                    DraftClaim(fact.fact_id, fact.claims[0].value, fact.claims[0].unit)
                    for fact in facts
                ],
            )
        ])


def _query(
    model: LanguageModel | None = None,
    *,
    property_repository: PropertyRepository | None = None,
    rail_repository: RailRepository | None = None,
    retail_repository: RetailRepository | None = None,
    without_references: bool = False,
    school_repository=None,
    academy_repository=None,
    childcare_repository=None,
    question: str = "잠실엘스와 헬리오시티 84㎡ 비교",
):
    property_repository = property_repository or PropertyRepository()
    rail_repository = None if without_references else rail_repository or RailRepository()
    retail_repository = None if without_references else retail_repository or RetailRepository()
    engine = GroundedChatbotEngine(
        repository=property_repository,
        language_model=model or LanguageModel(),
        enabled_capabilities=frozenset({"comparison"}),
        rail_station_repository=rail_repository,
        point_facility_repository=retail_repository,
        school_repository=school_repository,
        academy_location_repository=academy_repository,
        childcare_repository=childcare_repository,
        today=lambda: date(2026, 7, 20),
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question=question),
        user=AuthenticatedUser(user_id=1),
        request_id="request-comparison",
    ))
    return response, property_repository, rail_repository, retail_repository


def test_comparison_uses_batch_queries_and_keeps_partial_price_cells() -> None:
    response, property_repository, rail_repository, retail_repository = _query()

    assert response["success"] is True
    assert response["status"] == "partial_success"
    assert property_repository.batch_lookup_calls == 1
    assert property_repository.batch_trade_calls == 1
    assert rail_repository.calls == 1
    assert retail_repository.calls == 1
    table = response["uiArtifacts"][0]
    assert table["type"] == "comparisonTable"
    assert table["basis"] == {
        "cutoffDate": "2026-07-20",
        "startDate": "2025-07-21",
        "exclusiveAreaSquareMeters": 84.0,
    }
    assert [row["key"] for row in table["rows"]] == [
        "latestTrade", "recentThreeMedian", "tradeSampleCount", "unitCount",
        "useDate", "nearestRail", "nearestRetail",
    ]
    latest = table["rows"][0]["cells"]
    assert latest[0]["value"] == "2026-07-20 · 20억 5,000만원"
    assert latest[1]["availability"] == "unavailable"
    assert "3건 미만" in latest[1]["reason"]
    citation_fact_ids = {
        fact_id for citation in response["citations"] for fact_id in citation["factIds"]
    }
    assert set(latest[0]["factIds"]).issubset(citation_fact_ids)
    rail_cells = table["rows"][5]["cells"]
    retail_cells = table["rows"][6]["cells"]
    assert rail_cells[0]["factIds"] != rail_cells[1]["factIds"]
    assert retail_cells[0]["factIds"] != retail_cells[1]["factIds"]


def test_comparison_rejects_winner_language() -> None:
    model = LanguageModel()

    async def invalid_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        sentence = draft.sentences[0]
        return DraftAnswer([DraftSentence(
            "잠실엘스가 더 좋은 우승 단지입니다.", sentence.fact_ids, sentence.claims
        )])

    model.draft_answer = invalid_draft  # type: ignore[method-assign]
    with pytest.raises(Exception):
        _query(model)


def test_comparison_keeps_static_rows_when_reference_sources_are_unavailable() -> None:
    response, _, rail_repository, retail_repository = _query(without_references=True)

    assert rail_repository is None
    assert retail_repository is None
    table = response["uiArtifacts"][0]
    assert all(
        cell["reason"] == "시설 source가 준비되지 않았습니다."
        for row in table["rows"][5:]
        for cell in row["cells"]
    )


def test_comparison_only_marks_the_missing_coordinate_facility_cells() -> None:
    repository = PropertyRepository()
    repository.complexes["헬리오시티"] = (
        replace(
            repository.complexes["헬리오시티"][0],
            latitude=None,
            longitude=None,
            marker_safe=False,
        ),
    )

    response, _, _, _ = _query(property_repository=repository)

    table = response["uiArtifacts"][0]
    assert table["rows"][5]["cells"][0]["availability"] == "available"
    assert table["rows"][5]["cells"][1]["reason"] == (
        "검증된 단지 표시 좌표가 없습니다."
    )


def test_comparison_stops_before_observation_when_a_name_is_ambiguous() -> None:
    repository = PropertyRepository()
    repository.complexes["헬리오시티"] = (
        repository.complexes["헬리오시티"][0],
        _complex(503, "다른 지역 헬리오시티", 37.4, 127.0),
    )

    response, property_repository, _, _ = _query(property_repository=repository)

    assert response["status"] == "partial_success"
    assert response["uiArtifacts"] == []
    assert property_repository.batch_trade_calls == 0
    assert any("동명 단지" in item for item in response["limitations"])


def test_comparison_rejects_two_aliases_resolving_to_the_same_complex() -> None:
    repository = PropertyRepository()
    repository.complexes["헬리오시티"] = (
        replace(repository.complexes["잠실엘스"][0], display_name="잠실엘스 별칭"),
    )

    response, property_repository, _, _ = _query(property_repository=repository)

    assert response["uiArtifacts"] == []
    assert property_repository.batch_trade_calls == 0
    assert any("같은 단지" in item for item in response["limitations"])


def test_comparison_is_unavailable_without_a_global_or_explicit_cutoff() -> None:
    repository = PropertyRepository()
    repository.latest_trade_date = lambda: None  # type: ignore[method-assign]

    response, property_repository, _, _ = _query(property_repository=repository)

    assert response["success"] is False
    assert response["uiArtifacts"] == []
    assert property_repository.batch_trade_calls == 0
    assert any("최신 거래일" in item for item in response["limitations"])


def test_comparison_adds_student_rows_only_for_an_explicit_student_theme() -> None:
    class StudentModel(LanguageModel):
        async def plan_query(self, request):
            return replace(
                await super().plan_query(request), lifestyle_themes=("STUDENT",)
            )

    class Schools:
        def nearest_by_level_batch(self, *, points, school_levels, radius_meters):
            return SchoolSnapshot(
                "school-v1", date(2026, 6, 30), datetime(2026, 7, 1, tzinfo=UTC)
            ), {
                point.complex_id: SchoolSearchResult((SchoolRecord(
                    school_id=f"school-{point.complex_id}", school_name="가까운초등학교",
                    school_level="ELEMENTARY", operating_status="운영", road_address=None,
                    lot_address=None, latitude=point.latitude, longitude=point.longitude,
                    distance_meters=300,
                ),), 1) for point in points
            }

    class Academies:
        def nearby_counts_batch(self, *, points, radius_meters):
            return {point.complex_id: AcademyLocationSearchResult(
                (), 5, 1.0, "academy-v1", datetime(2026, 7, 1, tzinfo=UTC), False
            ) for point in points}

    response, *_ = _query(
        StudentModel(), school_repository=Schools(), academy_repository=Academies(),
        question="학생 기준으로 잠실엘스와 헬리오시티 84㎡ 비교",
    )

    table = response["uiArtifacts"][0]
    assert table["rows"][-1]["key"] == "studentAccess"
    assert all("Sbiz 교육업소 5곳" in cell["value"] for cell in table["rows"][-1]["cells"])


def test_comparison_adds_official_childcare_count_and_nearest_row() -> None:
    class ChildModel(LanguageModel):
        async def plan_query(self, request):
            return replace(
                await super().plan_query(request), lifestyle_themes=("YOUNG_CHILD",)
            )

    class Childcare:
        def nearby_batch(self, *, points, radius_meters):
            return {point.complex_id: ChildcareSearchResult(
                centers=(ChildcareCenter(
                    f"center-{point.complex_id}", "해뜰어린이집", "국공립", 50, 250,
                    date(2026, 7, 1), "child-v1",
                ),), matched_count=3, returned_count=1, has_more=True,
                verified_zero=False, coordinate_coverage=1.0, dataset_version="child-v1",
                observed_at=datetime(2026, 7, 1, tzinfo=UTC), freshness_days=45,
            ) for point in points}

    response, *_ = _query(
        ChildModel(), childcare_repository=Childcare(),
        question="영유아 기준으로 잠실엘스와 헬리오시티 84㎡ 비교",
    )

    row = response["uiArtifacts"][0]["rows"][-1]
    assert row["key"] == "youngChildAccess"
    assert all("공식 운영 어린이집 3곳" in cell["value"] for cell in row["cells"])
    assert "정원" not in str(row)
