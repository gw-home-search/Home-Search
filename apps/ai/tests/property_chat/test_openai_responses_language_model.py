from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping
from datetime import date

import pytest

from ai_service.models import ChatbotQueryRequest, ConversationContext, ConversationMessage
from ai_service.property_chat.models import EvidenceFact, FactClaim
from ai_service.property_chat import openai_responses
from ai_service.property_chat.openai_responses import (
    OpenAIResponsesError,
    OpenAIResponsesLanguageModel,
    OpenAIResponsesSettings,
)


class RecordingRequester:
    def __init__(self, response: bytes | Exception) -> None:
        self.response = response
        self.calls: list[tuple[str, Mapping[str, str], bytes, float]] = []

    def __call__(
        self,
        url: str,
        headers: Mapping[str, str],
        body: bytes,
        timeout_seconds: float,
    ) -> bytes:
        self.calls.append((url, headers, body, timeout_seconds))
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


def _response(output: object) -> bytes:
    return json.dumps(
        {
            "status": "completed",
            "output": [
                {
                    "type": "message",
                    "content": [
                        {
                            "type": "output_text",
                            "text": json.dumps(output, ensure_ascii=False),
                        }
                    ],
                }
            ],
        }
    ).encode()


def _model(requester: RecordingRequester, *, max_response_bytes: int = 262_144):
    return OpenAIResponsesLanguageModel(
        settings=OpenAIResponsesSettings(
            api_key="test-api-key",
            model="approved-test-model",
            timeout_seconds=7,
            max_response_bytes=max_response_bytes,
        ),
        requester=requester,
    )


def _valid_plan(**overrides: object) -> dict[str, object]:
    value: dict[str, object] = {
        "capability": "complex_identity",
        "complexName": "잠실엘스",
        "regionName": None,
        "startDate": None,
        "endDate": None,
        "exclusiveAreaSquareMeters": None,
        "limit": 5,
    }
    value.update(overrides)
    return value


def test_planning_uses_fixed_responses_endpoint_without_provider_storage() -> None:
    requester = RecordingRequester(
        _response(
            {
                "capability": "recent_trade_lookup",
                "complexName": "잠실엘스",
                "regionName": "서울 송파구",
                "startDate": "2026-01-01",
                "endDate": "2026-06-30",
                "exclusiveAreaSquareMeters": 84.9,
                "limit": 3,
            }
        )
    )
    model = _model(requester)
    request = ChatbotQueryRequest(
        question="잠실엘스 최근 거래 알려줘",
        conversationContext=ConversationContext(
            messages=[ConversationMessage(role="user", content="서울 송파구 기준")]
        ),
    )

    plan = asyncio.run(model.plan_query(request))

    assert plan.complex_name == "잠실엘스"
    assert plan.region_name == "서울 송파구"
    assert plan.start_date == date(2026, 1, 1)
    assert plan.limit == 3
    url, headers, raw_body, timeout = requester.calls[0]
    body = json.loads(raw_body)
    assert url == "https://api.openai.com/v1/responses"
    assert headers["Authorization"] == "Bearer test-api-key"
    assert timeout == 7
    assert body["model"] == "approved-test-model"
    assert body["store"] is False
    assert body["max_output_tokens"] == 500
    assert body["text"]["format"]["type"] == "json_schema"
    assert body["text"]["format"]["strict"] is True
    assert body["text"]["format"]["schema"]["additionalProperties"] is False
    plan_properties = body["text"]["format"]["schema"]["properties"]
    assert plan_properties["complexName"]["pattern"] == r"^.{1,100}$"
    assert plan_properties["exclusiveAreaSquareMeters"]["exclusiveMinimum"] == 0
    assert plan_properties["exclusiveAreaSquareMeters"]["maximum"] == 1000
    assert plan_properties["limit"]["minimum"] == 1
    assert plan_properties["limit"]["maximum"] == 10
    assert "previous_response_id" not in body
    developer_prompt = body["input"][0]["content"]
    assert "monthly or period aggregates" in developer_prompt
    assert "average, minimum, maximum, count, trend, or flow" in developer_prompt
    assert "latest individual trade records" in developer_prompt
    assert "Set limit to 5 when it is not used" in developer_prompt


