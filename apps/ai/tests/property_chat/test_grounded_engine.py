from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.engine import GroundedChatbotEngine
from ai_service.property_chat.models import (
    ComplexRecord,
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    MonthlyTrendRecord,
    PropertyQueryPlan,
    TradeRecord,
)


class FakeRepository:
    def __init__(self) -> None:
        self.complexes: list[ComplexRecord] = []
        self.trades: list[TradeRecord] = []
        self.trends: list[MonthlyTrendRecord] = []
        self.latest_trade_data_as_of = date(2026, 7, 16)
        self.trade_query: tuple[int, date | None, date | None, float | None, int] | None = None

    def find_complexes(self, name: str, region_name: str | None, limit: int) -> list[ComplexRecord]:
        del name, region_name, limit
        return self.complexes

    def recent_trades(
        self,
        complex_id: int,
        start_date: date | None,
        end_date: date | None,
        exclusive_area_square_meters: float | None,
        limit: int,
    ) -> list[TradeRecord]:
        self.trade_query = (complex_id, start_date, end_date, exclusive_area_square_meters, limit)
        return self.trades

    def monthly_trends(
        self,
        complex_id: int,
        start_date: date,
        end_date: date,
        exclusive_area_square_meters: float | None,
    ) -> list[MonthlyTrendRecord]:
        del complex_id, start_date, end_date, exclusive_area_square_meters
        return self.trends

    def latest_trade_date(self) -> date | None:
        return self.latest_trade_data_as_of


class FakeLanguageModel:
    def __init__(self, plan: PropertyQueryPlan, draft: DraftAnswer) -> None:
        self.plan = plan
        self.draft = draft
        self.received_fact_ids: list[str] = []

    async def plan_query(self, _request: ChatbotQueryRequest) -> PropertyQueryPlan:
        return self.plan

    async def draft_answer(self, *, facts, limitations, question) -> DraftAnswer:
        del limitations, question
        self.received_fact_ids = [fact.fact_id for fact in facts]
        return self.draft


def complex_record(complex_id: int = 11471, display_name: str = "잠실동 잠실엘스") -> ComplexRecord:
    return ComplexRecord(
        complex_id=complex_id,
        display_name=display_name,
        region_code="11710101",
        region_name="잠실동",
        address="서울 송파구 잠실동 19",
        latitude=37.513,
        longitude=127.082,
        marker_safe=True,
        data_updated_at=datetime(2026, 7, 16, tzinfo=UTC),
    )


def test_recent_trade_answer_uses_only_observed_fact_and_citation() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    repository.trades = [
        TradeRecord(
            trade_id=7001,
            complex_id=11471,
            deal_date=date(2026, 7, 15),
            deal_amount_ten_thousand_krw=250_000,
            exclusive_area_square_meters=84.8,
            floor=12,
        )
    ]
    model = FakeLanguageModel(
        PropertyQueryPlan(
            capability="recent_trade_lookup",
            complex_name="잠실엘스",
            start_date=date(2025, 7, 1),
            end_date=date(2026, 7, 16),
            exclusive_area_square_meters=84.8,
            limit=5,
        ),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="2026년 7월 15일 전용 84.8㎡가 250000만원에 거래됐습니다.",
                    fact_ids=["property-trade-7001"],
                    claims=[
                        DraftClaim(
                            fact_id="property-trade-7001",
                            value="250000",
                            unit="10_000_KRW",
                        )
                    ],
                )
            ]
        ),
    )
    engine = GroundedChatbotEngine(repository=repository, language_model=model)

    response = run_query(
        engine,
        "잠실엘스 전용 84.8㎡ 최근 거래 알려줘",
        "request-1",
    )

    assert response["success"] is True
    assert response["status"] == "success"
    assert response["requestId"] == "request-1"
    assert response["dataAsOf"] == "2026-07-16"
    assert response["evidenceSummary"] == {
        "status": "supported",
        "capabilities": ["recent_trade_lookup"],
        "factCount": 1,
        "citationCount": 1,
    }
    assert response["citations"][0]["factIds"] == ["property-trade-7001"]
    assert any("±1.0㎡" in limitation for limitation in response["limitations"])
    assert model.received_fact_ids == ["property-trade-7001"]
    assert repository.trade_query == (11471, date(2025, 7, 1), date(2026, 7, 16), 84.8, 5)


