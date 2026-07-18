from __future__ import annotations

import asyncio
import re
from collections.abc import Iterable
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Protocol

from ai_service.auth import AuthenticatedUser
from ai_service.chat import ChatbotProviderUnavailable
from ai_service.models import ChatbotQueryRequest

from .models import (
    ComplexRecord,
    DraftAnswer,
    EvidenceFact,
    FactClaim,
    MonthlyTrendRecord,
    PropertyCapability,
    PropertyQueryPlan,
    TradeRecord,
)


class PropertyFactRepository(Protocol):
    def find_complexes(
        self, name: str, region_name: str | None, limit: int
    ) -> list[ComplexRecord]: ...

    def recent_trades(
        self,
        complex_id: int,
        start_date: date | None,
        end_date: date | None,
        exclusive_area_square_meters: float | None,
        limit: int,
    ) -> list[TradeRecord]: ...

    def monthly_trends(
        self,
        complex_id: int,
        start_date: date,
        end_date: date,
        exclusive_area_square_meters: float | None,
    ) -> list[MonthlyTrendRecord]: ...

    def latest_trade_date(self) -> date | None: ...


class GroundedLanguageModel(Protocol):
    async def plan_query(self, request: ChatbotQueryRequest) -> PropertyQueryPlan: ...

    async def draft_answer(
        self,
        *,
        facts: list[EvidenceFact],
        limitations: list[str],
        question: str,
    ) -> DraftAnswer: ...


_GROUNDING_FAILURE_REASONS = frozenset(
    {
        "GROUNDING_CAPABILITY_UNSUPPORTED",
        "GROUNDING_ANSWER_EMPTY",
        "GROUNDING_SENTENCE_BLANK",
        "GROUNDING_FACT_IDS_MISSING",
        "GROUNDING_CLAIMS_MISSING",
        "GROUNDING_FACT_IDS_DUPLICATE",
        "GROUNDING_FACT_UNKNOWN",
        "GROUNDING_FACTS_OMITTED",
        "GROUNDING_CLAIM_NOT_ATTACHED",
        "GROUNDING_CLAIM_MISMATCH",
        "GROUNDING_RESULT_COUNT_OR_LIST_NUMBER",
        "GROUNDING_AMOUNT_UNIT_CONVERSION",
        "GROUNDING_NUMBER_OUTSIDE_OBSERVATION",
    }
)


class GroundingValidationError(ValueError):
    """Non-disclosing validation failure with a stable diagnostic category."""

    def __init__(self, reason_code: str) -> None:
        if reason_code not in _GROUNDING_FAILURE_REASONS:
            raise ValueError("invalid grounding failure reason")
        super().__init__()
        self.reason_code = reason_code


