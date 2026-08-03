from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest
from ai_service.models import ConversationContext, ConversationMemory
from ai_service.property_chat.answer_document import CompoundAnswerDocument
from ai_service.property_chat.academy_locations import (
    AcademyLocation,
    AcademyLocationSearchResult,
)
from ai_service.property_chat.engine import (
    GroundedChatbotEngine,
    RecommendationExecutionError,
)
from ai_service.property_chat.candidate_selection import CandidateObservationSummary
from ai_service.property_chat.deterministic_router import DeterministicQueryRouter
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


class PropertyRepository:
    def find_complexes(self, name, region_name, limit):
        del region_name, limit
        return [ComplexRecord(
            complex_id=501, display_name=name, region_code="11710",
            region_name="송파구", address="서울 송파구", latitude=37.5,
            longitude=127.1, marker_safe=True,
            data_updated_at=datetime(2026, 7, 20, tzinfo=UTC),
        )]

    def recent_trades(self, *args):
        del args
        return []

    def latest_trade_date(self):
        return date(2026, 7, 20)

    def find_complex_by_id(self, complex_id):
        names = {501: "잠실엘스", 502: "헬리오시티"}
        name = names.get(complex_id)
        if name is None:
            return None
        return ComplexRecord(
            complex_id=complex_id,
            display_name=name,
            region_code="11710",
            region_name="송파구",
            address=f"서울 송파구 {name}",
            latitude=37.5,
            longitude=127.1,
            marker_safe=True,
            data_updated_at=datetime(2026, 7, 20, tzinfo=UTC),
        )


class PartiallyFailingPropertyRepository(PropertyRepository):
    def recent_trades(self, *args):
        del args
        raise RuntimeError("must-not-leak")


class AcademyRepository:
    def nearby(self, **_kwargs):
        return AcademyLocationSearchResult(
            locations=(AcademyLocation(
                store_id="academy-1", name="잠실학원",
                small_category_code="P10101", status="OPEN",
                address="서울 송파구", distance_meters=320,
                dataset_version="academy-v1",
                observed_at=datetime(2026, 7, 20, tzinfo=UTC),
                registry_match=None,
            ),),
            matched_count=1, coordinate_coverage=1.0,
            dataset_version="academy-v1",
            observed_at=datetime(2026, 7, 20, tzinfo=UTC), verified_zero=False,
        )


class RailRepository:
    def nearby(self, **_kwargs):
        return RailStationSearchResult(
            stations=(RailStation(
                station_name="잠실", lines=("2호선", "8호선"),
                occurrence_ids=("rail-2", "rail-8"), distance_meters=640,
            ),),
            occurrence_count=2, dataset_version="rail-v1",
            source_date=date(2026, 6, 30),
        )


class FailingRailRepository:
    def nearby(self, **_kwargs):
        raise OSError("provider detail must not leak")


class AmbiguousCompoundRepository(PropertyRepository):
    def __init__(self) -> None:
        self.recent_complex_ids: list[int] = []
        self.summary_calls = 0

    def find_complexes(self, name, region_name, limit):
        del name, region_name
        assert limit == 6
        return [
            ComplexRecord(
                complex_id=501, parcel_id=8015, display_name="후보 1",
                region_code="11710", region_name="송파구", address="서울 송파구",
                latitude=37.5, longitude=127.1, marker_safe=True,
                data_updated_at=datetime(2026, 7, 20, tzinfo=UTC), unit_count=5_000,
            ),
            ComplexRecord(
                complex_id=502, parcel_id=8015, display_name="후보 2",
                region_code="11710", region_name="송파구", address="서울 송파구",
                latitude=37.5, longitude=127.1, marker_safe=True,
                data_updated_at=datetime(2026, 7, 20, tzinfo=UTC), unit_count=20,
            ),
        ]

    def candidate_observation_summaries(
        self, complex_ids, start_date, end_date, area, capability,
    ):
        del start_date, end_date, area
        self.summary_calls += 1
        return tuple(
            CandidateObservationSummary(
                complex_id,
                1 if complex_id == 502 else 0,
                date(2026, 7, 1) if complex_id == 502 else None,
                (capability,) if complex_id == 502 else (),
            )
            for complex_id in complex_ids
        )

    def recent_trades(self, complex_id, *_args):
        self.recent_complex_ids.append(complex_id)
        return [TradeRecord(7001, complex_id, date(2026, 7, 1), 200_000, 84.0, 10)]


