from __future__ import annotations

import asyncio
from copy import deepcopy
from datetime import date, datetime, timezone
from pathlib import Path

import pytest

from ai_service.chat import ChatbotProviderUnavailable
from ai_service.property_chat import golden
from ai_service.property_chat.golden import (
    GoldenCase,
    GoldenCaseResult,
    GoldenSuiteRunner,
    GoldenValidationError,
    ReplayGoldenLanguageModel,
)
from ai_service.property_chat.models import (
    ComplexRecord,
    EvidenceFact,
    FactClaim,
    MonthlyTrendRecord,
    PropertyQueryPlan,
    TradeRecord,
)


class MemoryPropertyRepository:
    def __init__(
        self,
        *,
        complexes: list[ComplexRecord] | None = None,
        trades: list[TradeRecord] | None = None,
        trends: list[MonthlyTrendRecord] | None = None,
        latest_trade_date: date | None = None,
    ) -> None:
        self.complexes = complexes or []
        self.trades = trades or []
        self.trends = trends or []
        self.latest = latest_trade_date

    def find_complexes(self, name, region_name, limit):
        del name, region_name, limit
        return self.complexes

    def recent_trades(self, complex_id, start_date, end_date, area, limit):
        del complex_id, start_date, end_date, area
        return self.trades[:limit]

    def monthly_trends(self, complex_id, start_date, end_date, area):
        del complex_id, start_date, end_date, area
        return self.trends

    def latest_trade_date(self):
        return self.latest


def complex_record(
    complex_id: int = 1,
    *,
    marker_safe: bool = True,
    updated_at: datetime | None = None,
) -> ComplexRecord:
    return ComplexRecord(
        complex_id=complex_id,
        display_name=f"단지 {complex_id}",
        region_code="11710101",
        region_name="잠실동",
        address=f"서울 송파구 잠실동 {complex_id}",
        latitude=37.5 if marker_safe else None,
        longitude=127.0 if marker_safe else None,
        marker_safe=marker_safe,
        data_updated_at=updated_at or datetime(2026, 7, 16, tzinfo=timezone.utc),
    )


def golden_case(
    capability: str = "complex_identity",
    *,
    readiness: str = "supported",
) -> GoldenCase:
    return GoldenCase(
        case_id=f"case-{capability.replace('_', '-')}",
        question="검증 질문",
        plan=PropertyQueryPlan(
            capability=capability,  # type: ignore[arg-type]
            complex_name="단지",
            start_date=date(2026, 1, 1) if capability == "price_trend" else None,
            end_date=date(2026, 12, 31) if capability == "price_trend" else None,
            exclusive_area_square_meters=(
                84.0 if capability in {"recent_trade_lookup", "price_trend"} else None
            ),
            limit=3,
        ),
        expected_readiness=readiness,  # type: ignore[arg-type]
    )


def run_offline(case: GoldenCase, repository: MemoryPropertyRepository) -> GoldenCaseResult:
    return asyncio.run(
        GoldenSuiteRunner(repository).run_case(
            case,
            ReplayGoldenLanguageModel(case.plan),
        )
    )


def test_packaged_catalog_is_valid_and_selectable() -> None:
    catalog_path = Path(golden.__file__).with_name("golden_catalog.json")

    catalog = golden.load_catalog(catalog_path)

    assert len(catalog) == 4
    assert golden._select_cases(catalog, ("complex-not-found",)) == (catalog[-1],)
    assert golden._select_cases(catalog, ()) == catalog


