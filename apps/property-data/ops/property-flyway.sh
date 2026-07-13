#!/usr/bin/env bash
set -euo pipefail

readonly SERVICE_NAME="property-data"
readonly EXPECTED_DATABASE="home_search"
readonly FLYWAY_IMAGE="redgate/flyway:11.7.2"
readonly SERVICE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SERVICE_ROOT}/../.." && pwd)"

usage() {
    printf '사용법: %s info|validate|migrate <target>\n' "$0" >&2
    exit 2
}

require_migrator_environment() {
    : "${PROPERTY_MIGRATOR_JDBC_URL:?PROPERTY_MIGRATOR_JDBC_URL is required}"
    : "${PROPERTY_MIGRATOR_DB_USERNAME:?PROPERTY_MIGRATOR_DB_USERNAME is required}"
    : "${PROPERTY_MIGRATOR_DB_PASSWORD:?PROPERTY_MIGRATOR_DB_PASSWORD is required}"

    local jdbc_without_query="${PROPERTY_MIGRATOR_JDBC_URL%%\?*}"
    local database_name="${jdbc_without_query##*/}"
    if [[ "${database_name}" != "${EXPECTED_DATABASE}" ]]; then
        printf '거부됨: JDBC database는 %s여야 합니다.\n' "${EXPECTED_DATABASE}" >&2
        exit 2
    fi
}

run_flyway() {
    local -a network_args=()
    if [[ -n "${MIGRATION_DOCKER_NETWORK:-}" ]]; then
        network_args=(--network "${MIGRATION_DOCKER_NETWORK}")
    fi
    FLYWAY_URL="${PROPERTY_MIGRATOR_JDBC_URL}" \
    FLYWAY_USER="${PROPERTY_MIGRATOR_DB_USERNAME}" \
    FLYWAY_PASSWORD="${PROPERTY_MIGRATOR_DB_PASSWORD}" \
    docker run --rm --platform linux/amd64 "${network_args[@]}" \
        -v "${SERVICE_ROOT}/db/migration/api:/flyway/sql:ro" \
        -v "${SERVICE_ROOT}/db:/flyway/conf:ro" \
        -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD \
        -e REDGATE_DISABLE_TELEMETRY=true \
        "${FLYWAY_IMAGE}" "$@"
}

highest_pending_version() {
    tr -d '\r\n' | tr '{' '\n' | awk '
        /"state"[[:space:]]*:[[:space:]]*"Pending"/ && /"version"[[:space:]]*:/ {
            value = $0
            sub(/^.*"version"[[:space:]]*:[[:space:]]*"/, "", value)
            sub(/".*$/, "", value)
            if (value ~ /^[0-9]+$/) print value
        }
    ' | sort -n | tail -n 1
}

record_migration_evidence() {
    : "${MIGRATION_EVIDENCE_FILE:?MIGRATION_EVIDENCE_FILE is required for migrate}"
    if [[ ! -f "${MIGRATION_EVIDENCE_FILE}" ]]; then
        printf '거부됨: MIGRATION_EVIDENCE_FILE은 미리 생성된 파일이어야 합니다.\n' >&2
        exit 2
    fi
    local timestamp git_sha
    timestamp="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    git_sha="$(git -C "${REPOSITORY_ROOT}" rev-parse HEAD)"
    printf 'timestamp=%s service=%s target=%s image=%s git_sha=%s\n' \
        "${timestamp}" "${SERVICE_NAME}" "$1" "${FLYWAY_IMAGE}" "${git_sha}" \
        >> "${MIGRATION_EVIDENCE_FILE}"
}

main() {
    [[ $# -ge 1 ]] || usage
    local operation="$1"
    shift

    case "${operation}" in
        info|validate)
            [[ $# -eq 0 ]] || usage
            require_migrator_environment
            run_flyway "${operation}"
            ;;
        migrate)
            [[ $# -eq 1 && "$1" =~ ^[1-9][0-9]*$ ]] || usage
            local target="$1" info_json pending
            require_migrator_environment
            : "${MIGRATION_EVIDENCE_FILE:?MIGRATION_EVIDENCE_FILE is required for migrate}"
            [[ -f "${MIGRATION_EVIDENCE_FILE}" ]] || {
                printf '거부됨: MIGRATION_EVIDENCE_FILE은 미리 생성된 파일이어야 합니다.\n' >&2
                exit 2
            }
            info_json="$(run_flyway -outputType=json info)"
            printf '%s\n' "${info_json}"
            pending="$(printf '%s\n' "${info_json}" | highest_pending_version)"
            if [[ "${pending}" != "${target}" ]]; then
                printf '거부됨: 최고 pending version(%s)이 요청 target(%s)과 다릅니다.\n' \
                    "${pending:-none}" "${target}" >&2
                exit 2
            fi
            run_flyway "-target=${target}" migrate
            run_flyway validate
            record_migration_evidence "${target}"
            ;;
        *)
            usage
            ;;
    esac
}

main "$@"