class CompoundLanguageModel:
    def __init__(self, plans: tuple[QueryPlan, ...]) -> None:
        self._plans = plans

    async def plan_query(self, _request):
        return QueryPlanBundle(self._plans)

    async def draft_answer(self, *, facts, limitations, question):
        del question
        if not facts:
            return DraftAnswer([DraftSentence(limitations[0], [], [])])
        return DraftAnswer([DraftSentence(
            "검증된 정보를 확인했습니다.",
            [fact.fact_id for fact in facts],
            [DraftClaim(fact.fact_id, fact.claims[0].value, fact.claims[0].unit)
             for fact in facts],
        )])


def _query(plans: tuple[QueryPlan, ...], *, property_enabled=True, map_enabled=True):
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(), language_model=CompoundLanguageModel(plans),
        enabled_capabilities=(frozenset({"complex_identity", "recent_trade_lookup"})
                              if property_enabled else frozenset()),
        enabled_reference_capabilities=(frozenset({"kakao_place_search"})
                                        if map_enabled else frozenset()),
    )
    return asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스를 확인하고 병원도 지도에 보여줘"),
        user=AuthenticatedUser(user_id=1), request_id="request-compound",
    ))


def test_query_plan_bundle_merges_duplicates_and_preserves_first_appearance() -> None:
    bundle = QueryPlanBundle((
        QueryPlan("kakao_place_search", "잠실엘스", place_category="HOSPITAL"),
        QueryPlan("complex_identity", "잠실엘스"),
        QueryPlan("complex_identity", "잠실엘스"),
    ))

    assert [plan.capability for plan in bundle.fragments] == [
        "kakao_place_search", "complex_identity",
    ]
    with pytest.raises(ValueError):
        QueryPlanBundle(tuple(QueryPlan("complex_identity", f"단지 {index}") for index in range(5)))


def test_deterministic_router_preserves_clear_academy_and_rail_compound() -> None:
    planned = DeterministicQueryRouter(today=date(2026, 8, 3)).plan(
        ChatbotQueryRequest(
            question="잠실엘스 주변 학원 위치와 가까운 역·노선을 함께 알려줘"
        )
    )

    assert isinstance(planned, QueryPlanBundle)
    assert [fragment.capability for fragment in planned.fragments] == [
        "academy_lookup",
        "rail_station_lookup",
    ]


@pytest.mark.parametrize(
    ("question", "expected_capabilities"),
    [
        (
            "마포래미안푸르지오 전용 84㎡의 최근 실거래 5건을 거래일과 층까지 알려줘",
            ("recent_trade_lookup",),
        ),
        (
            "헬리오시티 전용 59㎡의 최근 1년 월별 가격 흐름과 거래량을 보여줘",
            ("price_trend",),
        ),
        (
            "잠실엘스 주변 학원 위치와 가까운 역·노선을 함께 알려줘",
            ("academy_lookup", "rail_station_lookup"),
        ),
        (
            "헬리오시티 위치와 세대수·사용승인일을 알려줘",
            ("complex_identity",),
        ),
        (
            "잠실엘스 전용 84㎡ 최근 실거래 3건과 1년 가격 흐름을 함께 보여줘",
            ("recent_trade_lookup", "price_trend"),
        ),
        (
            "래미안대치팰리스 주변 운영 중 초등학교와 가까운 역을 거리순으로 알려줘",
            ("school_location", "rail_station_lookup"),
        ),
        (
            "반포자이 주변 대규모점포 위치와 가까운 역·노선을 알려줘",
            ("retail_location", "rail_station_lookup"),
        ),
        (
            "올림픽파크포레온 위치와 세대수·최근 실거래를 함께 알려줘",
            ("complex_identity", "recent_trade_lookup"),
        ),
    ],
)
def test_example_questions_keep_clear_engine_fallback_intents(
    question: str, expected_capabilities: tuple[str, ...],
) -> None:
    class UnavailablePlanner(CompoundLanguageModel):
        async def plan_query(self, _request):
            raise ChatbotProviderUnavailable()

    engine = GroundedChatbotEngine(
        repository=PropertyRepository(), language_model=UnavailablePlanner(()),
        enabled_capabilities=frozenset({
            "complex_identity", "recent_trade_lookup", "price_trend",
        }),
        enabled_reference_capabilities=frozenset({
            "academy_lookup", "rail_station_lookup", "school_location",
            "retail_location",
        }),
        answer_first_enabled=True,
    )

    plans = asyncio.run(engine.plan_goals(ChatbotQueryRequest(question=question)))
    assert tuple(plan.capability for plan in plans) == expected_capabilities