def test_planning_accepts_school_location_with_explicit_levels_and_radius() -> None:
    requester = RecordingRequester(
        _response(
            {
                **_valid_plan(
                    capability="school_location",
                    schoolLevels=["ELEMENTARY", "MIDDLE"],
                    radiusMeters=800,
                )
            }
        )
    )

    plan = asyncio.run(
        _model(requester).plan_query(
            ChatbotQueryRequest(question="잠실엘스 800m 안 초등학교와 중학교")
        )
    )

    assert plan.capability == "school_location"
    assert plan.school_levels == ("ELEMENTARY", "MIDDLE")
    assert plan.radius_meters == 800
    schema = json.loads(requester.calls[0][2])["text"]["format"]["schema"]
    assert "school_location" in schema["properties"]["capability"]["enum"]
    assert schema["properties"]["radiusMeters"] == {
        "type": ["integer", "null"],
        "minimum": 0,
        "maximum": 10000000,
    }


def test_planning_accepts_retail_location_with_default_radius_and_subtypes() -> None:
    requester = RecordingRequester(
        _response(
            {
                **_valid_plan(
                    capability="retail_location",
                    facilitySubtypes=["LARGE_MART", "COMPLEX_MALL"],
                    radiusMeters=None,
                )
            }
        )
    )

    plan = asyncio.run(
        _model(requester).plan_query(
            ChatbotQueryRequest(question="잠실엘스 주변 대형마트와 복합쇼핑몰")
        )
    )

    assert plan.capability == "retail_location"
    assert plan.radius_meters == 1000
    assert plan.facility_subtypes == ("LARGE_MART", "COMPLEX_MALL")
    schema = json.loads(requester.calls[0][2])["text"]["format"]["schema"]
    assert "retail_location" in schema["properties"]["capability"]["enum"]


def test_planning_accepts_academy_registry_summary_without_location_semantics() -> None:
    requester = RecordingRequester(
        _response(_valid_plan(capability="academy_registry_summary"))
    )

    plan = asyncio.run(
        _model(requester).plan_query(
            ChatbotQueryRequest(question="잠실엘스 지역 공식 등록 학원 수")
        )
    )

    assert plan.capability == "academy_registry_summary"
    schema = json.loads(requester.calls[0][2])["text"]["format"]["schema"]
    assert "academy_registry_summary" in schema["properties"]["capability"]["enum"]
    prompt = json.loads(requester.calls[0][2])["input"][0]["content"]
    assert "Do not interpret it as a nearby, radius, distance" in prompt


def test_planning_accepts_academy_lookup_with_800_meter_default() -> None:
    requester = RecordingRequester(
        _response(
            _valid_plan(
                capability="academy_lookup",
                schoolLevels=["ELEMENTARY", "MIDDLE", "HIGH"],
                facilitySubtypes=[],
                radiusMeters=None,
            )
        )
    )

    plan = asyncio.run(
        _model(requester).plan_query(
            ChatbotQueryRequest(question="잠실엘스 주변 학원 위치")
        )
    )

    assert plan.capability == "academy_lookup"
    assert plan.radius_meters == 800
    schema = json.loads(requester.calls[0][2])["text"]["format"]["schema"]
    assert "academy_lookup" in schema["properties"]["capability"]["enum"]


def test_planning_accepts_rail_station_lookup_with_1500_meter_default() -> None:
    requester = RecordingRequester(
        _response(
            _valid_plan(
                capability="rail_station_lookup",
                schoolLevels=["ELEMENTARY", "MIDDLE", "HIGH"],
                facilitySubtypes=[],
                radiusMeters=None,
            )
        )
    )

    plan = asyncio.run(
        _model(requester).plan_query(
            ChatbotQueryRequest(question="잠실엘스 가까운 역과 노선")
        )
    )

    assert plan.capability == "rail_station_lookup"
    assert plan.radius_meters == 1500
    schema = json.loads(requester.calls[0][2])["text"]["format"]["schema"]
    assert "rail_station_lookup" in schema["properties"]["capability"]["enum"]
    assert "uniqueItems" not in schema["properties"]["schoolLevels"]
    assert "uniqueItems" not in schema["properties"]["facilitySubtypes"]
    prompt = json.loads(requester.calls[0][2])["input"][0]["content"]
    assert "Do not claim commute time, schedule, or congestion" in prompt


