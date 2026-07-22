from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.engine import (
    GroundedChatbotEngine,
    GroundingValidationError,
    _verify_recommendation_plan,
    validate_draft,
)
from ai_service.property_chat.answer_document import (
    FactListArtifact,
    FactListItem,
    FactListPresenter,
)
from ai_service.property_chat.models import (
    ComplexRecord,
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    EvidenceFact,
    FactClaim,
    MonthlyTrendRecord,
    QueryPlan,
    TradeRecord,
)

ALL_PROPERTY_CAPABILITIES = frozenset(
    {"complex_identity", "recent_trade_lookup", "price_trend"}
)


class FakeRepository:
    def __init__(self) -> None:
        self.complexes: list[ComplexRecord] = []
        self.trades: list[TradeRecord] = []
        self.trends: list[MonthlyTrendRecord] = []
        self.latest_trade_data_as_of = date(2026, 7, 16)
        self.trade_query: tuple[int, date | None, date | None, float | None, int] | None = None
        self.complex_query_count = 0

    def find_complexes(self, name: str, region_name: str | None, limit: int) -> list[ComplexRecord]:
        del name, region_name, limit
        self.complex_query_count += 1
        return self.complexes

    def find_complex_by_id(self, complex_id: int) -> ComplexRecord | None:
        return next(
            (record for record in self.complexes if record.complex_id == complex_id),
            None,
        )

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


class FallbackTradeRepository(FakeRepository):
    def __init__(self) -> None:
        super().__init__()
        self.trade_queries: list[tuple[int, date | None, date | None, float | None, int]] = []

    def recent_trades(
        self,
        complex_id: int,
        start_date: date | None,
        end_date: date | None,
        exclusive_area_square_meters: float | None,
        limit: int,
    ) -> list[TradeRecord]:
        self.trade_queries.append(
            (complex_id, start_date, end_date, exclusive_area_square_meters, limit)
        )
        if len(self.trade_queries) == 1:
            return []
        return [TradeRecord(7009, complex_id, date(2024, 12, 20), 230_000, 84.8, 9)]


class TrendFallbackRepository(FakeRepository):
    def recent_trades(self, complex_id, start_date, end_date, area, limit):
        self.trade_query = (complex_id, start_date, end_date, area, limit)
        return [TradeRecord(7010, complex_id, date(2026, 6, 1), 240_000, 84.8, 10)]


class FakeLanguageModel:
    def __init__(self, plan: QueryPlan, draft: DraftAnswer) -> None:
        self.plan = plan
        self.draft = draft
        self.received_fact_ids: list[str] = []
        self.received_facts: list[EvidenceFact] = []

    async def plan_query(self, _request: ChatbotQueryRequest) -> QueryPlan:
        return self.plan

    async def draft_answer(self, *, facts, limitations, question) -> DraftAnswer:
        del limitations, question
        self.received_facts = list(facts)
        self.received_fact_ids = [fact.fact_id for fact in facts]
        return self.draft


class DraftFailingLanguageModel(FakeLanguageModel):
    async def draft_answer(self, *, facts, limitations, question) -> DraftAnswer:
        del facts, limitations, question
        raise ChatbotProviderUnavailable()


class UnavailablePlanningLanguageModel(FakeLanguageModel):
    async def plan_query(self, _request: ChatbotQueryRequest) -> QueryPlan:
        raise ChatbotProviderUnavailable()


