from __future__ import annotations

import asyncio
import json
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from typing import Literal, Protocol

MAX_TOOL_ROUNDS = 4
MAX_TOOL_CALLS = 12
MAX_CANDIDATES = 40
MAX_SHORTLIST = 10
MAX_FINAL_ROWS = 5
MAX_TOOL_OUTPUT_BYTES = 32 * 1024
MAX_TOTAL_TOOL_OUTPUT_BYTES = 128 * 1024

AgentRole = Literal[
    "BALANCED", "TRADE_ACTIVITY", "SCALE", "NEWER", "TRANSIT", "EDUCATION",
    "LIFESTYLE",
]
AgentRoute = Literal["primary", "repair", "secondary", "minimal_fallback"]

TOOL_CATALOG: tuple[dict[str, object], ...] = (
    {
        "type": "function",
        "name": "search_complexes",
        "description": "Search marker-safe apartment complexes by normalized name, alias, and address tokens.",
        "strict": True,
        "parameters": {
            "type": "object", "additionalProperties": False,
            "required": ["query", "limit"],
            "properties": {
                "query": {"type": "string", "minLength": 1, "maxLength": 100},
                "limit": {"type": "integer", "minimum": 1, "maximum": MAX_CANDIDATES},
            },
        },
    },
    {
        "type": "function", "name": "get_complex_profile", "strict": True,
        "description": "Read the public-safe building profile for one verified candidate.",
        "parameters": {"type": "object", "additionalProperties": False,
            "required": ["complexId"], "properties": {
                "complexId": {"type": "integer", "minimum": 1},
            }},
    },
    {
        "type": "function", "name": "get_recent_trades", "strict": True,
        "description": "Read recent verified trades for one candidate.",
        "parameters": {"type": "object", "additionalProperties": False,
            "required": ["complexId"], "properties": {
                "complexId": {"type": "integer", "minimum": 1},
            }},
    },
    {
        "type": "function", "name": "get_price_trend", "strict": True,
        "description": "Read server-calculated monthly price observations for one candidate.",
        "parameters": {"type": "object", "additionalProperties": False,
            "required": ["complexId"], "properties": {
                "complexId": {"type": "integer", "minimum": 1},
            }},
    },
    {
        "type": "function", "name": "get_region_candidate_pool", "strict": True,
        "description": "Build an unranked, marker-safe regional candidate pool after hard filters.",
        "parameters": {"type": "object", "additionalProperties": False,
            "required": ["regionName", "limit", "minimumUnitCount",
                         "maximumBudgetTenThousandKrw", "exclusiveAreaSquareMeters"],
            "properties": {
                "regionName": {"type": "string", "minLength": 1, "maxLength": 100},
                "limit": {"type": "integer", "minimum": 1, "maximum": MAX_CANDIDATES},
                "minimumUnitCount": {"type": ["integer", "null"], "minimum": 1, "maximum": 100000},
                "maximumBudgetTenThousandKrw": {"type": ["integer", "null"], "minimum": 1,
                                                  "maximum": 100000000},
                "exclusiveAreaSquareMeters": {"type": ["number", "null"],
                                                "exclusiveMinimum": 0, "maximum": 1000},
            }},
    },
    {
        "type": "function", "name": "get_candidate_evidence", "strict": True,
        "description": "Read bounded comparison evidence for up to ten verified candidates.",
        "parameters": {"type": "object", "additionalProperties": False,
            "required": ["complexIds"], "properties": {
                "complexIds": {"type": "array", "minItems": 1, "maxItems": MAX_SHORTLIST,
                    "uniqueItems": True, "items": {"type": "integer", "minimum": 1}},
            }},
    },
    {
        "type": "function", "name": "get_reference_evidence", "strict": True,
        "description": "Read available rail and lifestyle observations for verified candidates.",
        "parameters": {"type": "object", "additionalProperties": False,
            "required": ["complexIds"], "properties": {
                "complexIds": {"type": "array", "minItems": 1, "maxItems": MAX_SHORTLIST,
                    "uniqueItems": True, "items": {"type": "integer", "minimum": 1}},
            }},
    },
)


@dataclass(frozen=True)
class AgentToolCall:
    call_id: str
    name: str
    arguments: Mapping[str, object]


