#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sha256_stream() { if command -v sha256sum >/dev/null 2>&1; then sha256sum | awk '{print $1}'; else shasum -a 256 | awk '{print $1}'; fi; }
sha256_file() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }

result='{}'
while IFS=$'\t' read -r name path; do
  checksum="$(cd "${root}" && find "${path}" -type f -name '*.sql' | LC_ALL=C sort | while IFS= read -r file; do printf '%s\t%s\n' "${file}" "$(sha256_file "${file}")"; done | sha256_stream)"
  result="$(jq --arg name "${name}" --arg checksum "${checksum}" '. + {($name):$checksum}' <<<"${result}")"
done <<'PATHS'
property	apps/property-data/db/migration/api
admin	apps/admin/service/migration/src/main/resources/db/migration/admin
user	apps/user/service/db/migration/user
source_data	apps/source-data/src/main/resources/db/migration/coordinate-source
PATHS
jq . <<<"${result}"
