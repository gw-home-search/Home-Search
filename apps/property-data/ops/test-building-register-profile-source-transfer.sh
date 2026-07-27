#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

collection_id="123e4567-e89b-42d3-a456-426614174390"
parse_run_id="123e4567-e89b-42d3-a456-426614174391"
analysis_run_id="123e4567-e89b-42d3-a456-426614174392"
bundle="${temporary_directory}/building-profile-source-${analysis_run_id}"
mkdir "$bundle"

tables=(
  building_register_profile_parse_page
  building_register_profile_record
  building_register_profile_value
  building_register_profile_schema_observation
  building_register_profile_hierarchy_reason
  building_register_profile_scope_assignment
  building_register_profile_complex_match
)

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

file_bytes() {
  if stat -f %z "$1" >/dev/null 2>&1; then
    stat -f %z "$1"
  else
    stat -c %s "$1"
  fi
}

manifest="${bundle}/manifest.tsv"
printf 'table\tfile\trows\tbytes\tsha256\n' > "$manifest"
for table in "${tables[@]}"; do
  file="${bundle}/${table}.csv"
  printf 'fixture_header\n' > "$file"
  printf '%s\t%s.csv\t0\t%s\t%s\n' "$table" "$table" "$(file_bytes "$file")" \
    "$(sha256_file "$file")" >> "$manifest"
done
printf '%s  manifest.tsv\n' "$(sha256_file "$manifest")" > "${bundle}/manifest.sha256"

grep -q "source transfer contains rows outside the frozen lineage" \
  "$script_directory/building-register-profile-source-transfer.sh"

PROFILE_COLLECTION_ID="$collection_id" \
PROFILE_PARSE_RUN_ID="$parse_run_id" \
PROFILE_ANALYSIS_RUN_ID="$analysis_run_id" \
PROFILE_SOURCE_TRANSFER_DIRECTORY="$temporary_directory" \
  "$script_directory/building-register-profile-source-transfer.sh" verify >/dev/null

printf 'tampered\n' >> "${bundle}/building_register_profile_value.csv"
if PROFILE_COLLECTION_ID="$collection_id" \
  PROFILE_PARSE_RUN_ID="$parse_run_id" \
  PROFILE_ANALYSIS_RUN_ID="$analysis_run_id" \
  PROFILE_SOURCE_TRANSFER_DIRECTORY="$temporary_directory" \
  "$script_directory/building-register-profile-source-transfer.sh" verify >/dev/null 2>&1; then
  echo "변조된 source transfer 파일을 허용했습니다" >&2
  exit 1
fi

echo "building register profile source transfer test: pass"