@dataclass(frozen=True)
class AgentRecommendationRow:
    complex_id: int
    complex_name: str
    role: AgentRole
    summary: str
    strengths: tuple[tuple[str, tuple[str, ...]], ...]
    tradeoffs: tuple[tuple[str, tuple[str, ...]], ...]
    metrics: Mapping[str, object]
    fact_ids: tuple[str, ...]


@dataclass(frozen=True)
class WebCitation:
    fact_id: str
    title: str
    url: str


@dataclass(frozen=True)
class AgentDecision:
    answer: str
    rows: tuple[AgentRecommendationRow, ...]
    fact_ids: tuple[str, ...]
    limitations: tuple[str, ...] = ()
    web_citations: tuple[WebCitation, ...] = ()


@dataclass(frozen=True)
class AgentTurn:
    tool_calls: tuple[AgentToolCall, ...] = ()
    decision: AgentDecision | None = None

    def __post_init__(self) -> None:
        if bool(self.tool_calls) == bool(self.decision):
            raise ValueError("agent turn must contain either tools or one decision")


@dataclass(frozen=True)
class ToolEvidence:
    payload: Mapping[str, object]
    candidate_ids: frozenset[int] = frozenset()
    candidate_names: Mapping[int, str] = field(default_factory=dict)
    fact_ids: frozenset[str] = frozenset()
    scope_label: str | None = None


@dataclass(frozen=True)
class AgentRunResult:
    decision: AgentDecision
    route: AgentRoute
    readiness: Literal["supported", "partial"]
    tool_rounds: int
    tool_calls: int
    web_used: bool = False
    scope_label: str | None = None


class AgentModel(Protocol):
    async def respond(
        self, *, question: str, transcript: Sequence[Mapping[str, object]],
        tools: Sequence[Mapping[str, object]], repair_error: str | None,
    ) -> AgentTurn: ...


class AgentTools(Protocol):
    async def execute(self, name: str, arguments: Mapping[str, object]) -> ToolEvidence: ...


class AgentGroundingError(ValueError):
    pass


