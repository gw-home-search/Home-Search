from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP


_OVERVIEW_SUFFIX = re.compile(
    r"(?:\s+(?:전체적(?:으로)?|전반적(?:으로)?))?"
    r"(?:\s+(?:아파트|단지))?"
    r"(?:은|는|이|가)?\s*"
    r"(?:어때|어떄|어떤가|괜찮아|평가해\s*줘|살기\s*어때)\s*$"
)
_AREA_EXPRESSION = re.compile(
    r"(?P<exclusive>전용\s*)?"
    r"(?P<value>[0-9]+(?:\.[0-9]+)?)\s*"
    r"(?P<unit>m2|제곱미터|평(?:형|대)?)"
    r"(?:의)?",
    re.I,
)
_REGION_PREFIX = re.compile(r"^([가-힣]{1,20}(?:동|읍|면|구|군|시))\s+(.+)$")
_QUESTION_SPLIT = re.compile(
    r"\s+(?:전용|최근|월별|가격|실거래|거래|위치|주소|기본정보|어디|주변|가까운|전체적|전반적)"
)
_POSSESSIVE_SUPPORTED_INTENT = re.compile(
    r"의\s+(?:전용|최근|월별|가격|실거래|거래|위치|주소|기본정보|어디|주변|가까운|전체적|전반적)"
)


@dataclass(frozen=True)
class AreaCriterion:
    input_text: str
    input_value: float
    input_unit: str
    exclusive_area_square_meters: float | None
    requires_exclusive_confirmation: bool
    conversion_note: str | None = None


@dataclass(frozen=True)
class NormalizedQuestion:
    normalized_question: str
    entity_candidate: str | None
    region_hint: str | None
    overview: bool
    area_criterion: AreaCriterion | None = None
    intents: tuple[str, ...] = ()
    requested_count: int | None = None
    period_days: int | None = None
    uses_context_reference: bool = False


def normalize_question(question: str) -> NormalizedQuestion:
    normalized = unicodedata.normalize("NFKC", question)
    normalized = re.sub(r"[\s\u2000-\u200b\u3000]+", " ", normalized).strip()
    normalized = normalized.rstrip(" ?!,.\"")
    overview_match = _OVERVIEW_SUFFIX.search(normalized)
    area_criterion = _area_criterion(normalized)
    entity_source = _AREA_EXPRESSION.sub(" ", normalized)

    if overview_match is not None:
        candidate = _OVERVIEW_SUFFIX.sub("", entity_source).strip()
    else:
        candidate = _QUESTION_SPLIT.split(entity_source, maxsplit=1)[0]
    candidate = re.sub(r"\s+", " ", candidate).strip(" ?!,.\"")
    if candidate.endswith("의") and _POSSESSIVE_SUPPORTED_INTENT.search(entity_source):
        candidate = candidate.removesuffix("의").strip()

    if candidate in {"이 단지", "여기", "이곳"}:
        return NormalizedQuestion(
            normalized,
            None,
            None,
            overview_match is not None,
            area_criterion,
            intents=_intents(normalized),
            requested_count=_requested_count(normalized),
            period_days=_period_days(normalized),
            uses_context_reference=True,
        )

    region_hint = None
    region_match = _REGION_PREFIX.fullmatch(candidate)
    if region_match is not None:
        region_hint, candidate = region_match.groups()

    candidate = re.sub(r"(?:은|는)$", "", candidate).strip()
    candidate = re.sub(r"\s+(?:아파트|단지)$", "", candidate).strip()
    if not 1 <= len(candidate) <= 100:
        candidate = None
    return NormalizedQuestion(
        normalized_question=normalized,
        entity_candidate=candidate,
        region_hint=region_hint,
        overview=overview_match is not None,
        area_criterion=area_criterion,
        intents=_intents(normalized),
        requested_count=_requested_count(normalized),
        period_days=_period_days(normalized),
        uses_context_reference=bool(re.search(r"(?:이\s*단지|여기|이곳)", normalized)),
    )


def _area_criterion(question: str) -> AreaCriterion | None:
    match = _AREA_EXPRESSION.search(question)
    if match is None:
        return None
    input_text = match.group(0).removesuffix("의").strip()
    value_text = match.group("value")
    value = Decimal(value_text)
    unit = match.group("unit").lower()
    if unit in {"m2", "제곱미터"}:
        return AreaCriterion(
            input_text=input_text,
            input_value=float(value),
            input_unit="㎡",
            exclusive_area_square_meters=float(value),
            requires_exclusive_confirmation=False,
        )
    if match.group("exclusive") is not None and unit == "평":
        converted = (value * Decimal("3.305785")).quantize(
            Decimal("0.01"), rounding=ROUND_HALF_UP
        )
        converted_text = format(converted, "f")
        return AreaCriterion(
            input_text=input_text,
            input_value=float(value),
            input_unit="평",
            exclusive_area_square_meters=float(converted),
            requires_exclusive_confirmation=False,
            conversion_note=f"전용 {value_text}평을 {converted_text}㎡로 환산",
        )
    return AreaCriterion(
        input_text=input_text,
        input_value=float(value),
        input_unit=unit,
        exclusive_area_square_meters=None,
        requires_exclusive_confirmation=True,
    )


def _intents(question: str) -> tuple[str, ...]:
    patterns = (
        ("recent_trade_lookup", r"(?:실거래|최근\s*거래|거래\s*내역)"),
        ("price_trend", r"(?:가격\s*(?:흐름|추이)|월별|거래량)"),
        ("complex_identity", r"(?:위치|주소|기본\s*정보|단지\s*정보)"),
        ("academy_lookup", r"(?:학원|교습소)"),
        ("rail_station_lookup", r"(?:철도|지하철|가까운\s*역|역[·\s-]*노선|역세권)"),
    )
    return tuple(name for name, pattern in patterns if re.search(pattern, question))


def _requested_count(question: str) -> int | None:
    match = re.search(r"(?<![0-9])([1-9]|10)\s*건", question)
    return int(match.group(1)) if match is not None else None


def _period_days(question: str) -> int | None:
    match = re.search(r"최근\s*([1-9][0-9]?)\s*(년|개월|일)", question)
    if match is None:
        return None
    amount = int(match.group(1))
    unit = match.group(2)
    return amount * (365 if unit == "년" else 30 if unit == "개월" else 1)
