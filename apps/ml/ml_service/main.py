from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from time import perf_counter
from typing import Any

from fastapi import FastAPI, Request
from pydantic import BaseModel, Field
from starlette.responses import PlainTextResponse

from .f37_predictor import DEFAULT_ARTIFACT_DIR, F37Predictor
from .operational_metrics import APPROVED_ROUTE_TEMPLATES, HTTP_METRICS


class PredictionRequest(BaseModel):
    numeric_features: dict[str, Any] = Field(default_factory=dict)
    embedding_features: dict[str, Any] = Field(default_factory=dict)
    base_log_value: float | None = None
    area_m2: float | None = None
    interval_pct: float | None = None
    interval_basis: str | None = None
    transaction_id: str | None = None


@lru_cache(maxsize=1)
def get_predictor() -> F37Predictor:
    artifact_dir = Path(os.environ.get("F37_ARTIFACT_DIR", str(DEFAULT_ARTIFACT_DIR)))
    return F37Predictor(artifact_dir)


app = FastAPI(
    title="F37 Apartment Price Prediction Service",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


@app.middleware("http")
async def record_http_metrics(request: Request, call_next: Any) -> Any:
    started = perf_counter()
    status_code = 500
    try:
        response = await call_next(request)
        status_code = response.status_code
        return response
    finally:
        route = getattr(request.scope.get("route"), "path", "unmatched")
        route_template = route if route in APPROVED_ROUTE_TEMPLATES else "unmatched"
        HTTP_METRICS.observe(route_template, status_code, perf_counter() - started)


@app.get("/health")
def health() -> dict[str, str]:
    predictor = get_predictor()
    return {"status": "ok", "modelVersion": predictor.model_version}


@app.get("/metrics", include_in_schema=False)
def metrics() -> PlainTextResponse:
    return PlainTextResponse(
        HTTP_METRICS.render(),
        media_type="text/plain; version=0.0.4",
    )


@app.post("/predict")
def predict(request: PredictionRequest) -> dict[str, Any]:
    payload = request.model_dump() if hasattr(request, "model_dump") else request.dict()
    return get_predictor().predict_payload(payload)
