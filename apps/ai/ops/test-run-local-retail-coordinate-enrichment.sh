#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
runner="${script_dir}/run-local-retail-coordinate-enrichment.sh"
tmp_dir="$(mktemp -d)"

cleanup() {
    find "$tmp_dir" -type f -delete 2>/dev/null || true
    find "$tmp_dir" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$tmp_dir/bin"
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'if [[ "${1:-}" == inspect ]]; then echo home-search-test-network; exit 0; fi' \
    'if [[ "${1:-}" == build ]]; then echo build >>"$RETAIL_ENRICHMENT_TEST_LOG"; exit 0; fi' \
    'if [[ "${1:-}" == run ]]; then' \
    '  printf "run:%s|importer=%s|property=%s|coordinate=%s\n" "$*" "${HOME_AI_IMPORTER_DSN:+yes}" "${HOME_AI_PROPERTY_DSN:+yes}" "${HOME_COORDINATE_SOURCE_READER_DSN:+yes}" >>"$RETAIL_ENRICHMENT_TEST_LOG"' \
    '  if [[ "$*" == *home-ai-retail-coordinate-enrichment* ]]; then printf "%s\n" "상태: Pass" "matchedCount: 211" "coordinateCoverage: 0.887931"; fi' \
    '  exit 0' \
    'fi' \
    'exit 1' >"$tmp_dir/bin/docker"
chmod +x "$tmp_dir/bin/docker"

printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "bootstrap\n" >>"$RETAIL_ENRICHMENT_TEST_LOG"' \
    >"$tmp_dir/bootstrap"
chmod +x "$tmp_dir/bootstrap"

printf '%s\n' \
    'AI_DATA_MIGRATOR_DB_PASSWORD=migrator-test-secret' \
    'AI_DATA_IMPORTER_DB_PASSWORD=importer-test-secret' \
    'AI_DATA_RUNTIME_DB_PASSWORD=runtime-test-secret' \
    'AI_PROPERTY_READER_DB_PASSWORD=property-reader-test-secret' \
    'COORDINATE_SOURCE_DB_PASSWORD=coordinate-reader-test-secret' \
    >"$tmp_dir/property.env"
chmod 600 "$tmp_dir/property.env"

output="$(
    PATH="$tmp_dir/bin:$PATH" \
    HOME_AI_DATABASE_BOOTSTRAP="$tmp_dir/bootstrap" \
    RETAIL_ENRICHMENT_TEST_LOG="$tmp_dir/docker.log" \
    "$runner" "$tmp_dir/property.env"
)"

grep -Fq '상태: Pass' <<<"$output"
grep -Fq 'matchedCount: 211' <<<"$output"
grep -Fq 'coordinateCoverage: 0.887931' <<<"$output"
grep -Fqx 'bootstrap' "$tmp_dir/docker.log"
grep -Fq 'home-ai-migrate' "$tmp_dir/docker.log"
grep -Fq 'home-ai-retail-coordinate-enrichment' "$tmp_dir/docker.log"
grep -Fq 'importer=yes|property=yes|coordinate=yes' "$tmp_dir/docker.log"
if grep -Eq 'test-secret' <<<"$output"; then
    echo "상태: Fail - 출력에 비밀값이 포함됐습니다." >&2
    exit 1
fi

echo "상태: Pass - retail 좌표 보완 runner 경계와 비밀값 비노출을 확인했습니다."
