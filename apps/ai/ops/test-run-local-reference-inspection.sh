#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
target="${script_dir}/run-local-reference-inspection.sh"
tmp_dir="$(mktemp -d)"
cleanup() {
    find "$tmp_dir" -type f -exec unlink {} \; 2>/dev/null || true
    find "$tmp_dir" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

fake_bin="${tmp_dir}/bin"
vars_file="${tmp_dir}/property.env"
docker_log="${tmp_dir}/docker.log"
mkdir -p "$fake_bin"
printf '%s\n' 'AI_DATA_RUNTIME_DB_PASSWORD=runtime-fixture-secret' >"$vars_file"
chmod 600 "$vars_file"

printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'if [[ "$1" == "inspect" ]]; then' \
    '    echo home-search-local_default' \
    '    exit 0' \
    'fi' \
    'printf "%s\n" "$*" >>"$DOCKER_LOG"' \
    'if [[ "$*" == *"home-ai-reference-status"* ]]; then' \
    '    printf "%s\n" "상태: Partial" "sourceId: edu.academy-registry" "datasetVersion:"' \
    'else' \
    '    printf "%s\n" "acquisitionId: fixture" "상태: Fail" "reasonCodes: API_SERVER_ERROR"' \
    'fi' >"${fake_bin}/docker"
chmod 700 "${fake_bin}/docker"

PATH="${fake_bin}:$PATH" \
HOME_AI_REFERENCE_PROPERTY_VARS_FILE="$vars_file" \
DOCKER_LOG="$docker_log" \
    "$target" status --source edu.academy-registry >"${tmp_dir}/status.out"
grep -Fq 'sourceId: edu.academy-registry' "${tmp_dir}/status.out"

PATH="${fake_bin}:$PATH" \
HOME_AI_REFERENCE_PROPERTY_VARS_FILE="$vars_file" \
DOCKER_LOG="$docker_log" \
    "$target" audit --source edu.academy-registry --limit 3 >"${tmp_dir}/audit.out"
grep -Fq 'reasonCodes: API_SERVER_ERROR' "${tmp_dir}/audit.out"

grep -Fq -- '--env HOME_AI_REFERENCE_RUNTIME_DSN' "$docker_log"
grep -Fq 'home-ai-reference-status --source edu.academy-registry' "$docker_log"
grep -Fq 'home-ai-reference-audit --source edu.academy-registry --limit 3' "$docker_log"
if grep -Fq 'runtime-fixture-secret' "$docker_log" "${tmp_dir}/status.out" "${tmp_dir}/audit.out"; then
    echo '상태: Fail - runtime DB secret이 출력됐습니다.' >&2
    exit 1
fi

if PATH="${fake_bin}:$PATH" \
    HOME_AI_REFERENCE_PROPERTY_VARS_FILE="$vars_file" \
    DOCKER_LOG="$docker_log" \
    "$target" audit --source edu.academy-registry --limit 101 >/dev/null 2>&1; then
    echo '상태: Fail - audit limit 상한을 거부하지 않았습니다.' >&2
    exit 1
fi

echo '상태: Pass - local reference inspection runtime role·secret 경계를 확인했습니다.'
