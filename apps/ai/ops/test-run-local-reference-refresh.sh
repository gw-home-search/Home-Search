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
    if [[ "$*" == *'.State.Status'* ]]; then
        printf '%s\n' 'running|healthy'
    else
        printf '%s\n' 'home-search-local_home-search-local'
    fi
    exit 0
fi
if [[ " $* " == *' home-ai-reference-refresh '* ]]; then
    source_id='priority-family'
    previous=''
    for argument in "$@"; do
        if [[ "$previous" == '--source' ]]; then
            source_id="$argument"
            break
        fi
        previous="$argument"
    done
    printf '%s\n' '상태: Pass' "sourceId: $source_id" 'reasonCodes:'
    if [[ "$source_id" == "${REFERENCE_REFRESH_TEST_FAIL_SOURCE:-}" ]]; then
        exit 1
    fi
fi
SH
chmod +x "$tmp_dir/bin/docker"

property_vars="$tmp_dir/property.env"
ai_vars="$tmp_dir/ai.env"
ai_vars_without_neis="$tmp_dir/ai-without-neis.env"
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
    'HOME_AI_DATA_GO_KR_SERVICE_KEY=provider-fixture-secret' \
    'HOME_AI_NEIS_SERVICE_KEY=neis-fixture-secret' >"$ai_vars"
grep -Ev '^HOME_AI_NEIS_SERVICE_KEY=' "$ai_vars" >"$ai_vars_without_neis"
chmod 600 "$property_vars" "$ai_vars" "$ai_vars_without_neis"

[[ -x "$runner" ]] || {
    echo '상태: Fail - local reference refresh runner가 없습니다.' >&2
    exit 1
}
grep -Fq 'COPY --chown=home-ai:home-ai config ./config' "$dockerfile"
grep -Fxq 'RUN uv sync --frozen --no-dev' "$dockerfile"

run_refresh() {
    local selected_ai_vars_file="${REFERENCE_REFRESH_TEST_AI_VARS_FILE:-$ai_vars}"
    : >"$docker_log"
    PATH="$tmp_dir/bin:$PATH" \
    REFERENCE_REFRESH_TEST_DOCKER_LOG="$docker_log" \
    HOME_AI_REFERENCE_PROPERTY_VARS_FILE="$property_vars" \
    HOME_AI_REFERENCE_AI_VARS_FILE="$selected_ai_vars_file" \
    "$runner" "$@"
}

output="$(run_refresh --source edu.school-location)"

grep -Fq '상태: Pass' <<<"$output"
grep -Fq 'sourceId: edu.school-location' <<<"$output"
grep -Fq 'build --tag home-search-ai:local' "$docker_log"
grep -Fq 'exec --env AI_DATA_MIGRATOR_DB_PASSWORD --env AI_DATA_IMPORTER_DB_PASSWORD --env AI_DATA_RUNTIME_DB_PASSWORD --env AI_DATABASE_ONLY -i home-search-postgis bash -s' "$docker_log"
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
if grep -Fq -- '--env HOME_AI_NEIS_SERVICE_KEY' "$docker_log"; then
    echo '상태: Fail - school refresh에 NEIS secret이 전달됐습니다.' >&2
    exit 1
fi
grep -Fq 'home-ai-reference-refresh --source edu.school-location' "$docker_log"

bootstrap_line="$(grep -n -F 'exec --env AI_DATA_MIGRATOR_DB_PASSWORD' "$docker_log" | cut -d: -f1)"
migration_line="$(grep -n -F 'home-ai-migrate' "$docker_log" | cut -d: -f1)"
[[ "$bootstrap_line" -lt "$migration_line" ]] || {
    echo '상태: Fail - AI DB role bootstrap이 migration보다 먼저 실행되지 않았습니다.' >&2
    exit 1
}

if grep -Eq 'fixture-secret|provider-fixture|migrator-fixture|importer-fixture|runtime-fixture' \
    <<<"$output"; then
    echo '상태: Fail - refresh 출력에 비밀값이 포함됐습니다.' >&2
    exit 1
