from __future__ import annotations

import json
import os
from pathlib import Path

from .f37_predictor import DEFAULT_ARTIFACT_DIR, F37Predictor


def main() -> int:
    artifact_dir = Path(os.environ.get("F37_ARTIFACT_DIR", str(DEFAULT_ARTIFACT_DIR)))
    sample_path = artifact_dir / "sample_input.json"
    payload = json.loads(sample_path.read_text(encoding="utf-8"))
    payload.setdefault("interval_pct", 0.188077)
    payload.setdefault("interval_basis", "recent_holdout_p95")
    predictor = F37Predictor(artifact_dir)
    print(json.dumps(predictor.predict_payload(payload), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