@pytest.mark.parametrize(
    "draft",
    [
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="존재하지 않는 거래입니다.",
                    fact_ids=["property-trade-missing"],
                    claims=[],
                )
            ]
        ),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="거래금액은 999999만원입니다.",
                    fact_ids=["property-trade-7001"],
                    claims=[
                        DraftClaim(
                            fact_id="property-trade-7001",
                            value="999999",
                            unit="10_000_KRW",
                        )
                    ],
                )
            ]
        ),
    ],
)
def test_blocks_unknown_fact_or_numeric_mismatch(draft: DraftAnswer) -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    repository.trades = [
        TradeRecord(7001, 11471, date(2026, 7, 15), 250_000, 84.8, 12)
    ]
    model = FakeLanguageModel(
        PropertyQueryPlan(capability="recent_trade_lookup", complex_name="잠실엘스"),
        draft,
    )

    with pytest.raises(ChatbotProviderUnavailable):
        run_query(GroundedChatbotEngine(repository=repository, language_model=model), "최근 거래", "request-1")


def test_ambiguous_complex_is_not_selected_arbitrarily() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record(1, "중앙동 한빛"), complex_record(2, "서초동 한빛")]
    model = FakeLanguageModel(
        PropertyQueryPlan(capability="complex_identity", complex_name="한빛"),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="같은 이름의 단지가 2곳 확인되어 지역 조건이 필요합니다.",
                    fact_ids=["property-complex-1", "property-complex-2"],
                    claims=[
                        DraftClaim("property-complex-1", "중앙동 한빛", "TEXT"),
                        DraftClaim("property-complex-2", "서초동 한빛", "TEXT"),
                    ],
                )
            ]
        ),
    )

    response = run_query(
        GroundedChatbotEngine(repository=repository, language_model=model),
        "한빛아파트 어디야?",
        "request-2",
    )

    assert response["evidenceSummary"]["status"] == "partial"
    assert response["evidenceSummary"]["factCount"] == 2
    assert "동명 단지" in response["limitations"][0]
    assert repository.trade_query is None


def test_monthly_trend_exposes_amount_and_volume_facts() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    repository.trends = [
        MonthlyTrendRecord(
            complex_id=11471,
            month=date(2026, 6, 1),
            average_amount_ten_thousand_krw=245_000,
            trade_count=3,
            minimum_amount_ten_thousand_krw=240_000,
            maximum_amount_ten_thousand_krw=250_000,
        )
    ]
    model = FakeLanguageModel(
        PropertyQueryPlan(
            capability="price_trend",
            complex_name="잠실엘스",
            start_date=date(2026, 1, 1),
            end_date=date(2026, 6, 30),
        ),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="2026년 6월 평균은 245000만원이고 거래량은 3건입니다.",
                    fact_ids=["property-trend-11471-2026-06"],
                    claims=[
                        DraftClaim("property-trend-11471-2026-06", "245000", "10_000_KRW"),
                        DraftClaim("property-trend-11471-2026-06", "3", "COUNT"),
                    ],
                )
            ]
        ),
    )

    response = run_query(
        GroundedChatbotEngine(repository=repository, language_model=model),
        "잠실엘스 최근 반년 가격 추이와 거래량",
        "request-3",
    )

    assert response["evidenceSummary"]["capabilities"] == ["price_trend"]
    assert response["evidenceSummary"]["factCount"] == 1
    assert response["citations"][0]["factIds"] == ["property-trend-11471-2026-06"]


