from __future__ import annotations

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


def test_query_maps_provider_failure_to_non_disclosing_problem_detail() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator

    response = TestClient(app).post(
        "/api/v1/chatbot/query",
        headers={"Authorization": "Bearer test-token"},
        json={"question": "잠실엘스 최근 거래 알려줘"},
    )

    assert response.status_code == 503
    assert response.json()["code"] == "CHATBOT_PROVIDER_UNAVAILABLE"
    assert "provider" not in response.json()["detail"].lower()


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
    assert response.json() == {"answer": "최근 거래 알려줘", "requestId": request_id}


def test_stream_emits_only_validated_final_event_after_success() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = SuccessfulEngine

    response = TestClient(app).post(
        "/api/v1/chatbot/query/stream",
        headers={"Authorization": "Bearer test-token"},
        json={"question": "최근 거래 알려줘"},
    )

    assert response.status_code == 200
    assert "event: final" in response.text
    assert '"answer":"최근 거래 알려줘"' in response.text
    assert "event: error" not in response.text


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

    assert json_response.status_code == 503
    assert json_response.json()["code"] == "CHATBOT_PROVIDER_UNAVAILABLE"
    assert "internal provider detail" not in json_response.text
    assert "event: error" in stream_response.text
    assert "event: final" not in stream_response.text


def test_stream_emits_explicit_error_event_without_final_after_start() -> None:
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = UnavailableEngine

    response = TestClient(app).post(
        "/api/v1/chatbot/query/stream",
        headers={"Authorization": "Bearer test-token"},
        json={"question": "잠실엘스 최근 거래 알려줘"},
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: error" in response.text
    assert '"code":"CHATBOT_PROVIDER_UNAVAILABLE"' in response.text
    assert "event: final" not in response.text


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
