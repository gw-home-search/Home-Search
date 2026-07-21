from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime

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
from ai_service.property_chat.reference_facilities import FacilityFact, FacilitySearchResult


class PropertyRepository:
    def find_complexes(self, _name: str, _region: str | None, _limit: int):
        return [
            ComplexRecord(
                complex_id=501,
                display_name="잠실동 잠실엘스",
                region_code="11710",
                region_name="송파구",
                address="서울 송파구 잠실동 19",
                latitude=37.513,
                longitude=127.082,
                marker_safe=True,
                data_updated_at=datetime(2026, 7, 18, tzinfo=UTC),
            )
        ]

    def recent_trades(self, *_args):
        return []

    def monthly_trends(self, *_args):
        return []

    def latest_trade_date(self):
        return None


class FacilityRepository:
    def __init__(self, result: FacilitySearchResult) -> None:
        self.result = result
        self.calls: list[dict[str, object]] = []

    def nearby(self, **kwargs):
        self.calls.append(kwargs)
        return self.result


class LanguageModel:
    def __init__(self, *, radius_meters: int | None = None) -> None:
        self.radius_meters = radius_meters

    async def plan_query(self, _request: ChatbotQueryRequest) -> QueryPlan:
        return QueryPlan(
            capability="retail_location",
            complex_name="잠실엘스",
            radius_meters=self.radius_meters,
            facility_subtypes=("LARGE_MART",),
        )

    async def draft_answer(self, *, facts, limitations, question):
        del question
        if not facts:
            return DraftAnswer(
                sentences=[DraftSentence(text=limitations[0], fact_ids=[], claims=[])]
            )
        sentences = []
        for fact in facts:
            if fact.fact_id.startswith("reference-retail-scope-"):
                radius = next(claim for claim in fact.claims if claim.unit == "RADIUS_METERS")
                verified = next(claim for claim in fact.claims if claim.unit == "VERIFIED_ZERO")
                text = f"검색 반경 {radius.value}m의 확정 여부는 {verified.value}입니다."
                claims = [
                    DraftClaim(fact.fact_id, radius.value, radius.unit),
                    DraftClaim(fact.fact_id, verified.value, verified.unit),
                ]
            elif fact.fact_id.startswith("reference-retail-"):
                name = next(claim for claim in fact.claims if claim.unit == "TEXT")
                distance = next(claim for claim in fact.claims if claim.unit == "METERS")
                text = f"{name.value}은 직선거리 {distance.value}m입니다."
                claims = [
                    DraftClaim(fact.fact_id, name.value, name.unit),
                    DraftClaim(fact.fact_id, distance.value, distance.unit),
                ]
            else:
                name = next(claim for claim in fact.claims if claim.unit == "TEXT")
                text = f"{name.value}입니다."
                claims = [DraftClaim(fact.fact_id, name.value, name.unit)]
            sentences.append(DraftSentence(text=text, fact_ids=[fact.fact_id], claims=claims))
        return DraftAnswer(sentences=sentences)


def _result(*, verified_zero: bool = False) -> FacilitySearchResult:
    facilities = () if verified_zero else (
        FacilityFact(
            fact_id="store-1",
            name="롯데월드몰",
            category="RETAIL",
            subcategory="COMPLEX_MALL",
            status="OPEN",
            address="서울 송파구 올림픽로 300",
            distance_meters=1000,
            dataset_version="20260212-abc123def456",
            data_as_of=date(2026, 2, 12),
        ),
    )
    return FacilitySearchResult(
        facilities=facilities,
        matched_count=len(facilities),
        returned_count=len(facilities),
        has_more=False,
        verified_zero=verified_zero,
        coordinate_coverage=1.0,
        dataset_version="20260212-abc123def456",
        data_as_of=date(2026, 2, 12),
    )


def _query(repository: FacilityRepository, model: LanguageModel):
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(),
        point_facility_repository=repository,
        language_model=model,
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"retail_location"}),
    )
    return asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question="잠실엘스 주변 대형 상업시설 알려줘"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-retail",
        )
    )


def test_retail_uses_1000_meter_default_and_official_snapshot_citation() -> None:
    repository = FacilityRepository(_result())

    response = _query(repository, LanguageModel())

    assert response["success"] is True
    assert repository.calls[0]["radius_meters"] == 1000
    assert repository.calls[0]["subcategories"] == ("LARGE_MART",)
    assert {citation["sourceId"] for citation in response["citations"]} == {
        "property.ai_read",
        "retail.large-store",
    }
    retail_citation = next(
        item for item in response["citations"] if item["sourceId"] == "retail.large-store"
    )
    assert retail_citation["sourceUrl"] == (
        "https://www.data.go.kr/data/15045013/fileData.do"
    )


def test_retail_explicit_radius_outside_3000_is_not_clamped() -> None:
    repository = FacilityRepository(_result())

    response = _query(repository, LanguageModel(radius_meters=3001))

    assert response["success"] is False
    assert repository.calls == []
    assert "3000m" in response["answer"]


def test_retail_zero_fact_preserves_coverage_based_verification() -> None:
    repository = FacilityRepository(_result(verified_zero=True))

    response = _query(repository, LanguageModel())

    scope_fact_id = "reference-retail-scope-501-1000"
    retail_citation = next(
        citation for citation in response["citations"] if citation["sourceId"] == "retail.large-store"
    )
    assert scope_fact_id in retail_citation["factIds"]
    assert "true" in response["answer"]


@pytest.mark.parametrize(
    "unsupported_text",
    [
        "롯데월드몰이 있어 생활권이 좋습니다.",
        "롯데월드몰 때문에 투자가치가 높습니다.",
        "확인되지않은쇼핑몰은 직선거리 1000m입니다.",
        "롯데월드몰은 폐업 상태입니다.",
    ],
)
def test_retail_unsupported_or_unobserved_claims_are_rejected(
    unsupported_text: str,
) -> None:
    model = LanguageModel()

    async def unsupported_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        sentences = list(draft.sentences)
        facility_index = next(
            index
            for index, sentence in enumerate(sentences)
            if sentence.fact_ids[0] == "reference-retail-store-1"
        )
        sentences[facility_index] = DraftSentence(
            text=unsupported_text,
            fact_ids=sentences[facility_index].fact_ids,
            claims=sentences[facility_index].claims,
        )
        return DraftAnswer(sentences=sentences)

    model.draft_answer = unsupported_draft  # type: ignore[method-assign]

    with pytest.raises(Exception):
        _query(FacilityRepository(_result()), model)