def test_case_and_catalog_boundaries_are_rejected(tmp_path: Path) -> None:
    with pytest.raises(GoldenValidationError, match="CASE_ID_INVALID"):
        GoldenCase("INVALID", "질문", golden_case().plan, "supported")
    with pytest.raises(GoldenValidationError, match="QUESTION_INVALID"):
        GoldenCase("valid", " ", golden_case().plan, "supported")
    with pytest.raises(GoldenValidationError, match="READINESS_INVALID"):
        GoldenCase("valid", "질문", golden_case().plan, "unknown")  # type: ignore[arg-type]

    missing = tmp_path / "missing.json"
    with pytest.raises(GoldenValidationError, match="CATALOG_INVALID"):
        golden.load_catalog(missing)

    invalid = tmp_path / "invalid.json"
    invalid.write_text("[]", encoding="utf-8")
    with pytest.raises(GoldenValidationError, match="CATALOG_INVALID"):
        golden.load_catalog(invalid)


@pytest.mark.parametrize(
    ("case", "repository", "expected_readiness", "expected_facts"),
    [
        (
            golden_case(readiness="partial"),
            MemoryPropertyRepository(complexes=[complex_record(1), complex_record(2)]),
            "partial",
            ("property-complex-1", "property-complex-2"),
        ),
        (
            golden_case(),
            MemoryPropertyRepository(complexes=[complex_record(marker_safe=False)]),
            "supported",
            ("property-complex-1",),
        ),
        (
            golden_case("recent_trade_lookup", readiness="unavailable"),
            MemoryPropertyRepository(complexes=[complex_record()]),
            "unavailable",
            (),
        ),
        (
            golden_case("price_trend", readiness="unavailable"),
            MemoryPropertyRepository(complexes=[complex_record()]),
            "unavailable",
            (),
        ),
    ],
)
def test_offline_runner_handles_readiness_boundaries(
    case: GoldenCase,
    repository: MemoryPropertyRepository,
    expected_readiness: str,
    expected_facts: tuple[str, ...],
) -> None:
    result = run_offline(case, repository)

    assert result.readiness == expected_readiness
    assert result.fact_ids == expected_facts


def test_ambiguous_complexes_allow_independently_versioned_citations() -> None:
    case = golden_case(readiness="partial")
    repository = MemoryPropertyRepository(
        complexes=[
            complex_record(1),
            complex_record(
                2,
                updated_at=datetime(2026, 7, 15, tzinfo=timezone.utc),
            ),
            complex_record(3),
        ]
    )

    result = run_offline(case, repository)

    assert set(result.fact_ids) == {
        "property-complex-1",
        "property-complex-2",
        "property-complex-3",
    }
    assert result.data_as_of == "2026-07-15"


def test_offline_runner_uses_observed_dates_when_global_freshness_is_empty() -> None:
    trade_case = golden_case("recent_trade_lookup")
    trade_result = run_offline(
        trade_case,
        MemoryPropertyRepository(
            complexes=[complex_record()],
            trades=[TradeRecord(8, 1, date(2026, 2, 2), 250_000, 84.0, 9)],
        ),
    )
    trend_case = golden_case("price_trend")
    trend_result = run_offline(
        trend_case,
        MemoryPropertyRepository(
            complexes=[complex_record()],
            trends=[MonthlyTrendRecord(1, date(2026, 12, 1), 250_000, 1, 250_000, 250_000)],
        ),
    )

    assert trade_result.data_as_of == "2026-02-02"
    assert trend_result.data_as_of == "2026-12-31"


def test_runner_rejects_catalog_readiness_drift() -> None:
    case = golden_case(readiness="unavailable")

    with pytest.raises(GoldenValidationError, match="DATA_READINESS_MISMATCH"):
        run_offline(case, MemoryPropertyRepository(complexes=[complex_record()]))


def test_replay_model_enforces_the_fact_limit() -> None:
    model = ReplayGoldenLanguageModel(golden_case().plan)
    facts = [
        EvidenceFact(
            fact_id=f"fact-{index}",
            claims=(FactClaim(str(index), "COUNT"),),
            data_as_of=date(2026, 1, 1),
            payload={},
        )
        for index in range(13)
    ]

    with pytest.raises(GoldenValidationError, match="REPLAY_FACT_LIMIT"):
        asyncio.run(
            model.draft_answer(facts=facts, limitations=[], question="검증 질문")
        )


