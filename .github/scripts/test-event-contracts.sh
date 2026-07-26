#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
cleanup() { find "$tmp_dir" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

python3 "$repo_root/.github/scripts/validate-event-contracts.py" \
    "$repo_root/contracts/events"
baseline_ref="${EVENT_CONTRACT_BASE_REF:-HEAD^}"
if [[ "$baseline_ref" == "0000000000000000000000000000000000000000" ]]; then
    baseline_ref="HEAD^"
fi
if git -C "$repo_root" cat-file -e "${baseline_ref}:contracts/events/topics.json" 2>/dev/null; then
    git -C "$repo_root" archive "$baseline_ref" contracts/events | tar -x -C "$tmp_dir"
    python3 "$repo_root/.github/scripts/validate-event-contracts.py" \
        "$repo_root/contracts/events" \
        --baseline "$tmp_dir/contracts/events"
fi
python3 "$repo_root/.github/scripts/validate-event-contracts.py" --self-test

echo "상태: Pass - event schema, topic manifest, fixture, incompatible RED self-test를 확인했습니다."