class GroundedChatbotEngine:
    def __init__(
        self,
        *,
        repository: PropertyFactRepository,
        language_model: GroundedLanguageModel,
        enabled_capabilities: frozenset[PropertyCapability],
    ) -> None:
        self._repository = repository
        self._language_model = language_model
        self._enabled_capabilities = enabled_capabilities

    async def query(
        self,
        *,
        request: ChatbotQueryRequest,
        user: AuthenticatedUser,
        request_id: str,
    ) -> dict[str, object]:
        del user
        try:
            plan = await self._language_model.plan_query(request)
            if plan.capability in self._enabled_capabilities:
                facts, limitations, readiness = await self._observe(plan)
            else:
                facts, limitations, readiness = (
                    [],
                    ["해당 질문 기능은 현재 데이터 준비와 검증이 진행 중입니다."],
                    "unavailable",
                )
            draft = await self._language_model.draft_answer(
                facts=facts,
                limitations=limitations,
                question=request.question,
            )
            used_facts = validate_draft(draft, facts, readiness)
            return _response(
                request=request,
                request_id=request_id,
                plan=plan,
                draft=draft,
                used_facts=used_facts,
                limitations=limitations,
                readiness=readiness,
            )
        except ChatbotProviderUnavailable:
            raise
        except Exception as exception:
            raise ChatbotProviderUnavailable() from exception

    async def _observe(
        self, plan: PropertyQueryPlan
    ) -> tuple[list[EvidenceFact], list[str], str]:
        complexes = await asyncio.to_thread(
            self._repository.find_complexes,
            plan.complex_name,
            plan.region_name,
            6,
        )
        if not complexes:
            return (
                [],
                ["지정한 이름과 지역 조건으로 단지를 식별하지 못했습니다."],
                "unavailable",
            )
        if len(complexes) > 1:
            return (
                [_complex_fact(record) for record in complexes],
                ["동명 단지가 여러 곳이므로 지역이나 주소 조건을 추가해야 합니다."],
                "partial",
            )

        complex_record = complexes[0]
        if plan.capability == "complex_identity":
            limitations = []
            if not complex_record.marker_safe:
                limitations.append("검증된 표시 좌표가 없어 위치 좌표는 제공하지 않습니다.")
            return [_complex_fact(complex_record)], limitations, "supported"
        if plan.capability == "recent_trade_lookup":
            trades = await asyncio.to_thread(
                self._repository.recent_trades,
                complex_record.complex_id,
                plan.start_date,
                plan.end_date,
                plan.exclusive_area_square_meters,
                plan.limit,
            )
            if not trades:
                return (
                    [],
                    ["지정한 기간과 면적 조건에서 확인된 실거래가 없습니다."],
                    "unavailable",
                )
            latest_trade_date = await asyncio.to_thread(self._repository.latest_trade_date)
            data_as_of = latest_trade_date or max(record.deal_date for record in trades)
            limitations = ["신고 취소 또는 지연 신고가 이후 반영될 수 있습니다."]
            if plan.exclusive_area_square_meters is not None:
                limitations.append("전용면적은 요청값 기준 ±1.0㎡ 범위로 조회했습니다.")
            return [_trade_fact(record, data_as_of) for record in trades], limitations, "supported"
        if plan.capability == "price_trend":
            assert plan.start_date is not None and plan.end_date is not None
            trends = await asyncio.to_thread(
                self._repository.monthly_trends,
                complex_record.complex_id,
                plan.start_date,
                plan.end_date,
                plan.exclusive_area_square_meters,
            )
            if not trends:
                return (
                    [],
                    ["지정한 기간과 면적 조건으로 월별 추이를 계산할 거래가 없습니다."],
                    "unavailable",
                )
            latest_trade_date = await asyncio.to_thread(self._repository.latest_trade_date)
            data_as_of = latest_trade_date or min(
                plan.end_date, max(_month_end(record.month) for record in trends)
            )
            limitations = ["월별 수치는 실제 거래 관찰값이며 미래 가격을 의미하지 않습니다."]
            if plan.exclusive_area_square_meters is not None:
                limitations.append("전용면적은 요청값 기준 ±1.0㎡ 범위로 집계했습니다.")
            return [_trend_fact(record, data_as_of) for record in trends], limitations, "supported"
        raise GroundingValidationError("GROUNDING_CAPABILITY_UNSUPPORTED")


def _complex_fact(record: ComplexRecord) -> EvidenceFact:
    claims = [
        FactClaim(str(record.complex_id), "COMPLEX_ID"),
        FactClaim(record.display_name, "TEXT"),
    ]
    for value in (record.region_code, record.region_name, record.address):
        if value:
            claims.append(FactClaim(value, "TEXT"))
    payload: dict[str, object] = {
        "complexId": record.complex_id,
        "displayName": record.display_name,
        "regionCode": record.region_code,
        "regionName": record.region_name,
        "address": record.address,
        "markerSafe": record.marker_safe,
    }
    claims.append(FactClaim(str(record.marker_safe).lower(), "BOOLEAN"))
    if record.marker_safe and record.latitude is not None and record.longitude is not None:
        claims.extend(
            [
                FactClaim(_number(record.latitude), "DEGREES_LATITUDE"),
                FactClaim(_number(record.longitude), "DEGREES_LONGITUDE"),
            ]
        )
        payload["latitude"] = record.latitude
        payload["longitude"] = record.longitude
    return EvidenceFact(
        fact_id=f"property-complex-{record.complex_id}",
        claims=tuple(claims),
        data_as_of=record.data_updated_at.date(),
        payload=payload,
    )


