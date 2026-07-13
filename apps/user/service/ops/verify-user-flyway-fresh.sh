#!/usr/bin/env bash
set -euo pipefail

readonly SERVICE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SUFFIX="${PPID}-$$"
readonly NETWORK="home-search-user-flyway-${SUFFIX}"
readonly DATABASE_CONTAINER="home-search-user-flyway-db-${SUFFIX}"
readonly PASSWORD="flyway-smoke-only"
readonly EVIDENCE_FILE="${TMPDIR:-/tmp}/home-search-user-flyway-${SUFFIX}.evidence"

cleanup() {
    docker stop "${DATABASE_CONTAINER}" >/dev/null 2>&1 || true
    docker rm "${DATABASE_CONTAINER}" >/dev/null 2>&1 || true
    docker network rm "${NETWORK}" >/dev/null 2>&1 || true
    unlink "${EVIDENCE_FILE}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "${NETWORK}" >/dev/null
docker run -d --name "${DATABASE_CONTAINER}" --network "${NETWORK}" \
    -e POSTGRES_DB=home_search_user -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD="${PASSWORD}" \
    postgres:16.3-alpine >/dev/null

for _ in $(seq 1 40); do
    if docker exec "${DATABASE_CONTAINER}" pg_isready -U postgres -d home_search_user >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
docker exec "${DATABASE_CONTAINER}" pg_isready -U postgres -d home_search_user >/dev/null
docker exec "${DATABASE_CONTAINER}" psql -U postgres -d home_search_user -v ON_ERROR_STOP=1 \
    -c 'CREATE ROLE home_search_user_migrator' \
    -c 'CREATE ROLE home_search_user_runtime' >/dev/null

: > "${EVIDENCE_FILE}"
export USER_MIGRATOR_JDBC_URL="jdbc:postgresql://${DATABASE_CONTAINER}:5432/home_search_user"
export USER_MIGRATOR_DB_USERNAME=postgres
export USER_MIGRATOR_DB_PASSWORD="${PASSWORD}"
export MIGRATION_DOCKER_NETWORK="${NETWORK}"
export MIGRATION_EVIDENCE_FILE="${EVIDENCE_FILE}"

"${SERVICE_ROOT}/ops/user-deployment-preflight.sh" before 5
"${SERVICE_ROOT}/ops/user-flyway.sh" migrate 5 >/dev/null
"${SERVICE_ROOT}/ops/user-deployment-preflight.sh" after 5
"${SERVICE_ROOT}/ops/user-flyway.sh" validate >/dev/null

history_rows="$(docker exec "${DATABASE_CONTAINER}" psql -U postgres -d home_search_user \
    -v ON_ERROR_STOP=1 -Atc \
    "SELECT version || '|' || type || '|' || CASE WHEN success THEN 't' ELSE 'f' END FROM users.flyway_schema_history ORDER BY installed_rank" \
    | sed '/^[[:space:]]*$/d')"
expected_history=$'1|SQL|t\n2|SQL|t\n3|SQL|t\n4|SQL|t\n5|SQL|t'
if [[ "${history_rows}" != "${expected_history}" ]]; then
    printf '예상하지 않은 user-service migration history: %s\n' "${history_rows}" >&2
    exit 1
fi
