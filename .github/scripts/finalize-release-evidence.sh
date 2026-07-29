#!/usr/bin/env bash
set -euo pipefail

manifest="${1:?manifest path is required}"
sbom_dir="${2:?SBOM directory is required}"
vulnerability_dir="${3:?vulnerability directory is required}"

[[ -f "$manifest" && -d "$sbom_dir" && -d "$vulnerability_dir" ]]
[[ -f "$vulnerability_dir/summary.json" ]]

if ! diff -u \
  <(jq -r '(.images + (.platform_images // {})) | keys[]' "$manifest" | LC_ALL=C sort) \
  <(find "$sbom_dir" -maxdepth 1 -type f -name '*.spdx.json' -print \
      | sed -E 's#^.*/##; s#[.]spdx[.]json$##' | LC_ALL=C sort) >/dev/null; then
  echo "상태: Fail - image별 SBOM evidence가 완전하지 않습니다." >&2
  exit 1
fi
if ! diff -u \
  <(jq -r '(.images + (.platform_images // {})) | keys[]' "$manifest" | LC_ALL=C sort) \
  <(find "$vulnerability_dir" -maxdepth 1 -type f -name '*.json' ! -name 'summary.json' -print \
      | sed -E 's#^.*/##; s#[.]json$##' | LC_ALL=C sort) >/dev/null; then
  echo "상태: Fail - image별 vulnerability evidence가 완전하지 않습니다." >&2
  exit 1
fi

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

hash_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}

hash_evidence_set() {
  local root="$1"
  local count=0
  local file relative
  while IFS= read -r file; do
    relative="${file#"$root"/}"
    printf '%s  %s\n' "$(hash_file "$file")" "$relative"
    count=$((count + 1))
  done < <(find "$root" -type f -name '*.json' -print | LC_ALL=C sort)
  ((count > 0))
}

sbom_set_sha256="$(hash_evidence_set "$sbom_dir" | hash_stream)"
vulnerability_set_sha256="$(hash_evidence_set "$vulnerability_dir" | hash_stream)"
critical_gate_passed="$(
  jq -er '
    select(.scanner == "grype")
    | .critical_gate_passed
    | select(type == "boolean")
  ' "$vulnerability_dir/summary.json"
)"
[[ "$critical_gate_passed" == "true" ]]
policy_gate_passed="$(
  jq -er '
    .policy_gate_passed
    | select(type == "boolean")
  ' "$vulnerability_dir/summary.json"
)"
[[ "$policy_gate_passed" == "true" ]]

tmp="$(mktemp)"
cleanup() { unlink "$tmp" 2>/dev/null || true; }
trap cleanup EXIT

jq \
  --arg sbom_set_sha256 "$sbom_set_sha256" \
  --arg vulnerability_set_sha256 "$vulnerability_set_sha256" \
  --argjson critical_gate_passed "$critical_gate_passed" \
  --argjson policy_gate_passed "$policy_gate_passed" \
  '.sbom_set_sha256 = $sbom_set_sha256
   | .vulnerability_set_sha256 = $vulnerability_set_sha256
   | .vulnerability_critical_gate_passed = $critical_gate_passed
   | .vulnerability_policy_gate_passed = $policy_gate_passed' \
  "$manifest" >"$tmp"

jq -e '
  (.sbom_set_sha256 | test("^[0-9a-f]{64}$")) and
  (.vulnerability_set_sha256 | test("^[0-9a-f]{64}$")) and
  .vulnerability_critical_gate_passed == true and
  .vulnerability_policy_gate_passed == true
' "$tmp" >/dev/null

mv "$tmp" "$manifest"
