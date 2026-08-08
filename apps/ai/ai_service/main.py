from __future__ import annotations

import json
import logging
from contextlib import asynccontextmanager
from collections.abc import AsyncIterator
from typing import Any
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response, StreamingResponse

from .auth import AuthenticatedUser, AuthenticationRequired, require_authenticated_user
from .chat import (
    ChatbotEngine,
    ChatbotProviderUnavailable,
    get_chatbot_engine,
    get_supervisor_graph_canary_percent,
    get_supervisor_graph_mode,
)
from .models import ChatbotQueryRequest
from .operational_metrics import SUPERVISOR_METRICS
from .readiness import ReadinessChecker, get_readiness_checker
from .terminal_response import safe_final_response, with_terminal_outcome


_LOGGER = logging.getLogger(__name__)


@asynccontextmanager
async def _lifespan(_app: FastAPI) -> AsyncIterator[None]:
    get_supervisor_graph_mode()
    get_supervisor_graph_canary_percent()
    yield


app = FastAPI(
    title="Home Search AI",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=_lifespan,
)


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


@app.get("/ready")
async def ready(
    checker: ReadinessChecker = Depends(get_readiness_checker),
) -> JSONResponse:
    result = await checker.check()
    status_code = 503 if result.status == "NOT_READY" else 200
    return JSONResponse(
        status_code=status_code,
        content={"status": result.status, "checks": result.checks},
    )


@app.get("/metrics", include_in_schema=False)
async def metrics() -> Response:
    return Response(
        content=SUPERVISOR_METRICS.render(),
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )


@app.post("/api/v1/chatbot/query")
async def query(
    payload: ChatbotQueryRequest,
    request: Request,
    user: AuthenticatedUser = Depends(require_authenticated_user),
    engine: ChatbotEngine = Depends(get_chatbot_engine),
) -> dict[str, object]:
    try:
        response = await engine.query(
            request=payload, user=user, request_id=request.state.request_id
        )
        return with_terminal_outcome(response)
    except Exception:
        _LOGGER.warning(
            "ai_safe_final",
            extra={"terminal_status": "UNAVAILABLE", "terminal_reason": "TEMPORARY_FAILURE"},
        )
        return safe_final_response(request.state.request_id)


@app.post("/api/v1/chatbot/query/stream")
async def stream(
    payload: ChatbotQueryRequest,
    request: Request,
    user: AuthenticatedUser = Depends(require_authenticated_user),
    engine: ChatbotEngine = Depends(get_chatbot_engine),
) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            yield _status_sse(request.state.request_id, "QUESTION_INTERPRETATION", "질문 해석")
            yield _status_sse(request.state.request_id, "CANDIDATE_CHECK", "후보 확인")
            yield _status_sse(request.state.request_id, "EVIDENCE_COMPARISON", "근거 비교")
            response = with_terminal_outcome(await engine.query(
                request=payload, user=user, request_id=request.state.request_id
            ))
            execution = response.get("agentExecution")
            if isinstance(execution, dict) and execution.get("webUsed") is True:
                yield _status_sse(
                    request.state.request_id, "OFFICIAL_SOURCE_CHECK", "공식 자료 확인"
                )
            yield _status_sse(request.state.request_id, "ANSWER_VALIDATION", "답변 검증")
            yield _sse("final", {"requestId": request.state.request_id, "response": response})
        except Exception:
            _LOGGER.warning(
                "ai_safe_final",
                extra={"terminal_status": "UNAVAILABLE", "terminal_reason": "TEMPORARY_FAILURE"},
            )
            response = safe_final_response(request.state.request_id)
            yield _sse("final", {"requestId": request.state.request_id, "response": response})

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


def _status_sse(request_id: str, code: str, message: str) -> bytes:
    return _sse("status", {"requestId": request_id, "code": code, "message": message})


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
