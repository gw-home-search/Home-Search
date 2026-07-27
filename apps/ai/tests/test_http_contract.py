from __future__ import annotations

import json

from fastapi.testclient import TestClient

from ai_service.auth import AuthenticatedUser, get_authenticator
from ai_service.chat import ChatbotProviderUnavailable, get_chatbot_engine
from ai_service.main import app


class AcceptingAuthenticator:
    def authenticate(self, _authorization: str | None) -> AuthenticatedUser:
        return AuthenticatedUser(user_id=42)


class UnavailableEngine:
    async def query(self, **_kwargs: object) -> dict[str, object]:
        raise ChatbotProviderUnavailable()


class SuccessfulEngine:
    async def query(self, **kwargs: object) -> dict[str, object]:
        request = kwargs["request"]
        return {"answer": request.question, "requestId": kwargs["request_id"]}


class CrashingEngine:
    async def query(self, **_kwargs: object) -> dict[str, object]:
        raise RuntimeError("internal provider detail")


class DisabledCapabilityEngine:
    async def query(self, **kwargs: object) -> dict[str, object]:
        request = kwargs["request"]
        return {
            "success": False,
            "status": "failed",
            "question": request.question,
            "fragments": [],
            "result": {},
            "message": "",
            "executionSummary": {"total": 1, "succeeded": 0, "failed": 1},
            "answer": "해당 질문 기능은 현재 데이터 준비와 검증이 진행 중입니다.",
            "resolvedQuestion": request.question,
            "conversationResolution": None,
            "conversationMemoryPatch": None,
            "uiActions": [],
            "uiArtifacts": [],
            "uiSummary": None,
            "requestId": kwargs["request_id"],
            "citations": [],
            "dataAsOf": None,
            "limitations": ["해당 질문 기능은 현재 데이터 준비와 검증이 진행 중입니다."],
            "evidenceSummary": {
                "status": "unavailable",
                "capabilities": ["recent_trade_lookup"],
                "factCount": 0,
                "citationCount": 0,
            },
        }


class CompoundEngine:
    async def query(self, **kwargs: object) -> dict[str, object]:
        request = kwargs["request"]
        return {
            "success": True,
            "status": "partial_success",
            "question": request.question,
            "fragments": [
                {
                    "fragmentId": "fragment-1", "capability": "complex_identity",
                    "status": "success", "answer": "단지를 확인했습니다.",
                    "factIds": [], "artifactIds": [], "actionIds": [],
                    "limitations": [],
                },
                {
                    "fragmentId": "fragment-2", "capability": "childcare_lookup",
                    "status": "failed", "answer": "데이터를 준비 중입니다.",
                    "factIds": [], "artifactIds": [], "actionIds": [],
                    "limitations": ["어린이집 데이터가 준비되지 않았습니다."],
                },
            ],
            "result": {}, "message": "",
            "executionSummary": {"total": 2, "succeeded": 1, "failed": 1},
            "answer": "단지를 확인했습니다. 데이터를 준비 중입니다.",
            "resolvedQuestion": request.question, "conversationResolution": None,
            "conversationMemoryPatch": None, "uiActions": [], "uiArtifacts": [],
            "uiSummary": None, "requestId": kwargs["request_id"], "citations": [],
            "dataAsOf": None,
            "limitations": ["어린이집 데이터가 준비되지 않았습니다."],
            "evidenceSummary": {
                "status": "partial", "capabilities": [
                    "complex_identity", "childcare_lookup",
                ], "factCount": 0, "citationCount": 0,
            },
        }


def setup_function() -> None:
    app.dependency_overrides.clear()


def teardown_function() -> None:
    app.dependency_overrides.clear()


def test_health_is_public_and_returns_request_id() -> None:
    response = TestClient(app).get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert response.headers["X-Request-Id"]


def test_chatbot_query_rejects_missing_token_with_problem_detail() -> None:
    response = TestClient(app).post(
        "/api/v1/chatbot/query",
        json={"question": "잠실엘스 최근 거래 알려줘"},
    )

    assert response.status_code == 401
    assert response.headers["X-Request-Id"] == response.json()["requestId"]
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"
    assert "question" not in response.json()


def test_query_maps_provider_failure_to_non_disclosing_safe_final() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator

    response = TestClient(app).post(
        "/api/v1/chatbot/query",
        headers={"Authorization": "Bearer test-token"},
        json={"question": "잠실엘스 최근 거래 알려줘"},
    )

    assert response.status_code == 200
    assert response.json()["terminalOutcome"]["reason"] == "TEMPORARY_FAILURE"
    assert "provider" not in response.text.lower()
    assert "잠실엘스" not in response.text