def test_broad_question_revalidates_selected_complex_and_returns_overview_fragments() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    repository.trades = [
        TradeRecord(7001, 11471, date(2026, 7, 15), 250_000, 84.8, 12)
    ]
    repository.trends = [
        MonthlyTrendRecord(11471, date(2026, 7, 1), 250_000, 1, 250_000, 250_000)
    ]
    model = DraftFailingLanguageModel(
        QueryPlan("complex_identity", "이 단지"),
        DraftAnswer([]),
    )
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=model,
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
        property_overview_enabled=True,
        today=lambda: date(2026, 7, 20),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest.model_validate({
            "question": "이 단지 전체적으로 어때?",
            "uiContext": {
                "selectedComplex": {"complexId": 11471, "parcelId": 501}
            },
        }),
        user=AuthenticatedUser(user_id=1),
        request_id="request-overview",
    ))

    assert response["status"] == "partial_success"
    assert [fragment["capability"] for fragment in response["fragments"]] == [
        "complex_identity",
        "recent_trade_lookup",
        "price_trend",
        "rail_station_lookup",
    ]
    assert response["executionSummary"] == {"total": 4, "succeeded": 3, "failed": 1}
    assert response["conversationMemoryPatch"]["complexId"] == 11471


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
        QueryPlan(
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
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=model,
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
    )

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
    assert response["conversationResolution"] == {
        "version": 1,
        "answerMode": "COMPLETE",
        "goals": [{"capability": "recent_trade_lookup", "status": "answered"}],
        "assumptions": [],
        "omissions": [],
    }
    assert response["citations"][0]["factIds"] == ["property-trade-7001"]
    assert response["uiArtifacts"][0]["type"] == "tradeTable"
    assert response["uiArtifacts"][0]["rows"][0] == {
        "tradeId": 7001,
        "dealDate": "2026-07-15",
        "exclusiveAreaSquareMeters": 84.8,
        "amountTenThousandKrw": 250000,
        "floor": 12,
        "factIds": ["property-trade-7001"],
    }
    assert any("±1.0㎡" in limitation for limitation in response["limitations"])
    assert model.received_fact_ids == ["property-trade-7001"]
    assert repository.trade_query == (11471, date(2025, 7, 1), date(2026, 7, 16), 84.8, 5)


def test_observed_fact_returns_deterministic_answer_when_draft_provider_fails() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = DraftFailingLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer([]),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "잠실엘스 위치를 알려줘",
        "request-deterministic-fallback",
    )

    assert response["success"] is True
    assert response["status"] == "success"
    assert response["answer"] == (
        "잠실동 잠실엘스의 주소는 서울 송파구 잠실동 19로 확인했습니다."
    )
    assert response["evidenceSummary"]["factCount"] == 1


def test_reserved_assembly_budget_skips_optional_llm_polish() -> None:
    class PolishMustNotRun(FakeLanguageModel):
        async def draft_answer(self, *, facts, limitations, question) -> DraftAnswer:
            del facts, limitations, question
            raise AssertionError("optional polish must not consume assembly reserve")

    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = PolishMustNotRun(
        QueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer([]),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
            polish_budget_seconds=0,
        ),
        "잠실엘스 위치를 알려줘",
        "request-assembly-reserve",
    )

    assert "잠실동 잠실엘스" in response["answer"]


def test_capability_fallback_can_be_rolled_back_independently() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = DraftFailingLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer([]),
    )
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=model,
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
        answer_first_fallback_capabilities=frozenset(),
    )

    with pytest.raises(ChatbotProviderUnavailable):
        run_query(
            engine,
            "잠실엘스 위치를 알려줘",
            "request-capability-fallback-disabled",
        )


def test_observed_fact_replaces_request_only_draft_with_result() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer([
            DraftSentence(
                text="조건에 맞는 단지를 더 알려주세요.",
                fact_ids=["property-complex-11471"],
                claims=[DraftClaim("property-complex-11471", "11471", "COMPLEX_ID")],
            )
        ]),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "잠실엘스 위치를 알려줘",
        "request-quality-gate",
    )

    assert response["answer"] == (
        "잠실동 잠실엘스의 주소는 서울 송파구 잠실동 19로 확인했습니다."
    )


def test_clear_identity_question_uses_deterministic_router_when_planning_fails() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = UnavailablePlanningLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="unused"),
        DraftAnswer([]),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "잠실엘스 위치와 주소를 알려줘",
        "request-router-fallback",
    )

    assert response["success"] is True
    assert response["evidenceSummary"]["capabilities"] == ["complex_identity"]


def test_recent_trade_without_period_uses_one_year_default() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    repository.trades = [
        TradeRecord(7001, 11471, date(2026, 7, 15), 250_000, 84.8, 12)
    ]
    model = FakeLanguageModel(
        QueryPlan(capability="recent_trade_lookup", complex_name="잠실엘스"),
        DraftAnswer([
            DraftSentence(
                text="250000만원 거래입니다.",
                fact_ids=["property-trade-7001"],
                claims=[DraftClaim("property-trade-7001", "250000", "10_000_KRW")],
            )
        ]),
    )
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=model,
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
        today=lambda: date(2026, 7, 22),
    )

    run_query(engine, "잠실엘스 최근 실거래 알려줘", "request-default-period")

    assert repository.trade_query == (
        11471, date(2025, 7, 22), date(2026, 7, 22), None, 5,
    )


