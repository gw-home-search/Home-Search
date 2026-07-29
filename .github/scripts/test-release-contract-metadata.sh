#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
cleanup() { find "$tmp_dir" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

printf '%s\n' \
  '{"format_version":1,"tag":"v1.2.3","commit_sha":"0123456789abcdef0123456789abcdef01234567","images":{"property-api":{"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}},"platform_images":{"budget-postgres":{"digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"budget-valkey":{"digest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}}}' \
  >"$tmp_dir/manifest.json"

"$repo_root/.github/scripts/augment-release-manifest.sh" \
  "$tmp_dir/manifest.json" "$repo_root" "linux/amd64" "12345" "true"

mkdir -p "$tmp_dir/sbom" "$tmp_dir/vulnerability"
printf '%s\n' '{"spdxVersion":"SPDX-2.3"}' >"$tmp_dir/sbom/property-api.spdx.json"
printf '%s\n' '{"spdxVersion":"SPDX-2.3"}' >"$tmp_dir/sbom/budget-postgres.spdx.json"
printf '%s\n' '{"spdxVersion":"SPDX-2.3"}' >"$tmp_dir/sbom/budget-valkey.spdx.json"
printf '%s\n' '{"matches":[]}' >"$tmp_dir/vulnerability/property-api.json"
printf '%s\n' '{"matches":[]}' >"$tmp_dir/vulnerability/budget-postgres.json"
printf '%s\n' '{"matches":[]}' >"$tmp_dir/vulnerability/budget-valkey.json"
printf '%s\n' '{"scanner":"grype","critical_gate_passed":true,"policy_gate_passed":true}' \
  >"$tmp_dir/vulnerability/summary.json"

"$repo_root/.github/scripts/finalize-release-evidence.sh" \
  "$tmp_dir/manifest.json" "$tmp_dir/sbom" "$tmp_dir/vulnerability"

jq -e '
  .format_version == 2 and
  .build_architecture == "linux/amd64" and
  .workflow_run_id == "12345" and
  .build_flags.market_news_enabled == true and
  (.event_schema_sha256 | test("^[0-9a-f]{64}$")) and
  (.topic_manifest_sha256 | test("^[0-9a-f]{64}$")) and
  (.flyway_migration_set_sha256 | test("^[0-9a-f]{64}$")) and
  (.sbom_set_sha256 | test("^[0-9a-f]{64}$")) and
  (.vulnerability_set_sha256 | test("^[0-9a-f]{64}$")) and
  .vulnerability_critical_gate_passed == true and
  .vulnerability_policy_gate_passed == true
' "$tmp_dir/manifest.json" >/dev/null

mkdir -p "$tmp_dir/incomplete-sbom" "$tmp_dir/incomplete-vulnerability"
printf '%s\n' '{"spdxVersion":"SPDX-2.3"}' >"$tmp_dir/incomplete-sbom/property-api.spdx.json"
printf '%s\n' '{"matches":[]}' >"$tmp_dir/incomplete-vulnerability/property-api.json"
printf '%s\n' '{"scanner":"grype","critical_gate_passed":true,"policy_gate_passed":true}' \
  >"$tmp_dir/incomplete-vulnerability/summary.json"
if "$repo_root/.github/scripts/finalize-release-evidence.sh" \
  "$tmp_dir/manifest.json" "$tmp_dir/incomplete-sbom" "$tmp_dir/incomplete-vulnerability" \
  >/dev/null 2>&1; then
  echo "상태: Fail - image evidence 누락이 허용됐습니다." >&2
  exit 1
fi

echo "상태: Pass - release manifest contract/topic/migration metadata를 확인했습니다."
