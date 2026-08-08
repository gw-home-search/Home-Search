from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import UTC, date, datetime

import pytest

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest
from ai_service.terminal_response import with_terminal_outcome
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
from ai_service.property_chat.candidate_selection import CandidateObservationSummary

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


class UnavailablePropertyRepository(FakeRepository):
    def find_complexes(
        self, name: str, region_name: str | None, limit: int
    ) -> list[ComplexRecord]:
        del name, region_name, limit
        raise OSError("property database unavailable")


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


class AreaGuardRepository(FakeRepository):
    def monthly_trends(
        self,
        complex_id: int,
        start_date: date,
        end_date: date,
        exclusive_area_square_meters: float | None,
    ) -> list[MonthlyTrendRecord]:
        del complex_id, start_date, end_date, exclusive_area_square_meters
        raise AssertionError("area-less monthly trend query must not run")


class FakeLanguageModel:
    def __init__(self, plan: QueryPlan, draft: DraftAnswer) -> None:
        self.plan = plan
        self.draft = draft
        self.received_fact_ids: list[str] = []
        self.received_facts: list[EvidenceFact] = []
        self.plan_calls = 0

    async def plan_query(self, _request: ChatbotQueryRequest) -> QueryPlan:
        self.plan_calls += 1
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

    assert response["status"] == "success"
    assert [fragment["capability"] for fragment in response["fragments"]] == [
        "complex_identity",
        "recent_trade_lookup",
    ]
    assert response["executionSummary"] == {"total": 2, "succeeded": 2, "failed": 0}
    assert response["conversationMemoryPatch"]["complexId"] == 11471
    assert model.plan_calls == 0


def test_verified_selected_complex_precedes_ambiguous_name_candidates() -> None:
    repository = FakeRepository()
    repository.complexes = [
        replace(complex_record(501, "가락동 헬리오시티"), parcel_id=1501),
        replace(complex_record(502, "작동 헬리오시티"), parcel_id=1502),
    ]
    repository.trades = [
        TradeRecord(7002, 502, date(2026, 7, 10), 120_000, 59.0, 8)
    ]
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=DraftFailingLanguageModel(
            QueryPlan("recent_trade_lookup", "unused"), DraftAnswer([])
        ),
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest.model_validate({
            "question": "헬리오시티 전용 59㎡ 최근 실거래 5건",
            "uiContext": {
                "selectedComplex": {"complexId": 502, "parcelId": 1502}
            },
        }),
        user=AuthenticatedUser(user_id=1),
        request_id="request-selected-precedence",
    ))

    assert response["status"] == "success"
    assert repository.trade_query is not None
    assert repository.trade_query[0] == 502


def test_selected_complex_with_mismatched_parcel_is_not_trusted() -> None:
    repository = FakeRepository()
    repository.complexes = [
        replace(complex_record(501, "가락동 헬리오시티"), parcel_id=1501),
        replace(complex_record(502, "작동 헬리오시티"), parcel_id=1502),
    ]
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=DraftFailingLanguageModel(
            QueryPlan("recent_trade_lookup", "unused"), DraftAnswer([])
        ),
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest.model_validate({
            "question": "헬리오시티 전용 59㎡ 최근 실거래 5건",
            "uiContext": {
                "selectedComplex": {"complexId": 502, "parcelId": 9999}
            },
        }),
        user=AuthenticatedUser(user_id=1),
        request_id="request-selected-mismatch",
    ))

    assert response["status"] == "partial_success"
    assert repository.trade_query is None


def test_compound_trade_and_area_less_trend_keeps_trade_without_mixed_area_query() -> None:
    repository = AreaGuardRepository()
    repository.complexes = [complex_record()]
    repository.trades = [
        TradeRecord(7001, 11471, date(2026, 7, 15), 250_000, 84.8, 12)
    ]
    model = DraftFailingLanguageModel(
        QueryPlan("complex_identity", "잠실엘스"),
        DraftAnswer([]),
    )
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=model,
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
        answer_first_fallback_capabilities=ALL_PROPERTY_CAPABILITIES,
        today=lambda: date(2026, 7, 20),
    )

    response = asyncio.run(engine.query(
        request=ChatbotQueryRequest(
            question="잠실엘스 최근 거래와 가격 흐름을 함께 알려줘"
        ),
        user=AuthenticatedUser(user_id=1),
        request_id="request-compound-area-guard",
    ))

    assert [fragment["capability"] for fragment in response["fragments"]] == [
        "recent_trade_lookup",
        "price_trend",
    ]
    assert [fragment["status"] for fragment in response["fragments"]] == [
        "success",
        "failed",
    ]
    assert response["status"] == "partial_success"
    assert response["evidenceSummary"]["factCount"] >= 1


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
        "잠실동 잠실엘스는 서울 송파구 잠실동 19에 있습니다."
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
        "잠실동 잠실엘스는 서울 송파구 잠실동 19에 있습니다."
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


