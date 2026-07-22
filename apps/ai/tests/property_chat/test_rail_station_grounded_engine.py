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
from ai_service.property_chat.rail_stations import (
    RailStation,
    RailStationSearchResult,
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


class RailRepository:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def nearby(self, **kwargs):
        self.calls.append(kwargs)
        return RailStationSearchResult(
            stations=(
                RailStation(
                    station_name="잠실",
                    lines=("2호선", "8호선"),
                    occurrence_ids=("operator|2|잠실", "operator|8|잠실"),
                    distance_meters=640,
                ),
            ),
            occurrence_count=2,
            dataset_version="rail-v1",
            source_date=date(2026, 6, 30),
        )


class LanguageModel:
    def __init__(self, *, radius_meters: int | None = None) -> None:
        self.radius_meters = radius_meters

    async def plan_query(self, _request):
        return QueryPlan(
            capability="rail_station_lookup",
            complex_name="잠실엘스",
            radius_meters=self.radius_meters,
        )

    async def draft_answer(self, *, facts, limitations, question):
        del question
        if not facts:
            return DraftAnswer([DraftSentence(limitations[0], [], [])])
        sentences = []
        for fact in facts:
            if fact.fact_id.startswith("rail-station-"):
                name = next(claim for claim in fact.claims if claim.unit == "TEXT")
                lines = next(claim for claim in fact.claims if claim.unit == "RAIL_LINES")
                distance = next(claim for claim in fact.claims if claim.unit == "METERS")
                sentences.append(
                    DraftSentence(
                        f"{name.value}역은 {lines.value}, 직선거리 {distance.value}m입니다.",
                        [fact.fact_id],
                        [
                            DraftClaim(fact.fact_id, name.value, name.unit),
                            DraftClaim(fact.fact_id, lines.value, lines.unit),
                            DraftClaim(fact.fact_id, distance.value, distance.unit),
                        ],
                    )
                )
            elif fact.fact_id.startswith("rail-scope-"):
                radius = next(
                    claim for claim in fact.claims if claim.unit == "RADIUS_METERS"
                )
                count = next(claim for claim in fact.claims if claim.unit == "COUNT")
                sentences.append(
                    DraftSentence(
                        f"검색 반경은 {radius.value}m이고 역은 {count.value}개입니다.",
                        [fact.fact_id],
                        [
                            DraftClaim(fact.fact_id, radius.value, radius.unit),
                            DraftClaim(fact.fact_id, count.value, count.unit),
                        ],
                    )
                )
        return DraftAnswer(sentences)


def _query(repository: RailRepository, model: LanguageModel | None = None):
    engine = GroundedChatbotEngine(
        repository=PropertyRepository(),
        rail_station_repository=repository,
        language_model=model or LanguageModel(),
        enabled_capabilities=frozenset(),
        enabled_reference_capabilities=frozenset({"rail_station_lookup"}),
        today=lambda: date(2026, 7, 20),
    )
    return asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question="잠실엘스 가까운 역과 노선"),
            user=AuthenticatedUser(user_id=1),
            request_id="request-rail-station",
        )
    )


def test_rail_lookup_uses_1500_meter_default_and_A_grade_citation() -> None:
    repository = RailRepository()

    response = _query(repository)

    assert repository.calls == [
        {
            "latitude": 37.513,
            "longitude": 127.082,
            "radius_meters": 1500,
            "limit": 5,
        }
    ]
    assert response["success"] is True
    assert response["evidenceSummary"]["capabilities"] == [
        "rail_station_lookup"
    ]
    assert response["citations"] == [
        {
            "citationId": "citation-1",
            "sourceId": "transport.rail-station",
            "sourceName": "전국도시철도역사정보표준데이터",
            "sourceUrl": "https://www.data.go.kr/data/15013205/standard.do",
            "evidenceGrade": "A",
            "datasetVersion": "rail-v1",
            "dataAsOf": "2026-06-30",
            "observedAt": None,
            "factIds": [
                "rail-station-operator|2|잠실",
                "rail-scope-501-1500",
            ],
        }
    ]


@pytest.mark.parametrize("radius", [99, 3001])
def test_rail_lookup_rejects_out_of_range_radius(radius: int) -> None:
    repository = RailRepository()

    response = _query(repository, LanguageModel(radius_meters=radius))

    assert response["success"] is False
    assert repository.calls == []


def test_rail_lookup_rejects_commute_time_claim() -> None:
    model = LanguageModel()

    async def invalid_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        first = draft.sentences[0]
        return DraftAnswer(
            [
                DraftSentence(
                    "잠실역까지 통근시간은 640분입니다.",
                    first.fact_ids,
                    first.claims,
                ),
                *draft.sentences[1:],
            ]
        )

    model.draft_answer = invalid_draft  # type: ignore[method-assign]

    with pytest.raises(Exception):
        _query(RailRepository(), model)


def test_rail_lookup_allows_a_generic_station_label_with_observed_name() -> None:
    model = LanguageModel()

    async def generic_label_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        first = draft.sentences[0]
        return DraftAnswer(
            [
                DraftSentence(
                    "최근접 지하철역은 잠실역이며 직선거리 640m입니다.",
                    first.fact_ids,
                    first.claims,
                ),
                *draft.sentences[1:],
            ]
        )

    model.draft_answer = generic_label_draft  # type: ignore[method-assign]

    response = _query(RailRepository(), model)

    assert response["success"] is True


def test_rail_lookup_still_rejects_an_unobserved_station_name() -> None:
    model = LanguageModel()

    async def invented_station_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        first = draft.sentences[0]
        return DraftAnswer(
            [
                DraftSentence(
                    "강남역은 직선거리 640m입니다.",
                    first.fact_ids,
                    first.claims,
                ),
                *draft.sentences[1:],
            ]
        )

    model.draft_answer = invented_station_draft  # type: ignore[method-assign]

    with pytest.raises(Exception):
        _query(RailRepository(), model)


def test_rail_lookup_allows_explicit_unsupported_service_limitation() -> None:
    model = LanguageModel()

    async def safe_draft(*, facts, limitations, question):
        draft = await LanguageModel().draft_answer(
            facts=facts, limitations=limitations, question=question
        )
        first = draft.sentences[0]
        return DraftAnswer(
            [
                DraftSentence(
                    "통근시간·배차·혼잡도는 근거에 포함되지 않습니다.",
                    first.fact_ids,
                    first.claims,
                ),
                *draft.sentences[1:],
            ]
        )

    model.draft_answer = safe_draft  # type: ignore[method-assign]

    response = _query(RailRepository(), model)

    assert response["success"] is True