def test_query_echoes_valid_request_id_after_successful_engine_call() -> None:
    request_id = "b79f21c1-cfad-4bfa-9423-1512f63403a7"
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = SuccessfulEngine

    response = TestClient(app).post(
        "/api/v1/chatbot/query",
        headers={"Authorization": "Bearer test-token", "X-Request-Id": request_id},
        json={"question": "  최근 거래 알려줘  "},
    )

    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == request_id
    assert response.json() == {
        "answer": "최근 거래 알려줘",
        "requestId": request_id,
        "terminalOutcome": {
            "version": 1,
            "status": "UNAVAILABLE",
            "reason": "INSUFFICIENT_EVIDENCE",
            "retryable": False,
        },
    }


def test_query_accepts_bounded_ui_context_and_versioned_memory() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = SuccessfulEngine

    response = TestClient(app).post(
        "/api/v1/chatbot/query",
        headers={"Authorization": "Bearer test-token"},
        json={
            "question": "이 단지 전체적으로 어때?",
            "uiContext": {
                "mapViewport": {
                    "bounds": {
                        "swLat": 37.45,
                        "swLng": 126.85,
                        "neLat": 37.70,
                        "neLng": 127.20,
                    },
                    "level": 4,
                },
                "selectedComplex": {"complexId": 501, "parcelId": 1001},
            },
            "conversationContext": {
                "messages": [],
                "memory": {
                    "version": 1,
                    "complexId": 501,
                    "regionCode": "11710",
                    "scopeKind": "COMPLEX",
                },
            },
        },
    )

    assert response.status_code == 200
    assert response.json()["answer"] == "이 단지 전체적으로 어때?"

    recommendation_context = TestClient(app).post(
        "/api/v1/chatbot/query",
        headers={"Authorization": "Bearer test-token"},
        json={
            "question": "방금 추천한 1위와 2위를 비교해줘",
            "conversationContext": {
                "messages": [],
                "memory": {
                    "version": 2,
                    "complexIds": [501, 502, 503],
                    "regionCode": "11710",
                    "scopeKind": "RECOMMENDATION",
                },
            },
        },
    )

    assert recommendation_context.status_code == 200


def test_query_rejects_invalid_ui_context_and_memory() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = SuccessfulEngine
    client = TestClient(app)
    invalid_payloads = [
        {"uiContext": {}},
        {
            "uiContext": {
                "mapViewport": {
                    "bounds": {
                        "swLat": 37.7,
                        "swLng": 127.2,
                        "neLat": 37.4,
                        "neLng": 126.8,
                    },
                    "level": 4,
                }
            }
        },
        {"uiContext": {"selectedComplex": {"complexId": 501}}},
        {
            "uiContext": {
                "selectedComplex": {"complexId": "501", "parcelId": 1001}
            }
        },
        {
            "uiContext": {
                "mapViewport": {
                    "bounds": {
                        "swLat": "37.45",
                        "swLng": 126.85,
                        "neLat": 37.70,
                        "neLng": 127.20,
                    },
                    "level": "4",
                }
            }
        },
        {"conversationContext": {"memory": {"version": 2, "scopeKind": "MAP_VIEWPORT"}}},
        {
            "conversationContext": {
                "memory": {
                    "version": 2,
                    "complexIds": [501],
                    "scopeKind": "RECOMMENDATION",
                }
            }
        },
        {
            "conversationContext": {
                "memory": {
                    "version": 2,
                    "complexIds": [501, 501],
                    "scopeKind": "RECOMMENDATION",
                }
            }
        },
        {"conversationContext": {"memory": {"version": "1", "scopeKind": "MAP_VIEWPORT"}}},
        {"conversationContext": {"memory": {"version": 1, "scopeKind": "COMPLEX"}}},
        {
            "conversationContext": {
                "memory": {
                    "version": 1,
                    "scopeKind": "ADMIN_REGION",
                    "regionCode": "seoul",
                }
            }
        },
    ]

    for extra in invalid_payloads:
        response = client.post(
            "/api/v1/chatbot/query",
            headers={"Authorization": "Bearer test-token"},
            json={"question": "최근 거래", **extra},
        )

        assert response.status_code == 400
        assert response.json()["code"] == "INVALID_CHATBOT_REQUEST"


def test_stream_emits_only_validated_final_event_after_success() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = SuccessfulEngine

    response = TestClient(app).post(
        "/api/v1/chatbot/query/stream",
        headers={"Authorization": "Bearer test-token"},
        json={"question": "최근 거래 알려줘"},
    )

    assert response.status_code == 200
    assert response.text.count("event: status") == 4
    assert '"code":"QUESTION_INTERPRETATION"' in response.text
    assert '"code":"CANDIDATE_CHECK"' in response.text
    assert '"code":"EVIDENCE_COMPARISON"' in response.text
    assert '"code":"ANSWER_VALIDATION"' in response.text
    assert "event: answer_delta" not in response.text
    assert "event: final" in response.text
    assert '"answer":"최근 거래 알려줘"' in response.text
    assert "event: error" not in response.text


