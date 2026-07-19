from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import UTC, date, datetime, timedelta

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
    SchoolRecord,
    SchoolSearchResult,
    SchoolSnapshot,
)


class PropertyRepository:
    def __init__(self, complexes: list[ComplexRecord] | None = None) -> None:
        self.complexes = complexes if complexes is not None else [_complex()]

    def find_complexes(self, _name: str, _region: str | None, _limit: int):
        return self.complexes

    def recent_trades(self, *_args):
        return []

    def monthly_trends(self, *_args):
        return []

    def latest_trade_date(self):
        return None


class SchoolRepository:
    def __init__(
        self,
        result: SchoolSearchResult,
        snapshot: SchoolSnapshot | None = None,
    ) -> None:
        self.result = result
        self.snapshot = snapshot or SchoolSnapshot(
            dataset_version="2026-03-20-abc123",
            source_date=date(2026, 3, 20),
            published_at=datetime(2026, 3, 21, tzinfo=UTC),
        )
        self.query_count = 0

    def active_snapshot(self):
        return self.snapshot

    def nearby_schools(self, *_args, **_kwargs):
        self.query_count += 1
        return self.result


class LanguageModel:
    def __init__(self, *, forbidden: bool = False, radius_meters: int = 800) -> None:
        self.forbidden = forbidden
        self.radius_meters = radius_meters

    async def plan_query(self, _request: ChatbotQueryRequest) -> QueryPlan:
        return QueryPlan(
            capability="school_location",
            complex_name="잠실엘스",
            school_levels=("ELEMENTARY",),
            radius_meters=self.radius_meters,
            limit=5,
        )

    async def draft_answer(self, *, facts, limitations, question):
        del question
        if not facts:
            return DraftAnswer(
                sentences=[DraftSentence(text=limitations[0], fact_ids=[], claims=[])]
            )
        sentences: list[DraftSentence] = []
        for fact in facts:
            if fact.fact_id.startswith("property-complex"):
                claim = next(claim for claim in fact.claims if claim.unit == "TEXT")
                text = f"{claim.value}입니다."
                claims = [DraftClaim(fact.fact_id, claim.value, claim.unit)]
            elif fact.fact_id.startswith("school-location-scope-"):
                radius = next(claim for claim in fact.claims if claim.unit == "RADIUS_METERS")
                matched = next(claim for claim in fact.claims if claim.unit == "COUNT")
                text = f"검색 반경 {radius.value}m에서 {matched.value}곳을 확인했습니다."
                claims = [
                    DraftClaim(fact.fact_id, radius.value, radius.unit),
                    DraftClaim(fact.fact_id, matched.value, matched.unit),
                ]
            elif fact.fact_id.startswith("school-location-"):
                name = next(claim for claim in fact.claims if claim.unit == "TEXT")
                distance = next(claim for claim in fact.claims if claim.unit == "METERS")
                text = (
                    f"{name.value}은 배정학교이며 {distance.value}m입니다."
                    if self.forbidden
                    else f"{name.value}은 직선거리 {distance.value}m입니다."
                )
                claims = [
                    DraftClaim(fact.fact_id, name.value, name.unit),
                    DraftClaim(fact.fact_id, distance.value, distance.unit),
                ]
            else:
                raise AssertionError("unexpected fact type")
            sentences.append(DraftSentence(text=text, fact_ids=[fact.fact_id], claims=claims))
        return DraftAnswer(sentences=sentences)


def _complex() -> ComplexRecord:
    return ComplexRecord(
        complex_id=501,
        display_name="잠실동 잠실엘스",
        region_code="11710101",
        region_name="잠실동",
        address="서울 송파구 잠실동 19",
        latitude=37.513,
        longitude=127.082,
        marker_safe=True,
        data_updated_at=datetime(2026, 7, 18, tzinfo=UTC),
    )