def test_recent_trade_exact_empty_returns_same_area_reference_trade() -> None:
    repository = FallbackTradeRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        QueryPlan(
            capability="recent_trade_lookup",
            complex_name="잠실엘스",
            start_date=date(2025, 7, 22),
            end_date=date(2026, 7, 22),
            exclusive_area_square_meters=84.8,
        ),
        DraftAnswer([]),
    )

    response = run_query(
        GroundedChatbotEngine(
        repository=repository,
        language_model=model,
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
        ),
        "잠실엘스 전용 84.8㎡ 최근 실거래 알려줘",
        "request-reference-trade",
    )

    assert response["success"] is True
    assert response["status"] == "partial_success"
    assert response["conversationResolution"]["answerMode"] == "BEST_EFFORT"
    assert response["conversationResolution"]["goals"] == [
        {"capability": "recent_trade_lookup", "status": "degraded"}
    ]
    assert response["conversationResolution"]["assumptions"][0]["code"] == (
        "SAME_AREA_ANY_PERIOD"
    )
    assert response["uiArtifacts"][0]["type"] == "tradeTable"
    assert any("정확 조건에서는 0건" in item for item in response["limitations"])
    assert repository.trade_queries == [
        (11471, date(2025, 7, 22), date(2026, 7, 22), 84.8, 5),
        (11471, None, None, 84.8, 5),
    ]


def test_price_trend_empty_returns_recent_individual_trade_reference() -> None:
    repository = TrendFallbackRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        QueryPlan(
            capability="price_trend",
            complex_name="잠실엘스",
            start_date=date(2025, 7, 22),
            end_date=date(2026, 7, 22),
            exclusive_area_square_meters=84.8,
        ),
        DraftAnswer([]),
    )

    response = run_query(
        GroundedChatbotEngine(
        repository=repository,
        language_model=model,
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
        ),
        "잠실엘스 전용 84.8㎡ 가격 흐름 알려줘",
        "request-trend-reference",
    )

    assert response["success"] is True
    assert response["status"] == "partial_success"
    assert response["evidenceSummary"]["factCount"] == 1
    assert any("월별 추이" in item and "개별 거래" in item for item in response["limitations"])


def test_trade_with_no_exact_or_reference_result_keeps_verified_complex() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = DraftFailingLanguageModel(
        QueryPlan(
            capability="recent_trade_lookup",
            complex_name="잠실엘스",
            exclusive_area_square_meters=84.8,
        ),
        DraftAnswer([]),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "잠실엘스 전용 84.8㎡ 최근 실거래 알려줘",
        "request-no-trade-reference",
    )

    assert response["success"] is True
    assert response["status"] == "partial_success"
    assert response["conversationResolution"]["answerMode"] == "NO_RESULT"
    assert response["conversationResolution"]["goals"] == [
        {"capability": "recent_trade_lookup", "status": "degraded"}
    ]
    assert "잠실동 잠실엘스" in response["answer"]
    assert "실거래가 없습니다" in response["limitations"][0]
    assert response["conversationMemoryPatch"]["complexId"] == 11471


def test_recent_trade_accepts_server_supplied_korean_amount_display_claim() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    repository.trades = [
        TradeRecord(7001, 11471, date(2026, 7, 15), 253_000, 84.8, 12)
    ]
    model = FakeLanguageModel(
        QueryPlan(capability="recent_trade_lookup", complex_name="잠실엘스"),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="거래 금액은 25억 3,000만원입니다.",
                    fact_ids=["property-trade-7001"],
                    claims=[
                        DraftClaim(
                            fact_id="property-trade-7001",
                            value="25억 3,000만원",
                            unit="KOREAN_KRW_DISPLAY",
                        )
                    ],
                )
            ]
        ),
    )

    response = run_query(
        GroundedChatbotEngine(
                repository=repository,
                language_model=model,
                enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
                answer_first_enabled=True,
        ),
        "최근 거래",
        "request-formatted-amount",
    )

    assert response["success"] is True
    assert response["evidenceSummary"]["factCount"] == 1


