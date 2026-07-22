from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.childcare_centers import (
    ChildcareCenter,
    ChildcareSearchResult,
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
    def __init__(
        self,
        *,
        marker_safe: bool = True,
        region_code: str | None = "11710101",
    ) -> None:
        self.marker_safe = marker_safe
        self.region_code = region_code

    def find_complexes(self, *_args):
        return [
            ComplexRecord(
                complex_id=501,
                display_name="잠실동 잠실엘스",
                region_code=self.region_code,
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


class ChildcareRepository:
    def __init__(self, result: ChildcareSearchResult | None) -> None:
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
            capability="childcare_lookup",
            complex_name="잠실엘스",
            radius_meters=self.radius_meters,
        )

    async def draft_answer(self, *, facts, limitations, question):
        del question
        if not facts:
            return DraftAnswer([DraftSentence(limitations[0], [], [])])
        sentences: list[DraftSentence] = []
        for fact in facts:
            if fact.fact_id.startswith("childcare-center-"):
                name = _claim(fact, "TEXT")
                center_type = _claim(fact, "CHILDCARE_TYPE")
                capacity = _claim(fact, "CAPACITY_PERSONS")
                distance = _claim(fact, "METERS")
                reference_date = _claim(fact, "DATE")
                claims = [name, center_type, capacity, distance, reference_date]
                text = (
                    f"{name.value}은 {center_type.value}, 정원 {capacity.value}명, "
                    f"직선거리 {distance.value}m이며 기준일은 {reference_date.value}입니다."
                )
            else:
                radius = _claim(fact, "RADIUS_METERS")
                count = _claim(fact, "COUNT")
                verified = _claim(fact, "VERIFIED_ZERO")
                claims = [radius, count, verified]
                text = (
                    f"검색 반경은 {radius.value}m이고 확인된 시설은 {count.value}개이며 "
                    f"미확인 여부의 검증값은 {verified.value}입니다."
                )
            sentences.append(
                DraftSentence(
                    text,
                    [fact.fact_id],
                    [
                        DraftClaim(fact.fact_id, claim.value, claim.unit)
                        for claim in claims
                    ],
                )
            )
        return DraftAnswer(sentences)


def _claim(fact, unit: str):
    return next(claim for claim in fact.claims if claim.unit == unit)


def _result(
    *,
    centers: tuple[ChildcareCenter, ...] | None = None,
    verified_zero: bool = False,
    observed_at: datetime = datetime(2026, 7, 20, tzinfo=UTC),
    coordinate_coverage: float | None = 1.0,
) -> ChildcareSearchResult:
    if centers is None:
        centers = () if verified_zero else (
            ChildcareCenter(
                center_id="116800001",
                center_name="해뜰어린이집",
                center_type="국공립",
                capacity=45,
                distance_meters=320,
                reference_date=date(2026, 7, 19),
                dataset_version="childcare-v1",
            ),
        )
    return ChildcareSearchResult(
        centers=centers,
        matched_count=len(centers),
        returned_count=len(centers),
        has_more=False,
        verified_zero=verified_zero,
        coordinate_coverage=coordinate_coverage,
        dataset_version="childcare-v1",
        observed_at=observed_at,
        freshness_days=45,
    )


def _query(
    repository: ChildcareRepository,
    model: LanguageModel | None = None,
    *,
    marker_safe: bool = True,
    region_code: str | None = "11710101",
):
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(
            marker_safe=marker_safe,
            region_code=region_code,
        ),
        childcare_repository=repository,
        language_model=model or LanguageModel(),
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"childcare_lookup"}),
        today=lambda: date(2026, 7, 21),
    )
    return asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question="잠실엘스 주변 어린이집 알려줘"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-childcare",
        )
    )


def test_childcare_lookup_uses_800_meter_default_and_returns_fact_list() -> None:
    repository = ChildcareRepository(_result())

    response = _query(repository)

    assert repository.calls == [
        {
            "latitude": 37.513,
            "longitude": 127.082,
            "radius_meters": 800,
            "limit": 5,
            "region_code": "11710",
        }
    ]
    assert response["success"] is True
    assert response["evidenceSummary"]["capabilities"] == ["childcare_lookup"]
    assert response["citations"][0]["sourceId"] == "childcare.center"
    artifact = response["uiArtifacts"][0]
    assert artifact["type"] == "factList"
    assert artifact["items"] == [
        {
            "label": "해뜰어린이집",
            "value": "국공립 · 정원 45명 · 직선거리 320m · 기준일 2026-07-19",
            "factIds": ["childcare-center-116800001"],
        }
    ]


