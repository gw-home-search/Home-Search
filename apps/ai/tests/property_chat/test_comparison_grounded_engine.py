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
    QueryPlanBundle,
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

    def find_complex_by_id(self, complex_id):
        return next(
            (
                records[0]
                for records in self.complexes.values()
                if records[0].complex_id == complex_id
            ),
            None,
        )

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
    answer_first_enabled: bool = False,
    question: str = "잠실엘스와 헬리오시티 전용 84㎡ 최근 실거래를 비교해줘",
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
        answer_first_enabled=answer_first_enabled,
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question=question),
        user=AuthenticatedUser(user_id=1),
        request_id="request-comparison",
    ))
    return response, property_repository, rail_repository, retail_repository


def test_mapo_complex_information_comparison_example_runs_through_engine() -> None:
    names = ("마포래미안푸르지오1단지", "마포래미안푸르지오4단지")

    class MapoRepository(PropertyRepository):
        def __init__(self) -> None:
            super().__init__()
            self.complexes = {
                names[0]: (_complex(601, names[0], 37.55, 126.95),),
                names[1]: (_complex(604, names[1], 37.56, 126.96),),
            }

        def find_complexes_batch(self, requested_names, region_name, limit_per_name):
            self.batch_lookup_calls += 1
            assert (requested_names, region_name, limit_per_name) == (names, None, 6)
            return self.complexes

        def recent_trades_batch(self, ids, start, end, area, limit):
            self.batch_trade_calls += 1
            assert (ids, area, limit) == ((601, 604), None, 3)
            return {
                601: (TradeRecord(6011, 601, end, 180_000, 84.0, 10),),
                604: (TradeRecord(6041, 604, end, 175_000, 84.0, 8),),
            }

    class MapoLanguageModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="comparison", complex_name=names[0],
                complex_names=names,
            )

    response, *_ = _query(
        model=MapoLanguageModel(), property_repository=MapoRepository(),
        without_references=True, answer_first_enabled=True,
        question="마포래미안푸르지오1단지와 4단지를 세대수·사용승인일로 비교해줘",
    )

    assert response["success"] is True
    assert all(name in response["uiSummary"]["headline"]["text"] for name in names)
    table = next(
        artifact for artifact in response["uiArtifacts"]
        if artifact["type"] == "comparisonTable"
    )
    assert [column["label"] for column in table["columns"]] == list(names)
    assert {row["key"] for row in table["rows"]} >= {"unitCount", "useDate"}


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


def test_comparison_without_area_keeps_non_price_result_and_skips_trade_query() -> None:
    class NoAreaModel(LanguageModel):
        async def plan_query(self, request):
            del request
            return QueryPlan(
                capability="comparison",
                complex_name="잠실엘스",
                complex_names=("잠실엘스", "헬리오시티"),
                exclusive_area_square_meters=None,
            )

    response, property_repository, _, _ = _query(
        NoAreaModel(),
        question="잠실엘스와 헬리오시티를 교육과 교통으로 비교해줘",
    )

    assert response["success"] is True
    assert property_repository.batch_trade_calls == 0
    table = response["uiArtifacts"][0]
    assert table["version"] == 2
    assert table["basis"] == {
        "cutoffDate": None,
        "startDate": None,
        "exclusiveAreaSquareMeters": None,
    }
    assert [row["key"] for row in table["rows"]] == [
        "unitCount", "useDate", "nearestRail", "nearestRetail",
    ]
    assert [row["group"] for row in table["rows"]] == [
        "SCALE", "SCALE", "TRANSPORT", "LIFESTYLE",
    ]
    assert all("가격" not in row["label"] for row in table["rows"])


