#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
cleanup() { find "$tmp_dir" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

mkdir -p "$tmp_dir/repo/.github/scripts"
cp "$repo_root/.github/scripts/test-event-contracts.sh" "$tmp_dir/repo/.github/scripts/"
cp "$repo_root/.github/scripts/validate-event-contracts.py" "$tmp_dir/repo/.github/scripts/"
cp -R "$repo_root/contracts" "$tmp_dir/repo/contracts"

(
  cd "$tmp_dir/repo"
  git init -b main >/dev/null
  git config user.email contract-test@example.invalid
  git config user.name contract-test
  git add .
  git commit -m baseline >/dev/null

  python3 - <<'PY'
import json
from pathlib import Path

path = Path("contracts/events/schemas/DatasetActivated.schema.json")
schema = json.loads(path.read_text(encoding="utf-8"))
schema["properties"]["payload"]["properties"]["datasetType"]["enum"].remove("ACADEMY")
path.write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
  git add contracts
  git commit -m breaking-contract >/dev/null
  touch unrelated-evidence
  git add unrelated-evidence
  git commit -m unrelated-follow-up >/dev/null

  if EVENT_CONTRACT_BASE_REF=HEAD~2 .github/scripts/test-event-contracts.sh >/dev/null 2>&1; then
    echo "상태: Fail - merge-base 이후 앞선 commit의 breaking schema가 허용됐습니다." >&2
    exit 1
  fi
)

echo "상태: Pass - event contract는 merge-base 전체 변경과 호환됩니다."
