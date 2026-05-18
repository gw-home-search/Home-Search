#!/usr/bin/env bash
set -euo pipefail

manifest=".ko-docs.toml"
today="${KO_DOCS_TODAY:-$(date +%F)}"
fail=0

if [[ ! -f "$manifest" ]]; then
  printf 'missing KO docs manifest: %s\n' "$manifest" >&2
  exit 1
fi

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

ko_path_for() {
  local src="$1"
  local dir base stem
  dir="$(dirname "$src")"
  base="$(basename "$src" .md)"
  stem="${base}_KO.md"
  if [[ "$dir" == "." ]]; then
    printf '%s\n' "$stem"
  else
    printf '%s/%s\n' "$dir" "$stem"
  fi
}

entries_file="$(mktemp)"
sources_file="$(mktemp)"
trap 'rm -f "$entries_file" "$sources_file"' EXIT

awk '
  function emit() {
    if (source != "") {
      print source "\t" ko "\t" sha "\t" synced
    }
  }
  function value(line) {
    sub(/^[^=]+=[[:space:]]*"/, "", line)
    sub(/"[[:space:]]*$/, "", line)
    return line
  }
  /^\[\[docs\]\]/ {
    emit()
    source = ""; ko = ""; sha = ""; synced = ""
    next
  }
  /^[[:space:]]*source[[:space:]]*=/ { source = value($0); next }
  /^[[:space:]]*ko[[:space:]]*=/ { ko = value($0); next }
  /^[[:space:]]*source_sha256[[:space:]]*=/ { sha = value($0); next }
  /^[[:space:]]*last_synced[[:space:]]*=/ { synced = value($0); next }
  END { emit() }
' "$manifest" > "$entries_file"

rg --files --hidden -g '*.md' \
  -g '!.git/**' \
  -g '!ai-docs/**' \
  -g '!**/*_KO.md' \
  -g '!**/*_KO.local.md' \
  -g '!**/*_ko.md' \
  -g '!**/*_ko.local.md' \
  | sort > "$sources_file"

while IFS= read -r src; do
  expected_ko="$(ko_path_for "$src")"
  expected_sha="$(sha256_file "$src")"

  entry="$(
    awk -F '\t' -v src="$src" '
      $1 == src { print; found = 1; exit }
      END { if (!found) exit 1 }
    ' "$entries_file"
  )" || {
    printf 'missing manifest entry for %s\n' "$src" >&2
    fail=1
    continue
  }

  IFS=$'\t' read -r manifest_src ko_path manifest_sha synced_value <<< "$entry"

  if [[ "$ko_path" != "$expected_ko" ]]; then
    printf 'invalid KO path for %s: expected %s, got %s\n' "$src" "$expected_ko" "$ko_path" >&2
    fail=1
  fi

  if [[ ! -f "$ko_path" ]]; then
    printf 'missing KO doc: %s -> %s\n' "$src" "$ko_path" >&2
    fail=1
  fi

  if [[ "$manifest_sha" != "$expected_sha" ]]; then
    printf 'stale source_sha256 for %s\n' "$src" >&2
    fail=1
  fi

  if [[ ! "$synced_value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
    printf 'invalid last_synced for %s\n' "$src" >&2
    fail=1
  elif [[ "$synced_value" > "$today" ]]; then
    printf 'future last_synced for %s\n' "$src" >&2
    fail=1
  fi
done < "$sources_file"

while IFS=$'\t' read -r manifest_src ko_path manifest_sha synced_value; do
  if [[ -z "$manifest_src" ]]; then
    continue
  fi

  if ! grep -Fxq "$manifest_src" "$sources_file"; then
    printf 'manifest entry has no canonical source: %s\n' "$manifest_src" >&2
    fail=1
  fi
done < "$entries_file"

duplicates="$(awk -F '\t' '{ print $1 }' "$entries_file" | sort | uniq -d)"
if [[ -n "$duplicates" ]]; then
  printf 'duplicate manifest source entries:\n%s\n' "$duplicates" >&2
  fail=1
fi

if rg -n '@.*_KO\.md|@.*_ko\.md' AGENTS.md CLAUDE.md >/dev/null 2>&1; then
  printf 'agent docs must not import KO docs\n' >&2
  fail=1
fi

if [[ "$fail" -ne 0 ]]; then
  exit 1
fi

printf 'KO docs manifest is synchronized.\n'