def _trade_fact(record: TradeRecord, data_as_of: date) -> EvidenceFact:
    claims = (
        FactClaim(record.deal_date.isoformat(), "DATE"),
        FactClaim(str(record.deal_amount_ten_thousand_krw), "10_000_KRW"),
        FactClaim(
            _korean_krw_display(record.deal_amount_ten_thousand_krw),
            "KOREAN_KRW_DISPLAY",
        ),
        FactClaim(_number(record.exclusive_area_square_meters), "SQUARE_METERS"),
        *(() if record.floor is None else (FactClaim(str(record.floor), "FLOOR"),)),
    )
    return EvidenceFact(
        fact_id=f"property-trade-{record.trade_id}",
        claims=claims,
        data_as_of=data_as_of,
        payload={
            "tradeId": record.trade_id,
            "complexId": record.complex_id,
            "dealDate": record.deal_date.isoformat(),
            "dealAmountTenThousandKrw": record.deal_amount_ten_thousand_krw,
            "exclusiveAreaSquareMeters": record.exclusive_area_square_meters,
            "floor": record.floor,
        },
    )


def _trend_fact(record: MonthlyTrendRecord, data_as_of: date) -> EvidenceFact:
    return EvidenceFact(
        fact_id=f"property-trend-{record.complex_id}-{record.month:%Y-%m}",
        claims=(
            FactClaim(record.month.strftime("%Y-%m"), "MONTH"),
            FactClaim(str(record.average_amount_ten_thousand_krw), "10_000_KRW"),
            FactClaim(str(record.trade_count), "COUNT"),
            FactClaim(str(record.minimum_amount_ten_thousand_krw), "10_000_KRW_MIN"),
            FactClaim(str(record.maximum_amount_ten_thousand_krw), "10_000_KRW_MAX"),
        ),
        data_as_of=data_as_of,
        payload={
            "complexId": record.complex_id,
            "month": record.month.strftime("%Y-%m"),
            "averageAmountTenThousandKrw": record.average_amount_ten_thousand_krw,
            "tradeCount": record.trade_count,
            "minimumAmountTenThousandKrw": record.minimum_amount_ten_thousand_krw,
            "maximumAmountTenThousandKrw": record.maximum_amount_ten_thousand_krw,
        },
    )


def validate_draft(
    draft: DraftAnswer,
    facts: list[EvidenceFact],
    readiness: str,
) -> list[EvidenceFact]:
    if not draft.sentences:
        raise GroundingValidationError("GROUNDING_ANSWER_EMPTY")
    fact_by_id = {fact.fact_id: fact for fact in facts}
    used_ids: list[str] = []
    for sentence in draft.sentences:
        if not sentence.text.strip():
            raise GroundingValidationError("GROUNDING_SENTENCE_BLANK")
        if readiness != "unavailable" and not sentence.fact_ids:
            raise GroundingValidationError("GROUNDING_FACT_IDS_MISSING")
        if readiness != "unavailable" and not sentence.claims:
            raise GroundingValidationError("GROUNDING_CLAIMS_MISSING")
        if len(sentence.fact_ids) != len(set(sentence.fact_ids)):
            raise GroundingValidationError("GROUNDING_FACT_IDS_DUPLICATE")
        referenced: list[EvidenceFact] = []
        for fact_id in sentence.fact_ids:
            fact = fact_by_id.get(fact_id)
            if fact is None:
                raise GroundingValidationError("GROUNDING_FACT_UNKNOWN")
            referenced.append(fact)
            if fact_id not in used_ids:
                used_ids.append(fact_id)
        for claim in sentence.claims:
            if claim.fact_id not in sentence.fact_ids:
                raise GroundingValidationError("GROUNDING_CLAIM_NOT_ATTACHED")
            fact = fact_by_id[claim.fact_id]
            if FactClaim(claim.value, claim.unit) not in fact.claims:
                raise GroundingValidationError("GROUNDING_CLAIM_MISMATCH")
        allowed_numbers = _number_tokens(
            claim.value for fact in referenced for claim in fact.claims
        )
        unexpected_numbers = _number_tokens([sentence.text]) - allowed_numbers
        if unexpected_numbers:
            ordinal_candidates = {
                str(index) for index in range(1, len(facts) + 1)
            }
            if unexpected_numbers.issubset(ordinal_candidates):
                reason_code = "GROUNDING_RESULT_COUNT_OR_LIST_NUMBER"
            elif any(
                claim.unit.startswith("10_000_KRW")
                for fact in referenced
                for claim in fact.claims
            ):
                reason_code = "GROUNDING_AMOUNT_UNIT_CONVERSION"
            else:
                reason_code = "GROUNDING_NUMBER_OUTSIDE_OBSERVATION"
            raise GroundingValidationError(reason_code)
    if readiness != "unavailable" and set(used_ids) != set(fact_by_id):
        raise GroundingValidationError("GROUNDING_FACTS_OMITTED")
    return [fact_by_id[fact_id] for fact_id in used_ids]