def test_recent_trade_exact_empty_does_not_widen_period_or_area() -> None:
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
        today=lambda: date(2026, 7, 22),
        ),
        "잠실엘스 전용 84.8㎡ 최근 실거래 알려줘",
        "request-reference-trade",
    )

    assert response["success"] is True
    assert response["status"] == "partial_success"
    assert response["conversationResolution"]["answerMode"] == "NO_RESULT"
    assert response["conversationResolution"]["goals"] == [
        {"capability": "recent_trade_lookup", "status": "degraded"}
    ]
    assert not any(
        item["type"] == "tradeTable" for item in response["uiArtifacts"]
    )
    assert "2025-07-22부터 2026-07-22까지" in response["limitations"][0]
    assert "전용 84.8㎡ ±1.0㎡" in response["limitations"][0]
    assert "최근 3년 실거래" in response["uiSummary"]["followUp"]
    assert repository.trade_queries == [
        (11471, date(2025, 7, 22), date(2026, 7, 22), 84.8, 5),
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
    assert response["evidenceSummary"]["factCount"] == 2
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
    assert "실거래는 확인되지 않았습니다" in response["limitations"][0]
    assert response["conversationMemoryPatch"]["complexId"] == 11471


def test_all_empty_candidates_keep_the_original_representative() -> None:
    repository = FakeRepository()
    repository.complexes = [
        replace(complex_record(11471, "대표 단지"), unit_count=2_000),
        replace(complex_record(11472, "다른 후보"), unit_count=20),
    ]
    model = DraftFailingLanguageModel(
        QueryPlan(
            capability="recent_trade_lookup",
            complex_name="동명 단지",
            exclusive_area_square_meters=84.0,
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
        "동명 단지 전용 84㎡ 최근 실거래 알려줘",
        "request-all-empty-candidates",
    )

    assert response["conversationMemoryPatch"] is None
    assert "동명 단지" in response["limitations"][0]
    assert response["uiActions"] == []
    assert repository.trade_query is None


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
    assert response["evidenceSummary"]["factCount"] == 2


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
    assert response["evidenceSummary"]["factCount"] == 3


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
            answer_first_enabled=False,
        ),
        "한빛아파트 어디야?",
        "request-2",
    )

    assert response["evidenceSummary"]["status"] == "partial"
    assert response["status"] == "partial_success"
    assert response["evidenceSummary"]["factCount"] == 2
    assert "동명 단지" in response["limitations"][0]
    assert repository.trade_query is None


def test_answer_first_overview_does_not_choose_a_partial_name_by_trade_activity() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record(1, "반포자이"), complex_record(2, "개포자이")]
    model = DraftFailingLanguageModel(
        QueryPlan("complex_identity", "unused"), DraftAnswer([])
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "자이 어때?",
        "request-ambiguous-overview",
    )

    assert response["status"] == "partial_success"
    assert any("동명 단지" in item for item in response["limitations"]), response["limitations"]
    assert response["uiActions"] == []
    assert repository.trade_query is None
    assert model.plan_calls == 0
    response["limitations"] = ["지역이나 주소를 더 알려주세요."]
    terminal = with_terminal_outcome(response)
    assert terminal["terminalOutcome"]["status"] == "CLARIFICATION"


def test_property_core_failure_propagates_to_temporary_failure_boundary() -> None:
    engine = GroundedChatbotEngine(
        repository=UnavailablePropertyRepository(),
        language_model=DraftFailingLanguageModel(
            QueryPlan("complex_identity", "unused"), DraftAnswer([])
        ),
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
    )

    with pytest.raises(ChatbotProviderUnavailable):
        run_query(engine, "반포자이 위치 알려줘", "request-property-core-failure")