def test_supported_answer_recovers_when_model_omits_an_observed_trade_fact() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    repository.trades = [
        TradeRecord(7001, 11471, date(2026, 7, 15), 250_000, 84.8, 12),
        TradeRecord(7002, 11471, date(2026, 7, 14), 249_000, 84.8, 10),
    ]
    model = FakeLanguageModel(
        QueryPlan(capability="recent_trade_lookup", complex_name="잠실엘스"),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="250000만원 거래입니다.",
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

    response = run_query(
        GroundedChatbotEngine(
                repository=repository,
                language_model=model,
                enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
                answer_first_enabled=True,
        ),
        "최근 거래",
        "request-omitted-fact",
    )

    assert response["status"] == "success"
    assert response["evidenceSummary"]["factCount"] == 2


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
def test_recovers_from_unknown_fact_or_numeric_mismatch(draft: DraftAnswer) -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    repository.trades = [
        TradeRecord(7001, 11471, date(2026, 7, 15), 250_000, 84.8, 12)
    ]
    model = FakeLanguageModel(
        QueryPlan(capability="recent_trade_lookup", complex_name="잠실엘스"),
        draft,
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "최근 거래",
        "request-1",
    )

    assert response["status"] == "success"
    assert "999999" not in response["answer"]
    assert "존재하지" not in response["answer"]


def test_ambiguous_complex_is_not_selected_arbitrarily() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record(1, "중앙동 한빛"), complex_record(2, "서초동 한빛")]
    model = FakeLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="한빛"),
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
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "한빛아파트 어디야?",
        "request-2",
    )

    assert response["evidenceSummary"]["status"] == "partial"
    assert response["status"] == "partial_success"
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
        QueryPlan(
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
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        ),
        "잠실엘스 최근 반년 가격 추이와 거래량",
        "request-3",
    )

    assert response["evidenceSummary"]["capabilities"] == ["price_trend"]
    assert response["evidenceSummary"]["factCount"] == 1
    assert response["citations"][0]["factIds"] == ["property-trend-11471-2026-06"]
    assert {
        (claim.value, claim.unit) for claim in model.received_facts[0].claims
    }.issuperset(
        {
            ("24억 5,000만원", "KOREAN_KRW_AVERAGE_DISPLAY"),
            ("24억원", "KOREAN_KRW_MIN_DISPLAY"),
            ("25억원", "KOREAN_KRW_MAX_DISPLAY"),
        }
    )
    assert response["uiArtifacts"][0]["type"] == "trendTable"
    assert response["uiArtifacts"][0]["rows"][0]["month"] == "2026-06"
    assert response["uiArtifacts"][0]["rows"][0]["factIds"] == [
        "property-trend-11471-2026-06"
    ]


@pytest.mark.parametrize(
    "plan",
    [
        QueryPlan(capability="recent_trade_lookup", complex_name="없는단지"),
        QueryPlan(capability="recent_trade_lookup", complex_name="잠실엘스"),
        QueryPlan(
            capability="price_trend",
            complex_name="잠실엘스",
            start_date=date(2026, 1, 1),
            end_date=date(2026, 6, 30),
        ),
    ],
)
def test_empty_observation_returns_llm_written_unavailable_answer(plan: QueryPlan) -> None:
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
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        ),
        "조회해줘",
        "request-empty",
    )

    assert response["success"] is False
    assert response["status"] == "failed"
    assert response["evidenceSummary"]["status"] == "unavailable"
    assert response["citations"] == []
    assert response["dataAsOf"] is None


@pytest.mark.parametrize("capability", ["recent_trade_lookup", "price_trend"])
def test_disabled_capability_uses_llm_written_unavailable_without_repository_access(
    capability: str,
) -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        QueryPlan(
            capability=capability,
            complex_name="잠실엘스",
            start_date=date(2026, 1, 1) if capability == "price_trend" else None,
            end_date=date(2026, 6, 30) if capability == "price_trend" else None,
        ),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="해당 질문 기능은 현재 데이터 준비와 검증이 진행 중입니다.",
                    fact_ids=[],
                )
            ]
        ),
    )
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=model,
        enabled_capabilities=frozenset({"complex_identity"}),
    )

    response = run_query(engine, "조회해줘", "request-disabled")

    assert response["success"] is False
    assert response["status"] == "failed"
    assert response["evidenceSummary"] == {
        "status": "unavailable",
        "capabilities": [capability],
        "factCount": 0,
        "citationCount": 0,
    }
    assert response["citations"] == []
    assert response["dataAsOf"] is None
    assert model.received_fact_ids == []
    assert repository.complex_query_count == 0
    assert repository.trade_query is None


