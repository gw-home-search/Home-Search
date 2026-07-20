#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ai_root="$(cd "${script_dir}/.." && pwd)"
repo_root="$(cd "${ai_root}/../.." && pwd)"
property_vars_file="${HOME_AI_REFERENCE_PROPERTY_VARS_FILE:-${repo_root}/apps/property-data/.env}"
image="home-search-ai:local"

usage() {
    echo "사용법: $0 status [--source <sourceId>] | audit --source <sourceId> [--limit 1..100]" >&2
    exit 2
}

reject_configuration() {
    echo "상태: Fail - $1" >&2
    exit 2
}

command_args=()
case "${1:-}" in
    status)
        if [[ "$#" == "1" ]]; then
            command_args=(home-ai-reference-status)
        elif [[ "$#" == "3" && "$2" == "--source" ]]; then
            command_args=(home-ai-reference-status --source "$3")
        else
            usage
        fi
        ;;
    audit)
        if [[ "$#" == "3" && "$2" == "--source" ]]; then
            command_args=(home-ai-reference-audit --source "$3")
        elif [[ "$#" == "5" && "$2" == "--source" && "$4" == "--limit" ]]; then
            [[ "$5" =~ ^[0-9]+$ ]] && (( 10#$5 >= 1 && 10#$5 <= 100 )) || usage
            command_args=(home-ai-reference-audit --source "$3" --limit "$5")
        else
            usage
        fi
        ;;
    *)
        usage
        ;;
esac

source_id=""
for ((index = 0; index < ${#command_args[@]}; index++)); do
    if [[ "${command_args[$index]}" == "--source" ]]; then
        source_id="${command_args[$((index + 1))]}"
    fi
done
if [[ -n "$source_id" && ! "$source_id" =~ ^[a-z0-9]+([.-][a-z0-9]+)*$ ]]; then
    reject_configuration "sourceId 형식이 올바르지 않습니다."
fi

[[ -f "$property_vars_file" && ! -L "$property_vars_file" ]] \
    || reject_configuration "property vars 파일이 없거나 일반 파일이 아닙니다."
permissions="$(stat -c '%a' "$property_vars_file" 2>/dev/null || stat -f '%Lp' "$property_vars_file" 2>/dev/null || true)"
[[ "$permissions" =~ ^[0-7]{3,4}$ ]] \
    || reject_configuration "property vars 파일 권한을 확인할 수 없습니다."
(( 10#$permissions % 100 == 0 )) \
    || reject_configuration "property vars 파일은 group/other 권한이 없어야 합니다. chmod 600을 적용하세요."

count="$(awk '
    /^[[:space:]]*#/ { next }
    index($0, "AI_DATA_RUNTIME_DB_PASSWORD=") == 1 { count += 1 }
    END { print count + 0 }
' "$property_vars_file")"
[[ "$count" == "1" ]] \
    || reject_configuration "AI_DATA_RUNTIME_DB_PASSWORD는 정확히 한 번 정의해야 합니다."
runtime_password="$(awk '
    /^[[:space:]]*#/ { next }
    index($0, "AI_DATA_RUNTIME_DB_PASSWORD=") == 1 {
        print substr($0, length("AI_DATA_RUNTIME_DB_PASSWORD") + 2)
        exit
    }
' "$property_vars_file")"
if (( ${#runtime_password} >= 2 )); then
    if [[ "${runtime_password:0:1}" == '"' && "${runtime_password: -1}" == '"' ]]; then
        runtime_password="${runtime_password:1:${#runtime_password}-2}"
    elif [[ "${runtime_password:0:1}" == "'" && "${runtime_password: -1}" == "'" ]]; then
        runtime_password="${runtime_password:1:${#runtime_password}-2}"
    fi
fi
if ! RUNTIME_PASSWORD="$runtime_password" python3 - <<'PY'
import os
import sys

value = os.environ["RUNTIME_PASSWORD"]
valid = (
    0 < len(value) <= 512
    and value == value.strip()
    and all(ord(character) >= 32 and ord(character) != 127 for character in value)
    and "replace-with" not in value
    and "<" not in value
    and ">" not in value
)
sys.exit(0 if valid else 1)
PY
then
    reject_configuration "AI_DATA_RUNTIME_DB_PASSWORD 설정이 올바르지 않습니다."
fi

command -v docker >/dev/null 2>&1 || reject_configuration "docker 명령을 찾을 수 없습니다."
network_name="$(docker inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' home-search-postgis 2>/dev/null | head -n 1)"
[[ -n "$network_name" ]] || reject_configuration "PostgreSQL local network를 확인할 수 없습니다."

runtime_dsn="$(RUNTIME_PASSWORD="$runtime_password" python3 - <<'PY'
import os
from urllib.parse import quote

password = quote(os.environ["RUNTIME_PASSWORD"], safe="")
print(f"postgresql://home_search_ai_runtime:{password}@postgis:5432/home_search_ai")
PY
)"

HOME_AI_REFERENCE_RUNTIME_DSN="$runtime_dsn" docker run --rm \
    --network "$network_name" \
    --env HOME_AI_REFERENCE_RUNTIME_DSN \
    "$image" "${command_args[@]}"