def test_academy_and_rail_compound_preserves_both_verified_results() -> None:
    plans = (
        QueryPlan("academy_lookup", "잠실엘스", radius_meters=1_500),
        QueryPlan("rail_station_lookup", "잠실엘스", radius_meters=1_500),
    )
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(), language_model=CompoundLanguageModel(plans),
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"academy_lookup", "rail_station_lookup"}),
        academy_location_repository=AcademyRepository(),
        rail_station_repository=RailRepository(),
        answer_first_enabled=True,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question="잠실엘스 주변 학원 위치와 가까운 역·노선을 함께 알려줘"
        ),
        user=AuthenticatedUser(user_id=1), request_id="request-academy-rail",
    ))

    assert response["executionSummary"] == {"total": 2, "succeeded": 2, "failed": 0}
    assert [fragment["capability"] for fragment in response["fragments"]] == [
        "academy_lookup", "rail_station_lookup",
    ]
    assert "학원 위치 1곳" in response["uiSummary"]["headline"]["text"]
    assert "잠실" in response["uiSummary"]["headline"]["text"]
    assert "2호선·8호선" in response["uiSummary"]["headline"]["text"]
    assert {citation["sourceName"] for citation in response["citations"]} >= {
        "상가(상권)정보 API 교육업종",
        "전국도시철도역사정보표준데이터",
    }


def test_academy_and_rail_partial_keeps_academy_when_rail_fails() -> None:
    plans = (
        QueryPlan("academy_lookup", "잠실엘스", radius_meters=1_500),
        QueryPlan("rail_station_lookup", "잠실엘스", radius_meters=1_500),
    )
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(), language_model=CompoundLanguageModel(plans),
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"academy_lookup", "rail_station_lookup"}),
        academy_location_repository=AcademyRepository(),
        rail_station_repository=FailingRailRepository(),
        answer_first_enabled=True,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question="잠실엘스 주변 학원 위치와 가까운 역·노선을 함께 알려줘"
        ),
        user=AuthenticatedUser(user_id=1), request_id="request-academy-rail-partial",
    ))

    assert response["status"] == "partial_success"
    assert response["executionSummary"] == {"total": 2, "succeeded": 2, "failed": 0}
    assert response["conversationResolution"]["goals"][1]["status"] == "degraded"
    assert "학원 위치 1곳" in response["uiSummary"]["headline"]["text"]
    assert "철도역·노선" in response["uiSummary"]["headline"]["text"]
    assert any(
        citation["sourceName"] == "상가(상권)정보 API 교육업종"
        for citation in response["citations"]
    )
    assert "provider detail" not in str(response)


