#!/usr/bin/env bash
set -euo pipefail

readonly SERVICE_NAME="user-service"
readonly EXPECTED_DATABASE="home_search_user"
readonly FLYWAY_IMAGE="redgate/flyway:12.4.0"
readonly POSTGRES_CLIENT_IMAGE="postgres:16.3-alpine"
readonly SERVICE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly MIGRATION_DIRECTORY="${SERVICE_ROOT}/db/migration/user"

policy_error() {
    printf '거부됨: %s\n' "$1" >&2
    exit 2
}

runtime_error() {
    printf '실패: %s\n' "$1" >&2
    exit 1
}

require_inputs() {
    [[ $# -eq 2 ]] || policy_error 'before|after와 양의 정수 target이 필요합니다.'
    [[ "$1" == "before" || "$1" == "after" ]] || policy_error 'phase는 before 또는 after여야 합니다.'
    [[ "$2" =~ ^[1-9][0-9]*$ ]] || policy_error 'target은 양의 정수여야 합니다.'
    [[ -n "${USER_MIGRATOR_JDBC_URL:-}" ]] || policy_error 'USER_MIGRATOR_JDBC_URL이 필요합니다.'
    [[ -n "${USER_MIGRATOR_DB_USERNAME:-}" ]] || policy_error 'USER_MIGRATOR_DB_USERNAME이 필요합니다.'
    [[ -n "${USER_MIGRATOR_DB_PASSWORD:-}" ]] || policy_error 'USER_MIGRATOR_DB_PASSWORD가 필요합니다.'
    command -v docker >/dev/null 2>&1 || runtime_error 'Docker를 찾을 수 없습니다.'

    local jdbc_without_query="${USER_MIGRATOR_JDBC_URL%%\?*}"
    local database_name="${jdbc_without_query##*/}"
    [[ "${database_name}" == "${EXPECTED_DATABASE}" ]] || policy_error "JDBC database는 ${EXPECTED_DATABASE}여야 합니다."
    [[ "${USER_MIGRATOR_JDBC_URL}" == jdbc:postgresql://* ]] || policy_error 'PostgreSQL JDBC URL만 허용합니다.'
    [[ "${USER_MIGRATOR_JDBC_URL}" != *password=* ]] || policy_error 'JDBC URL에 password를 포함할 수 없습니다.'
}

catalog_versions() {
    local file version
    local -a files=("${MIGRATION_DIRECTORY}"/V*__*.sql)
    [[ -e "${files[0]}" ]] || policy_error 'resolved SQL migration catalog가 비어 있습니다.'
    for file in "${files[@]}"; do
        version="$(basename "${file}")"
        version="${version#V}"
        version="${version%%__*}"
        [[ "${version}" =~ ^[1-9][0-9]*$ ]] || policy_error 'migration version 형식이 잘못됐습니다.'
        printf '%s\n' "${version}"
    done | sort -n
}

run_flyway() {
    local -a network_args=()
    if [[ -n "${MIGRATION_DOCKER_NETWORK:-}" ]]; then
        network_args=(--network "${MIGRATION_DOCKER_NETWORK}")
    fi
    FLYWAY_URL="${USER_MIGRATOR_JDBC_URL}" \
    FLYWAY_USER="${USER_MIGRATOR_DB_USERNAME}" \
    FLYWAY_PASSWORD="${USER_MIGRATOR_DB_PASSWORD}" \
    docker run --rm --platform linux/amd64 "${network_args[@]}" \
        -v "${MIGRATION_DIRECTORY}:/flyway/sql:ro" \
        -v "${SERVICE_ROOT}/db:/flyway/conf:ro" \
        -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD \
        -e REDGATE_DISABLE_TELEMETRY=true \
        "${FLYWAY_IMAGE}" "$@"
}

run_psql() {
    local sql="$1"
    local libpq_url="${USER_MIGRATOR_JDBC_URL#jdbc:}"
    local -a network_args=()
    if [[ -n "${MIGRATION_DOCKER_NETWORK:-}" ]]; then
        network_args=(--network "${MIGRATION_DOCKER_NETWORK}")
    fi
    PGPASSWORD="${USER_MIGRATOR_DB_PASSWORD}" \
    PGOPTIONS='-c default_transaction_read_only=on' \
    docker run --rm "${network_args[@]}" \
        -e PGPASSWORD -e PGOPTIONS \
        "${POSTGRES_CLIENT_IMAGE}" \
        psql "${libpq_url}" --username "${USER_MIGRATOR_DB_USERNAME}" \
        -X -v ON_ERROR_STOP=1 -At -c "${sql}"
}

flyway_rows() {
    local json="$1"
    local chunk version type state
    while IFS= read -r chunk || [[ -n "${chunk}" ]]; do
        version="$(printf '%s' "${chunk}" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
        [[ -n "${version}" ]] || continue
        type="$(printf '%s' "${chunk}" | sed -n 's/.*"type"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
        state="$(printf '%s' "${chunk}" | sed -n 's/.*"state"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
        printf '%s|%s|%s\n' "${version}" "${type}" "${state}"
    done < <(printf '%s' "${json}" | tr -d '\r\n' | tr '{' '\n')
}

verify_catalog_state() {
    local phase="$1" target="$2" info_json="$3"
    local expected_state rows catalog actual_versions duplicate highest compact_info
    expected_state="$([[ "${phase}" == "before" ]] && printf Pending || printf Success)"
    catalog="$(catalog_versions)"
    duplicate="$(printf '%s\n' "${catalog}" | uniq -d | head -n 1)"
    [[ -z "${duplicate}" ]] || policy_error "duplicate catalog version ${duplicate}"
    highest="$(printf '%s\n' "${catalog}" | tail -n 1)"
    [[ "${highest}" == "${target}" ]] || policy_error "최고 resolved version(${highest})이 target(${target})과 다릅니다."

    rows="$(flyway_rows "${info_json}")"
    [[ -n "${rows}" ]] || policy_error 'Flyway info에 resolved migration이 없습니다.'
    compact_info="$(printf '%s' "${info_json}" | tr -d '[:space:]')"
    [[ "${compact_info}" != *'"category":"Repeatable"'* ]] || policy_error 'repeatable migration은 fresh-only catalog에 허용되지 않습니다.'
    actual_versions="$(printf '%s\n' "${rows}" | cut -d'|' -f1 | sort -n)"
    [[ "${actual_versions}" == "${catalog}" ]] || policy_error 'Flyway resolved version set이 SQL catalog와 다릅니다.'
    while IFS='|' read -r version type state; do
        [[ "${version}" =~ ^[1-9][0-9]*$ ]] || policy_error 'numeric version이 아닌 migration이 있습니다.'
        [[ "${type}" == "SQL" ]] || policy_error "version ${version} type은 SQL이어야 합니다."
        [[ "${state}" == "${expected_state}" ]] || policy_error "version ${version} state는 ${expected_state}여야 합니다."
    done <<< "${rows}"
}

verify_history_rows() {
    local actual="$1" catalog expected
    catalog="$(catalog_versions)"
    expected="$(printf '%s\n' "${catalog}" | sed 's/$/|SQL|t/')"
    actual="$(printf '%s' "${actual}" | sed '/^[[:space:]]*$/d' | sort -t'|' -k1,1n)"
    [[ "${actual}" == "${expected}" ]] || policy_error 'Flyway history가 version별 SQL/Success 정확히 한 건이 아닙니다.'
}

main() {
    require_inputs "$@"
    local phase="$1" target="$2"
    local current_database history_present relation_count info_json history_rows validate_json compact_validate

    if ! current_database="$(run_psql 'SELECT current_database();')"; then
        runtime_error 'read-only current_database probe가 실패했습니다.'
    fi
    [[ "${current_database}" == "${EXPECTED_DATABASE}" ]] || policy_error "current_database()가 ${EXPECTED_DATABASE}와 다릅니다."

    if ! history_present="$(run_psql "SELECT to_regclass('users.flyway_schema_history') IS NOT NULL;")"; then
        runtime_error 'Flyway history probe가 실패했습니다.'
    fi
    if ! relation_count="$(run_psql "SELECT /* service_owned_relations */ count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='users' AND c.relkind IN ('r','p','v','m','S','f');")"; then
        runtime_error 'service-owned relation probe가 실패했습니다.'
    fi
    [[ "${relation_count}" =~ ^[0-9]+$ ]] || runtime_error 'relation probe 결과 형식이 잘못됐습니다.'

    if ! info_json="$(run_flyway -outputType=json info)"; then
        runtime_error 'Flyway info 실행이 실패했습니다.'
    fi
    verify_catalog_state "${phase}" "${target}" "${info_json}"

    if [[ "${phase}" == "before" ]]; then
        [[ "${history_present}" == "f" || "${history_present}" == "false" ]] || policy_error 'fresh DB에 Flyway history가 존재합니다.'
        [[ "${relation_count}" == "0" ]] || policy_error 'Flyway history 없이 service-owned relation이 존재합니다.'
        printf 'service=%s phase=before target=%s state=EMPTY\n' "${SERVICE_NAME}" "${target}"
        return
    fi

    [[ "${history_present}" == "t" || "${history_present}" == "true" ]] || policy_error 'Flyway history가 없습니다.'
    if ! history_rows="$(run_psql "SELECT /* preflight_history_rows */ version || '|' || type || '|' || CASE WHEN success THEN 't' ELSE 'f' END FROM users.flyway_schema_history ORDER BY installed_rank;")"; then
        runtime_error 'Flyway history 조회가 실패했습니다.'
    fi
    verify_history_rows "${history_rows}"
    if ! validate_json="$(run_flyway -outputType=json validate)"; then
        runtime_error 'Flyway validate 실행이 실패했습니다.'
    fi
    compact_validate="$(printf '%s' "${validate_json}" | tr -d '[:space:]')"
    [[ "${compact_validate}" == *'"validationSuccessful":true'* ]] || policy_error 'Flyway validationSuccessful이 true가 아닙니다.'
    [[ "${compact_validate}" == *'"invalidMigrations":[]'* ]] || policy_error 'invalidMigrations가 비어 있지 않습니다.'
    printf 'service=%s phase=after target=%s state=READY\n' "${SERVICE_NAME}" "${target}"
}

main "$@"