def test_childcare_verified_zero_is_supported() -> None:
    response = _query(ChildcareRepository(_result(verified_zero=True)))

    assert response["success"] is True
    assert response["evidenceSummary"]["status"] == "supported"
    assert response["uiArtifacts"][0]["items"][0]["factIds"] == [
        "childcare-scope-501-800"
    ]


def test_childcare_invalid_artifact_label_keeps_text_fallback() -> None:
    center = ChildcareCenter(
        center_id="116800001",
        center_name=f"{'가' * 100}어린이집",
        center_type="국공립",
        capacity=45,
        distance_meters=320,
        reference_date=date(2026, 7, 19),
        dataset_version="childcare-v1",
    )

    response = _query(ChildcareRepository(_result(centers=(center,))))

    assert response["success"] is True
    assert response["answer"]
    assert response["uiArtifacts"] == []


@pytest.mark.parametrize("radius", [99, 2001])
def test_childcare_lookup_rejects_radius_outside_supported_range(radius: int) -> None:
    repository = ChildcareRepository(_result())

    response = _query(repository, LanguageModel(radius_meters=radius))

    assert response["success"] is False
    assert repository.calls == []


def test_childcare_unavailable_snapshot_and_missing_coordinates_are_distinct() -> None:
    inactive_repository = ChildcareRepository(None)
    inactive = _query(inactive_repository)
    missing_coordinates_repository = ChildcareRepository(_result())
    missing_coordinates = _query(
        missing_coordinates_repository,
        marker_safe=False,
    )

    assert inactive["success"] is False
    assert "active snapshot" in inactive["answer"]
    assert missing_coordinates["success"] is False
    assert "표시 좌표" in missing_coordinates["answer"]
    assert missing_coordinates_repository.calls == []


def test_childcare_stale_snapshot_is_unavailable() -> None:
    stale = _result(observed_at=datetime(2026, 5, 1, tzinfo=UTC))

    response = _query(ChildcareRepository(stale))

    assert response["success"] is False
    assert response["uiArtifacts"] == []


@pytest.mark.parametrize("coverage", [None, 0.89])
def test_childcare_incomplete_region_coverage_is_unavailable(
    coverage: float | None,
) -> None:
    response = _query(
        ChildcareRepository(_result(coordinate_coverage=coverage))
    )

    assert response["success"] is False


def test_childcare_unverified_zero_is_partial() -> None:
    response = _query(ChildcareRepository(_result(centers=())))

    assert response["success"] is True
    assert response["status"] == "partial_success"
    assert response["uiArtifacts"] == []
    assert "확정할 수 없습니다" in response["limitations"][-1]


@pytest.mark.parametrize("region_code", [None, "invalid"])
def test_childcare_missing_district_code_does_not_invent_one(
    region_code: str | None,
) -> None:
    repository = ChildcareRepository(_result())

    response = _query(repository, region_code=region_code)

    assert response["success"] is True
    assert repository.calls[0]["region_code"] is None


@pytest.mark.parametrize(
    "unsupported_text",
    [
        "해뜰어린이집은 현재 입소 가능합니다.",
        "해뜰어린이집의 대기기간은 45일입니다.",
        "해뜰어린이집은 보육 품질이 우수합니다.",
        "해뜰어린이집을 추천 순위 1위로 봅니다.",
    ],
)
def test_childcare_forbidden_claims_are_rejected(unsupported_text: str) -> None:
    model = LanguageModel()

    async def invalid_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts,
            limitations=limitations,
            question=question,
        )
        first = draft.sentences[0]
        return DraftAnswer(
            [DraftSentence(unsupported_text, first.fact_ids, first.claims), *draft.sentences[1:]]
        )

    model.draft_answer = invalid_draft  # type: ignore[method-assign]

    with pytest.raises(Exception):
        _query(ChildcareRepository(_result()), model)


def test_childcare_explicit_unsupported_limitation_is_allowed() -> None:
    model = LanguageModel()

    async def safe_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts,
            limitations=limitations,
            question=question,
        )
        first = draft.sentences[0]
        return DraftAnswer(
            [
                DraftSentence(
                    "입소 대기와 보육 품질은 현재 근거에 포함되지 않습니다.",
                    first.fact_ids,
                    first.claims,
                ),
                *draft.sentences[1:],
            ]
        )

    model.draft_answer = safe_draft  # type: ignore[method-assign]

    response = _query(ChildcareRepository(_result()), model)

    assert response["success"] is True
