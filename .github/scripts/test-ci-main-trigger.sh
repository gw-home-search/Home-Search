#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/ci.yml"
eligibility="$repo_root/.github/scripts/verify-release-eligibility.sh"

python3 - "$workflow" "$eligibility" <<'PY'
from pathlib import Path
import re
import sys

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
eligibility = Path(sys.argv[2]).read_text(encoding="utf-8")
required_fragments = (
    "  push:\n    branches: [main]\n",
    "          BEFORE_SHA: ${{ github.event.before }}\n",
    '          elif [[ "$EVENT_NAME" == "push" && "$BEFORE_SHA" != "0000000000000000000000000000000000000000" ]]; then\n',
)
missing = [fragment for fragment in required_fragments if fragment not in workflow]
if missing:
    raise SystemExit("상태: Fail - main push의 exact commit 변경 분류 계약이 없습니다.")

required_match = re.search(r"required_checks=\(\n(?P<body>.*?)\n\)", eligibility, re.DOTALL)
if required_match is None:
    raise SystemExit("상태: Fail - release eligibility 필수 check 목록을 읽을 수 없습니다.")
required_checks = required_match.group("body").split()
jobs_text = workflow.split("\njobs:\n", 1)[1]
not_forced_on_main = []
for check_name in required_checks:
    job_match = re.search(
        rf"^  {re.escape(check_name)}:\n(?P<body>.*?)(?=^  [a-z0-9][a-z0-9-]*:\n|\Z)",
        jobs_text,
        re.MULTILINE | re.DOTALL,
    )
    if job_match is None:
        not_forced_on_main.append(f"{check_name}=missing")
        continue
    job_header = job_match.group("body").split("\n    steps:", 1)[0]
    if "\n    if:" in job_header and "github.event_name == 'push'" not in job_header:
        not_forced_on_main.append(f"{check_name}=conditional")
if not_forced_on_main:
    raise SystemExit(
        "상태: Fail - release 필수 check가 main push에서 강제 실행되지 않습니다: "
        + " ".join(not_forced_on_main)
    )
PY

echo "상태: Pass - main push CI와 exact commit 변경 분류 계약을 확인했습니다."
