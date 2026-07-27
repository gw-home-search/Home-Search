from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest
from ai_service.models import ConversationContext, ConversationMemory
from ai_service.property_chat.answer_document import CompoundAnswerDocument
from ai_service.property_chat.engine import (
    GroundedChatbotEngine,
    RecommendationExecutionError,
)
from ai_service.property_chat.models import (
    ComplexRecord,
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    QueryPlan,
    QueryPlanBundle,
)


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


def test_query_plan_bundle_merges_duplicates_and_uses_static_capability_order() -> None:
    bundle = QueryPlanBundle((
        QueryPlan("kakao_place_search", "잠실엘스", place_category="HOSPITAL"),
        QueryPlan("complex_identity", "잠실엘스"),
        QueryPlan("complex_identity", "잠실엘스"),
    ))

    assert [plan.capability for plan in bundle.fragments] == [
        "complex_identity", "kakao_place_search",
    ]
    with pytest.raises(ValueError):
        QueryPlanBundle(tuple(QueryPlan("complex_identity", f"단지 {index}") for index in range(5)))


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
    assert response["uiSummary"]["headline"]["text"] == "2개 요청을 모두 확인했습니다."
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
