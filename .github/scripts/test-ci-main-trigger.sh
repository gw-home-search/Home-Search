#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/ci.yml"

python3 - "$workflow" <<'PY'
from pathlib import Path
import sys

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
required_fragments = (
    "  push:\n    branches: [main]\n",
    "          BEFORE_SHA: ${{ github.event.before }}\n",
    '          elif [[ "$EVENT_NAME" == "push" && "$BEFORE_SHA" != "0000000000000000000000000000000000000000" ]]; then\n',
)
missing = [fragment for fragment in required_fragments if fragment not in workflow]
if missing:
    raise SystemExit("상태: Fail - main push의 exact commit 변경 분류 계약이 없습니다.")
PY

echo "상태: Pass - main push CI와 exact commit 변경 분류 계약을 확인했습니다."