def test_bare_pyeong_returns_clarification_without_querying_trades() -> None:
    repository = FakeRepository()
    repository.complexes = [complex_record()]
    engine = GroundedChatbotEngine(
        repository=repository,
        language_model=DraftFailingLanguageModel(
            QueryPlan("recent_trade_lookup", "unused"), DraftAnswer([])
        ),
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
    )

    response = with_terminal_outcome(run_query(
        engine,
        "잠실엘스 24평형 최근 실거래를 알려줘",
        "request-area-clarification",
    ))

    assert response["terminalOutcome"] == {
        "version": 1,
        "status": "CLARIFICATION",
        "reason": "INSUFFICIENT_EVIDENCE",
        "retryable": False,
    }
    assert "전용면적인지 확인" in response["answer"]
    assert repository.trade_query is None


def test_answer_first_overview_clarifies_multiple_long_partial_names() -> None:
    repository = FakeRepository()
    repository.complexes = [
        replace(complex_record(1, "마포래미안"), match_tier=3),
        replace(complex_record(2, "서초래미안"), match_tier=3),
    ]
    model = DraftFailingLanguageModel(
        QueryPlan("complex_identity", "unused"), DraftAnswer([])
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "래미안 어때?",
        "request-ambiguous-long-overview",
    )

    assert with_terminal_outcome(response)["terminalOutcome"]["status"] == "CLARIFICATION"
    assert response["conversationMemoryPatch"] is None
    assert response["uiActions"] == []
    assert repository.trade_query is None


def test_answer_first_overview_keeps_unique_alias_before_trade_activity() -> None:
    class AliasRepository(FakeRepository):
        def candidate_observation_summaries(
            self, complex_ids, start_date, end_date, area, capability
        ):
            del start_date, end_date, area
            return tuple(
                CandidateObservationSummary(
                    complex_id,
                    3 if complex_id == 2 else 0,
                    date(2026, 7, 1) if complex_id == 2 else None,
                    (capability,) if complex_id == 2 else (),
                )
                for complex_id in complex_ids
            )

    repository = AliasRepository()
    repository.complexes = [
        replace(complex_record(1, "래미안퍼스티지"), match_tier=2),
        replace(complex_record(2, "퍼스티지자이"), match_tier=3),
    ]
    model = DraftFailingLanguageModel(
        QueryPlan("complex_identity", "unused"), DraftAnswer([])
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        "퍼스티지 어때?",
        "request-unique-alias-overview",
    )

    assert response["conversationMemoryPatch"]["complexId"] == 1
    assert repository.trade_query is not None
    assert repository.trade_query[0] == 1


def test_answer_first_multi_candidate_trade_requires_clarification() -> None:
    class MultiCandidateRepository(FakeRepository):
        def candidate_observation_summaries(
            self, complex_ids, start_date, end_date, area, capability
        ):
            del start_date, end_date, area
            return tuple(
                CandidateObservationSummary(
                    complex_id,
                    2 if complex_id == 7756 else 0,
                    date(2026, 6, 20) if complex_id == 7756 else None,
                    (capability,) if complex_id == 7756 else (),
                )
                for complex_id in complex_ids
            )

        def recent_trades(self, complex_id, start_date, end_date, area, limit):
            self.trade_query = (complex_id, start_date, end_date, area, limit)
            if complex_id != 7756:
                return []
            return [
                TradeRecord(9005, 7756, date(2026, 6, 20), 250_000, 84.60, 18),
                TradeRecord(9004, 7756, date(2026, 6, 5), 265_000, 84.60, 21),
            ]

    repository = MultiCandidateRepository()
    repository.complexes = [
        ComplexRecord(
            complex_id=7753,
            parcel_id=8015,
            display_name="마포래미안푸르지오1단지",
            region_code="11440101",
            region_name="아현동",
            address="서울 마포구 아현동",
            latitude=37.5555141,
            longitude=126.9537536,
            marker_safe=True,
            data_updated_at=datetime(2026, 7, 31, tzinfo=UTC),
            unit_count=3885,
        ),
        ComplexRecord(
            complex_id=7756,
            parcel_id=8015,
            display_name="마포래미안푸르지오4단지",
            region_code="11440101",
            region_name="아현동",
            address="서울 마포구 아현동",
            latitude=37.5555141,
            longitude=126.9537536,
            marker_safe=True,
            data_updated_at=datetime(2026, 7, 31, tzinfo=UTC),
            unit_count=1237,
        ),
    ]
    model = DraftFailingLanguageModel(
        QueryPlan(
            capability="recent_trade_lookup",
            complex_name="마포래미안푸르지오",
            start_date=date(2025, 7, 31),
            end_date=date(2026, 7, 31),
            exclusive_area_square_meters=84,
            limit=5,
        ),
        DraftAnswer([]),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
            today=lambda: date(2026, 7, 31),
        ),
        "마포래미안푸르지오 전용 84㎡의 최근 실거래 5건을 거래일과 층까지 알려줘",
        "request-mapo",
    )

    assert with_terminal_outcome(response)["terminalOutcome"]["status"] == "CLARIFICATION"
    assert repository.trade_query is None
    assert response["conversationMemoryPatch"] is None
    assert response["uiActions"]
    assert all(action["autoRun"] is False for action in response["uiActions"])