def _response(
    *,
    request: ChatbotQueryRequest,
    request_id: str,
    plan: PropertyQueryPlan,
    draft: DraftAnswer,
    used_facts: list[EvidenceFact],
    limitations: list[str],
    readiness: str,
) -> dict[str, object]:
    answer = " ".join(sentence.text.strip() for sentence in draft.sentences)
    citations = _citations(used_facts)
    data_as_of = min((fact.data_as_of for fact in used_facts), default=None)
    success = readiness != "unavailable"
    return {
        "success": success,
        "status": "success" if success else "failed",
        "question": request.question,
        "fragments": [],
        "result": {},
        "message": "",
        "executionSummary": {"total": 1, "succeeded": int(success), "failed": int(not success)},
        "answer": answer,
        "resolvedQuestion": request.question,
        "conversationResolution": None,
        "conversationMemoryPatch": None,
        "uiActions": [],
        "uiArtifacts": [],
        "uiSummary": None,
        "requestId": request_id,
        "citations": citations,
        "dataAsOf": data_as_of.isoformat() if data_as_of else None,
        "limitations": limitations,
        "evidenceSummary": {
            "status": readiness,
            "capabilities": [plan.capability],
            "factCount": len(used_facts),
            "citationCount": len(citations),
        },
    }


def _citations(facts: list[EvidenceFact]) -> list[dict[str, object]]:
    grouped: dict[tuple[str, date], list[str]] = {}
    for fact in facts:
        grouped.setdefault((fact.dataset_version, fact.data_as_of), []).append(fact.fact_id)
    return [
        {
            "citationId": f"citation-{index}",
            "sourceId": "property.ai_read",
            "sourceName": "Home Search 실거래",
            "sourceUrl": None,
            "evidenceGrade": "A",
            "datasetVersion": version,
            "dataAsOf": data_as_of.isoformat(),
            "observedAt": None,
            "factIds": fact_ids,
        }
        for index, ((version, data_as_of), fact_ids) in enumerate(grouped.items(), start=1)
    ]


def _number(value: int | float) -> str:
    return format(Decimal(str(value)).normalize(), "f")


def _korean_krw_display(amount_ten_thousand_krw: int) -> str:
    eok, man_won = divmod(amount_ten_thousand_krw, 10_000)
    if eok and man_won:
        return f"{eok:,}억 {man_won:,}만원"
    if eok:
        return f"{eok:,}억원"
    return f"{man_won:,}만원"


def _number_tokens(values: Iterable[str]) -> set[str]:
    tokens: set[str] = set()
    for value in values:
        for raw in re.findall(r"[0-9]+(?:[.,][0-9]+)*", value):
            try:
                tokens.add(format(Decimal(raw.replace(",", "")).normalize(), "f"))
            except InvalidOperation:
                continue
    return tokens


def _month_end(month: date) -> date:
    if month.month == 12:
        return date(month.year, 12, 31)
    return date.fromordinal(date(month.year, month.month + 1, 1).toordinal() - 1)
