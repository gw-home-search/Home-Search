#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ai_root="$(cd "${script_dir}/.." && pwd)"
repo_root="$(cd "${ai_root}/../.." && pwd)"
compose_file="${repo_root}/infra/docker-compose.local.yml"
ai_database_bootstrap="${repo_root}/infra/postgres/bootstrap-ai-database.sh"
property_vars_file="${HOME_AI_REFERENCE_PROPERTY_VARS_FILE:-${repo_root}/apps/property-data/.env}"
ai_vars_file="${HOME_AI_REFERENCE_AI_VARS_FILE:-${ai_root}/.env}"
image="home-search-ai:local"

usage() {
    echo "사용법: $0 --source <sourceId> | --family priority" >&2
    exit 2
}

reject_configuration() {
    echo "상태: Fail - $1" >&2
    exit 2
}

reject_runtime() {
    echo "상태: Fail - $1" >&2
    exit 1
}

[[ "$#" == "2" ]] || usage
selector="$1"
selector_value="$2"
needs_data_go_kr_key=false
needs_neis_key=false
if [[ "$selector" == "--family" && "$selector_value" == "priority" ]]; then
    needs_data_go_kr_key=true
    needs_neis_key=true
elif [[ "$selector" == "--source" ]]; then
    case "$selector_value" in
        edu.school-location|place.sbiz-academy)
            needs_data_go_kr_key=true
            ;;
        edu.academy-registry)
            needs_neis_key=true
            ;;
        retail.large-store|transport.rail-station)
            ;;
        *)
            usage
            ;;
    esac
else
    usage
fi

