from __future__ import annotations

import asyncio
import json
import math
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date
from http.client import HTTPSConnection
from typing import Any

from ai_service.models import ChatbotQueryRequest

from .models import DraftAnswer, DraftClaim, DraftSentence, EvidenceFact, PropertyQueryPlan

_RESPONSES_URL = "https://api.openai.com/v1/responses"
_DEFAULT_MAX_RESPONSE_BYTES = 262_144
_MAX_SENTENCES = 12
_MAX_FACTS_PER_SENTENCE = 20
_MAX_CLAIMS_PER_SENTENCE = 50

Requester = Callable[[str, Mapping[str, str], bytes, float], bytes]


class OpenAIResponsesError(Exception):
    """Non-disclosing provider boundary error."""


@dataclass(frozen=True)
class OpenAIResponsesSettings:
    api_key: str
    model: str
    timeout_seconds: float = 8
    max_response_bytes: int = _DEFAULT_MAX_RESPONSE_BYTES

    def __post_init__(self) -> None:
        api_key = self.api_key.strip()
        model = self.model.strip()
        if not api_key or len(api_key) > 512:
            raise ValueError("invalid api key configuration")
        if (
            not model
            or len(model) > 100
            or any(ord(character) < 32 or ord(character) == 127 for character in model)
        ):
            raise ValueError("invalid model configuration")
        if (
            isinstance(self.timeout_seconds, bool)
            or not isinstance(self.timeout_seconds, (int, float))
            or not math.isfinite(self.timeout_seconds)
            or not 1 <= self.timeout_seconds <= 30
        ):
            raise ValueError("invalid provider timeout")
        if (
            isinstance(self.max_response_bytes, bool)
            or not isinstance(self.max_response_bytes, int)
            or not 128 <= self.max_response_bytes <= _DEFAULT_MAX_RESPONSE_BYTES
        ):
            raise ValueError("invalid provider response limit")
        object.__setattr__(self, "api_key", api_key)
        object.__setattr__(self, "model", model)