def test_planning_accepts_childcare_lookup_with_800_meter_default() -> None:
    requester = RecordingRequester(
        _response(
            _valid_plan(
                capability="childcare_lookup",
                schoolLevels=["ELEMENTARY", "MIDDLE", "HIGH"],
                facilitySubtypes=[],
                radiusMeters=None,
            )
        )
    )

    plan = asyncio.run(
        _model(requester).plan_query(
            ChatbotQueryRequest(question="잠실엘스 주변 어린이집")
        )
    )

    assert plan.capability == "childcare_lookup"
    assert plan.radius_meters == 800
    body = json.loads(requester.calls[0][2])
    assert "childcare_lookup" in body["text"]["format"]["schema"]["properties"][
        "capability"
    ]["enum"]
    assert "Do not claim current admission availability" in body["input"][0][
        "content"
    ]


def test_planning_accepts_kakao_hospital_map_action() -> None:
    requester = RecordingRequester(
        _response(
            _valid_plan(
                capability="kakao_place_search",
                schoolLevels=["ELEMENTARY", "MIDDLE", "HIGH"],
                facilitySubtypes=[],
                radiusMeters=None,
                placeCategory="HOSPITAL",
            )
        )
    )

    plan = asyncio.run(
        _model(requester).plan_query(
            ChatbotQueryRequest(question="잠실엘스 주변 병원을 지도에 보여줘")
        )
    )

    assert plan.capability == "kakao_place_search"
    assert plan.place_category == "HOSPITAL"
    body = json.loads(requester.calls[0][2])
    assert "kakao_place_search" in body["text"]["format"]["schema"]["properties"][
        "capability"
    ]["enum"]
    assert body["text"]["format"]["schema"]["properties"]["placeCategory"] == {
        "type": ["string", "null"],
        "enum": ["HOSPITAL", "DAYCARE_KINDERGARTEN", None],
    }
    assert "map search runs only after the user clicks" in body["input"][0][
        "content"
    ]


def test_draft_answer_serializes_only_supplied_evidence_and_parses_claims() -> None:
    requester = RecordingRequester(
        _response(
            {
                "sentences": [
                    {
                        "text": "거래 금액은 120000만원입니다.",
                        "factIds": ["property-trade-7"],
                        "claims": [
                            {
                                "factId": "property-trade-7",
                                "value": "120000",
                                "unit": "10_000_KRW",
                            }
                        ],
                    }
                ]
            }
        )
    )
    model = _model(requester)
    fact = EvidenceFact(
        fact_id="property-trade-7",
        claims=(FactClaim("120000", "10_000_KRW"),),
        data_as_of=date(2026, 6, 30),
        payload={"dealAmountTenThousandKrw": 120000},
    )

    draft = asyncio.run(
        model.draft_answer(
            facts=[fact],
            limitations=["신고 지연이 반영될 수 있습니다."],
            question="거래 금액은?",
        )
    )

    assert draft.sentences[0].fact_ids == ["property-trade-7"]
    assert draft.sentences[0].claims[0].unit == "10_000_KRW"
    request_body = json.loads(requester.calls[0][2])
    user_payload = json.loads(request_body["input"][1]["content"])
    assert user_payload["facts"] == [
        {
            "factId": "property-trade-7",
            "claims": [{"value": "120000", "unit": "10_000_KRW"}],
            "dataAsOf": "2026-06-30",
            "payload": {"dealAmountTenThousandKrw": 120000},
        }
    ]
    assert "subject" not in user_payload
    assert request_body["text"]["format"]["name"] == "grounded_property_answer"
    assert request_body["max_output_tokens"] == 3200
    sentence_schema = request_body["text"]["format"]["schema"]["properties"][
        "sentences"
    ]["items"]
    fact_ids_schema = sentence_schema["properties"]["factIds"]
    claims_schema = sentence_schema["properties"]["claims"]
    assert fact_ids_schema["minItems"] == 1
    assert fact_ids_schema["maxItems"] == 20
    assert fact_ids_schema["items"]["enum"] == ["property-trade-7"]
    assert claims_schema["minItems"] == 1
    assert claims_schema["maxItems"] == 50
    assert claims_schema["items"]["properties"]["factId"]["enum"] == [
        "property-trade-7"
    ]
    developer_prompt = request_body["input"][0]["content"]
    assert "Every number token in sentence text must exactly match" in developer_prompt
    assert "Do not state fact counts, list numbers, or converted units" in developer_prompt


