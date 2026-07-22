#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ai_root="$(cd "${script_dir}/.." && pwd)"
repo_root="$(cd "${ai_root}/../.." && pwd)"
property_vars_file="${1:-${HOME_AI_REFERENCE_PROPERTY_VARS_FILE:-${repo_root}/apps/property-data/.env}}"
ai_database_bootstrap="${HOME_AI_DATABASE_BOOTSTRAP:-${repo_root}/infra/postgres/bootstrap-ai-database.sh}"
coordinate_container="${HOME_COORDINATE_SOURCE_CONTAINER:-home-search-coordinate-source-postgis-arm64-v4}"
image="home-search-ai:local"

reject() {
    echo "상태: Fail - $1" >&2
    exit 1
}

[[ "$#" -le 1 ]] || reject "사용법: $0 [property-vars-file]"
[[ -f "$property_vars_file" && ! -L "$property_vars_file" ]] \
    || reject "property vars 파일이 없거나 일반 파일이 아닙니다."
permissions="$(stat -c '%a' "$property_vars_file" 2>/dev/null || stat -f '%Lp' "$property_vars_file" 2>/dev/null || true)"
[[ "$permissions" =~ ^[0-7]{3,4}$ ]] \
    || reject "property vars 파일 권한을 확인할 수 없습니다."
