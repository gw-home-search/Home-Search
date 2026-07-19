#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ai_root="$(cd "${script_dir}/.." && pwd)"
runner="${script_dir}/run-local-reference-refresh.sh"
dockerfile="${ai_root}/Dockerfile"
tmp_dir="$(mktemp -d)"

cleanup() {
    find "$tmp_dir" -type f -exec unlink {} \; 2>/dev/null || true
    find "$tmp_dir" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$tmp_dir/bin"
docker_log="$tmp_dir/docker.log"
cat >"$tmp_dir/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$REFERENCE_REFRESH_TEST_DOCKER_LOG"
if [[ "${1:-}" == "inspect" ]]; then
    printf '%s\n' 'home-search-local_home-search-local'
    exit 0
fi
if [[ " $* " == *' home-ai-school-location-ingest '* ]]; then
    printf '%s\n' \
        '상태: Pass' \
        'sourceId: edu.school-location' \
        'sourceDate: 2026-07-19' \
        'pageCount: 17' \
        'rawRowCount: 12000' \
        'acceptedRowCount: 12000' \
        'rejectedRowCount: 0' \
        'datasetVersion: 2026-07-19-0123456789ab' \
        'reasonCodes:'
fi
SH
chmod +x "$tmp_dir/bin/docker"

property_vars="$tmp_dir/property.env"
ai_vars="$tmp_dir/ai.env"
printf '%s\n' \
    'AI_DATA_MIGRATOR_DB_PASSWORD=migrator-fixture-secret' \
    'AI_DATA_IMPORTER_DB_PASSWORD=importer-fixture-secret' \
    'AI_DATA_RUNTIME_DB_PASSWORD=runtime-fixture-secret' >"$property_vars"
printf '%s\n' \
    'HOME_AI_MINIO_ROOT_USER=minio-root-fixture' \
    'HOME_AI_MINIO_ROOT_PASSWORD=minio-root-fixture-secret' \
    'AWS_ACCESS_KEY_ID=importer-access-fixture' \
    'AWS_SECRET_ACCESS_KEY=importer-s3-fixture-secret' \
    'HOME_AI_RAW_S3_BUCKET=home-ai-raw-fixture' \
    'HOME_AI_RAW_S3_PREFIX=raw' \
    'HOME_AI_RAW_S3_REGION=ap-northeast-2' \
    'HOME_AI_RAW_S3_ENDPOINT=http://minio:9000' \
    'HOME_AI_DATA_GO_KR_SERVICE_KEY=provider-fixture-secret' >"$ai_vars"
chmod 600 "$property_vars" "$ai_vars"

[[ -x "$runner" ]] || {
    echo '상태: Fail - local reference refresh runner가 없습니다.' >&2
    exit 1
}
grep -Fq 'COPY --chown=home-ai:home-ai config ./config' "$dockerfile"
grep -Fxq 'RUN uv sync --frozen --no-dev' "$dockerfile"

output="$(
    PATH="$tmp_dir/bin:$PATH" \
    REFERENCE_REFRESH_TEST_DOCKER_LOG="$docker_log" \
    HOME_AI_REFERENCE_PROPERTY_VARS_FILE="$property_vars" \
    HOME_AI_REFERENCE_AI_VARS_FILE="$ai_vars" \
    "$runner" --source edu.school-location
)"

grep -Fq '상태: Pass' <<<"$output"
grep -Fq 'sourceId: edu.school-location' <<<"$output"
grep -Fq 'rejectedRowCount: 0' <<<"$output"
grep -Fq 'build --tag home-search-ai:local' "$docker_log"
grep -Fq 'up -d --wait minio' "$docker_log"
grep -Fq 'run --rm minio-init' "$docker_log"
if grep -Fq -- '--env-file' "$docker_log"; then
    echo '상태: Fail - refresh runner가 전체 env 파일을 Docker Compose에 전달했습니다.' >&2
    exit 1
fi
grep -Fq -- '--env HOME_AI_MIGRATOR_DSN' "$docker_log"
grep -Fq 'home-ai-migrate' "$docker_log"
grep -Fq -- '--env HOME_AI_IMPORTER_DSN' "$docker_log"
grep -Fq -- '--env HOME_AI_DATA_GO_KR_SERVICE_KEY' "$docker_log"
grep -Fq 'home-ai-school-location-ingest' "$docker_log"

if grep -Eq 'fixture-secret|provider-fixture|migrator-fixture|importer-fixture|runtime-fixture' \
    <<<"$output"; then
    echo '상태: Fail - refresh 출력에 비밀값이 포함됐습니다.' >&2
    exit 1
fi
if grep -Eq 'fixture-secret|provider-fixture' "$docker_log"; then
    echo '상태: Fail - Docker argument에 비밀값이 포함됐습니다.' >&2
    exit 1
fi

echo '상태: Pass - local reference refresh secret 경계와 실행 순서를 확인했습니다.'
