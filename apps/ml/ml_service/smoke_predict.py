from __future__ import annotations

import json
import os
from pathlib import Path

from .f37_predictor import DEFAULT_ARTIFACT_DIR, F37Predictor


def assert_sample_prediction_quality(
    payload: dict[str, object],
    prediction: dict[str, object],
    metadata: dict[str, object],
) -> None:
    actual = float(payload["actual_price_per_m2"])
    predicted = float(prediction["predictedPricePerM2"])
    metrics = metadata.get("metrics")
    if not isinstance(metrics, list):
        raise ValueError("F37 metadata metrics are required for sample validation")
    recent_holdout = next(
        (metric for metric in metrics if isinstance(metric, dict) and metric.get("split") == "recent_holdout"),
        None,
    )
    if recent_holdout is None:
        raise ValueError("F37 recent_holdout metrics are required for sample validation")
    maximum_error = float(recent_holdout["abs_pct_error_p99"])
    relative_error = abs(predicted - actual) / actual
    if relative_error > maximum_error:
        raise RuntimeError(
            "F37 sample prediction exceeds the recent_holdout p99 error: "
            f"relative_error={relative_error:.6f}, maximum={maximum_error:.6f}"
        )


def main() -> int:
    artifact_dir = Path(os.environ.get("F37_ARTIFACT_DIR", str(DEFAULT_ARTIFACT_DIR)))
    sample_path = artifact_dir / "sample_input.json"
    payload = json.loads(sample_path.read_text(encoding="utf-8"))
    payload.setdefault("interval_pct", 0.188077)
    payload.setdefault("interval_basis", "recent_holdout_p95")
    predictor = F37Predictor(artifact_dir)
    prediction = predictor.predict_payload(payload)
    assert_sample_prediction_quality(payload, prediction, predictor.metadata)
    print(json.dumps(prediction, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