(( 10#$permissions % 100 == 0 )) \
    || reject "property vars 파일은 group/other 권한이 없어야 합니다."

read_value() {
    local key="$1"
    local required="${2:-true}"
    local count value
    count="$(awk -v key="$key" '
        /^[[:space:]]*#/ { next }
        index($0, key "=") == 1 { count += 1 }
        END { print count + 0 }
    ' "$property_vars_file")"
    if [[ "$count" == 0 && "$required" == false ]]; then
        printf ''
        return
    fi
    [[ "$count" == 1 ]] || reject "${key}는 정확히 한 번 정의해야 합니다."
    value="$(awk -v key="$key" '
        /^[[:space:]]*#/ { next }
        index($0, key "=") == 1 { print substr($0, length(key) + 2); exit }
    ' "$property_vars_file")"
    if (( ${#value} >= 2 )); then
        if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
            value="${value:1:${#value}-2}"
        elif [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
            value="${value:1:${#value}-2}"
        fi
    fi
    [[ -n "$value" && "$value" != *'replace-with'* && "$value" != *'<'* && "$value" != *'>'* ]] \
        || reject "${key} 설정이 올바르지 않습니다."
    printf '%s' "$value"
}

ai_migrator_password="$(read_value AI_DATA_MIGRATOR_DB_PASSWORD)"
ai_importer_password="$(read_value AI_DATA_IMPORTER_DB_PASSWORD)"
ai_runtime_password="$(read_value AI_DATA_RUNTIME_DB_PASSWORD)"
property_reader_password="$(read_value AI_PROPERTY_READER_DB_PASSWORD)"
coordinate_reader_password="$(read_value COORDINATE_SOURCE_DB_PASSWORD false)"
coordinate_reader_password="${coordinate_reader_password:-coordinate_reader_local_password}"

if ! AI_MIGRATOR_PASSWORD="$ai_migrator_password" \
    AI_IMPORTER_PASSWORD="$ai_importer_password" \
    AI_RUNTIME_PASSWORD="$ai_runtime_password" \
    PROPERTY_READER_PASSWORD="$property_reader_password" \
    COORDINATE_READER_PASSWORD="$coordinate_reader_password" python3 - <<'PY'
import os
import sys


def valid(name: str) -> bool:
    value = os.environ[name]
    return (
        0 < len(value) <= 512
        and value == value.strip()
        and all(ord(character) >= 32 and ord(character) != 127 for character in value)
    )


names = (
    "AI_MIGRATOR_PASSWORD",
    "AI_IMPORTER_PASSWORD",
    "AI_RUNTIME_PASSWORD",
    "PROPERTY_READER_PASSWORD",
    "COORDINATE_READER_PASSWORD",
)
sys.exit(0 if all(valid(name) for name in names) else 1)
PY
then
    reject "좌표 보완용 DB 설정이 올바르지 않습니다."
fi

command -v docker >/dev/null 2>&1 || reject "docker 명령을 찾을 수 없습니다."
[[ -x "$ai_database_bootstrap" ]] || reject "AI DB bootstrap 실행 파일을 찾을 수 없습니다."

if ! AI_DATA_MIGRATOR_DB_PASSWORD="$ai_migrator_password" \
    AI_DATA_IMPORTER_DB_PASSWORD="$ai_importer_password" \
    AI_DATA_RUNTIME_DB_PASSWORD="$ai_runtime_password" \
    "$ai_database_bootstrap" >/dev/null; then
    reject "AI DB role bootstrap에 실패했습니다."
fi

network_name="$(docker inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' home-search-postgis 2>/dev/null | head -n 1)"
[[ -n "$network_name" ]] || reject "PostgreSQL local network를 확인할 수 없습니다."
coordinate_network="$(docker inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "$coordinate_container" 2>/dev/null | head -n 1)"
[[ "$coordinate_network" == "$network_name" ]] \
    || reject "좌표 source DB가 property DB와 같은 local network에 실행 중이어야 합니다."

docker build --tag "$image" "$ai_root" >/dev/null \
    || reject "AI importer image build에 실패했습니다."

dsn_values="$(AI_MIGRATOR_PASSWORD="$ai_migrator_password" \
    AI_IMPORTER_PASSWORD="$ai_importer_password" \
    PROPERTY_READER_PASSWORD="$property_reader_password" \
    COORDINATE_READER_PASSWORD="$coordinate_reader_password" \
    COORDINATE_CONTAINER="$coordinate_container" python3 - <<'PY'
import os
from urllib.parse import quote


def encoded(name: str) -> str:
    return quote(os.environ[name], safe="")


print(f"postgresql://home_search_ai_migrator:{encoded('AI_MIGRATOR_PASSWORD')}@postgis:5432/home_search_ai")
print(f"postgresql://home_search_ai_importer:{encoded('AI_IMPORTER_PASSWORD')}@postgis:5432/home_search_ai")
print(f"postgresql://home_search_ai_reader:{encoded('PROPERTY_READER_PASSWORD')}@postgis:5432/home_search")
print(
    "postgresql://home_search_coordinate_reader:"
    f"{encoded('COORDINATE_READER_PASSWORD')}@{os.environ['COORDINATE_CONTAINER']}:5432/"
    "home_search_coordinate_source"
)
PY
)"
home_ai_migrator_dsn="$(sed -n '1p' <<<"$dsn_values")"
home_ai_importer_dsn="$(sed -n '2p' <<<"$dsn_values")"
home_ai_property_dsn="$(sed -n '3p' <<<"$dsn_values")"
coordinate_reader_dsn="$(sed -n '4p' <<<"$dsn_values")"

if ! HOME_AI_MIGRATOR_DSN="$home_ai_migrator_dsn" docker run --rm \
    --network "$network_name" --env HOME_AI_MIGRATOR_DSN \
    "$image" home-ai-migrate >/dev/null; then
    reject "AI dataset migration에 실패했습니다."
fi

HOME_AI_IMPORTER_DSN="$home_ai_importer_dsn" \
HOME_AI_PROPERTY_DSN="$home_ai_property_dsn" \
HOME_COORDINATE_SOURCE_READER_DSN="$coordinate_reader_dsn" \
docker run --rm --network "$network_name" \
    --env HOME_AI_IMPORTER_DSN \
    --env HOME_AI_PROPERTY_DSN \
    --env HOME_COORDINATE_SOURCE_READER_DSN \
    "$image" home-ai-retail-coordinate-enrichment