def test_answer_first_helio_trend_counts_month_rows_not_candidates() -> None:
    class HelioRepository(FakeRepository):
        selected_complex_id: int | None = None

        def candidate_observation_summaries(
            self, complex_ids, start_date, end_date, area, capability
        ):
            del start_date, end_date, area
            return tuple(CandidateObservationSummary(
                complex_id,
                20 if complex_id == 12416 else 0,
                date(2026, 7, 1) if complex_id == 12416 else None,
                (capability,) if complex_id == 12416 else (),
            ) for complex_id in complex_ids)

        def monthly_trends(self, complex_id, start_date, end_date, area):
            del start_date, end_date, area
            self.selected_complex_id = complex_id
            return self.trends if complex_id == 12416 else []

    repository = HelioRepository()
    repository.complexes = [
        ComplexRecord(
            12417, "작동 헬리오시티", "11710103", "작동", "서울 송파구 작동",
            37.49, 127.10, True, datetime(2026, 7, 31, tzinfo=UTC),
            unit_count=20, parcel_id=9016, match_tier=3,
        ),
        ComplexRecord(
            12416, "가락동 헬리오시티", "11710107", "가락동", "서울 송파구 가락동",
            37.497, 127.107, True, datetime(2026, 7, 31, tzinfo=UTC),
            unit_count=9510, parcel_id=9015, match_tier=2,
        ),
    ]
    rows = (
        (date(2025, 9, 1), 255_500, 4),
        (date(2025, 10, 1), 270_000, 1),
        (date(2025, 11, 1), 278_000, 1),
        (date(2025, 12, 1), 278_000, 1),
        (date(2026, 3, 1), 246_500, 2),
        (date(2026, 4, 1), 253_429, 7),
        (date(2026, 5, 1), 257_667, 3),
        (date(2026, 7, 1), 193_000, 1),
    )
    repository.trends = [
        MonthlyTrendRecord(12416, month, average, count, average, average)
        for month, average, count in rows
    ]
    model = DraftFailingLanguageModel(QueryPlan(
        "price_trend", "헬리오시티",
        start_date=date(2025, 8, 1), end_date=date(2026, 8, 1),
        exclusive_area_square_meters=59,
    ), DraftAnswer([]))

    response = run_query(GroundedChatbotEngine(
        repository=repository,
        language_model=model,
        enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
        answer_first_enabled=True,
        today=lambda: date(2026, 8, 1),
    ), "헬리오시티 전용 59㎡의 최근 1년 월별 가격 흐름과 거래량을 보여줘", "request-helio")

    trend = next(item for item in response["uiArtifacts"] if item["type"] == "trendTable")
    assert len(trend["rows"]) == 8
    assert sum(row["tradeCount"] for row in trend["rows"]) == 20
    expected_lead = (
        "가락동 헬리오시티의 2025-08-01~2026-08-01·전용 59㎡ 월별 관찰값은 "
        "8개월·총 20건입니다. 최근 관찰월 평균은 19억 3,000만원입니다."
    )
    assert response["answer"].startswith(expected_lead)
    assert response["uiSummary"]["headline"]["text"] == expected_lead
    assert response["uiReport"]["opening"]["text"] == expected_lead
    assert repository.selected_complex_id == 12416
    assert response["conversationMemoryPatch"]["complexId"] == 12416
    assert response["uiActions"][0]["complexId"] == 12416
    assert response["uiActions"][0]["autoRun"] is True


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
            exclusive_area_square_meters=84.0,
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
        "잠실엘스 전용 84㎡ 최근 반년 가격 추이와 거래량",
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
            ],
        }
    ]
    assert response["citations"][0]["factIds"] == ["property-complex-11471"]