def valid_response() -> dict[str, object]:
    return {
        "success": True,
        "status": "success",
        "answer": "근거가 확인되었습니다.",
        "dataAsOf": "2026-07-16",
        "limitations": ["검증된 표시 좌표가 없어 위치 좌표는 제공하지 않습니다."],
        "evidenceSummary": {
            "status": "supported",
            "capabilities": ["complex_identity"],
            "factCount": 1,
            "citationCount": 1,
        },
        "citations": [
            {
                "sourceId": "property.ai_read",
                "evidenceGrade": "A",
                "datasetVersion": "property-2026-07-16",
                "dataAsOf": "2026-07-16",
                "factIds": ["property-complex-1"],
            }
        ],
    }


def validate_response(response: dict[str, object]) -> GoldenCaseResult:
    case = golden_case()
    expected = golden._ExpectedObservation(
        readiness="supported",
        fact_ids=("property-complex-1",),
        fact_data_as_of=(("property-complex-1", date(2026, 7, 16)),),
        data_as_of=date(2026, 7, 16),
        limitation_fragments=("표시 좌표",),
    )
    return golden._validate_response(case, expected, response)


@pytest.mark.parametrize(
    ("mutation", "reason"),
    [
        (lambda response: response.update(evidenceSummary=None), "RESPONSE_SHAPE_INVALID"),
        (lambda response: response.update(citations=None), "RESPONSE_SHAPE_INVALID"),
        (lambda response: response.update(limitations="invalid"), "RESPONSE_SHAPE_INVALID"),
        (
            lambda response: response["evidenceSummary"].update(status="partial"),
            "READINESS_MISMATCH",
        ),
        (
            lambda response: response["evidenceSummary"].update(capabilities=[]),
            "CAPABILITY_MISMATCH",
        ),
        (
            lambda response: response["citations"][0].update(factIds=["other-fact"]),
            "CITATION_INVALID",
        ),
        (
            lambda response: response["evidenceSummary"].update(factCount=2),
            "FACT_COUNT_MISMATCH",
        ),
        (
            lambda response: response["evidenceSummary"].update(citationCount=2),
            "CITATION_COUNT_MISMATCH",
        ),
        (lambda response: response.update(dataAsOf="2026-07-15"), "DATA_AS_OF_MISMATCH"),
        (lambda response: response.update(success=False), "SUCCESS_MISMATCH"),
        (lambda response: response.update(status="failed"), "STATUS_MISMATCH"),
        (lambda response: response.update(answer=" "), "ANSWER_MISSING"),
        (lambda response: response.update(limitations=[]), "LIMITATION_MISMATCH"),
    ],
)
def test_response_validator_rejects_contract_drift(mutation, reason: str) -> None:
    response = deepcopy(valid_response())
    mutation(response)

    with pytest.raises(GoldenValidationError, match=reason):
        validate_response(response)


@pytest.mark.parametrize(
    ("citations", "reason"),
    [
        (["invalid"], "CITATION_INVALID"),
        ([{"factIds": []}], "CITATION_INVALID"),
        ([{"factIds": [1]}], "CITATION_INVALID"),
        (
            [
                {
                    "sourceId": "other",
                    "evidenceGrade": "A",
                    "datasetVersion": "property-2026-07-16",
                    "dataAsOf": "2026-07-16",
                    "factIds": ["fact-1"],
                }
            ],
            "CITATION_INVALID",
        ),
        (
            [
                {
                    "sourceId": "property.ai_read",
                    "evidenceGrade": "A",
                    "datasetVersion": "property-2026-07-16",
                    "dataAsOf": "2026-07-16",
                    "factIds": ["fact-1", "fact-1"],
                }
            ],
            "CITATION_FACT_DUPLICATE",
        ),
    ],
)
def test_citation_validator_rejects_untrusted_metadata(citations, reason: str) -> None:
    with pytest.raises(GoldenValidationError, match=reason):
        golden._citation_fact_ids(citations, (("fact-1", date(2026, 7, 16)),))