def test_identity_does_not_expose_unverified_coordinates() -> None:
    repository = FakeRepository()
    unsafe = complex_record()
    repository.complexes = [
        ComplexRecord(
            **{**unsafe.__dict__, "marker_safe": False},
        )
    ]
    model = FakeLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="잠실엘스"),
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
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        ),
        "잠실엘스 위치",
        "request-identity",
    )

    assert response["evidenceSummary"]["status"] == "supported"
    assert "표시 좌표" in response["limitations"][0]


def test_complex_identity_returns_fact_list_artifact_from_the_validated_fact() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer(
            sentences=[
                DraftSentence(
                    text="잠실동 잠실엘스의 주소는 서울 송파구 잠실동 19입니다.",
                    fact_ids=["property-complex-11471"],
                    claims=[
                        DraftClaim(
                            "property-complex-11471",
                            "잠실동 잠실엘스",
                            "TEXT",
                        ),
                        DraftClaim(
                            "property-complex-11471",
                            "서울 송파구 잠실동 19",
                            "TEXT",
                        ),
                    ],
                )
            ]
        ),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        ),
        "잠실엘스 위치",
        "request-identity-artifact",
    )

    assert response["uiArtifacts"] == [
        {
            "type": "factList",
            "version": 1,
            "artifactId": "fact-list-complex-11471",
            "title": "확인된 단지 정보",
            "items": [
                {
                    "label": "단지명",
                    "value": "잠실동 잠실엘스",
                    "factIds": ["property-complex-11471"],
                },
                {
                    "label": "지역",
                    "value": "잠실동",
                    "factIds": ["property-complex-11471"],
                },
                {
                    "label": "주소",
                    "value": "서울 송파구 잠실동 19",
                    "factIds": ["property-complex-11471"],
                },
                {
                    "label": "위치",
                    "value": "37.513, 127.082",
                    "factIds": ["property-complex-11471"],
                },
            ],
        }
    ]
    assert response["citations"][0]["factIds"] == ["property-complex-11471"]


def test_complex_identity_returns_grounded_ui_summary_v1() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer(sentences=[DraftSentence(
            text="잠실동 잠실엘스를 확인했습니다.",
            fact_ids=["property-complex-11471"],
            claims=[DraftClaim("property-complex-11471", "잠실동 잠실엘스", "TEXT")],
        )]),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        ),
        "잠실엘스 위치",
        "request-identity-summary",
    )

    assert response["uiSummary"] == {
        "version": 1,
        "scopeNotice": {
            "text": "‘잠실엘스’ 단지를 기준으로 확인했습니다.",
            "factIds": ["property-complex-11471"],
        },
        "headline": {
            "text": "잠실동 잠실엘스의 확인된 단지 정보를 정리했습니다.",
            "factIds": ["property-complex-11471"],
        },
        "criteria": [],
        "interpretations": [],
        "followUp": "최근 실거래, 가격 흐름 또는 주변 시설을 이어서 확인할 수 있습니다.",
        "fragmentSummaries": [],
    }


@pytest.mark.parametrize(
    ("label", "value", "fact_ids"),
    [
        (" ", "value", ("fact-1",)),
        ("label", " ", ("fact-1",)),
        ("label", "value", ()),
        ("label", "value", ("fact-1", "fact-1")),
    ],
)
def test_fact_list_item_rejects_invalid_public_fields(
    label: str, value: str, fact_ids: tuple[str, ...]
) -> None:
    with pytest.raises(ValueError):
        FactListItem(label, value, fact_ids)


def test_fact_list_artifact_enforces_title_item_and_serialized_size_limits() -> None:
    item = FactListItem("항목", "값", ("fact-1",))
    with pytest.raises(ValueError):
        FactListArtifact("artifact-1", " ", (item,))
    with pytest.raises(ValueError):
        FactListArtifact("artifact-1", "제목", ())

    oversized_items = tuple(
        FactListItem(f"항목-{index}", "🙂" * 2_000, (f"fact-{index}",))
        for index in range(10)
    )
    with pytest.raises(ValueError):
        FactListArtifact("artifact-oversized", "제목", oversized_items).to_public_dict()


