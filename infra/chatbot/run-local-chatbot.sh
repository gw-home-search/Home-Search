#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
using_default_runtime_files=false
if (( $# == 0 )); then
    using_default_runtime_files=true
    property_vars_file="${CHATBOT_PROPERTY_VARS_FILE:-${repo_root}/apps/property-data/.env}"
    user_vars_file="${CHATBOT_USER_VARS_FILE:-${repo_root}/apps/user/service/.env}"
    bff_vars_file="${CHATBOT_BFF_VARS_FILE:-${repo_root}/apps/chat-bff/.env}"
    ai_vars_file="${CHATBOT_AI_VARS_FILE:-${repo_root}/apps/ai/.env}"
elif (( $# == 4 )); then
    property_vars_file="$1"
    user_vars_file="$2"
    bff_vars_file="$3"
    ai_vars_file="$4"
else
    echo "사용법: $0 [<property-vars-file> <user-vars-file> <bff-vars-file> <ai-vars-file>]" >&2
    exit 2
fi
base_compose="${repo_root}/infra/docker-compose.local.yml"
chatbot_compose="${repo_root}/infra/docker-compose.chatbot.yml"
ai_database_bootstrap="${repo_root}/infra/postgres/bootstrap-ai-database.sh"
bff_jar="${CHATBOT_BFF_JAR_PATH:-${repo_root}/apps/chat-bff/build/libs/chat-bff.jar}"
ai_dockerfile="${CHATBOT_AI_DOCKERFILE_PATH:-${repo_root}/apps/ai/Dockerfile}"
user_public_key="${CHATBOT_USER_PUBLIC_KEY_PATH:-${repo_root}/runtime-keys/user/public}"
user_private_key="${CHATBOT_USER_PRIVATE_KEY_PATH:-${repo_root}/runtime-keys/user/private}"

reject() {
    echo "거부됨: $1" >&2
    exit 1
}

require_file() {
    local path="$1"
    local label="$2"
    [[ -f "$path" && -s "$path" ]] || reject "${label} 파일이 없거나 비어 있습니다."
}

read_value() {
    local path="$1"
    local key="$2"
    local value
    value="$(awk -v key="$key" '
        /^[[:space:]]*#/ { next }
        {
            line = $0
            sub(/^[[:space:]]*/, "", line)
        }
        index(line, key "=") == 1 { print substr(line, length(key) + 2); exit }
    ' "$path")"
    if (( ${#value} >= 2 )); then
        if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
            value="${value:1:${#value}-2}"
        elif [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
            value="${value:1:${#value}-2}"
        fi
    fi
    printf '%s' "$value"
}

required_value() {
    local path="$1"
    local key="$2"
    local count
    local value
    count="$(awk -v key="$key" '
        /^[[:space:]]*#/ { next }
        {
            line = $0
            sub(/^[[:space:]]*/, "", line)
        }
        index(line, key "=") == 1 { count += 1 }
        END { print count + 0 }
    ' "$path")"
    [[ "$count" == "1" ]] || reject "${key}는 정확히 한 번 정의해야 합니다."
    value="$(read_value "$path" "$key")"
    [[ -n "$value" ]] || reject "${key} 값이 없습니다."
    [[ "$value" != *'replace-with'* && "$value" != *'<'* && "$value" != *'>'* ]] \
        || reject "${key} placeholder를 실제 설정으로 교체해야 합니다."
    printf '%s' "$value"
}

optional_value() {
    local path="$1"
    local key="$2"
    local default_value="$3"
    local count
    local value
    count="$(awk -v key="$key" '
        /^[[:space:]]*#/ { next }
        {
            line = $0
            sub(/^[[:space:]]*/, "", line)
        }
        index(line, key "=") == 1 { count += 1 }
        END { print count + 0 }
    ' "$path")"
    [[ "$count" == "0" || "$count" == "1" ]] \
        || reject "${key}는 최대 한 번 정의할 수 있습니다."
    if [[ "$count" == "0" ]]; then
        printf '%s' "$default_value"
        return
    fi
    value="$(read_value "$path" "$key")"
    [[ -n "$value" ]] || reject "${key} 값이 없습니다."
    [[ "$value" != *'replace-with'* && "$value" != *'<'* && "$value" != *'>'* ]] \
        || reject "${key} placeholder를 실제 설정으로 교체해야 합니다."
    printf '%s' "$value"
}

optional_blank_value() {
    local path="$1"
    local key="$2"
    local default_value="$3"
    local count
    local value
    count="$(awk -v key="$key" '
        /^[[:space:]]*#/ { next }
        {
            line = $0
            sub(/^[[:space:]]*/, "", line)
        }
        index(line, key "=") == 1 { count += 1 }
        END { print count + 0 }
    ' "$path")"
    [[ "$count" == "0" || "$count" == "1" ]] \
        || reject "${key}는 최대 한 번 정의할 수 있습니다."
    if [[ "$count" == "0" ]]; then
        printf '%s' "$default_value"
        return
    fi
    value="$(read_value "$path" "$key")"
    if [[ -n "$value" ]]; then
        [[ "$value" != *'replace-with'* && "$value" != *'<'* && "$value" != *'>'* ]] \
            || reject "${key} placeholder를 실제 설정으로 교체해야 합니다."
    fi
    printf '%s' "$value"
}

for file in "$property_vars_file" "$user_vars_file" "$bff_vars_file" "$ai_vars_file"; do
    require_file "$file" "runtime vars"
done
require_file "$base_compose" "base compose"
require_file "$chatbot_compose" "chatbot compose"
require_file "$ai_database_bootstrap" "AI DB bootstrap"
[[ -x "$ai_database_bootstrap" ]] || reject "AI DB bootstrap을 실행할 수 없습니다."
require_file "$bff_jar" "chat BFF artifact"
require_file "$ai_dockerfile" "AI Dockerfile"
require_file "$user_public_key" "user JWT public key"
require_file "$user_private_key" "user JWT private key"

openssl pkey -pubin -in "$user_public_key" -noout >/dev/null 2>&1 \
    || reject "user JWT public key를 읽을 수 없습니다."
openssl pkey -in "$user_private_key" -noout >/dev/null 2>&1 \
    || reject "user JWT private key를 읽을 수 없습니다."
public_fingerprint="$(openssl pkey -pubin -in "$user_public_key" -outform DER 2>/dev/null | openssl dgst -sha256)"
private_fingerprint="$(openssl pkey -in "$user_private_key" -pubout -outform DER 2>/dev/null | openssl dgst -sha256)"
[[ "$public_fingerprint" == "$private_fingerprint" ]] \
    || reject "user JWT private/public key pair가 일치하지 않습니다."

home_search_db_password_count="$(awk '
    /^[[:space:]]*#/ { next }
    {
        line = $0
        sub(/^[[:space:]]*/, "", line)
    }
    index(line, "HOME_SEARCH_DB_PASSWORD=") == 1 { count += 1 }
    END { print count + 0 }
' "$property_vars_file")"
if [[ "$home_search_db_password_count" == "0" ]]; then
    property_db_username="$(required_value "$property_vars_file" DB_USERNAME)"
    [[ "$property_db_username" == "home_search" ]] \
        || reject "DB_PASSWORD를 bootstrap에 사용하려면 DB_USERNAME은 home_search여야 합니다."
    home_search_db_password="$(required_value "$property_vars_file" DB_PASSWORD)"
else
    home_search_db_password="$(required_value "$property_vars_file" HOME_SEARCH_DB_PASSWORD)"
fi
property_runtime_db_password="$(optional_value "$property_vars_file" PROPERTY_RUNTIME_DB_PASSWORD property_runtime_local_password)"
property_migrator_db_password="$(required_value "$property_vars_file" PROPERTY_MIGRATOR_DB_PASSWORD)"
ai_property_reader_db_password="$(required_value "$property_vars_file" AI_PROPERTY_READER_DB_PASSWORD)"
ai_data_migrator_db_password="$(required_value "$property_vars_file" AI_DATA_MIGRATOR_DB_PASSWORD)"
ai_data_importer_db_password="$(required_value "$property_vars_file" AI_DATA_IMPORTER_DB_PASSWORD)"
ai_data_runtime_db_password="$(required_value "$property_vars_file" AI_DATA_RUNTIME_DB_PASSWORD)"
user_active_kid="$(required_value "$user_vars_file" USER_JWT_ACTIVE_KID)"
user_db_password="$(required_value "$user_vars_file" USER_DB_PASSWORD)"
user_runtime_db_password="$(optional_value "$property_vars_file" USER_RUNTIME_DB_PASSWORD "$user_db_password")"
user_migrator_db_password="$(optional_value "$property_vars_file" USER_MIGRATOR_DB_PASSWORD chatbot_migration_not_used)"
[[ "$user_active_kid" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] \
    || reject "USER_JWT_ACTIVE_KID 형식이 올바르지 않습니다."
expected_bff_mapping="${user_active_kid}=/run/keys/user-signing-public"
if [[ "$using_default_runtime_files" == "true" ]]; then
    bff_public_key_paths="$expected_bff_mapping"
    ai_public_key_paths="{\"${user_active_kid}\":\"/run/keys/user-signing-public\"}"
else
    bff_public_key_paths="$(required_value "$bff_vars_file" HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS)"
    ai_public_key_paths="$(required_value "$ai_vars_file" HOME_AI_JWT_PUBLIC_KEY_PATHS)"
fi
if [[ "$using_default_runtime_files" == "true" ]]; then
    ai_property_dsn="$(AI_READER_PASSWORD="$ai_property_reader_db_password" python3 - <<'PY'
import os
from urllib.parse import quote

password = quote(os.environ["AI_READER_PASSWORD"], safe="")
print(f"postgresql://home_search_ai_reader:{password}@postgis:5432/home_search")
PY
)"
else
    ai_property_dsn="$(required_value "$ai_vars_file" HOME_AI_PROPERTY_DSN)"
fi
default_ai_reference_dsn="$(AI_RUNTIME_PASSWORD="$ai_data_runtime_db_password" python3 - <<'PY'
import os
from urllib.parse import quote

password = quote(os.environ["AI_RUNTIME_PASSWORD"], safe="")
print(f"postgresql://home_search_ai_runtime:{password}@postgis:5432/home_search_ai")
PY
)"
if [[ "$using_default_runtime_files" == "true" ]]; then
    ai_reference_dsn="$default_ai_reference_dsn"
else
    ai_reference_dsn="$(optional_value "$ai_vars_file" HOME_AI_REFERENCE_DSN "$default_ai_reference_dsn")"
fi
ai_enabled_reference_capabilities="$(optional_blank_value "$ai_vars_file" HOME_AI_ENABLED_REFERENCE_CAPABILITIES "")"
ai_openai_api_key="$(required_value "$ai_vars_file" HOME_AI_OPENAI_API_KEY)"
ai_openai_primary_model="$(required_value "$ai_vars_file" HOME_AI_OPENAI_PRIMARY_MODEL)"
ai_openai_secondary_model="$(required_value "$ai_vars_file" HOME_AI_OPENAI_SECONDARY_MODEL)"
ai_openai_timeout_seconds="$(optional_value "$ai_vars_file" HOME_AI_OPENAI_TIMEOUT_SECONDS 8)"
ai_query_timeout_seconds="$(optional_value "$ai_vars_file" HOME_AI_QUERY_TIMEOUT_SECONDS 45)"
if [[ "$using_default_runtime_files" == "true" ]]; then
    ai_enabled_property_capabilities="$(optional_value "$ai_vars_file" HOME_AI_ENABLED_PROPERTY_CAPABILITIES complex_identity,recent_trade_lookup,price_trend)"
else
    ai_enabled_property_capabilities="$(required_value "$ai_vars_file" HOME_AI_ENABLED_PROPERTY_CAPABILITIES)"
fi

[[ "$user_db_password" == "$user_runtime_db_password" ]] \
    || reject "USER_DB_PASSWORD와 USER_RUNTIME_DB_PASSWORD가 일치하지 않습니다."
[[ "$bff_public_key_paths" == "$expected_bff_mapping" ]] \
    || reject "user JWT kid와 BFF public-key mapping이 일치하지 않습니다."

if ! AI_PUBLIC_KEY_PATHS="$ai_public_key_paths" USER_ACTIVE_KID="$user_active_kid" python3 - <<'PY'
import json
import os
import sys

try:
    mapping = json.loads(os.environ["AI_PUBLIC_KEY_PATHS"])
except (KeyError, json.JSONDecodeError):
    sys.exit(1)
expected = {os.environ["USER_ACTIVE_KID"]: "/run/keys/user-signing-public"}
sys.exit(0 if mapping == expected else 1)
PY
then
    reject "user JWT kid와 AI public-key mapping이 일치하지 않습니다."
fi

[[ "$ai_property_dsn" != *[[:space:]]* ]] \
    || reject "HOME_AI_PROPERTY_DSN에 공백을 사용할 수 없습니다."
if ! AI_PROPERTY_DSN="$ai_property_dsn" AI_READER_PASSWORD="$ai_property_reader_db_password" python3 - <<'PY'
import os
import sys
from urllib.parse import quote, unquote, urlsplit

try:
    raw_dsn = os.environ["AI_PROPERTY_DSN"]
    reader_password = os.environ["AI_READER_PASSWORD"]
    expected_dsn = (
        "postgresql://home_search_ai_reader:"
        f"{quote(reader_password, safe='')}@postgis:5432/home_search"
    )
    parsed = urlsplit(raw_dsn)
    valid = (
        raw_dsn == expected_dsn
        and parsed.scheme == "postgresql"
        and parsed.username == "home_search_ai_reader"
        and unquote(parsed.password or "") == reader_password
        and parsed.hostname == "postgis"
        and parsed.port == 5432
        and parsed.path == "/home_search"
        and not parsed.query
        and not parsed.fragment
    )
except (KeyError, ValueError):
    valid = False
sys.exit(0 if valid else 1)
PY
then
    reject "HOME_AI_PROPERTY_DSN과 AI_PROPERTY_READER_DB_PASSWORD가 일치하지 않습니다."
fi

[[ "$ai_reference_dsn" != *[[:space:]]* ]] \
    || reject "HOME_AI_REFERENCE_DSN에 공백을 사용할 수 없습니다."
if ! AI_REFERENCE_DSN="$ai_reference_dsn" AI_RUNTIME_PASSWORD="$ai_data_runtime_db_password" python3 - <<'PY'
import os
import sys
from urllib.parse import quote, unquote, urlsplit

try:
    raw_dsn = os.environ["AI_REFERENCE_DSN"]
    runtime_password = os.environ["AI_RUNTIME_PASSWORD"]
    expected_dsn = (
        "postgresql://home_search_ai_runtime:"
        f"{quote(runtime_password, safe='')}@postgis:5432/home_search_ai"
    )
    parsed = urlsplit(raw_dsn)
    valid = (
        raw_dsn == expected_dsn
        and parsed.scheme == "postgresql"
        and parsed.username == "home_search_ai_runtime"
        and unquote(parsed.password or "") == runtime_password
        and parsed.hostname == "postgis"
        and parsed.port == 5432
        and parsed.path == "/home_search_ai"
        and not parsed.query
        and not parsed.fragment
    )
except (KeyError, ValueError):
    valid = False
sys.exit(0 if valid else 1)
PY
then
    reject "HOME_AI_REFERENCE_DSN과 AI_DATA_RUNTIME_DB_PASSWORD가 일치하지 않습니다."
fi

if ! AI_OPENAI_API_KEY="$ai_openai_api_key" \
    AI_OPENAI_PRIMARY_MODEL="$ai_openai_primary_model" \
    AI_OPENAI_SECONDARY_MODEL="$ai_openai_secondary_model" \
    AI_OPENAI_TIMEOUT_SECONDS="$ai_openai_timeout_seconds" \
    AI_QUERY_TIMEOUT_SECONDS="$ai_query_timeout_seconds" python3 - <<'PY'
import math
import os
import sys


def normalized(value: str, maximum: int) -> bool:
    return (
        value == value.strip()
        and 0 < len(value) <= maximum
        and all(ord(character) >= 32 and ord(character) != 127 for character in value)
    )


try:
    api_key = os.environ["AI_OPENAI_API_KEY"]
    primary = os.environ["AI_OPENAI_PRIMARY_MODEL"]
    secondary = os.environ["AI_OPENAI_SECONDARY_MODEL"]
    timeout = float(os.environ["AI_OPENAI_TIMEOUT_SECONDS"])
    query_timeout = float(os.environ["AI_QUERY_TIMEOUT_SECONDS"])
    valid = (
        normalized(api_key, 512)
        and normalized(primary, 100)
        and normalized(secondary, 100)
        and primary != secondary
        and math.isfinite(timeout)
        and 1 <= timeout <= 30
        and math.isfinite(query_timeout)
        and 1 <= query_timeout <= 60
    )
except (KeyError, ValueError):
    valid = False
sys.exit(0 if valid else 1)
PY
then
    reject "HOME_AI_OPENAI 설정이 올바르지 않습니다."
fi
case "$ai_enabled_property_capabilities" in
    complex_identity | complex_identity,recent_trade_lookup | complex_identity,recent_trade_lookup,price_trend) ;;
    *) reject "HOME_AI_ENABLED_PROPERTY_CAPABILITIES는 승인된 누적 설정만 허용합니다." ;;
esac
case "$ai_enabled_reference_capabilities" in
    "" | school_location) ;;
    *) reject "HOME_AI_ENABLED_REFERENCE_CAPABILITIES는 빈 값 또는 school_location만 허용합니다." ;;
esac

export HOME_SEARCH_DB_PASSWORD="$home_search_db_password"
export PROPERTY_RUNTIME_DB_PASSWORD="$property_runtime_db_password"
export PROPERTY_MIGRATOR_DB_PASSWORD="$property_migrator_db_password"
export AI_PROPERTY_READER_DB_PASSWORD="$ai_property_reader_db_password"
export AI_DATA_MIGRATOR_DB_PASSWORD="$ai_data_migrator_db_password"
export AI_DATA_IMPORTER_DB_PASSWORD="$ai_data_importer_db_password"
export AI_DATA_RUNTIME_DB_PASSWORD="$ai_data_runtime_db_password"
export USER_RUNTIME_DB_PASSWORD="$user_runtime_db_password"
export USER_MIGRATOR_DB_PASSWORD="$user_migrator_db_password"
export HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS="$bff_public_key_paths"
export HOME_AI_PROPERTY_DSN="$ai_property_dsn"
export HOME_AI_JWT_PUBLIC_KEY_PATHS="$ai_public_key_paths"
export HOME_AI_OPENAI_API_KEY="$ai_openai_api_key"
export HOME_AI_OPENAI_PRIMARY_MODEL="$ai_openai_primary_model"
export HOME_AI_OPENAI_SECONDARY_MODEL="$ai_openai_secondary_model"
export HOME_AI_OPENAI_TIMEOUT_SECONDS="$ai_openai_timeout_seconds"
export HOME_AI_QUERY_TIMEOUT_SECONDS="$ai_query_timeout_seconds"
export HOME_AI_ENABLED_PROPERTY_CAPABILITIES="$ai_enabled_property_capabilities"
export HOME_AI_REFERENCE_DSN="$ai_reference_dsn"
export HOME_AI_ENABLED_REFERENCE_CAPABILITIES="$ai_enabled_reference_capabilities"
export CHATBOT_BFF_JAR_PATH="$bff_jar"
export USER_JWT_PUBLIC_KEY_HOST_PATH="$user_public_key"
export USER_JWT_PRIVATE_KEY_HOST_PATH="$user_private_key"

compose=(docker compose -f "$base_compose" -f "$chatbot_compose")

require_base_container() {
    local container="$1"
    local health_required="$2"
    local state
    state="$(docker inspect \
        --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
        "$container" 2>/dev/null)" \
        || reject "기존 ${container} 컨테이너를 확인할 수 없습니다."
    [[ "$state" == running\|* ]] || reject "기존 ${container} 컨테이너가 실행 중이 아닙니다."
    if [[ "$health_required" == "true" ]]; then
        [[ "$state" == "running|healthy" ]] || reject "기존 ${container} 컨테이너가 healthy 상태가 아닙니다."
    fi
}

require_base_container home-search-postgis true
require_base_container home-search-redis true
require_base_container home-search-api false
"${compose[@]}" config --quiet
"$ai_database_bootstrap"

echo "상태: Pass - chatbot local preflight"
"${compose[@]}" --profile user up -d --build --force-recreate --no-deps \
    user-service ai chat-bff public-api-gateway