def test_case_selection_rejects_duplicates_and_unknown_ids() -> None:
    catalog = (golden_case(),)

    with pytest.raises(GoldenValidationError, match="CASE_SELECTION_DUPLICATE"):
        golden._select_cases(catalog, (catalog[0].case_id, catalog[0].case_id))
    with pytest.raises(GoldenValidationError, match="CASE_NOT_FOUND"):
        golden._select_cases(catalog, ("missing",))


def test_execution_policy_rejects_invalid_modes_and_offline_overflow() -> None:
    with pytest.raises(GoldenValidationError, match="OFFLINE_CASE_LIMIT"):
        golden.validate_execution_policy("offline", tuple(str(i) for i in range(13)), "")
    with pytest.raises(GoldenValidationError, match="MODE_INVALID"):
        golden.validate_execution_policy("unknown", (), "")  # type: ignore[arg-type]


def test_live_runner_reuses_one_provider_model(monkeypatch) -> None:
    case = golden_case()
    repository = MemoryPropertyRepository(complexes=[complex_record()])
    model = ReplayGoldenLanguageModel(case.plan)
    monkeypatch.setattr(golden, "get_grounded_language_model", lambda: model)

    results = asyncio.run(golden._run_cases(repository, (case,), "live"))

    assert results[0].case_id == case.case_id
    assert "providerRequestUpperBound: 6" in golden.format_report("live", results)


class CloseTrackingRepository(MemoryPropertyRepository):
    closed = False

    def __init__(self, dsn: str) -> None:
        assert dsn == "test-dsn"
        super().__init__(complexes=[complex_record()])

    def close(self) -> None:
        self.closed = True


def test_cli_requires_dsn_without_disclosing_configuration(monkeypatch, capsys) -> None:
    monkeypatch.delenv("HOME_AI_PROPERTY_DSN", raising=False)

    exit_code = golden.main([])

    output = capsys.readouterr().out
    assert exit_code == 1
    assert "reasonCode: PROPERTY_DSN_REQUIRED" in output
    assert "question" not in output.lower()


def test_cli_prints_only_sanitized_success_report(monkeypatch, capsys) -> None:
    monkeypatch.setenv("HOME_AI_PROPERTY_DSN", "test-dsn")
    monkeypatch.setattr(golden, "PostgresPropertyFactRepository", CloseTrackingRepository)
    monkeypatch.setattr(
        golden,
        "load_catalog",
        lambda path: (golden_case(),),
    )

    exit_code = golden.main([])

    output = capsys.readouterr().out
    assert exit_code == 0
    assert "상태: Pass" in output
    assert "검증 질문" not in output


@pytest.mark.parametrize(
    ("exception", "reason"),
    [
        (ChatbotProviderUnavailable(), "PROVIDER_UNAVAILABLE"),
        (RuntimeError("secret detail"), "GOLDEN_EXECUTION_FAILED"),
    ],
)
def test_cli_sanitizes_execution_failures(monkeypatch, capsys, exception, reason: str) -> None:
    async def fail(*args, **kwargs):
        del args, kwargs
        raise exception

    monkeypatch.setenv("HOME_AI_PROPERTY_DSN", "test-dsn")
    monkeypatch.setattr(golden, "PostgresPropertyFactRepository", CloseTrackingRepository)
    monkeypatch.setattr(golden, "load_catalog", lambda path: (golden_case(),))
    monkeypatch.setattr(golden, "_run_cases", fail)

    exit_code = golden.main([])

    output = capsys.readouterr().out
    assert exit_code == 1
    assert f"reasonCode: {reason}" in output
    assert "secret detail" not in output