require_vars_file() {
    local path="$1"
    local label="$2"
    local permissions
    [[ -f "$path" && ! -L "$path" ]] \
        || reject_configuration "${label} vars 파일이 없거나 일반 파일이 아닙니다."
    permissions="$(stat -c '%a' "$path" 2>/dev/null || stat -f '%Lp' "$path" 2>/dev/null || true)"
    [[ "$permissions" =~ ^[0-7]{3,4}$ ]] \
        || reject_configuration "${label} vars 파일 권한을 확인할 수 없습니다."
    (( 10#$permissions % 100 == 0 )) \
        || reject_configuration "${label} vars 파일은 group/other 권한이 없어야 합니다. chmod 600을 적용하세요."
}

read_value() {
    local path="$1"
    local key="$2"
    local required="${3:-true}"
    local count
    local value
    count="$(awk -v key="$key" '
        /^[[:space:]]*#/ { next }
        index($0, key "=") == 1 { count += 1 }
        END { print count + 0 }
    ' "$path")"
    if [[ "$count" == 0 && "$required" == false ]]; then
        printf ''
        return 0
    fi
    [[ "$count" == "1" ]] || reject_configuration "${key}는 정확히 한 번 정의해야 합니다."
    value="$(awk -v key="$key" '
        /^[[:space:]]*#/ { next }
        index($0, key "=") == 1 { print substr($0, length(key) + 2); exit }
    ' "$path")"
    if (( ${#value} >= 2 )); then
        if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
            value="${value:1:${#value}-2}"
        elif [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
            value="${value:1:${#value}-2}"
        fi
    fi
    [[ -n "$value" ]] || reject_configuration "${key} 값이 없습니다."
    [[ "$value" != *'replace-with'* && "$value" != *'<'* && "$value" != *'>'* ]] \
        || reject_configuration "${key} placeholder를 실제 설정으로 교체해야 합니다."
    printf '%s' "$value"
}

require_vars_file "$property_vars_file" property
require_vars_file "$ai_vars_file" AI
command -v docker >/dev/null 2>&1 || reject_configuration "docker 명령을 찾을 수 없습니다."
[[ -x "$ai_database_bootstrap" ]] \
    || reject_configuration "AI DB role bootstrap 실행 파일을 찾을 수 없습니다."

ai_data_migrator_password="$(read_value "$property_vars_file" AI_DATA_MIGRATOR_DB_PASSWORD)"
ai_data_importer_password="$(read_value "$property_vars_file" AI_DATA_IMPORTER_DB_PASSWORD)"
ai_data_runtime_password="$(read_value "$property_vars_file" AI_DATA_RUNTIME_DB_PASSWORD)"
minio_root_user="$(read_value "$ai_vars_file" HOME_AI_MINIO_ROOT_USER)"
minio_root_password="$(read_value "$ai_vars_file" HOME_AI_MINIO_ROOT_PASSWORD)"
aws_access_key_id="$(read_value "$ai_vars_file" AWS_ACCESS_KEY_ID)"
aws_secret_access_key="$(read_value "$ai_vars_file" AWS_SECRET_ACCESS_KEY)"
raw_bucket="$(read_value "$ai_vars_file" HOME_AI_RAW_S3_BUCKET)"
raw_prefix="$(read_value "$ai_vars_file" HOME_AI_RAW_S3_PREFIX)"
raw_region="$(read_value "$ai_vars_file" HOME_AI_RAW_S3_REGION)"
raw_endpoint="$(read_value "$ai_vars_file" HOME_AI_RAW_S3_ENDPOINT)"
data_go_kr_service_key=""
neis_service_key=""
if [[ "$selector" == "--family" ]]; then
    data_go_kr_service_key="$(
        read_value "$ai_vars_file" HOME_AI_DATA_GO_KR_SERVICE_KEY false
    )"
    neis_service_key="$(read_value "$ai_vars_file" HOME_AI_NEIS_SERVICE_KEY false)"
elif [[ "$needs_data_go_kr_key" == true ]]; then
    data_go_kr_service_key="$(read_value "$ai_vars_file" HOME_AI_DATA_GO_KR_SERVICE_KEY)"
elif [[ "$needs_neis_key" == true ]]; then
    neis_service_key="$(read_value "$ai_vars_file" HOME_AI_NEIS_SERVICE_KEY)"
fi

if ! AI_MIGRATOR_PASSWORD="$ai_data_migrator_password" \
    AI_IMPORTER_PASSWORD="$ai_data_importer_password" \
    AI_RUNTIME_PASSWORD="$ai_data_runtime_password" \
    MINIO_ROOT_USER_VALUE="$minio_root_user" \
    MINIO_ROOT_PASSWORD_VALUE="$minio_root_password" \
    AWS_ACCESS_KEY_VALUE="$aws_access_key_id" \
    AWS_SECRET_KEY_VALUE="$aws_secret_access_key" \
    RAW_BUCKET="$raw_bucket" \
    RAW_PREFIX="$raw_prefix" \
    RAW_REGION="$raw_region" \
    RAW_ENDPOINT="$raw_endpoint" \
    DATA_SERVICE_KEY="$data_go_kr_service_key" \
    NEIS_SERVICE_KEY="$neis_service_key" python3 - <<'PY'
import os
import re
import sys


def secret(name: str, maximum: int) -> bool:
    value = os.environ[name]
    return (
        0 < len(value) <= maximum
        and value == value.strip()
        and all(ord(character) >= 32 and ord(character) != 127 for character in value)
    )


def optional_secret(name: str, maximum: int) -> bool:
    return os.environ[name] == "" or secret(name, maximum)


valid = (
    secret("AI_MIGRATOR_PASSWORD", 512)
    and secret("AI_IMPORTER_PASSWORD", 512)
    and secret("AI_RUNTIME_PASSWORD", 512)
    and secret("MINIO_ROOT_USER_VALUE", 128)
    and secret("MINIO_ROOT_PASSWORD_VALUE", 512)
    and secret("AWS_ACCESS_KEY_VALUE", 128)
    and secret("AWS_SECRET_KEY_VALUE", 512)
    and optional_secret("DATA_SERVICE_KEY", 1024)
    and optional_secret("NEIS_SERVICE_KEY", 1024)
    and re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", os.environ["RAW_BUCKET"])
    is not None
    and os.environ["RAW_PREFIX"] == "raw"
    and re.fullmatch(r"[a-z]{2}-[a-z]+-\d", os.environ["RAW_REGION"]) is not None
    and os.environ["RAW_ENDPOINT"] == "http://minio:9000"
)
sys.exit(0 if valid else 1)
PY
then
    reject_configuration "local reference refresh 설정이 올바르지 않습니다."
fi

dsn_values="$(AI_MIGRATOR_PASSWORD="$ai_data_migrator_password" \
    AI_IMPORTER_PASSWORD="$ai_data_importer_password" python3 - <<'PY'
import os
from urllib.parse import quote

migrator = quote(os.environ["AI_MIGRATOR_PASSWORD"], safe="")
importer = quote(os.environ["AI_IMPORTER_PASSWORD"], safe="")
print(f"postgresql://home_search_ai_migrator:{migrator}@postgis:5432/home_search_ai")
print(f"postgresql://home_search_ai_importer:{importer}@postgis:5432/home_search_ai")
PY
)"
home_ai_migrator_dsn="${dsn_values%%$'\n'*}"
home_ai_importer_dsn="${dsn_values#*$'\n'}"

if ! AI_DATA_MIGRATOR_DB_PASSWORD="$ai_data_migrator_password" \
    AI_DATA_IMPORTER_DB_PASSWORD="$ai_data_importer_password" \
    AI_DATA_RUNTIME_DB_PASSWORD="$ai_data_runtime_password" \
    "$ai_database_bootstrap" >/dev/null; then
    reject_runtime "AI DB role bootstrap에 실패했습니다."
fi

docker build --tag "$image" "$ai_root" >/dev/null \
    || reject_runtime "AI importer image build에 실패했습니다."

run_minio_compose() {
    HOME_SEARCH_DB_PASSWORD=reference-refresh-not-used \
    PROPERTY_MIGRATOR_DB_PASSWORD=reference-refresh-not-used \
    AI_PROPERTY_READER_DB_PASSWORD=reference-refresh-not-used \
    AI_DATA_MIGRATOR_DB_PASSWORD=reference-refresh-not-used \
    AI_DATA_IMPORTER_DB_PASSWORD=reference-refresh-not-used \
    AI_DATA_RUNTIME_DB_PASSWORD=reference-refresh-not-used \
    USER_RUNTIME_DB_PASSWORD=reference-refresh-not-used \
    USER_MIGRATOR_DB_PASSWORD=reference-refresh-not-used \
    HOME_AI_MINIO_ROOT_USER="$minio_root_user" \
    HOME_AI_MINIO_ROOT_PASSWORD="$minio_root_password" \
    HOME_AI_RAW_S3_BUCKET="$raw_bucket" \
    AWS_ACCESS_KEY_ID="$aws_access_key_id" \
    AWS_SECRET_ACCESS_KEY="$aws_secret_access_key" \
        docker compose -f "$compose_file" "$@"
}

run_minio_compose up -d --wait minio \
    || reject_runtime "MinIO 시작 또는 health 확인에 실패했습니다."
run_minio_compose run --rm minio-init \
    || reject_runtime "MinIO bucket/versioning/object-lock 초기화에 실패했습니다."

network_name="$(docker inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' home-search-postgis 2>/dev/null | head -n 1)"
[[ -n "$network_name" ]] || reject_runtime "PostgreSQL local network를 확인할 수 없습니다."

if ! HOME_AI_MIGRATOR_DSN="$home_ai_migrator_dsn" docker run --rm \
    --network "$network_name" \
    --env HOME_AI_MIGRATOR_DSN \
    "$image" home-ai-migrate >/dev/null; then
    reject_runtime "AI dataset migration에 실패했습니다."
fi

print_source_configuration_failure() {
    local source_id="$1"
    printf '%s\n' \
        '상태: Fail' \
        "sourceId: $source_id" \
        'temporalBasis:' \
        'dataAsOf:' \
        'pageCount: 0' \
        'rawRowCount: 0' \
        'acceptedRowCount: 0' \
        'nonSpatialRowCount: 0' \
        'rejectedRowCount: 0' \
        'coordinateCoverage:' \
        'datasetVersion:' \
        'reasonCodes: CONFIGURATION_INVALID'
}

run_source() {
    local source_id="$1"
    local docker_environment=(
        --env HOME_AI_IMPORTER_DSN
        --env HOME_AI_RAW_S3_BUCKET
        --env HOME_AI_RAW_S3_PREFIX
        --env HOME_AI_RAW_S3_REGION
        --env HOME_AI_RAW_S3_ENDPOINT
        --env AWS_ACCESS_KEY_ID
        --env AWS_SECRET_ACCESS_KEY
    )
    case "$source_id" in
        edu.school-location|place.sbiz-academy)
            if [[ -z "$data_go_kr_service_key" ]]; then
                print_source_configuration_failure "$source_id"
                return 2
            fi
            docker_environment+=(--env HOME_AI_DATA_GO_KR_SERVICE_KEY)
            ;;
        edu.academy-registry)
            if [[ -z "$neis_service_key" ]]; then
                print_source_configuration_failure "$source_id"
                return 2
            fi
            docker_environment+=(--env HOME_AI_NEIS_SERVICE_KEY)
            ;;
    esac

    HOME_AI_IMPORTER_DSN="$home_ai_importer_dsn" \
    HOME_AI_DATA_GO_KR_SERVICE_KEY="$data_go_kr_service_key" \
    HOME_AI_NEIS_SERVICE_KEY="$neis_service_key" \
    HOME_AI_RAW_S3_BUCKET="$raw_bucket" \
    HOME_AI_RAW_S3_PREFIX="$raw_prefix" \
    HOME_AI_RAW_S3_REGION="$raw_region" \
    HOME_AI_RAW_S3_ENDPOINT="$raw_endpoint" \
    AWS_ACCESS_KEY_ID="$aws_access_key_id" \
    AWS_SECRET_ACCESS_KEY="$aws_secret_access_key" \
    docker run --rm \
        --network "$network_name" \
        "${docker_environment[@]}" \
        "$image" home-ai-reference-refresh --source "$source_id"
}

if [[ "$selector" == "--source" ]]; then
    run_source "$selector_value"
else
    family_exit_code=0
    for source_id in \
        edu.school-location \
        edu.academy-registry \
        place.sbiz-academy \
        retail.large-store \
        transport.rail-station; do
        if run_source "$source_id"; then
            continue
        else
            source_exit_code="$?"
        fi
        if [[ "$source_exit_code" == 2 ]]; then
            family_exit_code=2
        elif [[ "$family_exit_code" == 0 ]]; then
            family_exit_code=1
        fi
    done
    exit "$family_exit_code"
fi