def _school(distance: int = 800) -> SchoolRecord:
    return SchoolRecord(
        school_id="B000000001",
        school_name="서울잠전초등학교",
        school_level="ELEMENTARY",
        operating_status="운영",
        road_address="서울 송파구 올림픽로",
        lot_address=None,
        latitude=37.514,
        longitude=127.082,
        distance_meters=distance,
    )


def _query(
    school_repository: SchoolRepository,
    model: LanguageModel | None = None,
    *,
    property_repository: PropertyRepository | None = None,
):
    engine = GroundedChatbotEngine(
        repository=property_repository or PropertyRepository(),
        school_repository=school_repository,
        language_model=model or LanguageModel(),
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"school_location"}),
        today=lambda: date(2026, 7, 19),
    )
    return asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question="잠실엘스 주변 초등학교 알려줘"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-school",
        )
    )


def test_school_result_is_supported_with_separate_property_and_school_citations() -> None:
    response = _query(SchoolRepository(SchoolSearchResult((_school(),), matched_count=1)))

    assert response["success"] is True
    assert response["evidenceSummary"]["status"] == "supported"
    assert response["evidenceSummary"]["capabilities"] == ["school_location"]
    assert {citation["sourceId"] for citation in response["citations"]} == {
        "property.ai_read",
        "edu.school-location",
    }
    school_citation = next(
        citation for citation in response["citations"] if citation["sourceId"] == "edu.school-location"
    )
    assert school_citation["sourceName"] == "전국초중등학교위치표준데이터"
    assert school_citation["datasetVersion"] == "2026-03-20-abc123"


def test_zero_school_result_is_supported_with_grounded_scope_fact() -> None:
    response = _query(SchoolRepository(SchoolSearchResult((), matched_count=0)))

    assert response["success"] is True
    assert response["evidenceSummary"]["status"] == "supported"
    assert "800m" in response["answer"]
    assert "0곳" in response["answer"]


def test_stale_snapshot_is_unavailable_without_school_query() -> None:
    repository = SchoolRepository(
        SchoolSearchResult((_school(),), matched_count=1),
        snapshot=SchoolSnapshot(
            dataset_version="stale",
            source_date=date(2025, 1, 1),
            published_at=datetime(2025, 1, 2, tzinfo=UTC),
        ),
    )

    response = _query(repository)

    assert response["success"] is False
    assert response["evidenceSummary"]["status"] == "unavailable"
    assert repository.query_count == 0


def test_unavailable_school_query_still_rejects_assignment_hallucination() -> None:
    model = LanguageModel()

    async def hallucinated_draft(*, facts, limitations, question):
        del facts, limitations, question
        return DraftAnswer(
            sentences=[
                DraftSentence(
                    text="서울잠전초등학교로 배정됩니다.",
                    fact_ids=[],
                    claims=[],
                )
            ]
        )

    model.draft_answer = hallucinated_draft  # type: ignore[method-assign]
    repository = SchoolRepository(
        SchoolSearchResult((_school(),), matched_count=1),
        snapshot=SchoolSnapshot(
            dataset_version="stale",
            source_date=date(2025, 1, 1),
            published_at=datetime(2025, 1, 2, tzinfo=UTC),
        ),
    )

    with pytest.raises(Exception):
        _query(repository, model)


@pytest.mark.parametrize(
    ("age_days", "expected_success", "expected_queries"),
    [(214, True, 1), (215, False, 0)],
)
def test_snapshot_freshness_boundary(
    age_days: int,
    expected_success: bool,
    expected_queries: int,
) -> None:
    source_date = date(2026, 7, 19) - timedelta(days=age_days)
    repository = SchoolRepository(
        SchoolSearchResult((_school(),), matched_count=1),
        snapshot=SchoolSnapshot(
            dataset_version=f"age-{age_days}",
            source_date=source_date,
            published_at=datetime.combine(source_date, datetime.min.time(), tzinfo=UTC),
        ),
    )

    response = _query(repository)

    assert response["success"] is expected_success
    assert repository.query_count == expected_queries


