from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.academy_registry import AcademyRegistrySummary
from ai_service.property_chat.engine import GroundedChatbotEngine
from ai_service.property_chat.models import (
    AdministrativeRegionContext,
    ComplexRecord,
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    QueryPlan,
)


class PropertyRepository:
    def find_complexes(self, *_args):
        return [
            ComplexRecord(
                complex_id=501,
                display_name="잠실동 잠실엘스",
                region_code="11710101",
                region_name="잠실동",
                address="서울 송파구 잠실동 19",
                latitude=37.513,
                longitude=127.082,
                marker_safe=True,
                data_updated_at=datetime(2026, 7, 20, tzinfo=UTC),
            )
        ]

    def resolve_region_context(self, region_code: str):
        assert region_code == "11710101"
        return AdministrativeRegionContext(
            province_name="서울특별시",
            district_name="송파구",
            education_office_name="서울특별시교육청",
        )

    def recent_trades(self, *_args):
        return []

    def monthly_trends(self, *_args):
        return []

    def latest_trade_date(self):
        return None


class AcademyRepository:
    def __init__(self) -> None:
        self.calls: list[dict[str, str]] = []

    def summary(self, **kwargs):
        self.calls.append(kwargs)
        return AcademyRegistrySummary(
            education_office_code="B10",
            education_office_name="서울특별시교육청",
            district_name="송파구",
            total_count=150,
            open_count=120,
            dataset_version="v1",
            observed_at=datetime(2026, 7, 19, 1, tzinfo=UTC),
            freshness_days=45,
        )


class MissingRegionPropertyRepository(PropertyRepository):
    def resolve_region_context(self, _region_code: str):
        return None


class MissingRegionCodePropertyRepository(PropertyRepository):
    def find_complexes(self, *_args):
        return [replace(super().find_complexes()[0], region_code=None)]


class MissingSummaryAcademyRepository(AcademyRepository):
    def summary(self, **kwargs):
        self.calls.append(kwargs)
        return None


class StaleAcademyRepository(AcademyRepository):
    def summary(self, **kwargs):
        summary = super().summary(**kwargs)
        return replace(summary, observed_at=datetime(2026, 5, 1, tzinfo=UTC))


class LanguageModel:
    async def plan_query(self, _request):
        return QueryPlan(capability="academy_registry_summary", complex_name="잠실엘스")

    async def draft_answer(self, *, facts, limitations, question):
        del limitations, question
        if not facts:
            return DraftAnswer(
                sentences=[DraftSentence(text="공식 집계를 확인하지 못했습니다.", fact_ids=[])]
            )
        sentences = []
        for fact in facts:
            if fact.fact_id.startswith("academy-registry-"):
                district = next(claim for claim in fact.claims if claim.unit == "DISTRICT")
                total = next(claim for claim in fact.claims if claim.unit == "COUNT")
                opened = next(claim for claim in fact.claims if claim.unit == "OPEN_COUNT")
                text = f"{district.value}의 공식 등록 총수는 {total.value}건이고 운영 수는 {opened.value}건입니다."
                claims = [
                    DraftClaim(fact.fact_id, district.value, district.unit),
                    DraftClaim(fact.fact_id, total.value, total.unit),
                    DraftClaim(fact.fact_id, opened.value, opened.unit),
                ]
            else:
                name = next(claim for claim in fact.claims if claim.unit == "TEXT")
                text = f"{name.value} 기준입니다."
                claims = [DraftClaim(fact.fact_id, name.value, name.unit)]
            sentences.append(DraftSentence(text=text, fact_ids=[fact.fact_id], claims=claims))
        return DraftAnswer(sentences=sentences)


def _query(model: LanguageModel | None = None):
    academy = AcademyRepository()
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(),
        academy_registry_repository=academy,
        language_model=model or LanguageModel(),
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"academy_registry_summary"}),
        today=lambda: date(2026, 7, 20),
    )
    response = asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question="잠실엘스 지역 등록 학원 수 알려줘"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-academy-registry",
        )
    )
    return response, academy


def _query_boundary(property_repository, academy_repository):
    engine = GroundedChatbotEngine(
        repository=property_repository,
        academy_registry_repository=academy_repository,
        language_model=LanguageModel(),
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"academy_registry_summary"}),
        today=lambda: date(2026, 7, 20),
    )
    return asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question="공식 등록 학원 수"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-academy-boundary",
        )
    )


def test_summary_uses_sequential_region_resolution_and_A_grade_citation() -> None:
    response, repository = _query()

    assert repository.calls == [
        {
            "education_office_name": "서울특별시교육청",
            "district_name": "송파구",
        }
    ]
    assert response["success"] is True
    citation = next(
        item for item in response["citations"] if item["sourceId"] == "edu.academy-registry"
    )
    assert citation["evidenceGrade"] == "A"
    assert {item["sourceId"] for item in response["citations"]} == {
        "edu.academy-registry"
    }
    assert response["evidenceSummary"]["capabilities"] == ["academy_registry_summary"]
    assert response["evidenceSummary"]["factCount"] == 1
    assert "반경" not in response["answer"]
    assert "거리" not in response["answer"]


@pytest.mark.parametrize(
    "unsupported",
    [
        "주변 학원은 150건입니다.",
        "반경 내 등록 총수는 150건입니다.",
        "거리 기준 등록 총수는 150건입니다.",
    ],
)
def test_summary_rejects_radius_or_nearby_claims(unsupported: str) -> None:
    model = LanguageModel()

    async def invalid_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        sentences = list(draft.sentences)
        index = next(
            i
            for i, sentence in enumerate(sentences)
            if sentence.fact_ids[0].startswith("academy-registry-")
        )
        original = sentences[index]
        sentences[index] = DraftSentence(
            text=unsupported, fact_ids=original.fact_ids, claims=original.claims
        )
        return DraftAnswer(sentences=sentences)

    model.draft_answer = invalid_draft  # type: ignore[method-assign]
    with pytest.raises(Exception):
        _query(model)


@pytest.mark.parametrize(
    ("property_repository", "academy_repository"),
    [
        (PropertyRepository(), None),
        (MissingRegionCodePropertyRepository(), AcademyRepository()),
        (MissingRegionPropertyRepository(), AcademyRepository()),
        (PropertyRepository(), MissingSummaryAcademyRepository()),
        (PropertyRepository(), StaleAcademyRepository()),
    ],
)
def test_summary_fails_closed_for_missing_or_stale_region_evidence(
    property_repository,
    academy_repository,
) -> None:
    response = _query_boundary(property_repository, academy_repository)

    assert response["success"] is False
    assert response["citations"] == []
