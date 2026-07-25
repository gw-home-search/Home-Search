#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow_dir="$repo_root/.github/workflows"
unpinned="$(
  rg --no-heading --line-number '^[[:space:]]*(?:-[[:space:]]*)?uses:[[:space:]]*[^./[:space:]][^[:space:]]*@[A-Za-z0-9._/-]+[[:space:]]*$' "$workflow_dir" || true
)"

if [[ -n "$unpinned" ]]; then
  printf 'External GitHub Actions must be pinned to a 40-character commit SHA:\n%s\n' "$unpinned" >&2
  exit 1
fi

invalid_sha="$(
  rg --no-heading --line-number '^[[:space:]]*(?:-[[:space:]]*)?uses:[[:space:]]*[^./[:space:]][^[:space:]]*@' "$workflow_dir" \
    | rg -v '@[0-9a-f]{40}(?:[[:space:]]*#.*)?$' || true
)"
if [[ -n "$invalid_sha" ]]; then
  printf 'External GitHub Actions contain an invalid pin:\n%s\n' "$invalid_sha" >&2
  exit 1
fi

echo "GitHub Actions SHA pinning: Pass"