def test_draft_schema_for_empty_facts_forbids_fact_references() -> None:
    requester = RecordingRequester(
        _response(
            {
                "sentences": [
                    {
                        "text": "검증된 근거 데이터가 없습니다.",
                        "factIds": [],
                        "claims": [],
                    }
                ]
            }
        )
    )
    model = _model(requester)

    asyncio.run(
        model.draft_answer(
            facts=[],
            limitations=["조건에 맞는 검증된 근거 데이터가 없습니다."],
            question="거래 내역은?",
        )
    )

    request_body = json.loads(requester.calls[0][2])
    sentence_schema = request_body["text"]["format"]["schema"]["properties"][
        "sentences"
    ]["items"]
    fact_ids_schema = sentence_schema["properties"]["factIds"]
    claims_schema = sentence_schema["properties"]["claims"]
    assert fact_ids_schema["maxItems"] == 0
    assert claims_schema["maxItems"] == 0


@pytest.mark.parametrize(
    ("response", "reason_code"),
    [
        (
            b'{"status":"incomplete","output":[]}',
            "PROVIDER_RESPONSE_INCOMPLETE",
        ),
        (
            b'{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"cannot comply"}]}]}',
            "PROVIDER_RESPONSE_REFUSED",
        ),
        (
            b'{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"not-json"}]}]}',
            "PROVIDER_RESPONSE_INVALID",
        ),
    ],
)
def test_incomplete_refusal_and_malformed_output_are_rejected_without_details(
    response: bytes,
    reason_code: str,
) -> None:
    model = _model(RecordingRequester(response))

    with pytest.raises(OpenAIResponsesError) as raised:
        asyncio.run(model.plan_query(ChatbotQueryRequest(question="잠실엘스 위치")))

    assert str(raised.value) == ""
    assert raised.value.reason_code == reason_code
    assert "cannot comply" not in repr(raised.value)


def test_transport_failure_and_oversized_response_do_not_expose_provider_data() -> None:
    failing = _model(RecordingRequester(RuntimeError("test-api-key provider body")))
    oversized = _model(RecordingRequester(b"x" * 129), max_response_bytes=128)

    for model in (failing, oversized):
        with pytest.raises(OpenAIResponsesError) as raised:
            asyncio.run(model.plan_query(ChatbotQueryRequest(question="잠실엘스 위치")))
        assert str(raised.value) == ""
        assert "test-api-key" not in repr(raised.value)


@pytest.mark.parametrize(
    "output",
    [
        _valid_plan(capability="unsupported"),
        _valid_plan(exclusiveAreaSquareMeters=True),
        _valid_plan(limit=True),
        _valid_plan(capability="price_trend"),
        {"unexpected": "field"},
    ],
)
def test_semantically_invalid_plans_are_rejected(output: object) -> None:
    model = _model(RecordingRequester(_response(output)))

    with pytest.raises(OpenAIResponsesError):
        asyncio.run(model.plan_query(ChatbotQueryRequest(question="잠실엘스 조회")))


@pytest.mark.parametrize(
    "output",
    [
        {"sentences": []},
        {"sentences": [{"text": "안내", "factIds": "bad", "claims": []}]},
        {"sentences": [{"text": "안내", "factIds": [], "claims": "bad"}]},
        {"sentences": [{"text": " 안내 ", "factIds": [], "claims": []}]},
    ],
)
def test_semantically_invalid_drafts_are_rejected(output: object) -> None:
    model = _model(RecordingRequester(_response(output)))

    with pytest.raises(OpenAIResponsesError):
        asyncio.run(model.draft_answer(facts=[], limitations=[], question="조회"))