def test_comparison_reuses_ranked_candidates_from_recommendation_memory() -> None:
    class ContextModel(LanguageModel):
        async def plan_query(self, _request):
            return QueryPlan(
                capability="comparison",
                complex_name="이전 추천",
                complex_names=("이전 추천 1", "이전 추천 2"),
                exclusive_area_square_meters=None,
            )

    repository = PropertyRepository()
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=ContextModel(),
        enabled_capabilities=frozenset({"comparison"}),
        dependent_workflow_enabled=True,
        today=lambda: date(2026, 7, 20),
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest.model_validate({
            "question": "방금 추천한 1위와 2위를 교육과 교통으로 비교해줘",
            "conversationContext": {
                "messages": [],
                "memory": {
                    "version": 2,
                    "scopeKind": "RECOMMENDATION",
                    "complexIds": [501, 502],
                    "regionCode": "11710",
                },
            },
        }),
        user=AuthenticatedUser(user_id=1),
        request_id="request-dependent-comparison",
    ))

    table = response["uiArtifacts"][0]
    assert table["type"] == "comparisonTable"
    assert [column["label"] for column in table["columns"]] == [
        "잠실엘스", "헬리오시티",
    ]


def test_compound_comparison_and_hospital_map_action_keep_independent_results() -> None:
    class CompoundPropertyRepository(PropertyRepository):
        def find_complexes(self, name, region_name, limit):
            assert (name, region_name, limit) == ("잠실엘스", None, 6)
            return list(self.complexes[name])

    class CompoundLanguageModel(LanguageModel):
        async def plan_query(self, request):
            return QueryPlanBundle((
                QueryPlan(
                    capability="kakao_place_search", complex_name="잠실엘스",
                    place_category="HOSPITAL",
                ),
                await super().plan_query(request),
            ))

    engine = GroundedChatbotEngine(
        repository=CompoundPropertyRepository(), language_model=CompoundLanguageModel(),
        enabled_capabilities=frozenset({"comparison"}),
        enabled_reference_capabilities=frozenset({"kakao_place_search"}),
        rail_station_repository=RailRepository(),
        point_facility_repository=RetailRepository(),
        today=lambda: date(2026, 7, 20),
    )
    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question="잠실엘스와 헬리오시티 84㎡를 비교하고 병원도 지도에 보여줘"
        ),
        user=AuthenticatedUser(user_id=1), request_id="request-comparison-map",
    ))

    assert [fragment["capability"] for fragment in response["fragments"]] == [
        "comparison", "kakao_place_search",
    ]
    assert response["uiArtifacts"][0]["type"] == "comparisonTable"
    assert response["uiActions"][0]["category"] == "HOSPITAL"


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
        cell["reason"] == "필요한 시설 데이터가 아직 준비되지 않았습니다."
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


def test_answer_first_comparison_selects_one_candidate_per_explicit_name() -> None:
    repository = PropertyRepository()
    repository.complexes["헬리오시티"] = (
        replace(repository.complexes["헬리오시티"][0], unit_count=9_510),
        replace(
            _complex(503, "다른 지역 헬리오시티", 37.4, 127.0),
            unit_count=20,
        ),
    )

    response, property_repository, _, _ = _query(
        property_repository=repository,
        answer_first_enabled=True,
    )

    assert response["success"] is True
    assert property_repository.batch_trade_calls == 1
    comparison = next(
        artifact for artifact in response["uiArtifacts"]
        if artifact["type"] == "comparisonTable"
    )
    assert [column["key"] for column in comparison["columns"]] == ["501", "502"]
    alternatives = next(
        artifact for artifact in response["uiArtifacts"]
        if artifact["type"] == "factList"
    )
    assert alternatives["title"] == "추가로 확인된 후보"
    assert alternatives["items"][0]["label"] == "다른 지역 헬리오시티"


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
    assert all("학원 위치 5곳" in cell["value"] for cell in table["rows"][-1]["cells"])


def test_comparison_includes_verified_childcare_in_active_rows() -> None:
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

    rows = response["uiArtifacts"][0]["rows"]
    childcare_row = next(row for row in rows if row["key"] == "youngChildAccess")
    assert childcare_row["label"] == "800m 공식 어린이집"
    assert all(cell["availability"] == "available" for cell in childcare_row["cells"])
    assert all("어린이집" in cell["value"] for cell in childcare_row["cells"])