def test_fact_list_presenter_ignores_other_capabilities_and_invalid_identity_payloads() -> None:
    fact = EvidenceFact(
        fact_id="property-complex-1",
        claims=(FactClaim("1", "COMPLEX_ID"),),
        data_as_of=date(2026, 7, 16),
        payload={"complexId": "1", "displayName": "단지"},
    )
    presenter = FactListPresenter()

    assert presenter.present(
        plan=QueryPlan(capability="recent_trade_lookup", complex_name="단지"),
        used_facts=[fact],
        readiness="supported",
    ) == []
    assert presenter.present(
        plan=QueryPlan(capability="complex_identity", complex_name="단지"),
        used_facts=[fact],
        readiness="unavailable",
    ) == []
    assert presenter.present(
        plan=QueryPlan(capability="complex_identity", complex_name="단지"),
        used_facts=[],
        readiness="supported",
    ) == []
    assert presenter.present(
        plan=QueryPlan(capability="complex_identity", complex_name="단지"),
        used_facts=[fact],
        readiness="supported",
    ) == []


@pytest.mark.parametrize(
    "factory",
    [
        lambda: QueryPlan(capability="complex_identity", complex_name=" "),
        lambda: QueryPlan(
            capability="complex_identity", complex_name="단지", region_name=" "
        ),
        lambda: QueryPlan(
            capability="recent_trade_lookup",
            complex_name="단지",
            start_date=date(2026, 2, 1),
            end_date=date(2026, 1, 1),
        ),
        lambda: QueryPlan(
            capability="recent_trade_lookup",
            complex_name="단지",
            exclusive_area_square_meters=0,
        ),
        lambda: QueryPlan(capability="recent_trade_lookup", complex_name="단지", limit=11),
        lambda: QueryPlan(capability="school_location", complex_name="단지", limit=6),
        lambda: QueryPlan(
            capability="school_location",
            complex_name="단지",
            school_levels=("ELEMENTARY", "ELEMENTARY"),
        ),
        lambda: QueryPlan(
            capability="retail_location",
            complex_name="단지",
            facility_subtypes=("LARGE_MART", "LARGE_MART"),
        ),
        lambda: QueryPlan(
            capability="retail_location",
            complex_name="단지",
            radius_meters=-1,
        ),
        lambda: QueryPlan(capability="price_trend", complex_name="단지"),
    ],
)
def test_query_plan_rejects_unsafe_or_incomplete_constraints(factory) -> None:
    with pytest.raises(ValueError):
        factory()


def test_supported_answer_recovers_when_fact_reference_is_missing() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="잠실엘스"),
        DraftAnswer(sentences=[DraftSentence(text="위치를 확인했습니다.", fact_ids=[])]),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "잠실엘스 위치",
        "request-invalid",
    )
    assert response["status"] == "success"


def test_supported_answer_recovers_when_validated_claim_is_missing() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    model = FakeLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="잠실엘스"),
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

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "잠실엘스 위치",
        "request-invalid-claim",
    )
    assert response["status"] == "success"


def test_grounding_diagnostic_classifies_result_count_or_list_number() -> None:
    facts = [
        EvidenceFact(
            fact_id=f"property-trade-{index}",
            claims=(FactClaim(str(120000 + index), "10_000_KRW"),),
            data_as_of=date(2026, 7, 16),
            payload={},
        )
        for index in range(1, 4)
    ]
    draft = DraftAnswer(
        sentences=[
            DraftSentence(
                text="최근 3건의 거래입니다.",
                fact_ids=[fact.fact_id for fact in facts],
                claims=[
                    DraftClaim(
                        fact_id=fact.fact_id,
                        value=fact.claims[0].value,
                        unit=fact.claims[0].unit,
                    )
                    for fact in facts
                ],
            )
        ]
    )

    with pytest.raises(GroundingValidationError) as raised:
        validate_draft(draft, facts, "supported")

    assert raised.value.reason_code == "GROUNDING_RESULT_COUNT_OR_LIST_NUMBER"