def test_complex_identity_returns_grounded_ui_summary_v1() -> None:
    repository = FakeRepository()
    repository.complexes = [replace(
        complex_record(), parcel_id=101, unit_count=5_678, use_date=date(2008, 9, 30),
    )]
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

    expected_lead = "잠실동 잠실엘스는 서울 송파구 잠실동 19에 있습니다."
    assert response["answer"].startswith(expected_lead)
    assert response["uiSummary"] == {
        "version": 1,
        "scopeNotice": {
            "text": "‘잠실엘스’ 단지를 기준으로 확인했습니다.",
            "factIds": ["property-complex-11471"],
        },
        "headline": {
            "text": expected_lead,
            "factIds": ["property-complex-11471"],
        },
        "criteria": [],
        "interpretations": [],
        "followUp": (
            "잠실동 잠실엘스 최근 실거래 5건을 알려줘 · "
            "잠실동 잠실엘스 최근 1년 가격 흐름과 거래량을 보여줘 · "
            "잠실동 잠실엘스 주변 학원 위치와 가까운 역·노선을 알려줘"
        ),
        "fragmentSummaries": [],
    }
    fact_list = next(
        artifact for artifact in response["uiArtifacts"]
        if artifact["type"] == "factList"
    )
    assert [(item["label"], item["value"]) for item in fact_list["items"]] == [
        ("단지명", "잠실동 잠실엘스"),
        ("지역", "잠실동"),
        ("주소", "서울 송파구 잠실동 19"),
        ("세대수", "5,678세대"),
        ("사용승인일", "2008.09.30"),
    ]
    assert response["citations"][0]["sourceName"] == "Home Search 단지 정보"
    assert response["uiReport"]["opening"]["text"] == expected_lead


@pytest.mark.parametrize(
    "question",
    [
        "헬리오시티 어디에 있어",
        "헬리오시티 위치와 세대수·사용승인일을 알려줘",
    ],
)
def test_answer_first_helio_location_example_resolves_map_ready_identity(
    question: str,
) -> None:
    repository = FakeRepository()
    repository.complexes = [
        replace(
            complex_record(), complex_id=12417, parcel_id=9016,
            display_name="작동 헬리오시티", region_name="작동",
            address="서울 송파구 작동", unit_count=20, match_tier=3,
        ),
        replace(
            complex_record(), complex_id=12416, parcel_id=9015,
            display_name="가락동 헬리오시티", region_name="가락동",
            address="서울 송파구 가락동", unit_count=9510,
            use_date=date(2018, 12, 28), latitude=37.497, longitude=127.107,
            match_tier=2,
        ),
    ]
    model = DraftFailingLanguageModel(
        QueryPlan(capability="complex_identity", complex_name="헬리오시티"),
        DraftAnswer([]),
    )

    response = run_query(
        GroundedChatbotEngine(
            repository=repository,
            language_model=model,
            enabled_capabilities=ALL_PROPERTY_CAPABILITIES,
            answer_first_enabled=True,
        ),
        question,
        "request-helio-location",
    )

    assert response["answer"].startswith(
        "가락동 헬리오시티는 서울 송파구 가락동에 있습니다."
    )
    assert response["uiSummary"]["headline"]["text"] == response["uiReport"]["opening"]["text"]
    fact_list = next(
        artifact for artifact in response["uiArtifacts"]
        if artifact["type"] == "factList" and artifact["title"] == "확인된 단지 정보"
    )
    assert [(item["label"], item["value"]) for item in fact_list["items"]] == [
        ("단지명", "가락동 헬리오시티"),
        ("지역", "가락동"),
        ("주소", "서울 송파구 가락동"),
        ("세대수", "9,510세대"),
        ("사용승인일", "2018.12.28"),
    ]
    assert not any(item["label"] in {"위도", "경도"} for item in fact_list["items"])
    focus = next(action for action in response["uiActions"] if action["type"] == "focusComplex")
    assert focus == {
        "type": "focusComplex",
        "version": 1,
        "actionId": "action-request-helio-location-focus-complex-12416",
        "label": "가락동 헬리오시티 지도에서 보기",
        "parcelId": 9015,
        "complexId": 12416,
        "center": {"lat": 37.497, "lng": 127.107},
        "level": 4,
        "openDetail": True,
        "autoRun": True,
        "factIds": ["property-complex-12416"],
    }
    assert response["citations"][0]["sourceName"] == "Home Search 단지 정보"
    assert all(
        action.get("complexId") != 12417 for action in response["uiActions"]
    )


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


