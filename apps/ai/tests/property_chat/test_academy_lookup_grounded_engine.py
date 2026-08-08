from __future__ import annotations

import asyncio
import re
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.academy_locations import (
    AcademyLocation,
    AcademyLocationSearchResult,
    RegistryExactMatch,
)
from ai_service.property_chat.engine import GroundedChatbotEngine
from ai_service.property_chat.models import (
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

    def recent_trades(self, *_args):
        return []

    def monthly_trends(self, *_args):
        return []

    def latest_trade_date(self):
        return None


class AcademyLocationRepository:
    def __init__(self, *, exact: bool, coordinate_coverage: float = 1.0) -> None:
        self.exact = exact
        self.coordinate_coverage = coordinate_coverage
        self.calls: list[dict[str, object]] = []

    def nearby(self, **kwargs):
        self.calls.append(kwargs)
        match = (
            RegistryExactMatch(
                registry_fact_id="B10|B10-001",
                academy_name="가나다 학원",
                status="OPEN",
                dataset_version="neis-v1",
                observed_at=datetime(2026, 7, 19, 1, tzinfo=UTC),
            )
            if self.exact
            else None
        )
        return AcademyLocationSearchResult(
            locations=(
                AcademyLocation(
                    store_id="store-1",
                    name="가나다 학원",
                    small_category_code="P10101",
                    status="OPEN",
                    address="서울특별시 송파구 올림픽로 300",
                    distance_meters=800,
                    dataset_version="sbiz-v1",
                    observed_at=datetime(2026, 7, 20, tzinfo=UTC),
                    registry_match=match,
                ),
            ),
            matched_count=1,
            coordinate_coverage=self.coordinate_coverage,
            dataset_version="sbiz-v1",
            observed_at=datetime(2026, 7, 20, tzinfo=UTC),
            verified_zero=False,
        )


class LanguageModel:
    def __init__(self, *, radius_meters: int | None = None) -> None:
        self.radius_meters = radius_meters

    async def plan_query(self, _request):
        return QueryPlan(
            capability="academy_lookup",
            complex_name="잠실엘스",
            radius_meters=self.radius_meters,
        )

    async def draft_answer(self, *, facts, limitations, question):
        del question
        if not facts:
            return DraftAnswer([DraftSentence(limitations[0], [], [])])
        sentences: list[DraftSentence] = []
        for fact in facts:
            if fact.fact_id.startswith("sbiz-academy-location-"):
                name = next(claim for claim in fact.claims if claim.unit == "TEXT")
                distance = next(claim for claim in fact.claims if claim.unit == "METERS")
                text = f"{name.value}은 직선거리 {distance.value}m의 교육업소입니다."
                claims = [
                    DraftClaim(fact.fact_id, name.value, name.unit),
                    DraftClaim(fact.fact_id, distance.value, distance.unit),
                ]
            elif fact.fact_id.startswith("academy-registry-exact-"):
                name = next(claim for claim in fact.claims if claim.unit == "TEXT")
                match = next(claim for claim in fact.claims if claim.unit == "MATCH_TYPE")
                text = f"{name.value}은 NEIS 공식 등록 원장과 {match.value} 일치합니다."
                claims = [
                    DraftClaim(fact.fact_id, name.value, name.unit),
                    DraftClaim(fact.fact_id, match.value, match.unit),
                ]
            else:
                radius = next(claim for claim in fact.claims if claim.unit == "RADIUS_METERS")
                count = next(claim for claim in fact.claims if claim.unit == "COUNT")
                text = f"검색 반경은 {radius.value}m이고 위치 결과는 {count.value}건입니다."
                claims = [
                    DraftClaim(fact.fact_id, radius.value, radius.unit),
                    DraftClaim(fact.fact_id, count.value, count.unit),
                ]
            sentences.append(DraftSentence(text, [fact.fact_id], claims))
        return DraftAnswer(sentences)


def _query(
    repository: AcademyLocationRepository,
    model: LanguageModel | None = None,
    *,
    question: str = "잠실엘스 주변 학원 알려줘",
):
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(),
        academy_location_repository=repository,
        language_model=model or LanguageModel(),
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"academy_lookup"}),
        today=lambda: date(2026, 7, 20),
    )
    return asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question=question),
            user=AuthenticatedUser(user_id=1),
            request_id="request-academy-lookup",
        )
    )


def test_unmatched_location_uses_800_meter_default_and_sbiz_B_grade_only() -> None:
    repository = AcademyLocationRepository(exact=False)

    response = _query(repository)

    assert repository.calls[0]["radius_meters"] == 800
    assert repository.calls[0]["limit"] == 5
    assert {item["sourceId"] for item in response["citations"]} == {
        "place.sbiz-academy"
    }
    assert response["citations"][0]["evidenceGrade"] == "B"


def test_exact_match_adds_NEIS_A_grade_citation() -> None:
    response = _query(
        AcademyLocationRepository(exact=True),
        question="잠실동 잠실엘스 주변 학원 위치와 가까운 역·노선을 알려줘",
    )

    assert {
        (item["sourceId"], item["evidenceGrade"])
        for item in response["citations"]
    } == {
        ("place.sbiz-academy", "B"),
        ("edu.academy-registry", "A"),
    }
    public_identifier = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$")
    assert all(
        public_identifier.fullmatch(fact_id)
        for citation in response["citations"]
        for fact_id in citation["factIds"]
    )


@pytest.mark.parametrize("radius", [99, 2001])
def test_lookup_rejects_out_of_range_radius_without_query(radius: int) -> None:
    repository = AcademyLocationRepository(exact=False)

    response = _query(repository, LanguageModel(radius_meters=radius))

    assert response["success"] is False
    assert repository.calls == []


@pytest.mark.parametrize(
    "unsupported_text",
    [
        "공식 등록 학원 수는 1건입니다.",
        "가나다 학원과 이름이 비슷해 등록 학원으로 보입니다.",
    ],
)
def test_lookup_rejects_registry_count_or_fuzzy_match_claim(
    unsupported_text: str,
) -> None:
    model = LanguageModel()

    async def invalid_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        sentences = list(draft.sentences)
        sentences[0] = DraftSentence(
            unsupported_text,
            sentences[0].fact_ids,
            sentences[0].claims,
        )
        return DraftAnswer(sentences)

    model.draft_answer = invalid_draft  # type: ignore[method-assign]
    with pytest.raises(Exception):
        _query(AcademyLocationRepository(exact=True), model)


def test_lookup_rejects_an_unobserved_academy_name_with_a_space() -> None:
    model = LanguageModel()

    async def invalid_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        sentences = list(draft.sentences)
        location_index = next(
            index
            for index, sentence in enumerate(sentences)
            if sentence.fact_ids[0].startswith("sbiz-academy-location-")
        )
        original = sentences[location_index]
        sentences[location_index] = DraftSentence(
            "가짜 학원은 직선거리 800m의 교육업소입니다.",
            original.fact_ids,
            original.claims,
        )
        return DraftAnswer(sentences)

    model.draft_answer = invalid_draft  # type: ignore[method-assign]

    with pytest.raises(Exception):
        _query(AcademyLocationRepository(exact=False), model)


def test_lookup_fails_closed_below_nationwide_coordinate_coverage() -> None:
    response = _query(
        AcademyLocationRepository(exact=False, coordinate_coverage=0.949)
    )

    assert response["success"] is False
    assert response["citations"] == []
