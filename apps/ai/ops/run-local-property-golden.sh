#!/usr/bin/env bash
set -euo pipefail
umask 077

activation_stderr=""
cleanup() {
    if [[ -n "$activation_stderr" && -f "$activation_stderr" ]]; then
        unlink "$activation_stderr"
    fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ai_root="$(cd "${script_dir}/.." && pwd)"
mode="${1:-}"

usage() {
    cat >&2 <<EOF
사용법:
  $0 offline [ai-vars-file]
  $0 live --case-id <approved-case-id> [ai-vars-file]
EOF
    exit 2
}

reject() {
    echo "거부됨: $1" >&2
    exit 1
}

[[ -n "$mode" ]] || usage
shift

case_id=""
if [[ "$mode" == "offline" ]]; then
    (( $# <= 1 )) || usage
    vars_file="${1:-${ai_root}/.env}"
elif [[ "$mode" == "live" ]]; then
    (( $# == 2 || $# == 3 )) || usage
    [[ "$1" == "--case-id" && -n "$2" ]] || usage
    case_id="$2"
    vars_file="${3:-${ai_root}/.env}"
    case "$case_id" in
        complex-identity-jamsil-ells | recent-trades-jamsil-ells-84 | price-trend-jamsil-ells-84 | criteria-recommendation-academy-transit | school-location-jamsil-ells | comparison-jamsil-ells-helio-84) ;;
        *) reject "승인된 live case ID가 아닙니다." ;;
    esac
else
    usage
fi

[[ -f "$vars_file" && ! -L "$vars_file" ]] \
    || reject "AI runtime vars 파일이 없거나 일반 파일이 아닙니다."

permissions="$(stat -c '%a' "$vars_file" 2>/dev/null || stat -f '%Lp' "$vars_file" 2>/dev/null || true)"
[[ "$permissions" =~ ^[0-7]{3,4}$ ]] || reject "AI runtime vars 파일 권한을 확인할 수 없습니다."
(( 10#$permissions % 100 == 0 )) \
    || reject "AI runtime vars 파일은 group/other 권한이 없어야 합니다. chmod 600을 적용하세요."

read_value() {
    local path="$1"
    local key="$2"
    local count
    local value
    count="$(awk -v key="$key" '
        /^[[:space:]]*#/ { next }
        index($0, key "=") == 1 { count += 1 }
        END { print count + 0 }
    ' "$path")"
    [[ "$count" == "1" ]] || reject "${key}는 정확히 한 번 정의해야 합니다."
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
    [[ -n "$value" ]] || reject "${key} 값이 없습니다."
    [[ "$value" != *'replace-with'* && "$value" != *'<'* && "$value" != *'>'* ]] \
        || reject "${key} placeholder를 실제 설정으로 교체해야 합니다."
    printf '%s' "$value"
}

property_dsn="$(read_value "$vars_file" HOME_AI_PROPERTY_DSN)"
if ! property_password="$(AI_PROPERTY_DSN="$property_dsn" python3 - <<'PY'
import os
import sys
from urllib.parse import unquote, urlsplit

try:
    parsed = urlsplit(os.environ["AI_PROPERTY_DSN"])
    password = unquote(parsed.password or "")
    host_and_port = (parsed.hostname, parsed.port)
    valid = (
        parsed.scheme == "postgresql"
        and parsed.username == "home_search_ai_reader"
        and host_and_port in {
            ("postgis", 5432),
            ("127.0.0.1", 15432),
            ("localhost", 15432),
        }
        and parsed.path == "/home_search"
        and not parsed.query
        and not parsed.fragment
        and password
        and len(password) <= 512
        and all(ord(character) >= 32 and ord(character) != 127 for character in password)
    )
except (KeyError, ValueError):
    valid = False
    password = ""

if not valid:
    sys.exit(1)
print(password, end="")
PY
)"; then
    reject "HOME_AI_PROPERTY_DSN은 승인된 local AI reader 연결 형식이어야 합니다."
fi

host_property_dsn="host=127.0.0.1 port=15432 dbname=home_search user=home_search_ai_reader"
host_property_uri=""
host_reference_uri=""
if [[ "$case_id" == "school-location-jamsil-ells" || "$case_id" == "comparison-jamsil-ells-helio-84" ]]; then
    reference_property_vars_file="${HOME_AI_REFERENCE_PROPERTY_VARS_FILE:-}"
    if [[ -z "$reference_property_vars_file" && "$vars_file" == "${ai_root}/.env" ]]; then
        reference_property_vars_file="${ai_root}/../property-data/.env"
    fi
    if [[ -n "$reference_property_vars_file" ]]; then
        [[ -f "$reference_property_vars_file" && ! -L "$reference_property_vars_file" ]] \
            || reject "reference property vars 파일이 없거나 일반 파일이 아닙니다."
        reference_permissions="$(stat -c '%a' "$reference_property_vars_file" 2>/dev/null || stat -f '%Lp' "$reference_property_vars_file" 2>/dev/null || true)"
        [[ "$reference_permissions" =~ ^[0-7]{3,4}$ ]] \
            || reject "reference property vars 파일 권한을 확인할 수 없습니다."
        (( 10#$reference_permissions % 100 == 0 )) \
            || reject "reference property vars 파일은 group/other 권한이 없어야 합니다."
        reference_password="$(read_value "$reference_property_vars_file" AI_DATA_RUNTIME_DB_PASSWORD)"
        if ! host_reference_uri="$(REFERENCE_PASSWORD="$reference_password" python3 - <<'PY'
import os
import sys
from urllib.parse import quote

password = os.environ["REFERENCE_PASSWORD"]
valid = (
    0 < len(password) <= 512
    and password == password.strip()
    and all(ord(character) >= 32 and ord(character) != 127 for character in password)
)
if not valid:
    sys.exit(1)
print(
    "postgresql://home_search_ai_runtime:"
    f"{quote(password, safe='')}@127.0.0.1:15432/home_search_ai",
    end="",
)
PY
)"; then
            reject "AI_DATA_RUNTIME_DB_PASSWORD 설정이 올바르지 않습니다."
        fi
    else
        reference_dsn="$(read_value "$vars_file" HOME_AI_REFERENCE_DSN)"
        if ! host_reference_uri="$(AI_REFERENCE_DSN="$reference_dsn" python3 - <<'PY'
import os
import sys
from urllib.parse import quote, unquote, urlsplit

try:
    parsed = urlsplit(os.environ["AI_REFERENCE_DSN"])
    password = unquote(parsed.password or "")
    valid = (
        parsed.scheme == "postgresql"
        and parsed.username == "home_search_ai_runtime"
        and (parsed.hostname, parsed.port) in {
            ("postgis", 5432),
            ("127.0.0.1", 15432),
            ("localhost", 15432),
        }
        and parsed.path == "/home_search_ai"
        and not parsed.query
        and not parsed.fragment
        and password
        and len(password) <= 512
        and all(ord(character) >= 32 and ord(character) != 127 for character in password)
    )
except (KeyError, ValueError):
    valid = False
    password = ""

if not valid:
    sys.exit(1)
print(
    "postgresql://home_search_ai_runtime:"
    f"{quote(password, safe='')}@127.0.0.1:15432/home_search_ai",
    end="",
)
PY
)"; then
            reject "HOME_AI_REFERENCE_DSN은 승인된 local AI runtime 연결 형식이어야 합니다."
        fi
    fi
    host_property_uri="$(PROPERTY_PASSWORD="$property_password" python3 - <<'PY'
import os
from urllib.parse import quote

print(
    "postgresql://home_search_ai_reader:"
    f"{quote(os.environ['PROPERTY_PASSWORD'], safe='')}@127.0.0.1:15432/home_search",
    end="",
)
PY
)"
fi
uv_bin="$(command -v uv || true)"
[[ -n "$uv_bin" ]] || reject "uv 실행 파일을 찾을 수 없습니다."

cd "$ai_root"
if [[ "$mode" == "offline" ]]; then
    unset HOME_AI_OPENAI_API_KEY HOME_AI_OPENAI_PRIMARY_MODEL \
        HOME_AI_OPENAI_SECONDARY_MODEL HOME_AI_OPENAI_TIMEOUT_SECONDS \
        HOME_AI_GOLDEN_LIVE_CONFIRM
    PGPASSWORD="$property_password" HOME_AI_PROPERTY_DSN="$host_property_dsn" \
        "$uv_bin" run home-ai-property-golden --mode offline
    exit 0
fi

[[ "${HOME_AI_GOLDEN_LIVE_CONFIRM:-}" == "RUN_ONE_LIVE_GOLDEN_CASE" ]] \
    || reject "live 골든 확인값 RUN_ONE_LIVE_GOLDEN_CASE가 필요합니다."

api_key="$(read_value "$vars_file" HOME_AI_OPENAI_API_KEY)"
primary_model="$(read_value "$vars_file" HOME_AI_OPENAI_PRIMARY_MODEL)"
secondary_model="$(read_value "$vars_file" HOME_AI_OPENAI_SECONDARY_MODEL)"
timeout_seconds="$(read_value "$vars_file" HOME_AI_OPENAI_TIMEOUT_SECONDS)"

if ! AI_API_KEY="$api_key" AI_PRIMARY="$primary_model" AI_SECONDARY="$secondary_model" \
    AI_TIMEOUT="$timeout_seconds" python3 - <<'PY'
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
    api_key = os.environ["AI_API_KEY"]
    primary = os.environ["AI_PRIMARY"]
    secondary = os.environ["AI_SECONDARY"]
    timeout = float(os.environ["AI_TIMEOUT"])
    valid = (
        normalized(api_key, 512)
        and normalized(primary, 100)
        and normalized(secondary, 100)
        and primary != secondary
        and math.isfinite(timeout)
        and 1 <= timeout <= 30
    )
except (KeyError, ValueError):
    valid = False

sys.exit(0 if valid else 1)
PY
then
    reject "HOME_AI_OPENAI 설정이 올바르지 않습니다."
fi
activation_timeout_seconds="$timeout_seconds"
if [[ "$case_id" == "comparison-jamsil-ells-helio-84" ]]; then
    activation_timeout_seconds=30
fi

if [[ "$case_id" == "criteria-recommendation-academy-transit" ]]; then
    HOME_AI_OPENAI_API_KEY="$api_key" \
    HOME_AI_OPENAI_PRIMARY_MODEL="$primary_model" \
    HOME_AI_OPENAI_SECONDARY_MODEL="$secondary_model" \
    HOME_AI_OPENAI_TIMEOUT_SECONDS="$timeout_seconds" \
    HOME_AI_GOLDEN_LIVE_CONFIRM="RUN_ONE_LIVE_GOLDEN_CASE" \
        "$uv_bin" run python -m ai_service.property_chat.criteria_activation
elif [[ "$case_id" == "school-location-jamsil-ells" || "$case_id" == "comparison-jamsil-ells-helio-84" ]]; then
    activation_stderr="$(mktemp "${TMPDIR:-/tmp}/home-ai-school-activation.XXXXXX")"
    if ! HOME_AI_PROPERTY_DSN="$host_property_uri" \
        HOME_AI_REFERENCE_DSN="$host_reference_uri" \
        HOME_AI_OPENAI_API_KEY="$api_key" \
        HOME_AI_OPENAI_PRIMARY_MODEL="$primary_model" \
        HOME_AI_OPENAI_SECONDARY_MODEL="$secondary_model" \
        HOME_AI_OPENAI_TIMEOUT_SECONDS="$activation_timeout_seconds" \
        HOME_AI_GOLDEN_LIVE_CONFIRM="RUN_ONE_LIVE_GOLDEN_CASE" \
        HOME_AI_REFERENCE_ACTIVATION_CASE_ID="$case_id" \
            "$uv_bin" run python -m ai_service.property_chat.reference_activation \
            2>"$activation_stderr"; then
        exit 1
    fi
else
    PGPASSWORD="$property_password" \
    HOME_AI_PROPERTY_DSN="$host_property_dsn" \
    HOME_AI_OPENAI_API_KEY="$api_key" \
    HOME_AI_OPENAI_PRIMARY_MODEL="$primary_model" \
    HOME_AI_OPENAI_SECONDARY_MODEL="$secondary_model" \
    HOME_AI_OPENAI_TIMEOUT_SECONDS="$timeout_seconds" \
    HOME_AI_GOLDEN_LIVE_CONFIRM="RUN_ONE_LIVE_GOLDEN_CASE" \
        "$uv_bin" run home-ai-property-golden --mode live \
            --case-id "$case_id"
fi