def test_grounding_diagnostic_classifies_amount_unit_conversion() -> None:
    fact = EvidenceFact(
        fact_id="property-trade-7",
        claims=(FactClaim("120000", "10_000_KRW"),),
        data_as_of=date(2026, 7, 16),
        payload={},
    )
    draft = DraftAnswer(
        sentences=[
            DraftSentence(
                text="거래 금액은 12억원입니다.",
                fact_ids=[fact.fact_id],
                claims=[
                    DraftClaim(
                        fact_id=fact.fact_id,
                        value="120000",
                        unit="10_000_KRW",
                    )
                ],
            )
        ]
    )

    with pytest.raises(GroundingValidationError) as raised:
        validate_draft(draft, [fact], "supported")

    assert raised.value.reason_code == "GROUNDING_AMOUNT_UNIT_CONVERSION"


def run_query(engine: GroundedChatbotEngine, question: str, request_id: str) -> dict[str, object]:
    return asyncio.run(
        engine.query(
            request=ChatbotQueryRequest(question=question),
            user=AuthenticatedUser(user_id=42),
            request_id=request_id,
        )
    )


@pytest.mark.parametrize(
    ("plan", "question", "clarification"),
    (
        (
            QueryPlan(
                capability="recommendation", complex_name="송파구",
                region_name="송파구", recommendation_mode="CRITERIA",
            ),
            "송파구 교육 조건으로 추천",
            None,
        ),
        (
            QueryPlan(
                capability="recommendation", complex_name="송파구",
                region_name="송파구", recommendation_mode="CRITERIA",
                minimum_unit_count=700, recommendation_criteria=("ACADEMY",),
                criteria_order=("ACADEMY",),
            ),
            "송파구 500세대 이상 학원 추천",
            None,
        ),
        (
            QueryPlan(
                capability="recommendation", complex_name="마포구",
                region_name="마포구", recommendation_mode="CRITERIA",
                recommendation_criteria=("ACADEMY",), criteria_order=("ACADEMY",),
            ),
            "송파구 학원 추천",
            "REGION_NOT_CONFIRMED",
        ),
        (
            QueryPlan(
                capability="recommendation", complex_name="여의도역",
                recommendation_mode="CRITERIA", station_name="여의도",
                radius_meters=800, recommendation_criteria=("ACADEMY",),
                criteria_order=("ACADEMY",),
            ),
            "다른 역 800m 학원 추천",
            "REGION_NOT_CONFIRMED",
        ),
        (
            QueryPlan(
                capability="recommendation", complex_name="여의도역",
                recommendation_mode="CRITERIA", station_name="여의도",
                recommendation_criteria=("ACADEMY",), criteria_order=("ACADEMY",),
            ),
            "여의도역 학원 추천",
            None,
        ),
        (
            QueryPlan(
                capability="recommendation", complex_name="여의도역",
                recommendation_mode="CRITERIA", station_name="여의도",
                radius_meters=500, recommendation_criteria=("ACADEMY",),
                criteria_order=("ACADEMY",),
            ),
            "여의도역 800m 학원 추천",
            None,
        ),
        (
            QueryPlan(
                capability="recommendation", complex_name="여의도역",
                recommendation_mode="CRITERIA", station_name="여의도",
                radius_meters=250, recommendation_criteria=("ACADEMY",),
                criteria_order=("ACADEMY",),
            ),
            "여의도역 250m 학원 추천",
            None,
        ),
    ),
)
def test_server_revalidates_criteria_conditions_from_the_current_question(
    plan: QueryPlan, question: str, clarification: str | None,
) -> None:
    verified = _verify_recommendation_plan(plan, question)

    assert verified.clarification_code == clarification


def test_server_revalidates_explicit_recommendation_result_limit() -> None:
    verified = _verify_recommendation_plan(
        QueryPlan(
            capability="recommendation",
            complex_name="송파구",
            region_name="송파구",
            recommendation_mode="BUDGET",
            maximum_budget_ten_thousand_krw=200_000,
            exclusive_area_square_meters=84.0,
            limit=5,
        ),
        "송파구에서 20억원 이하 전용 84㎡ 아파트 3곳을 추천해줘",
    )

    assert verified.limit == 3
    assert verified.clarification_code is None


def test_recommendation_verifier_leaves_non_recommendation_plan_unchanged() -> None:
    plan = QueryPlan(capability="complex_identity", complex_name="잠실엘스")

    assert _verify_recommendation_plan(plan, "잠실엘스 알려줘") is plan
