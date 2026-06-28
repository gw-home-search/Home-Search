from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

from fastapi import FastAPI
from pydantic import BaseModel, Field

from .f37_predictor import DEFAULT_ARTIFACT_DIR, F37Predictor


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


app = FastAPI(title="F37 Apartment Price Prediction Service")


@app.get("/health")
def health() -> dict[str, str]:
    predictor = get_predictor()
    return {"status": "ok", "modelVersion": predictor.model_version}


@app.post("/predict")
def predict(request: PredictionRequest) -> dict[str, Any]:
    payload = request.model_dump() if hasattr(request, "model_dump") else request.dict()
    return get_predictor().predict_payload(payload)