def test_ambiguous_complex_does_not_query_school_repository() -> None:
    repository = SchoolRepository(SchoolSearchResult((_school(),), matched_count=1))
    property_repository = PropertyRepository(
        complexes=[_complex(), replace(_complex(), complex_id=502, display_name="서초동 잠실엘스")]
    )

    response = _query(repository, property_repository=property_repository)

    assert response["evidenceSummary"]["status"] == "partial"
    assert repository.query_count == 0


def test_marker_unsafe_complex_does_not_query_school_repository() -> None:
    repository = SchoolRepository(SchoolSearchResult((_school(),), matched_count=1))
    property_repository = PropertyRepository(
        complexes=[replace(_complex(), marker_safe=False, latitude=None, longitude=None)]
    )

    response = _query(repository, property_repository=property_repository)

    assert response["evidenceSummary"]["status"] == "unavailable"
    assert repository.query_count == 0


def test_out_of_range_radius_is_unavailable_without_school_query() -> None:
    repository = SchoolRepository(SchoolSearchResult((_school(),), matched_count=1))

    response = _query(repository, LanguageModel(radius_meters=2500))

    assert response["success"] is False
    assert response["evidenceSummary"]["status"] == "unavailable"
    assert "100m" in response["answer"]
    assert "2000m" in response["answer"]
    assert repository.query_count == 0


def test_assignment_claim_is_rejected_by_grounding_policy() -> None:
    with pytest.raises(Exception):
        _query(
            SchoolRepository(SchoolSearchResult((_school(),), matched_count=1)),
            LanguageModel(forbidden=True),
        )


def test_negative_assignment_limitation_is_allowed() -> None:
    model = LanguageModel()

    async def negative_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        sentences = list(draft.sentences)
        sentences[-1] = replace(
            sentences[-1], text=sentences[-1].text + " 배정학교를 의미하지 않습니다."
        )
        return DraftAnswer(sentences=sentences)

    model.draft_answer = negative_draft  # type: ignore[method-assign]

    response = _query(
        SchoolRepository(SchoolSearchResult((_school(),), matched_count=1)), model
    )

    assert response["success"] is True


def test_unknown_school_name_is_rejected() -> None:
    model = LanguageModel()

    async def hallucinated_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        sentences = [
            replace(
                sentence,
                text=sentence.text.replace("서울잠전초등학교", "존재하지않는초등학교"),
            )
            if sentence.fact_ids[0].startswith("school-location-B")
            else sentence
            for sentence in draft.sentences
        ]
        return DraftAnswer(sentences=sentences)

    model.draft_answer = hallucinated_draft  # type: ignore[method-assign]

    with pytest.raises(Exception):
        _query(
            SchoolRepository(SchoolSearchResult((_school(),), matched_count=1)), model
        )


@pytest.mark.parametrize(
    "unsupported_text",
    [
        "서울잠전초등학교는 도보 거리로 가깝습니다.",
        "서울잠전초등학교로 배정됩니다.",
        "서울잠전초등학교는 걸어서 가깝습니다.",
        "서울잠전초등학교는 곧 폐교 예정입니다.",
        "이 주변에 새로운 학교는 더 없습니다.",
    ],
)
def test_unsupported_school_semantics_are_rejected(unsupported_text: str) -> None:
    model = LanguageModel()

    async def unsupported_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        school_index = next(
            index
            for index, sentence in enumerate(draft.sentences)
            if sentence.fact_ids[0].startswith("school-location-B")
        )
        sentences = list(draft.sentences)
        sentences[school_index] = replace(
            sentences[school_index], text=unsupported_text
        )
        return DraftAnswer(sentences=sentences)

    model.draft_answer = unsupported_draft  # type: ignore[method-assign]

    with pytest.raises(Exception):
        _query(
            SchoolRepository(SchoolSearchResult((_school(),), matched_count=1)), model
        )