def test_query_plan_bundle_merges_compatible_lists_and_rejects_conflicts() -> None:
    bundle = QueryPlanBundle((
        QueryPlan("school_location", "잠실엘스", school_levels=("ELEMENTARY",)),
        QueryPlan("school_location", "잠실엘스", school_levels=("MIDDLE",)),
    ))

    assert len(bundle.fragments) == 1
    assert bundle.fragments[0].school_levels == ("ELEMENTARY", "MIDDLE")
    with pytest.raises(ValueError, match="conflict"):
        QueryPlanBundle((
            QueryPlan("complex_identity", "잠실엘스"),
            QueryPlan("complex_identity", "헬리오시티"),
        ))


def test_supervisor_planning_preserves_same_turn_recommendation_and_comparison() -> None:
    plans = (
        QueryPlan(
            "recommendation", "강남구", region_name="강남구", limit=2,
            recommendation_mode="CRITERIA", minimum_unit_count=500,
        ),
        QueryPlan(
            "comparison", "새 추천 후보", complex_names=("새 후보 1", "새 후보 2"),
        ),
    )
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(),
        language_model=CompoundLanguageModel(plans),
        enabled_capabilities=frozenset({"recommendation", "comparison"}),
        dependent_workflow_enabled=True,
    )
    request = ChatbotQueryRequest(
        question="강남구에서 새로 추천하고 그 후보들을 비교해줘",
        conversationContext=ConversationContext(
            memory=ConversationMemory(
                version=2,
                complexIds=[501, 502],
                regionCode="11710",
                scopeKind="RECOMMENDATION",
            ),
        ),
    )

    planned = asyncio.run(engine.plan_supervisor_goals(request))

    assert {plan.capability for plan in planned} == {"recommendation", "comparison"}


def test_supervisor_planning_still_resolves_pure_prior_recommendation_reference() -> None:
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(),
        language_model=CompoundLanguageModel((QueryPlan(
            "comparison", "이전 추천", complex_names=("이전 1", "이전 2"),
        ),)),
        enabled_capabilities=frozenset({"comparison"}),
        dependent_workflow_enabled=True,
    )
    request = ChatbotQueryRequest(
        question="방금 추천한 1위와 2위를 비교해줘",
        conversationContext=ConversationContext(
            memory=ConversationMemory(
                version=2,
                complexIds=[501, 502],
                regionCode="11710",
                scopeKind="RECOMMENDATION",
            ),
        ),
    )

    planned = asyncio.run(engine.plan_supervisor_goals(request))

    assert len(planned) == 1
    assert planned[0].capability == "comparison"
    assert planned[0].complex_names == ("잠실엘스", "헬리오시티")


def test_compound_query_aggregates_grounded_artifact_and_map_action() -> None:
    response = _query((
        QueryPlan("kakao_place_search", "잠실엘스", place_category="HOSPITAL"),
        QueryPlan("complex_identity", "잠실엘스"),
    ))

    assert response["success"] is True
    assert response["status"] == "success"
    assert response["executionSummary"] == {"total": 2, "succeeded": 2, "failed": 0}
    assert [fragment["capability"] for fragment in response["fragments"]] == [
        "complex_identity", "kakao_place_search",
    ]
    assert [artifact["type"] for artifact in response["uiArtifacts"]] == ["factList"]
    assert [action["category"] for action in response["uiActions"]] == ["HOSPITAL"]
    assert response["evidenceSummary"]["factCount"] == 1
    assert response["uiSummary"]["version"] == 1
    assert response["uiSummary"]["headline"]["text"] == (
        "잠실엘스는 서울 송파구에 있습니다. "
        "잠실엘스의 요청 조건에서 검증된 정보를 확인했습니다."
    )
    assert "개 요청을" not in response["uiSummary"]["headline"]["text"]
    assert [item["fragmentId"] for item in response["uiSummary"]["fragmentSummaries"]] == [
        "fragment-1", "fragment-2",
    ]


def test_compound_query_preserves_success_when_one_fragment_is_unavailable() -> None:
    response = _query((
        QueryPlan("complex_identity", "잠실엘스"),
        QueryPlan("recent_trade_lookup", "잠실엘스"),
    ))

    assert response["success"] is True
    assert response["status"] == "partial_success"
    assert response["executionSummary"] == {"total": 2, "succeeded": 1, "failed": 1}
    assert [fragment["status"] for fragment in response["fragments"]] == [
        "success", "failed",
    ]
    assert response["evidenceSummary"]["status"] == "partial"
    assert response["uiArtifacts"][0]["type"] == "factList"


