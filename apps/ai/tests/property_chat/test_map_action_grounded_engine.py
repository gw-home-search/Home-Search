from __future__ import annotations

import asyncio
from datetime import UTC, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.engine import GroundedChatbotEngine
from ai_service.property_chat.models import (
    ComplexRecord,
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    QueryPlan,
)


class PropertyRepository:
    def __init__(self, *, marker_safe: bool = True) -> None:
        self.marker_safe = marker_safe

    def find_complexes(self, *_args):
        return [
            ComplexRecord(
                complex_id=501,
                display_name="잠실동 잠실엘스",
                region_code="11710101",
                region_name="잠실동",
                address="서울 송파구 잠실동 19",
                latitude=37.513 if self.marker_safe else None,
                longitude=127.082 if self.marker_safe else None,
                marker_safe=self.marker_safe,
                data_updated_at=datetime(2026, 7, 20, tzinfo=UTC),
            )
        ]

    def recent_trades(self, *_args):
        return []

    def monthly_trends(self, *_args):
        return []

    def latest_trade_date(self):
        return None


class LanguageModel:
    def __init__(self, category: str = "HOSPITAL") -> None:
        self.category = category

    async def plan_query(self, _request):
        return QueryPlan(
            capability="kakao_place_search",
            complex_name="잠실엘스",
            place_category=self.category,
        )

    async def draft_answer(self, *, facts, limitations, question):
        del question
        if not facts:
            return DraftAnswer([DraftSentence(limitations[0], [], [])])
        fact = facts[0]
        name = next(claim for claim in fact.claims if claim.unit == "TEXT")
        return DraftAnswer([
            DraftSentence(
                f"{name.value}을 기준으로 지도 검색을 열 수 있습니다.",
                [fact.fact_id],
                [DraftClaim(fact.fact_id, name.value, name.unit)],
            )
        ])


def _query(model: LanguageModel, *, marker_safe: bool = True):
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(marker_safe=marker_safe),
        language_model=model,
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"kakao_place_search"}),
    )
    return asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question="잠실엘스 주변 병원을 지도에 보여줘"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-map-action",
        )
    )


@pytest.mark.parametrize(
    ("category", "label"),
    [
        ("HOSPITAL", "지도에서 병원 보기"),
        ("DAYCARE_KINDERGARTEN", "지도에서 어린이집 보기"),
    ],
)
def test_map_action_uses_verified_complex_coordinate(
    category: str,
    label: str,
) -> None:
    response = _query(LanguageModel(category))

    assert response["success"] is True
    assert response["uiActions"] == [{
        "type": "showNearbyCategory",
        "version": 1,
        "actionId": f"action-request-map-action-{category.lower()}",
        "label": label,
        "category": category,
        "center": {"lat": 37.513, "lng": 127.082},
        "level": 4,
        "factIds": ["property-complex-501"],
    }]
    assert response["citations"][0]["factIds"] == ["property-complex-501"]


def test_map_action_without_verified_coordinate_is_unavailable() -> None:
    response = _query(LanguageModel(), marker_safe=False)

    assert response["success"] is False
    assert response["uiActions"] == []


@pytest.mark.parametrize(
    "invalid_text",
    [
        "잠실엘스 주변에는 가까운 병원이 있습니다.",
        "잠실엘스 주변 병원은 3개입니다.",
        "잠실엘스에서 어린이집까지 거리는 500m입니다.",
        "잠실엘스 주변 공식 병원 정보를 지도에 표시합니다.",
    ],
)
def test_map_action_rejects_unobserved_place_claim(invalid_text: str) -> None:
    model = LanguageModel()

    async def invalid_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts,
            limitations=limitations,
            question=question,
        )
        sentence = draft.sentences[0]
        return DraftAnswer([
            DraftSentence(
                invalid_text,
                sentence.fact_ids,
                sentence.claims,
            )
        ])

    model.draft_answer = invalid_draft  # type: ignore[method-assign]

    with pytest.raises(Exception):
        _query(model)