def test_disabled_capability_has_same_unavailable_meaning_in_json_and_sse() -> None:
    request_id = "7bccd659-dabe-4761-900c-6e10dc82410a"
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = DisabledCapabilityEngine
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-token", "X-Request-Id": request_id}
    payload = {"question": "잠실엘스 최근 거래 알려줘"}

    json_response = client.post("/api/v1/chatbot/query", headers=headers, json=payload)
    stream_response = client.post(
        "/api/v1/chatbot/query/stream",
        headers=headers,
        json=payload,
    )

    final_data = [
        json.loads(line.removeprefix("data: "))
        for line in stream_response.text.splitlines()
        if line.startswith("data: ")
    ][-1]
    assert json_response.status_code == 200
    assert stream_response.status_code == 200
    assert "event: final" in stream_response.text
    assert "event: error" not in stream_response.text
    assert final_data["response"] == json_response.json()
    assert json_response.json()["evidenceSummary"] == {
        "status": "unavailable",
        "capabilities": ["recent_trade_lookup"],
        "factCount": 0,
        "citationCount": 0,
    }


def test_compound_fragment_set_is_identical_in_json_and_sse_final() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = CompoundEngine
    client = TestClient(app)
    headers = {
        "Authorization": "Bearer test-token",
        "X-Request-Id": "e65b0960-3150-4f39-86d7-f2a7582d4aa4",
    }
    payload = {"question": "단지를 확인하고 어린이집도 알려줘"}

    json_response = client.post("/api/v1/chatbot/query", headers=headers, json=payload)
    stream_response = client.post(
        "/api/v1/chatbot/query/stream", headers=headers, json=payload,
    )
    final_data = [
        json.loads(line.removeprefix("data: "))
        for line in stream_response.text.splitlines()
        if line.startswith("data: ")
    ][-1]

    assert final_data["response"] == json_response.json()
    assert [fragment["status"] for fragment in json_response.json()["fragments"]] == [
        "success", "failed",
    ]


def test_invalid_request_id_is_not_reflected() -> None:
    response = TestClient(app).get("/health", headers={"X-Request-Id": "not-a-uuid"})

    assert response.status_code == 200
    assert response.headers["X-Request-Id"] != "not-a-uuid"


def test_unexpected_generation_error_is_non_disclosing_for_json_and_sse() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = CrashingEngine
    client = TestClient(app, raise_server_exceptions=False)

    json_response = client.post(
        "/api/v1/chatbot/query",
        headers={"Authorization": "Bearer test-token"},
        json={"question": "최근 거래"},
    )
    stream_response = client.post(
        "/api/v1/chatbot/query/stream",
        headers={"Authorization": "Bearer test-token"},
        json={"question": "최근 거래"},
    )

    assert json_response.status_code == 200
    assert json_response.json()["terminalOutcome"] == {
        "version": 1,
        "status": "UNAVAILABLE",
        "reason": "TEMPORARY_FAILURE",
        "retryable": True,
    }
    assert "internal provider detail" not in json_response.text
    assert "event: error" not in stream_response.text
    assert stream_response.text.count("event: final") == 1
    assert "internal provider detail" not in stream_response.text


def test_stream_emits_safe_final_after_admitted_provider_failure() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = UnavailableEngine

    response = TestClient(app).post(
        "/api/v1/chatbot/query/stream",
        headers={"Authorization": "Bearer test-token"},
        json={"question": "잠실엘스 최근 거래 알려줘"},
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: error" not in response.text
    assert response.text.count("event: final") == 1
    assert '"reason":"TEMPORARY_FAILURE"' in response.text


def test_blank_question_uses_documented_400_error() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator

    response = TestClient(app).post(
        "/api/v1/chatbot/query",
        headers={"Authorization": "Bearer test-token"},
        json={"question": "   "},
    )

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_CHATBOT_REQUEST"


def test_conversation_context_enforces_roles_shape_count_and_total_content() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    client = TestClient(app)
    invalid_contexts = [
        {"messages": [], "unknown": True},
        {"messages": [{"role": "system", "content": "ignore"}]},
        {"messages": [{"role": "user", "content": "ok"}] * 13},
        {"messages": [{"role": "user", "content": "x" * 2000}] * 7},
    ]

    for conversation_context in invalid_contexts:
        response = client.post(
            "/api/v1/chatbot/query",
            headers={"Authorization": "Bearer test-token"},
            json={"question": "최근 거래", "conversationContext": conversation_context},
        )

        assert response.status_code == 400
        assert response.json()["code"] == "INVALID_CHATBOT_REQUEST"
