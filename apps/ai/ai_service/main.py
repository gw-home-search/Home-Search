from __future__ import annotations

import json
from collections.abc import AsyncIterator
from typing import Any
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse

from .auth import AuthenticatedUser, AuthenticationRequired, require_authenticated_user
from .chat import ChatbotEngine, ChatbotProviderUnavailable, get_chatbot_engine
from .models import ChatbotQueryRequest


app = FastAPI(title="Home Search AI", docs_url=None, redoc_url=None, openapi_url=None)


@app.middleware("http")
async def request_id_middleware(request: Request, call_next: Any):
    request_id = _request_id(request.headers.get("X-Request-Id"))
    request.state.request_id = request_id
    response = await call_next(request)
    response.headers["X-Request-Id"] = request_id
    return response


@app.exception_handler(AuthenticationRequired)
async def authentication_error(request: Request, _exception: AuthenticationRequired) -> JSONResponse:
    return _problem(
        request,
        401,
        "Authentication required",
        "로그인이 필요합니다.",
        "AUTHENTICATION_REQUIRED",
    )


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, _exception: RequestValidationError) -> JSONResponse:
    return _problem(
        request,
        400,
        "Invalid chatbot request",
        "질문 또는 대화 문맥 형식이 올바르지 않습니다.",
        "INVALID_CHATBOT_REQUEST",
    )


@app.exception_handler(ChatbotProviderUnavailable)
async def provider_error(request: Request, _exception: ChatbotProviderUnavailable) -> JSONResponse:
    return _problem(
        request,
        503,
        "Chatbot unavailable",
        "답변을 생성하지 못했습니다.",
        "CHATBOT_PROVIDER_UNAVAILABLE",
    )


@app.exception_handler(Exception)
async def unexpected_generation_error(request: Request, _exception: Exception) -> JSONResponse:
    return _problem(
        request,
        503,
        "Chatbot unavailable",
        "답변을 생성하지 못했습니다.",
        "CHATBOT_PROVIDER_UNAVAILABLE",
    )


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/v1/chatbot/query")
async def query(
    payload: ChatbotQueryRequest,
    request: Request,
    user: AuthenticatedUser = Depends(require_authenticated_user),
    engine: ChatbotEngine = Depends(get_chatbot_engine),
) -> dict[str, object]:
    return await engine.query(request=payload, user=user, request_id=request.state.request_id)


@app.post("/api/v1/chatbot/query/stream")
async def stream(
    payload: ChatbotQueryRequest,
    request: Request,
    user: AuthenticatedUser = Depends(require_authenticated_user),
    engine: ChatbotEngine = Depends(get_chatbot_engine),
) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            response = await engine.query(request=payload, user=user, request_id=request.state.request_id)
            yield _sse("final", {"requestId": request.state.request_id, "response": response})
        except ChatbotProviderUnavailable:
            yield _sse(
                "error",
                {
                    "requestId": request.state.request_id,
                    "code": "CHATBOT_PROVIDER_UNAVAILABLE",
                    "message": "답변을 생성하지 못했습니다.",
                },
            )
        except Exception:
            yield _sse(
                "error",
                {
                    "requestId": request.state.request_id,
                    "code": "CHATBOT_PROVIDER_UNAVAILABLE",
                    "message": "답변을 생성하지 못했습니다.",
                },
            )

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


def _problem(request: Request, status: int, title: str, detail: str, code: str) -> JSONResponse:
    request_id = getattr(request.state, "request_id", str(uuid4()))
    return JSONResponse(
        status_code=status,
        media_type="application/problem+json",
        content={
            "type": "about:blank",
            "title": title,
            "status": status,
            "detail": detail,
            "instance": request.url.path,
            "code": code,
            "requestId": request_id,
        },
    )


def _request_id(value: str | None) -> str:
    if value:
        try:
            parsed = UUID(value)
            if str(parsed) == value.lower():
                return str(parsed)
        except ValueError:
            pass
    return str(uuid4())


def _sse(event: str, data: dict[str, object]) -> bytes:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event}\ndata: {payload}\n\n".encode()