class BoundedAgentOrchestrator:
    def __init__(self, *, primary: AgentModel, secondary: AgentModel, tools: AgentTools) -> None:
        self._primary = primary
        self._secondary = secondary
        self._tools = tools

    async def run(self, *, question: str, requested_count: int) -> AgentRunResult:
        if not 1 <= requested_count <= MAX_FINAL_ROWS:
            raise ValueError("requested count is outside the supported range")
        primary_result = await self._attempt(
            self._primary, question=question, requested_count=requested_count,
            allow_repair=True, success_route="primary",
        )
        if primary_result is not None:
            return primary_result
        secondary_result = await self._attempt(
            self._secondary, question=question, requested_count=requested_count,
            allow_repair=False, success_route="secondary",
        )
        if secondary_result is not None:
            return secondary_result
        return AgentRunResult(
            decision=AgentDecision(
                answer="AI 비교 분석을 완료하지 못해 확인 가능한 후보만 표시합니다.",
                rows=(), fact_ids=(),
                limitations=("생성 경로가 모두 실패해 최소 fallback을 사용했습니다.",),
            ),
            route="minimal_fallback", readiness="partial", tool_rounds=0, tool_calls=0,
        )

    async def _attempt(
        self, model: AgentModel, *, question: str, requested_count: int,
        allow_repair: bool, success_route: Literal["primary", "secondary"],
    ) -> AgentRunResult | None:
        transcript: list[Mapping[str, object]] = []
        candidate_ids: set[int] = set()
        candidate_names: dict[int, str] = {}
        fact_ids: set[str] = set()
        allowed_numbers = set(_number_tokens(question))
        scope_label: str | None = None
        total_bytes = 0
        call_count = 0
        for round_number in range(1, MAX_TOOL_ROUNDS + 1):
            try:
                turn = await model.respond(
                    question=question, transcript=tuple(transcript),
                    tools=TOOL_CATALOG, repair_error=None,
                )
            except Exception:
                return None
            if turn.decision is not None:
                try:
                    _validate_decision(
                        turn.decision, candidate_ids=candidate_ids,
                        candidate_names=candidate_names, fact_ids=fact_ids,
                        allowed_numbers=allowed_numbers,
                        requested_count=requested_count,
                    )
                except AgentGroundingError as error:
                    if not allow_repair:
                        return None
                    try:
                        repaired = await model.respond(
                            question=question, transcript=tuple(transcript), tools=(),
                            repair_error=str(error),
                        )
                        if repaired.decision is None:
                            return None
                        _validate_decision(
                            repaired.decision, candidate_ids=candidate_ids,
                            candidate_names=candidate_names, fact_ids=fact_ids,
                            allowed_numbers=allowed_numbers,
                            requested_count=requested_count,
                        )
                    except Exception:
                        return None
                    return AgentRunResult(
                        repaired.decision, "repair", "supported", round_number - 1,
                        call_count, bool(repaired.decision.web_citations), scope_label,
                    )
                return AgentRunResult(
                    turn.decision, success_route, "supported", round_number - 1,
                    call_count, bool(turn.decision.web_citations), scope_label,
                )
            calls = turn.tool_calls
            if call_count + len(calls) > MAX_TOOL_CALLS:
                return None
            try:
                for call in calls:
                    _validate_tool_call(call, candidate_ids)
                results = await asyncio.gather(*(
                    self._tools.execute(call.name, call.arguments) for call in calls
                ))
            except Exception:
                return None
            outputs: list[dict[str, object]] = []
            for call, evidence in zip(calls, results, strict=True):
                encoded = json.dumps(
                    evidence.payload, ensure_ascii=False, separators=(",", ":")
                ).encode()
                if len(encoded) > MAX_TOOL_OUTPUT_BYTES:
                    return None
                total_bytes += len(encoded)
                if total_bytes > MAX_TOTAL_TOOL_OUTPUT_BYTES:
                    return None
                candidate_ids.update(evidence.candidate_ids)
                for complex_id, name in evidence.candidate_names.items():
                    existing_name = candidate_names.get(complex_id)
                    if existing_name is not None and existing_name != name:
                        return None
                    candidate_names[complex_id] = name
                fact_ids.update(evidence.fact_ids)
                allowed_numbers.update(_number_tokens(encoded.decode()))
                if evidence.scope_label is not None:
                    scope_label = evidence.scope_label
                outputs.append({
                    "type": "function_call_output", "call_id": call.call_id,
                    "output": encoded.decode(),
                })
            call_count += len(calls)
            transcript.extend(outputs)
        return None


def _validate_tool_call(call: AgentToolCall, candidate_ids: set[int]) -> None:
    tool = next((item for item in TOOL_CATALOG if item["name"] == call.name), None)
    if tool is None or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", call.call_id):
        raise ValueError("unsupported tool call")
    arguments = dict(call.arguments)
    if call.name in {"get_complex_profile", "get_recent_trades", "get_price_trend"}:
        if set(arguments) != {"complexId"} or not _positive_int(arguments["complexId"]):
            raise ValueError("invalid complex tool arguments")
        if candidate_ids and int(arguments["complexId"]) not in candidate_ids:
            raise ValueError("tool requested a candidate outside the verified pool")
    elif call.name in {"get_candidate_evidence", "get_reference_evidence"}:
        if set(arguments) != {"complexIds"}:
            raise ValueError("invalid batch tool arguments")
        values = arguments["complexIds"]
        if (
            not isinstance(values, list) or not 1 <= len(values) <= MAX_SHORTLIST
            or len(values) != len(set(values)) or any(not _positive_int(value) for value in values)
            or candidate_ids and not set(values).issubset(candidate_ids)
        ):
            raise ValueError("invalid batch candidate ids")
    elif call.name == "search_complexes":
        if set(arguments) != {"query", "limit"} or not _bounded_text(arguments["query"], 100) \
                or not _bounded_int(arguments["limit"], 1, MAX_CANDIDATES):
            raise ValueError("invalid search arguments")
    elif call.name == "get_region_candidate_pool":
        if not {"regionName", "limit"}.issubset(arguments) or not set(arguments).issubset(
            {"regionName", "limit", "minimumUnitCount", "maximumBudgetTenThousandKrw",
             "exclusiveAreaSquareMeters"}
        ) or not _bounded_text(arguments["regionName"], 100) \
                or not _bounded_int(arguments["limit"], 1, MAX_CANDIDATES):
            raise ValueError("invalid candidate pool arguments")
        minimum = arguments.get("minimumUnitCount")
        if minimum is not None and not _bounded_int(minimum, 1, 100_000):
            raise ValueError("invalid hard filter")
        budget = arguments.get("maximumBudgetTenThousandKrw")
        if budget is not None and not _bounded_int(budget, 1, 100_000_000):
            raise ValueError("invalid budget hard filter")
        area = arguments.get("exclusiveAreaSquareMeters")
        if area is not None and (
            isinstance(area, bool) or not isinstance(area, (int, float))
            or not 0 < float(area) <= 1000
        ):
            raise ValueError("invalid area hard filter")
        if (budget is None) != (area is None):
            raise ValueError("budget and area hard filters must be supplied together")


