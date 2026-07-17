from __future__ import annotations

import asyncio
import json
from datetime import date

import pytest

from ai_service.property_chat.golden import (
    GoldenCase,
    GoldenSuiteRunner,
    GoldenValidationError,
    ReplayGoldenLanguageModel,
    format_report,
    load_catalog,
    validate_execution_policy,
)
from ai_service.property_chat.models import (
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    PropertyQueryPlan,
)
from ai_service.property_chat.postgres import PostgresPropertyFactRepository


def cases() -> tuple[GoldenCase, ...]:
    return (
        GoldenCase(
            case_id="complex-identity",
            question="잠실동 잠실엘스 위치를 알려줘",
            plan=PropertyQueryPlan(
                capability="complex_identity",
                complex_name="잠실엘스",
                region_name="잠실동",
            ),
            expected_readiness="supported",
        ),
        GoldenCase(
            case_id="recent-trades",
            question="잠실엘스 전용 84㎡의 2026년 1월부터 2월까지 최근 거래 3건을 알려줘",
            plan=PropertyQueryPlan(
                capability="recent_trade_lookup",
                complex_name="잠실엘스",
                region_name="잠실동",
                start_date=date(2026, 1, 1),
                end_date=date(2026, 2, 28),
                exclusive_area_square_meters=84.0,
                limit=3,
            ),
            expected_readiness="supported",
        ),
        GoldenCase(
            case_id="price-trend",
            question="잠실엘스 전용 84㎡의 2026년 1월부터 2월까지 월별 가격과 거래량을 알려줘",
            plan=PropertyQueryPlan(
                capability="price_trend",
                complex_name="잠실엘스",
                region_name="잠실동",
                start_date=date(2026, 1, 1),
                end_date=date(2026, 2, 28),
                exclusive_area_square_meters=84.0,
            ),
            expected_readiness="supported",
        ),
        GoldenCase(
            case_id="complex-not-found",
            question="홈서치골든질문존재하지않는단지를 찾아줘",
            plan=PropertyQueryPlan(
                capability="complex_identity",
                complex_name="홈서치골든질문존재하지않는단지",
            ),
            expected_readiness="unavailable",
        ),
    )


def test_offline_suite_matches_read_only_repository_observations(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    runner = GoldenSuiteRunner(repository)
    try:
        results = [
            asyncio.run(
                runner.run_case(
                    case,
                    ReplayGoldenLanguageModel(case.plan),
                )
            )
            for case in cases()
        ]
    finally:
        repository.close()

    assert [result.readiness for result in results] == [
        "supported",
        "supported",
        "supported",
        "unavailable",
    ]
    assert results[0].fact_ids == ("property-complex-1",)
    assert results[1].fact_ids == (
        "property-trade-14",
        "property-trade-12",
        "property-trade-11",
    )
    assert results[2].fact_ids == (
        "property-trend-1-2026-01",
        "property-trend-1-2026-02",
    )
    assert results[3].fact_ids == ()
    assert results[1].data_as_of == "2026-02-15"


class PartialFactLanguageModel(ReplayGoldenLanguageModel):
    async def draft_answer(self, *, facts, limitations, question) -> DraftAnswer:
        del limitations, question
        fact = facts[0]
        claim = fact.claims[0]
        return DraftAnswer(
            sentences=[
                DraftSentence(
                    text=f"{claim.value} 근거입니다.",
                    fact_ids=[fact.fact_id],
                    claims=[DraftClaim(fact.fact_id, claim.value, claim.unit)],
                )
            ]
        )


def test_suite_rejects_an_answer_that_omits_observed_facts(
    property_postgres_dsn: str,
) -> None:
    case = cases()[1]
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        with pytest.raises(GoldenValidationError, match="FACT_SET_MISMATCH"):
            asyncio.run(
                GoldenSuiteRunner(repository).run_case(
                    case,
                    PartialFactLanguageModel(case.plan),
                )
            )
    finally:
        repository.close()


def test_live_execution_requires_one_case_and_explicit_cost_confirmation() -> None:
    with pytest.raises(GoldenValidationError, match="LIVE_CASE_REQUIRED"):
        validate_execution_policy("live", (), "RUN_ONE_LIVE_GOLDEN_CASE")
    with pytest.raises(GoldenValidationError, match="LIVE_CASE_LIMIT"):
        validate_execution_policy(
            "live",
            ("complex-identity", "recent-trades"),
            "RUN_ONE_LIVE_GOLDEN_CASE",
        )
    with pytest.raises(GoldenValidationError, match="LIVE_CONFIRMATION_REQUIRED"):
        validate_execution_policy("live", ("complex-identity",), "")

    validate_execution_policy(
        "live",
        ("complex-identity",),
        "RUN_ONE_LIVE_GOLDEN_CASE",
    )


def test_catalog_rejects_unknown_fields(tmp_path) -> None:
    path = tmp_path / "catalog.json"
    path.write_text(
        json.dumps(
            {
                "version": 1,
                "cases": [
                    {
                        "caseId": "invalid-case",
                        "question": "질문",
                        "expectedReadiness": "supported",
                        "plan": {
                            "capability": "complex_identity",
                            "complexName": "단지",
                            "regionName": None,
                            "startDate": None,
                            "endDate": None,
                            "exclusiveAreaSquareMeters": None,
                            "limit": 5,
                            "unexpected": True,
                        },
                    }
                ],
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(GoldenValidationError, match="CATALOG_INVALID"):
        load_catalog(path)


def test_report_excludes_questions_answers_and_secrets(
    property_postgres_dsn: str,
) -> None:
    case = cases()[0]
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        result = asyncio.run(
            GoldenSuiteRunner(repository).run_case(
                case,
                ReplayGoldenLanguageModel(case.plan),
            )
        )
    finally:
        repository.close()

    report = format_report("offline", (result,))

    assert "상태: Pass" in report
    assert case.case_id in report
    assert case.question not in report
    assert "answer" not in report.lower()
    assert "secret" not in report.lower()