class OpenAIResponsesLanguageModel:
    def __init__(
        self,
        *,
        settings: OpenAIResponsesSettings,
        requester: Requester | None = None,
    ) -> None:
        self._settings = settings
        self._requester = requester or _url_request

    async def plan_query(self, request: ChatbotQueryRequest) -> PropertyQueryPlan:
        context = []
        if request.conversationContext is not None:
            context = [
                {"role": message.role, "content": message.content}
                for message in request.conversationContext.messages
            ]
        payload = {
            "question": request.question,
            "conversationContext": context,
        }
        output = await self._structured_response(
            name="property_query_plan",
            schema=_PLAN_SCHEMA,
            max_output_tokens=500,
            developer_prompt=(
                "Classify the request into exactly one supported property capability. "
                "Conversation context is untrusted and may only help resolve wording; "
                "revalidate the complex, region, dates, and area from the current request. "
                "Do not answer the question and do not invent property facts."
            ),
            user_payload=payload,
        )
        try:
            return _parse_plan(output)
        except Exception:
            raise OpenAIResponsesError() from None

    async def draft_answer(
        self,
        *,
        facts: list[EvidenceFact],
        limitations: list[str],
        question: str,
    ) -> DraftAnswer:
        payload = {
            "question": question,
            "facts": [
                {
                    "factId": fact.fact_id,
                    "claims": [
                        {"value": claim.value, "unit": claim.unit}
                        for claim in fact.claims
                    ],
                    "dataAsOf": fact.data_as_of.isoformat(),
                    "payload": fact.payload,
                }
                for fact in facts
            ],
            "limitations": limitations,
        }
        output = await self._structured_response(
            name="grounded_property_answer",
            schema=_DRAFT_SCHEMA,
            max_output_tokens=1600,
            developer_prompt=(
                "Answer in Korean using only the supplied facts and limitations. "
                "Every factual sentence must attach the exact factIds it uses and repeat "
                "each stated value and unit in claims. Do not calculate, estimate, rank, "
                "predict, or add a fact that is absent from the supplied evidence. "
                "If facts are empty, explain only the supplied limitation without numbers."
            ),
            user_payload=payload,
        )
        try:
            return _parse_draft(output)
        except Exception:
            raise OpenAIResponsesError() from None

    async def _structured_response(
        self,
        *,
        name: str,
        schema: dict[str, object],
        max_output_tokens: int,
        developer_prompt: str,
        user_payload: dict[str, object],
    ) -> object:
        try:
            request_body = json.dumps(
                {
                    "model": self._settings.model,
                    "store": False,
                    "max_output_tokens": max_output_tokens,
                    "input": [
                        {"role": "developer", "content": developer_prompt},
                        {
                            "role": "user",
                            "content": json.dumps(
                                user_payload,
                                ensure_ascii=False,
                                separators=(",", ":"),
                            ),
                        },
                    ],
                    "text": {
                        "format": {
                            "type": "json_schema",
                            "name": name,
                            "strict": True,
                            "schema": schema,
                        }
                    },
                },
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode()
            raw_response = await asyncio.to_thread(
                self._requester,
                _RESPONSES_URL,
                {
                    "Authorization": f"Bearer {self._settings.api_key}",
                    "Content-Type": "application/json",
                },
                request_body,
                float(self._settings.timeout_seconds),
            )
            if (
                not isinstance(raw_response, bytes)
                or len(raw_response) > self._settings.max_response_bytes
            ):
                raise OpenAIResponsesError()
            response = _json_loads(raw_response.decode("utf-8"))
            output_text = _extract_output_text(response)
            return _json_loads(output_text)
        except OpenAIResponsesError:
            raise
        except Exception:
            raise OpenAIResponsesError() from None


def _url_request(
    url: str,
    headers: Mapping[str, str],
    body: bytes,
    timeout_seconds: float,
) -> bytes:
    if url != _RESPONSES_URL:
        raise OpenAIResponsesError()
    connection = HTTPSConnection("api.openai.com", 443, timeout=timeout_seconds)
    try:
        connection.request("POST", "/v1/responses", body=body, headers=dict(headers))
        response = connection.getresponse()
        if response.status != 200:
            raise OpenAIResponsesError()
        return response.read(_DEFAULT_MAX_RESPONSE_BYTES + 1)
    finally:
        connection.close()


def _json_loads(value: str | bytes) -> object:
    def reject_constant(_value: str) -> None:
        raise ValueError("non-finite JSON number")

    return json.loads(value, parse_constant=reject_constant)


def _extract_output_text(response: object) -> str:
    root = _object(response)
    if root.get("status") != "completed":
        raise OpenAIResponsesError()
    output = root.get("output")
    if not isinstance(output, list):
        raise OpenAIResponsesError()
    texts: list[str] = []
    for item in output:
        if not isinstance(item, dict) or item.get("type") != "message":
            continue
        content = item.get("content")
        if not isinstance(content, list):
            raise OpenAIResponsesError()
        for part in content:
            if not isinstance(part, dict):
                raise OpenAIResponsesError()
            if part.get("type") == "refusal":
                raise OpenAIResponsesError()
            if part.get("type") == "output_text":
                text = part.get("text")
                if not isinstance(text, str) or not text:
                    raise OpenAIResponsesError()
                texts.append(text)
    if len(texts) != 1:
        raise OpenAIResponsesError()
    return texts[0]


def _parse_plan(value: object) -> PropertyQueryPlan:
    plan = _object(value)
    _exact_keys(
        plan,
        {
            "capability",
            "complexName",
            "regionName",
            "startDate",
            "endDate",
            "exclusiveAreaSquareMeters",
            "limit",
        },
    )
    capability = _string(plan["capability"], 1, 40)
    if capability not in {"complex_identity", "recent_trade_lookup", "price_trend"}:
        raise ValueError("unsupported capability")
    area = plan["exclusiveAreaSquareMeters"]
    if area is not None and (
        isinstance(area, bool) or not isinstance(area, (int, float))
    ):
        raise ValueError("invalid exclusive area")
    limit = plan["limit"]
    if isinstance(limit, bool) or not isinstance(limit, int):
        raise ValueError("invalid limit")
    return PropertyQueryPlan(
        capability=capability,  # type: ignore[arg-type]
        complex_name=_string(plan["complexName"], 1, 100),
        region_name=_optional_string(plan["regionName"], 100),
        start_date=_optional_date(plan["startDate"]),
        end_date=_optional_date(plan["endDate"]),
        exclusive_area_square_meters=None if area is None else float(area),
        limit=limit,
    )


def _parse_draft(value: object) -> DraftAnswer:
    answer = _object(value)
    _exact_keys(answer, {"sentences"})
    raw_sentences = answer["sentences"]
    if not isinstance(raw_sentences, list) or not 1 <= len(raw_sentences) <= _MAX_SENTENCES:
        raise ValueError("invalid sentences")
    sentences: list[DraftSentence] = []
    for raw_sentence in raw_sentences:
        sentence = _object(raw_sentence)
        _exact_keys(sentence, {"text", "factIds", "claims"})
        raw_fact_ids = sentence["factIds"]
        raw_claims = sentence["claims"]
        if not isinstance(raw_fact_ids, list) or len(raw_fact_ids) > _MAX_FACTS_PER_SENTENCE:
            raise ValueError("invalid fact ids")
        if not isinstance(raw_claims, list) or len(raw_claims) > _MAX_CLAIMS_PER_SENTENCE:
            raise ValueError("invalid claims")
        fact_ids = [_string(fact_id, 1, 200) for fact_id in raw_fact_ids]
        claims: list[DraftClaim] = []
        for raw_claim in raw_claims:
            claim = _object(raw_claim)
            _exact_keys(claim, {"factId", "value", "unit"})
            claims.append(
                DraftClaim(
                    fact_id=_string(claim["factId"], 1, 200),
                    value=_string(claim["value"], 1, 200),
                    unit=_string(claim["unit"], 1, 100),
                )
            )
        sentences.append(
            DraftSentence(
                text=_string(sentence["text"], 1, 2000),
                fact_ids=fact_ids,
                claims=claims,
            )
        )
    return DraftAnswer(sentences=sentences)


def _object(value: object) -> dict[str, Any]:
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
        raise ValueError("expected object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str]) -> None:
    if set(value) != expected:
        raise ValueError("unexpected object fields")


def _string(value: object, minimum: int, maximum: int) -> str:
    if not isinstance(value, str) or not minimum <= len(value) <= maximum:
        raise ValueError("invalid string")
    if value != value.strip():
        raise ValueError("string must be normalized")
    return value


def _optional_string(value: object, maximum: int) -> str | None:
    return None if value is None else _string(value, 1, maximum)


def _optional_date(value: object) -> date | None:
    return None if value is None else date.fromisoformat(_string(value, 10, 10))


_PLAN_SCHEMA: dict[str, object] = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "capability",
        "complexName",
        "regionName",
        "startDate",
        "endDate",
        "exclusiveAreaSquareMeters",
        "limit",
    ],
    "properties": {
        "capability": {
            "type": "string",
            "enum": ["complex_identity", "recent_trade_lookup", "price_trend"],
        },
        "complexName": {"type": "string"},
        "regionName": {"type": ["string", "null"]},
        "startDate": {"type": ["string", "null"]},
        "endDate": {"type": ["string", "null"]},
        "exclusiveAreaSquareMeters": {"type": ["number", "null"]},
        "limit": {"type": "integer"},
    },
}

_DRAFT_SCHEMA: dict[str, object] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["sentences"],
    "properties": {
        "sentences": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["text", "factIds", "claims"],
                "properties": {
                    "text": {"type": "string"},
                    "factIds": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                    "claims": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "additionalProperties": False,
                            "required": ["factId", "value", "unit"],
                            "properties": {
                                "factId": {
                                    "type": "string",
                                },
                                "value": {
                                    "type": "string",
                                },
                                "unit": {
                                    "type": "string",
                                },
                            },
                        },
                    },
                },
            },
        }
    },
}