def _validate_decision(
    decision: AgentDecision, *, candidate_ids: set[int],
    candidate_names: Mapping[int, str], fact_ids: set[str],
    allowed_numbers: set[str], requested_count: int,
) -> None:
    if not _bounded_text(decision.answer, 20_000):
        raise AgentGroundingError("answer is invalid")
    if not 1 <= len(decision.rows) <= requested_count:
        raise AgentGroundingError("final candidate count is invalid")
    selected = [row.complex_id for row in decision.rows]
    if len(selected) != len(set(selected)):
        raise AgentGroundingError("duplicate candidate id")
    if not set(selected).issubset(candidate_ids):
        raise AgentGroundingError("candidate is outside the verified pool")
    factual_texts = [decision.answer]
    referenced = set(decision.fact_ids)
    for row in decision.rows:
        if row.role not in {
            "BALANCED", "TRADE_ACTIVITY", "SCALE", "NEWER", "TRANSIT", "EDUCATION",
            "LIFESTYLE",
        } or not _bounded_text(row.complex_name, 100) or not _bounded_text(row.summary, 2_000):
            raise AgentGroundingError("recommendation row is invalid")
        if candidate_names.get(row.complex_id) != row.complex_name:
            raise AgentGroundingError("candidate name does not match verified identity")
        factual_texts.append(row.summary)
        row_facts = set(row.fact_ids)
        if not row_facts:
            raise AgentGroundingError("recommendation row lacks facts")
        referenced.update(row_facts)
        for text, claim_fact_ids in (*row.strengths, *row.tradeoffs):
            if not _bounded_text(text, 2_000) or not claim_fact_ids:
                raise AgentGroundingError("factual recommendation text lacks facts")
            referenced.update(claim_fact_ids)
            factual_texts.append(text)
    if not referenced.issubset(fact_ids):
        raise AgentGroundingError("unknown fact id")
    from .web_evidence import validate_official_source_url
    if (
        len({citation.fact_id for citation in decision.web_citations})
        != len(decision.web_citations)
        or any(
            not re.fullmatch(r"web:[a-f0-9]{32}", citation.fact_id)
            or not _bounded_text(citation.title, 500)
            or not validate_official_source_url(citation.url)
            for citation in decision.web_citations
        )
    ):
        raise AgentGroundingError("invalid official web citation")
    if any(_FORBIDDEN_CLAIM_PATTERN.search(text) for text in factual_texts):
        raise AgentGroundingError("forbidden property claim")
    stated_numbers = {
        number for text in factual_texts for number in _number_tokens(text)
    }
    if not stated_numbers.issubset(allowed_numbers):
        raise AgentGroundingError("unobserved numeric claim")


def _positive_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _bounded_int(value: object, minimum: int, maximum: int) -> bool:
    return _positive_int(value) and minimum <= int(value) <= maximum


def _bounded_text(value: object, maximum: int) -> bool:
    return isinstance(value, str) and 1 <= len(value.strip()) <= maximum


def _number_tokens(value: str) -> tuple[str, ...]:
    return tuple(match.replace(",", "") for match in re.findall(r"(?<![\w])\d[\d,]*(?:\.\d+)?", value))


_FORBIDDEN_CLAIM_PATTERN = re.compile(
    r"(무조건|최고|투자\s*추천|투자수익|수익률|미래\s*가격|"
    r"학교\s*품질|통근\s*시간|통근시간)"
)