@pytest.mark.parametrize(
    "plan",
    [
        PropertyQueryPlan(capability="recent_trade_lookup", complex_name="없는단지"),
        PropertyQueryPlan(capability="recent_trade_lookup", complex_name="잠실엘스"),
        PropertyQueryPlan(
            capability="price_trend",
            complex_name="잠실엘스",
            start_date=date(2026, 1, 1),
            end_date=date(2026, 6, 30),
        ),
    ],
)
def test_empty_observation_returns_llm_written_unavailable_answer(plan: PropertyQueryPlan) -> None:
    repository = FakeRepository()
    if plan.complex_name == "잠실엘스":
        repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        plan,
        DraftAnswer(
            sentences=[DraftSentence(text="조건에 맞는 근거 데이터가 없습니다.", fact_ids=[])]
        ),
    )

    response = run_query(
        GroundedChatbotEngine(repository=repository, language_model=model),
        "조회해줘",
        "request-empty",
    )

    assert response["success"] is False
    assert response["status"] == "failed"
    assert response["evidenceSummary"]["status"] == "unavailable"
    assert response["citations"] == []
    assert response["dataAsOf"] is None


def test_identity_does_not_expose_unverified_coordinates() -> None:
    repository = FakeRepository()
    unsafe = complex_record()
    repository.complexes = [
        ComplexRecord(
            **{**unsafe.__dict__, "marker_safe": False},
        )
    ]
    model = FakeLanguageModel(
        PropertyQueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="검증된 위치 좌표가 없습니다.",
                    fact_ids=["property-complex-11471"],
                    claims=[
                        DraftClaim("property-complex-11471", "false", "BOOLEAN")
                    ],
                )
            ]
        ),
    )

    response = run_query(
        GroundedChatbotEngine(repository=repository, language_model=model),
        "잠실엘스 위치",
        "request-identity",
    )

    assert response["evidenceSummary"]["status"] == "supported"
    assert "표시 좌표" in response["limitations"][0]


@pytest.mark.parametrize(
    "factory",
    [
        lambda: PropertyQueryPlan(capability="complex_identity", complex_name=" "),
        lambda: PropertyQueryPlan(
            capability="complex_identity", complex_name="단지", region_name=" "
        ),
        lambda: PropertyQueryPlan(
            capability="recent_trade_lookup",
            complex_name="단지",
            start_date=date(2026, 2, 1),
            end_date=date(2026, 1, 1),
        ),
        lambda: PropertyQueryPlan(
            capability="recent_trade_lookup",
            complex_name="단지",
            exclusive_area_square_meters=0,
        ),
        lambda: PropertyQueryPlan(capability="recent_trade_lookup", complex_name="단지", limit=11),
        lambda: PropertyQueryPlan(capability="price_trend", complex_name="단지"),
    ],
)
def test_query_plan_rejects_unsafe_or_incomplete_constraints(factory) -> None:
    with pytest.raises(ValueError):
        factory()


def test_supported_answer_requires_fact_reference_on_every_sentence() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        PropertyQueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer(sentences=[DraftSentence(text="위치를 확인했습니다.", fact_ids=[])]),
    )

    with pytest.raises(ChatbotProviderUnavailable):
        run_query(
            GroundedChatbotEngine(repository=repository, language_model=model),
            "잠실엘스 위치",
            "request-invalid",
        )


def test_supported_answer_requires_a_validated_claim_on_every_sentence() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        PropertyQueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="잠실엘스는 확인된 단지입니다.",
                    fact_ids=["property-complex-11471"],
                    claims=[],
                )
            ]
        ),
    )

    with pytest.raises(ChatbotProviderUnavailable):
        run_query(
            GroundedChatbotEngine(repository=repository, language_model=model),
            "잠실엘스 위치",
            "request-invalid-claim",
        )


def run_query(engine: GroundedChatbotEngine, question: str, request_id: str) -> dict[str, object]:
    return asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question=question),
            user=AuthenticatedUser(user_id=42),
            request_id=request_id,
        )
    )