fi

output="$(run_refresh --source edu.academy-registry)"
grep -Fq 'sourceId: edu.academy-registry' <<<"$output"
grep -Fq -- '--env HOME_AI_NEIS_SERVICE_KEY' "$docker_log"
if grep -Fq -- '--env HOME_AI_DATA_GO_KR_SERVICE_KEY' "$docker_log"; then
    echo '상태: Fail - NEIS refresh에 data.go.kr secret이 전달됐습니다.' >&2
    exit 1
fi
grep -Fq 'home-ai-reference-refresh --source edu.academy-registry' "$docker_log"

output="$(run_refresh --source retail.large-store)"
grep -Fq 'sourceId: retail.large-store' <<<"$output"
if grep -Eq -- '--env HOME_AI_(DATA_GO_KR|NEIS)_SERVICE_KEY' "$docker_log"; then
    echo '상태: Fail - retail file refresh에 provider secret이 전달됐습니다.' >&2
    exit 1
fi
grep -Fq 'home-ai-reference-refresh --source retail.large-store' "$docker_log"

output="$(run_refresh --family priority)"
[[ "$(grep -c 'home-ai-reference-refresh --source' "$docker_log")" == 5 ]]
for source_id in \
    edu.school-location \
    edu.academy-registry \
    place.sbiz-academy \
    retail.large-store \
    transport.rail-station; do
    grep -Fq "sourceId: $source_id" <<<"$output"
    line="$(grep -F "home-ai-reference-refresh --source $source_id" "$docker_log")"
    case "$source_id" in
        edu.school-location|place.sbiz-academy)
            grep -Fq -- '--env HOME_AI_DATA_GO_KR_SERVICE_KEY' <<<"$line"
            ! grep -Fq -- '--env HOME_AI_NEIS_SERVICE_KEY' <<<"$line"
            ;;
        edu.academy-registry)
            grep -Fq -- '--env HOME_AI_NEIS_SERVICE_KEY' <<<"$line"
            ! grep -Fq -- '--env HOME_AI_DATA_GO_KR_SERVICE_KEY' <<<"$line"
            ;;
        retail.large-store|transport.rail-station)
            ! grep -Eq -- '--env HOME_AI_(DATA_GO_KR|NEIS)_SERVICE_KEY' <<<"$line"
            ;;
    esac
done

if output="$(
    REFERENCE_REFRESH_TEST_FAIL_SOURCE='edu.academy-registry' \
        run_refresh --family priority
)"; then
    echo '상태: Fail - family refresh가 source 실패를 성공으로 처리했습니다.' >&2
    exit 1
else
    family_exit_code="$?"
fi
[[ "$family_exit_code" == 1 ]]
[[ "$(grep -c 'home-ai-reference-refresh --source' "$docker_log")" == 5 ]]
grep -Fq 'sourceId: transport.rail-station' <<<"$output"

if output="$(
    REFERENCE_REFRESH_TEST_AI_VARS_FILE="$ai_vars_without_neis" \
        run_refresh --family priority
)"; then
    echo '상태: Fail - family refresh가 누락된 source key를 성공으로 처리했습니다.' >&2
    exit 1
else
    family_exit_code="$?"
fi
[[ "$family_exit_code" == 2 ]]
grep -Fq 'sourceId: edu.academy-registry' <<<"$output"
grep -Fq 'reasonCodes: CONFIGURATION_INVALID' <<<"$output"
grep -Fq 'sourceId: transport.rail-station' <<<"$output"
[[ "$(grep -c 'home-ai-reference-refresh --source' "$docker_log")" == 4 ]]
if grep -Eq 'fixture-secret|provider-fixture' "$docker_log"; then
    echo '상태: Fail - Docker argument에 비밀값이 포함됐습니다.' >&2
    exit 1
fi

echo '상태: Pass - local reference refresh secret 경계와 실행 순서를 확인했습니다.'