@pytest.mark.parametrize(
    ("sentence", "reason"),
    [
        (
            DraftSentence(
                text=" ", fact_ids=["fact-1"],
                claims=[DraftClaim("fact-1", "value", "TEXT")],
            ),
            "GROUNDING_SENTENCE_BLANK",
        ),
        (
            DraftSentence(
                text="중복 근거", fact_ids=["fact-1", "fact-1"],
                claims=[DraftClaim("fact-1", "value", "TEXT")],
            ),
            "GROUNDING_FACT_IDS_DUPLICATE",
        ),
        (
            DraftSentence(
                text="알 수 없는 근거", fact_ids=["fact-2"],
                claims=[DraftClaim("fact-2", "value", "TEXT")],
            ),
            "GROUNDING_FACT_UNKNOWN",
        ),
        (
            DraftSentence(
                text="연결되지 않은 주장", fact_ids=["fact-1"],
                claims=[DraftClaim("fact-2", "value", "TEXT")],
            ),
            "GROUNDING_CLAIM_NOT_ATTACHED",
        ),
    ],
)
def test_validate_draft_fails_closed_for_malformed_grounding_links(
    sentence: DraftSentence, reason: str,
) -> None:
    fact = EvidenceFact(
        fact_id="fact-1", claims=(FactClaim("value", "TEXT"),),
        data_as_of=date(2026, 7, 16), payload={},
    )

    with pytest.raises(GroundingValidationError) as raised:
        validate_draft(DraftAnswer([sentence]), [fact], "supported")

    assert raised.value.reason_code == reason


def test_fact_list_presenter_ignores_non_identity_and_invalid_identity_facts() -> None:
    presenter = FactListPresenter()
    plan = QueryPlan(capability="complex_identity", complex_name="헬리오시티")
    unrelated = EvidenceFact(
        fact_id="property-trade-1", claims=(FactClaim("1", "COUNT"),),
        data_as_of=date(2026, 7, 16), payload={"complexId": 1},
    )
    invalid_identity = EvidenceFact(
        fact_id="property-complex-invalid", claims=(FactClaim("name", "TEXT"),),
        data_as_of=date(2026, 7, 16), payload={"complexId": "1"},
    )

    assert presenter.present(
        plan=plan, used_facts=[unrelated], readiness="supported",
    ) == []
    assert presenter.present(
        plan=plan, used_facts=[invalid_identity], readiness="supported",
    ) == []


def test_fact_list_presenter_omits_invalid_optional_identity_values() -> None:
    presenter = FactListPresenter()
    fact = EvidenceFact(
        fact_id="property-complex-1", claims=(FactClaim("1", "COMPLEX_ID"),),
        data_as_of=date(2026, 7, 16),
        payload={
            "complexId": 1,
            "displayName": "헬리오시티",
            "regionName": " ",
            "address": None,
            "unitCount": True,
            "useDate": "not-a-date",
        },
    )

    artifacts = presenter.present(
        plan=QueryPlan(capability="complex_identity", complex_name="헬리오시티"),
        used_facts=[fact], readiness="supported",
    )

    assert artifacts[0]["items"] == [{
        "label": "단지명", "value": "헬리오시티", "factIds": ["property-complex-1"],
    }]


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
