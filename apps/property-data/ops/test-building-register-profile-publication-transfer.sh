#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

publication_id="123e4567-e89b-42d3-a456-426614174399"
bundle="${temporary_directory}/building-profile-publication-${publication_id}"
mkdir "$bundle"

tables=(
  building_register_profile_publication
  building_register_profile_site
  building_register_profile_building
  building_register_profile_hierarchy
  building_register_profile_field_evidence
  complex_building_register_profile_summary
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
  printf 'publication_id\n' > "$file"
  printf '%s\t%s.csv\t0\t%s\t%s\n' "$table" "$table" "$(file_bytes "$file")" \
    "$(sha256_file "$file")" >> "$manifest"
done
printf '%s  manifest.tsv\n' "$(sha256_file "$manifest")" > "${bundle}/manifest.sha256"

grep -q "publication transfer contains a different publication_id" \
  "$script_directory/building-register-profile-publication-transfer.sh"
grep -q "target publication rows differ from transfer" \
  "$script_directory/building-register-profile-publication-transfer.sh"

PROFILE_PUBLICATION_ID="$publication_id" \
PROFILE_PUBLICATION_TRANSFER_DIRECTORY="$temporary_directory" \
  "$script_directory/building-register-profile-publication-transfer.sh" verify >/dev/null

printf 'tampered\n' >> "${bundle}/building_register_profile_site.csv"
if PROFILE_PUBLICATION_ID="$publication_id" \
  PROFILE_PUBLICATION_TRANSFER_DIRECTORY="$temporary_directory" \
  "$script_directory/building-register-profile-publication-transfer.sh" verify >/dev/null 2>&1; then
  echo "변조된 transfer 파일을 허용했습니다" >&2
  exit 1
fi

echo "building register profile publication transfer test: pass"