def test_default_transport_posts_with_bounded_read(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw_response = _response(_valid_plan())
    captured: dict[str, object] = {}

    class FakeResponse:
        status = 200

        def read(self, size: int) -> bytes:
            captured["readSize"] = size
            return raw_response

    class FakeConnection:
        def __init__(self, host: str, port: int, timeout: float) -> None:
            captured["connection"] = (host, port, timeout)

        def request(
            self,
            method: str,
            path: str,
            *,
            body: bytes,
            headers: Mapping[str, str],
        ) -> None:
            captured["request"] = (method, path, body, headers)

        def getresponse(self) -> FakeResponse:
            return FakeResponse()

        def close(self) -> None:
            captured["closed"] = True

    monkeypatch.setattr(openai_responses, "HTTPSConnection", FakeConnection)
    model = OpenAIResponsesLanguageModel(
        settings=OpenAIResponsesSettings(
            api_key="test-api-key",
            model="approved-test-model",
        )
    )

    plan = asyncio.run(model.plan_query(ChatbotQueryRequest(question="잠실엘스 위치")))

    assert plan.capability == "complex_identity"
    assert captured["connection"] == ("api.openai.com", 443, 8.0)
    method, path, _body, headers = captured["request"]  # type: ignore[misc]
    assert method == "POST"
    assert path == "/v1/responses"
    assert headers["Authorization"] == "Bearer test-api-key"
    assert captured["readSize"] == 262_145
    assert captured["closed"] is True


def test_default_transport_rejects_redirect_without_reading_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    class RedirectResponse:
        status = 302

        def read(self, _size: int) -> bytes:
            raise AssertionError("redirect body must not be read")

    class RedirectConnection:
        def __init__(self, _host: str, _port: int, timeout: float) -> None:
            captured["timeout"] = timeout

        def request(self, *_args: object, **_kwargs: object) -> None:
            return None

        def getresponse(self) -> RedirectResponse:
            return RedirectResponse()

        def close(self) -> None:
            captured["closed"] = True

    monkeypatch.setattr(openai_responses, "HTTPSConnection", RedirectConnection)
    model = OpenAIResponsesLanguageModel(
        settings=OpenAIResponsesSettings(
            api_key="test-api-key",
            model="approved-test-model",
        )
    )

    with pytest.raises(OpenAIResponsesError):
        asyncio.run(model.plan_query(ChatbotQueryRequest(question="잠실엘스 위치")))

    assert captured == {"timeout": 8.0, "closed": True}


@pytest.mark.parametrize(
    ("status", "reason_code"),
    [
        (400, "PROVIDER_REQUEST_REJECTED"),
        (401, "PROVIDER_AUTHENTICATION_FAILED"),
        (403, "PROVIDER_ACCESS_DENIED"),
        (404, "PROVIDER_MODEL_UNAVAILABLE"),
        (429, "PROVIDER_RATE_LIMITED"),
        (500, "PROVIDER_SERVER_ERROR"),
    ],
)
def test_default_transport_preserves_only_safe_http_failure_category(
    monkeypatch: pytest.MonkeyPatch,
    status: int,
    reason_code: str,
) -> None:
    class FailureResponse:
        def __init__(self) -> None:
            self.status = status

        def read(self, _size: int) -> bytes:
            raise AssertionError("provider failure body must not be read")

    class FailureConnection:
        def __init__(self, *_args: object, **_kwargs: object) -> None:
            return None

        def request(self, *_args: object, **_kwargs: object) -> None:
            return None

        def getresponse(self) -> FailureResponse:
            return FailureResponse()

        def close(self) -> None:
            return None

    monkeypatch.setattr(openai_responses, "HTTPSConnection", FailureConnection)
    model = OpenAIResponsesLanguageModel(
        settings=OpenAIResponsesSettings(
            api_key="test-api-key",
            model="approved-test-model",
        )
    )

    with pytest.raises(OpenAIResponsesError) as raised:
        asyncio.run(model.plan_query(ChatbotQueryRequest(question="잠실엘스 위치")))

    assert raised.value.reason_code == reason_code
    assert str(raised.value) == ""


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("api_key", ""),
        ("model", ""),
        ("model", "bad\nmodel"),
        ("timeout_seconds", 0),
        ("max_response_bytes", 127),
    ],
)
def test_settings_reject_invalid_or_unsafe_values(field: str, value: object) -> None:
    kwargs: dict[str, object] = {
        "api_key": "test-api-key",
        "model": "approved-test-model",
        "timeout_seconds": 7,
        "max_response_bytes": 262_144,
    }
    kwargs[field] = value

    with pytest.raises(ValueError):
        OpenAIResponsesSettings(**kwargs)  # type: ignore[arg-type]


def test_provider_error_rejects_non_allowlisted_reason() -> None:
    with pytest.raises(ValueError):
        OpenAIResponsesError("provider body must not become a reason")
