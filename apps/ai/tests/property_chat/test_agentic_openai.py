from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping

from ai_service.property_chat.agentic import TOOL_CATALOG
from ai_service.property_chat.agentic_openai import OpenAIResponsesAgentModel
from ai_service.property_chat.openai_responses import OpenAIResponsesSettings


class SequenceRequester:
    def __init__(self, responses: list[bytes]) -> None:
        self.responses = responses
        self.calls: list[bytes] = []

    def __call__(
        self, _url: str, _headers: Mapping[str, str], body: bytes, _timeout: float,
    ) -> bytes:
        self.calls.append(body)
        return self.responses.pop(0)


def _provider(output: list[dict[str, object]], response_id: str) -> bytes:
    return json.dumps({
        "id": response_id, "status": "completed", "output": output,
    }, ensure_ascii=False).encode()


def test_responses_agent_continues_function_loop_without_storage() -> None:
    requester = SequenceRequester([
        _provider([{
            "type": "function_call", "call_id": "pool-1",
            "name": "get_region_candidate_pool",
            "arguments": json.dumps({
                "regionName": "송파구", "limit": 40, "minimumUnitCount": None,
                "maximumBudgetTenThousandKrw": None,
                "exclusiveAreaSquareMeters": None,
            }),
        }], "resp-1"),
        _provider([{
            "type": "message", "content": [{
                "type": "output_text", "text": json.dumps({
                    "answer": "예산과 면적 조건 없이 균형 근거로 비교했습니다.",
                    "rows": [{
                        "complexId": 20, "complexName": "나단지", "role": "BALANCED",
                        "summary": "거래와 규모를 함께 확인했습니다.",
                        "strengths": [{"text": "규모가 확인됩니다.", "factIds": ["complex:20"]}],
                        "tradeoffs": [{"text": "예산 적합성은 별도 확인이 필요합니다.", "factIds": ["complex:20"]}],
                        "metrics": {}, "factIds": ["complex:20"],
                    }],
                    "factIds": ["complex:20"], "limitations": ["예산·면적 미지정"],
                }, ensure_ascii=False),
            }],
        }], "resp-2"),
    ])
    model = OpenAIResponsesAgentModel(
        settings=OpenAIResponsesSettings(
            api_key="test-key", model="test-model", timeout_seconds=7,
        ), requester=requester,
    )

    first = asyncio.run(model.respond(
        question="송파 아파트 추천", transcript=(), tools=TOOL_CATALOG,
        repair_error=None,
    ))
    second = asyncio.run(model.respond(
        question="송파 아파트 추천",
        transcript=({"type": "function_call_output", "call_id": "pool-1", "output": "{}"},),
        tools=TOOL_CATALOG, repair_error=None,
    ))

    assert first.tool_calls[0].name == "get_region_candidate_pool"
    assert second.decision is not None
    assert second.decision.rows[0].complex_id == 20
    first_body = json.loads(requester.calls[0])
    second_body = json.loads(requester.calls[1])
    assert first_body["store"] is False
    assert first_body["max_output_tokens"] == 2400
    assert first_body["parallel_tool_calls"] is True
    assert first_body["tools"] == list(TOOL_CATALOG)
    assert second_body["previous_response_id"] == "resp-1"
    assert second_body["input"] == [
        {"type": "function_call_output", "call_id": "pool-1", "output": "{}"}
    ]


def test_official_web_tool_is_exposed_only_when_enabled() -> None:
    requester = SequenceRequester([_provider([{
        "type": "function_call", "call_id": "pool-1", "name": "search_complexes",
        "arguments": '{"query":"잠실","limit":6}',
    }], "resp-web")])
    model = OpenAIResponsesAgentModel(
        settings=OpenAIResponsesSettings(api_key="key", model="model"),
        requester=requester, web_search_enabled=True,
    )

    asyncio.run(model.respond(
        question="최신 정비사업 공고", transcript=(), tools=TOOL_CATALOG,
        repair_error=None,
    ))

    body = json.loads(requester.calls[0])
    web_tool = next(tool for tool in body["tools"] if tool["type"] == "web_search_preview")
    assert web_tool["search_context_size"] == "medium"
    assert web_tool["user_location"]["country"] == "KR"
    assert "reb.or.kr" in web_tool["filters"]["allowed_domains"]