def test_answer_first_compound_shares_one_capability_aware_complex() -> None:
    repository = AmbiguousCompoundRepository()
    plans = (
        QueryPlan("complex_identity", "동명 단지"),
        QueryPlan(
            "recent_trade_lookup", "동명 단지",
            start_date=date(2025, 7, 20), end_date=date(2026, 7, 20),
            exclusive_area_square_meters=84.0,
        ),
    )
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=CompoundLanguageModel(plans),
        enabled_capabilities=frozenset({"complex_identity", "recent_trade_lookup"}),
        answer_first_enabled=True,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="동명 단지 정보와 84㎡ 실거래를 알려줘"),
        user=AuthenticatedUser(user_id=1),
        request_id="request-shared-compound",
    ))

    assert repository.summary_calls == 1
    assert repository.recent_complex_ids == [502]
    assert {action["complexId"] for action in response["uiActions"]} == {502}
    assert sum(action.get("autoRun") is True for action in response["uiActions"]) == 1


def test_compound_query_preserves_success_when_one_fragment_raises() -> None:
    plans = (
        QueryPlan("complex_identity", "잠실엘스"),
        QueryPlan("recent_trade_lookup", "잠실엘스"),
    )
    engine = GroundedChatbotEngine(
        repository=PartiallyFailingPropertyRepository(),
        language_model=CompoundLanguageModel(plans),
        enabled_capabilities=frozenset({"complex_identity", "recent_trade_lookup"}),
        answer_first_enabled=True,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(question="잠실엘스 위치와 최근 거래를 알려줘"),
        user=AuthenticatedUser(user_id=1),
        request_id="request-partial-exception",
    ))

    assert response["success"] is True
    assert response["status"] == "partial_success"
    assert response["executionSummary"] == {"total": 2, "succeeded": 2, "failed": 0}
    assert [fragment["status"] for fragment in response["fragments"]] == [
        "success", "success",
    ]
    assert response["conversationResolution"]["answerMode"] == "BEST_EFFORT"
    assert response["conversationResolution"]["goals"][1]["status"] == "degraded"
    assert "must-not-leak" not in str(response)


def test_compound_query_is_failed_when_every_fragment_is_unavailable() -> None:
    response = _query((
        QueryPlan("complex_identity", "잠실엘스"),
        QueryPlan("kakao_place_search", "잠실엘스", place_category="HOSPITAL"),
    ), property_enabled=False, map_enabled=False)

    assert response["success"] is False
    assert response["status"] == "failed"
    assert response["executionSummary"] == {"total": 2, "succeeded": 0, "failed": 2}
    assert response["uiArtifacts"] == []
    assert response["uiActions"] == []


def test_compound_recommendation_maps_response_serialization_failure(
    monkeypatch,
) -> None:
    def fail_serialization(*_args, **_kwargs):
        raise ValueError("must-not-leak")

    monkeypatch.setattr(
        CompoundAnswerDocument,
        "to_public_dict",
        fail_serialization,
    )
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(),
        language_model=CompoundLanguageModel((
            QueryPlan("complex_identity", "잠실엘스"),
            QueryPlan("recommendation", "추천 조건 확인"),
        )),
        enabled_capabilities=frozenset({"complex_identity", "recommendation"}),
    )

    with pytest.raises(ChatbotProviderUnavailable) as raised:
        asyncio.run(engine.query(
            request=ChatbotQueryRequest(question="잠실엘스를 확인하고 추천해줘"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-compound-recommendation-failure",
        ))

    assert isinstance(raised.value.__cause__, RecommendationExecutionError)
    assert raised.value.__cause__.reason_code == (
        "RECOMMENDATION_RESPONSE_SERIALIZATION_FAILED"
    )
