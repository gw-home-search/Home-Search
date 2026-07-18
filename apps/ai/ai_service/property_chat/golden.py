from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Literal

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable, get_grounded_language_model
from ai_service.models import ChatbotQueryRequest

from .engine import (
    GroundedChatbotEngine,
    GroundedLanguageModel,
    GroundingValidationError,
    PropertyFactRepository,
)
from .language import LanguageModelStageError
from .models import (
    DraftAnswer,
    DraftClaim,
    DraftSentence,
    EvidenceFact,
    PropertyQueryPlan,
)
from .openai_responses import OpenAIResponsesError
from .postgres import PostgresPropertyFactRepository

GoldenMode = Literal["offline", "live"]
Readiness = Literal["supported", "partial", "unavailable"]

_LIVE_CONFIRMATION = "RUN_ONE_LIVE_GOLDEN_CASE"
_MAX_CATALOG_CASES = 12
_MAX_CATALOG_BYTES = 64 * 1024
_MAX_REPLAY_FACTS = 12


class GoldenValidationError(ValueError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class GoldenCase:
    case_id: str
    question: str
    plan: PropertyQueryPlan
    expected_readiness: Readiness

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", self.case_id):
            raise GoldenValidationError("CASE_ID_INVALID")
        normalized_question = self.question.strip()
        if not 1 <= len(normalized_question) <= 2000:
            raise GoldenValidationError("QUESTION_INVALID")
        if self.expected_readiness not in {"supported", "partial", "unavailable"}:
            raise GoldenValidationError("READINESS_INVALID")
        object.__setattr__(self, "question", normalized_question)


@dataclass(frozen=True)
class GoldenCaseResult:
    case_id: str
    readiness: Readiness
    fact_ids: tuple[str, ...]
    citation_count: int
    data_as_of: str | None


@dataclass(frozen=True)
class _ExpectedObservation:
    readiness: Readiness
    fact_ids: tuple[str, ...]
    fact_data_as_of: tuple[tuple[str, date], ...]
    data_as_of: date | None
    limitation_fragments: tuple[str, ...]


class ReplayGoldenLanguageModel:
    def __init__(self, plan: PropertyQueryPlan) -> None:
        self._plan = plan

    async def plan_query(self, _request: ChatbotQueryRequest) -> PropertyQueryPlan:
        return self._plan

    async def draft_answer(
        self,
        *,
        facts: list[EvidenceFact],
        limitations: list[str],
        question: str,
    ) -> DraftAnswer:
        del limitations, question
        if not facts:
            return DraftAnswer(
                sentences=[
                    DraftSentence(
                        text="조건에 맞는 검증된 근거 데이터가 없습니다.",
                        fact_ids=[],
                    )
                ]
            )
        if len(facts) > _MAX_REPLAY_FACTS:
            raise GoldenValidationError("REPLAY_FACT_LIMIT")
        return DraftAnswer(
            sentences=[
                DraftSentence(
                    text=" / ".join(claim.value for claim in fact.claims) + " 근거입니다.",
                    fact_ids=[fact.fact_id],
                    claims=[
                        DraftClaim(
                            fact_id=fact.fact_id,
                            value=claim.value,
                            unit=claim.unit,
                        )
                        for claim in fact.claims
                    ],
                )
                for fact in facts
            ]
        )


class GoldenSuiteRunner:
    def __init__(self, repository: PropertyFactRepository) -> None:
        self._repository = repository

    async def run_case(
        self,
        case: GoldenCase,
        language_model: GroundedLanguageModel,
    ) -> GoldenCaseResult:
        expected = await asyncio.to_thread(
            _expected_observation,
            self._repository,
            case.plan,
        )
        if expected.readiness != case.expected_readiness:
            raise GoldenValidationError("DATA_READINESS_MISMATCH")
        response = await GroundedChatbotEngine(
            repository=self._repository,
            language_model=language_model,
            enabled_capabilities=frozenset(
                {"complex_identity", "recent_trade_lookup", "price_trend"}
            ),
        ).query(
            request=ChatbotQueryRequest(question=case.question),
            user=AuthenticatedUser(user_id=1),
            request_id=f"golden-{case.case_id}",
        )
        return _validate_response(case, expected, response)


def load_catalog(path: Path) -> tuple[GoldenCase, ...]:
    try:
        if not path.is_file() or path.stat().st_size > _MAX_CATALOG_BYTES:
            raise ValueError
        root = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(root, dict) or set(root) != {"version", "cases"}:
            raise ValueError
        raw_cases = root["cases"]
        if root["version"] != 1 or not isinstance(raw_cases, list):
            raise ValueError
        if not 1 <= len(raw_cases) <= _MAX_CATALOG_CASES:
            raise ValueError
        cases = tuple(_parse_case(raw_case) for raw_case in raw_cases)
        if len({case.case_id for case in cases}) != len(cases):
            raise ValueError
        return cases
    except (OSError, TypeError, ValueError, json.JSONDecodeError, GoldenValidationError) as exception:
        raise GoldenValidationError("CATALOG_INVALID") from exception


def validate_execution_policy(
    mode: GoldenMode,
    selected_case_ids: Sequence[str],
    live_confirmation: str,
) -> None:
    if mode == "offline":
        if len(selected_case_ids) > _MAX_CATALOG_CASES:
            raise GoldenValidationError("OFFLINE_CASE_LIMIT")
        return
    if mode != "live":
        raise GoldenValidationError("MODE_INVALID")
    if not selected_case_ids:
        raise GoldenValidationError("LIVE_CASE_REQUIRED")
    if len(selected_case_ids) != 1:
        raise GoldenValidationError("LIVE_CASE_LIMIT")
    if live_confirmation != _LIVE_CONFIRMATION:
        raise GoldenValidationError("LIVE_CONFIRMATION_REQUIRED")


def format_report(mode: GoldenMode, results: Sequence[GoldenCaseResult]) -> str:
    lines = [
        "상태: Pass",
        f"mode: {mode}",
        f"caseCount: {len(results)}",
    ]
    if mode == "live":
        lines.append("providerRequestUpperBound: 6")
    lines.extend(
        "- "
        + f"{result.case_id}: Pass, readiness={result.readiness}, "
        + f"facts={len(result.fact_ids)}, citations={result.citation_count}, "
        + f"dataAsOf={result.data_as_of or 'none'}"
        for result in results
    )
    return "\n".join(lines)


def _parse_case(value: object) -> GoldenCase:
    if not isinstance(value, dict) or set(value) != {
        "caseId",
        "question",
        "expectedReadiness",
        "plan",
    }:
        raise ValueError
    plan = value["plan"]
    if not isinstance(plan, dict) or set(plan) != {
        "capability",
        "complexName",
        "regionName",
        "startDate",
        "endDate",
        "exclusiveAreaSquareMeters",
        "limit",
    }:
        raise ValueError
    capability = plan["capability"]
    complex_name = plan["complexName"]
    region_name = plan["regionName"]
    area = plan["exclusiveAreaSquareMeters"]
    limit = plan["limit"]
    if not isinstance(capability, str) or not isinstance(complex_name, str):
        raise ValueError
    if region_name is not None and not isinstance(region_name, str):
        raise ValueError
    if isinstance(area, bool) or (area is not None and not isinstance(area, (int, float))):
        raise ValueError
    if isinstance(limit, bool) or not isinstance(limit, int):
        raise ValueError
    expected_readiness = value["expectedReadiness"]
    if not isinstance(value["caseId"], str) or not isinstance(value["question"], str):
        raise ValueError
    if not isinstance(expected_readiness, str):
        raise ValueError
    return GoldenCase(
        case_id=value["caseId"],
        question=value["question"],
        plan=PropertyQueryPlan(
            capability=capability,  # type: ignore[arg-type]
            complex_name=complex_name,
            region_name=region_name,
            start_date=_optional_date(plan["startDate"]),
            end_date=_optional_date(plan["endDate"]),
            exclusive_area_square_meters=None if area is None else float(area),
            limit=limit,
        ),
        expected_readiness=expected_readiness,  # type: ignore[arg-type]
    )


def _optional_date(value: object) -> date | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError
    return date.fromisoformat(value)


def _expected_observation(
    repository: PropertyFactRepository,
    plan: PropertyQueryPlan,
) -> _ExpectedObservation:
    complexes = repository.find_complexes(plan.complex_name, plan.region_name, 6)
    if not complexes:
        return _ExpectedObservation(
            readiness="unavailable",
            fact_ids=(),
            fact_data_as_of=(),
            data_as_of=None,
            limitation_fragments=("단지를 식별",),
        )
    if len(complexes) > 1:
        return _ExpectedObservation(
            readiness="partial",
            fact_ids=tuple(f"property-complex-{record.complex_id}" for record in complexes),
            fact_data_as_of=tuple(
                (f"property-complex-{record.complex_id}", record.data_updated_at.date())
                for record in complexes
            ),
            data_as_of=min(record.data_updated_at.date() for record in complexes),
            limitation_fragments=("동명 단지",),
        )
    complex_record = complexes[0]
    if plan.capability == "complex_identity":
        limitations = () if complex_record.marker_safe else ("표시 좌표",)
        return _ExpectedObservation(
            readiness="supported",
            fact_ids=(f"property-complex-{complex_record.complex_id}",),
            fact_data_as_of=(
                (
                    f"property-complex-{complex_record.complex_id}",
                    complex_record.data_updated_at.date(),
                ),
            ),
            data_as_of=complex_record.data_updated_at.date(),
            limitation_fragments=limitations,
        )
    if plan.capability == "recent_trade_lookup":
        trades = repository.recent_trades(
            complex_record.complex_id,
            plan.start_date,
            plan.end_date,
            plan.exclusive_area_square_meters,
            plan.limit,
        )
        if not trades:
            return _ExpectedObservation("unavailable", (), (), None, ("실거래",))
        data_as_of = repository.latest_trade_date() or max(record.deal_date for record in trades)
        limitations = ("신고 취소",) + (
            ("±1.0㎡",) if plan.exclusive_area_square_meters is not None else ()
        )
        return _ExpectedObservation(
            readiness="supported",
            fact_ids=tuple(f"property-trade-{record.trade_id}" for record in trades),
            fact_data_as_of=tuple(
                (f"property-trade-{record.trade_id}", data_as_of) for record in trades
            ),
            data_as_of=data_as_of,
            limitation_fragments=limitations,
        )
    if plan.capability == "price_trend":
        assert plan.start_date is not None and plan.end_date is not None
        trends = repository.monthly_trends(
            complex_record.complex_id,
            plan.start_date,
            plan.end_date,
            plan.exclusive_area_square_meters,
        )
        if not trends:
            return _ExpectedObservation("unavailable", (), (), None, ("월별 추이",))
        data_as_of = repository.latest_trade_date() or min(
            plan.end_date,
            max(_month_end(record.month) for record in trends),
        )
        limitations = ("미래 가격",) + (
            ("±1.0㎡",) if plan.exclusive_area_square_meters is not None else ()
        )
        return _ExpectedObservation(
            readiness="supported",
            fact_ids=tuple(
                f"property-trend-{record.complex_id}-{record.month:%Y-%m}"
                for record in trends
            ),
            fact_data_as_of=tuple(
                (
                    f"property-trend-{record.complex_id}-{record.month:%Y-%m}",
                    data_as_of,
                )
                for record in trends
            ),
            data_as_of=data_as_of,
            limitation_fragments=limitations,
        )
    raise GoldenValidationError("CAPABILITY_UNSUPPORTED")


def _validate_response(
    case: GoldenCase,
    expected: _ExpectedObservation,
    response: dict[str, object],
) -> GoldenCaseResult:
    evidence = response.get("evidenceSummary")
    citations = response.get("citations")
    limitations = response.get("limitations")
    if not isinstance(evidence, dict) or not isinstance(citations, list):
        raise GoldenValidationError("RESPONSE_SHAPE_INVALID")
    if not isinstance(limitations, list) or not all(isinstance(item, str) for item in limitations):
        raise GoldenValidationError("RESPONSE_SHAPE_INVALID")
    readiness = evidence.get("status")
    if readiness != expected.readiness or readiness != case.expected_readiness:
        raise GoldenValidationError("READINESS_MISMATCH")
    if evidence.get("capabilities") != [case.plan.capability]:
        raise GoldenValidationError("CAPABILITY_MISMATCH")
    fact_ids = _citation_fact_ids(citations, expected.fact_data_as_of)
    if set(fact_ids) != set(expected.fact_ids):
        raise GoldenValidationError("FACT_SET_MISMATCH")
    if evidence.get("factCount") != len(expected.fact_ids):
        raise GoldenValidationError("FACT_COUNT_MISMATCH")
    if evidence.get("citationCount") != len(citations):
        raise GoldenValidationError("CITATION_COUNT_MISMATCH")
    expected_data_as_of = expected.data_as_of.isoformat() if expected.data_as_of else None
    if response.get("dataAsOf") != expected_data_as_of:
        raise GoldenValidationError("DATA_AS_OF_MISMATCH")
    expected_success = expected.readiness != "unavailable"
    if response.get("success") is not expected_success:
        raise GoldenValidationError("SUCCESS_MISMATCH")
    if response.get("status") != ("success" if expected_success else "failed"):
        raise GoldenValidationError("STATUS_MISMATCH")
    if not isinstance(response.get("answer"), str) or not str(response["answer"]).strip():
        raise GoldenValidationError("ANSWER_MISSING")
    joined_limitations = " ".join(limitations)
    if any(fragment not in joined_limitations for fragment in expected.limitation_fragments):
        raise GoldenValidationError("LIMITATION_MISMATCH")
    return GoldenCaseResult(
        case_id=case.case_id,
        readiness=expected.readiness,
        fact_ids=fact_ids,
        citation_count=len(citations),
        data_as_of=expected_data_as_of,
    )


def _citation_fact_ids(
    citations: list[object],
    fact_data_as_of: Sequence[tuple[str, date]],
) -> tuple[str, ...]:
    expected_dates = dict(fact_data_as_of)
    fact_ids: list[str] = []
    for citation in citations:
        if not isinstance(citation, dict):
            raise GoldenValidationError("CITATION_INVALID")
        raw_fact_ids = citation.get("factIds")
        if not isinstance(raw_fact_ids, list) or not raw_fact_ids:
            raise GoldenValidationError("CITATION_INVALID")
        if not all(isinstance(fact_id, str) for fact_id in raw_fact_ids):
            raise GoldenValidationError("CITATION_INVALID")
        citation_date = citation.get("dataAsOf")
        if (
            citation.get("sourceId") != "property.ai_read"
            or citation.get("evidenceGrade") != "A"
            or not isinstance(citation_date, str)
            or citation.get("datasetVersion") != f"property-{citation_date}"
            or any(
                fact_id not in expected_dates
                or expected_dates[fact_id].isoformat() != citation_date
                for fact_id in raw_fact_ids
            )
        ):
            raise GoldenValidationError("CITATION_INVALID")
        fact_ids.extend(raw_fact_ids)
    if len(fact_ids) != len(set(fact_ids)):
        raise GoldenValidationError("CITATION_FACT_DUPLICATE")
    return tuple(fact_ids)


def _month_end(month: date) -> date:
    if month.month == 12:
        return date(month.year, 12, 31)
    return date.fromordinal(date(month.year, month.month + 1, 1).toordinal() - 1)


def _select_cases(
    catalog: Sequence[GoldenCase],
    selected_case_ids: Sequence[str],
) -> tuple[GoldenCase, ...]:
    if not selected_case_ids:
        return tuple(catalog)
    by_id = {case.case_id: case for case in catalog}
    if len(set(selected_case_ids)) != len(selected_case_ids):
        raise GoldenValidationError("CASE_SELECTION_DUPLICATE")
    try:
        return tuple(by_id[case_id] for case_id in selected_case_ids)
    except KeyError as exception:
        raise GoldenValidationError("CASE_NOT_FOUND") from exception


async def _run_cases(
    repository: PropertyFactRepository,
    cases: Iterable[GoldenCase],
    mode: GoldenMode,
) -> tuple[GoldenCaseResult, ...]:
    runner = GoldenSuiteRunner(repository)
    results: list[GoldenCaseResult] = []
    live_model = get_grounded_language_model() if mode == "live" else None
    for case in cases:
        model = live_model if live_model is not None else ReplayGoldenLanguageModel(case.plan)
        results.append(await runner.run_case(case, model))  # type: ignore[arg-type]
    return tuple(results)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify grounded property chatbot golden cases.")
    parser.add_argument(
        "--catalog",
        type=Path,
        default=Path(__file__).with_name("golden_catalog.json"),
    )
    parser.add_argument("--mode", choices=("offline", "live"), default="offline")
    parser.add_argument("--case-id", action="append", default=[])
    args = parser.parse_args(argv)
    pool_logger = logging.getLogger("psycopg.pool")
    pool_logger_was_disabled = pool_logger.disabled
    pool_logger.disabled = True
    repository: PostgresPropertyFactRepository | None = None
    current_case = "none"
    try:
        mode: GoldenMode = args.mode
        selected_case_ids = tuple(args.case_id)
        validate_execution_policy(
            mode,
            selected_case_ids,
            os.getenv("HOME_AI_GOLDEN_LIVE_CONFIRM", ""),
        )
        catalog = load_catalog(args.catalog)
        selected = _select_cases(catalog, selected_case_ids)
        dsn = os.getenv("HOME_AI_PROPERTY_DSN", "").strip()
        if not dsn:
            raise GoldenValidationError("PROPERTY_DSN_REQUIRED")
        repository = PostgresPropertyFactRepository(dsn)
        if selected:
            current_case = selected[0].case_id if mode == "live" else "suite"
        results = asyncio.run(_run_cases(repository, selected, mode))
        print(format_report(mode, results))
        return 0
    except GoldenValidationError as exception:
        print("상태: Fail")
        print(f"caseId: {current_case}")
        print(f"reasonCode: {exception.code}")
        return 1
    except ChatbotProviderUnavailable as exception:
        print("상태: Fail")
        print(f"caseId: {current_case}")
        print(f"reasonCode: {_provider_failure_reason(exception)}")
        return 1
    except Exception:
        print("상태: Fail")
        print(f"caseId: {current_case}")
        print("reasonCode: GOLDEN_EXECUTION_FAILED")
        return 1
    finally:
        if repository is not None:
            repository.close()
        pool_logger.disabled = pool_logger_was_disabled


def _provider_failure_reason(exception: ChatbotProviderUnavailable) -> str:
    stage: str | None = None
    reason: str | None = None
    cause: BaseException | None = exception
    while cause is not None:
        if isinstance(cause, LanguageModelStageError):
            stage = cause.stage
        elif isinstance(cause, OpenAIResponsesError):
            reason = cause.reason_code
        elif isinstance(cause, GroundingValidationError):
            reason = cause.reason_code
        cause = cause.__cause__
    if reason is None:
        return "PROVIDER_UNAVAILABLE"
    return f"{stage}_{reason}" if stage is not None else reason


if __name__ == "__main__":
    raise SystemExit(main())
