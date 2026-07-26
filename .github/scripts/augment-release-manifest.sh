#!/usr/bin/env bash
set -euo pipefail

manifest="${1:?manifest path is required}"
repo_root="${2:?repository root is required}"
build_architecture="${3:?build architecture is required}"
workflow_run_id="${4:?workflow run id is required}"
market_news_enabled="${5:-false}"

[[ -f "$manifest" ]]
[[ "$build_architecture" =~ ^linux/(amd64|arm64)$ ]]
[[ "$workflow_run_id" =~ ^[0-9]+$ ]]
[[ "$market_news_enabled" == "true" || "$market_news_enabled" == "false" ]]

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

hash_tree() {
  local search_root="$1"
  local pattern="$2"
  local count=0
  local file relative
  while IFS= read -r file; do
    relative="${file#"$repo_root"/}"
    printf '%s  %s\n' "$(hash_file "$file")" "$relative"
    count=$((count + 1))
  done < <(find "$search_root" -type f -name "$pattern" -print | LC_ALL=C sort)
  ((count > 0))
}

event_schema_sha256="$(
  hash_tree "$repo_root/contracts/events/schemas" '*.schema.json' | hash_stream
)"
topic_manifest_sha256="$(hash_file "$repo_root/contracts/events/topics.json")"
flyway_migration_set_sha256="$(
  migration_roots=(
    "$repo_root/apps/property-data/db/migration"
    "$repo_root/apps/user/service/db/migration"
    "$repo_root/apps/admin/service/migration/src/main/resources/db/migration"
    "$repo_root/apps/source-data/src/main/resources/db/migration"
    "$repo_root/apps/ai/ai_service/datasets/migrations"
  )
  find "${migration_roots[@]}" -type f -name '*.sql' -print |
    LC_ALL=C sort | while IFS= read -r file; do
    relative="${file#"$repo_root"/}"
    printf '%s  %s\n' "$(hash_file "$file")" "$relative"
  done | hash_stream
)"

tmp="$(mktemp)"
cleanup() { unlink "$tmp" 2>/dev/null || true; }
trap cleanup EXIT

jq \
  --arg event_schema_sha256 "$event_schema_sha256" \
  --arg topic_manifest_sha256 "$topic_manifest_sha256" \
  --arg flyway_migration_set_sha256 "$flyway_migration_set_sha256" \
  --arg build_architecture "$build_architecture" \
  --arg workflow_run_id "$workflow_run_id" \
  --argjson market_news_enabled "$market_news_enabled" \
  '.format_version = 2
   | .event_schema_sha256 = $event_schema_sha256
   | .topic_manifest_sha256 = $topic_manifest_sha256
   | .flyway_migration_set_sha256 = $flyway_migration_set_sha256
   | .build_architecture = $build_architecture
   | .workflow_run_id = $workflow_run_id
   | .build_flags = {market_news_enabled:$market_news_enabled}' \
  "$manifest" >"$tmp"

jq -e '
  .format_version == 2 and
  (.event_schema_sha256 | test("^[0-9a-f]{64}$")) and
  (.topic_manifest_sha256 | test("^[0-9a-f]{64}$")) and
  (.flyway_migration_set_sha256 | test("^[0-9a-f]{64}$")) and
  (.build_architecture | test("^linux/(amd64|arm64)$")) and
  (.workflow_run_id | test("^[0-9]+$")) and
  (.build_flags.market_news_enabled | type == "boolean")
' "$tmp" >/dev/null

mv "$tmp" "$manifest"
