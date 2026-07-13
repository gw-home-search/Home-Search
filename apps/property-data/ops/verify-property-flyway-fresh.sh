#!/usr/bin/env bash
set -euo pipefail

readonly SERVICE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SERVICE_ROOT}/../.." && pwd)"
readonly ROLE_INIT_SCRIPT="${REPOSITORY_ROOT}/infra/postgres/init/10-create-service-databases-and-roles.sh"
readonly SUFFIX="${PPID}-$$"
readonly NETWORK="home-search-property-flyway-${SUFFIX}"
readonly DATABASE_CONTAINER="home-search-property-flyway-db-${SUFFIX}"
readonly PASSWORD="flyway-smoke-only"
readonly EVIDENCE_FILE="${TMPDIR:-/tmp}/home-search-property-flyway-${SUFFIX}.evidence"

cleanup() {
    unlink "${EVIDENCE_FILE}" >/dev/null 2>&1 || true
    docker stop "${DATABASE_CONTAINER}" >/dev/null 2>&1 || true
    docker rm "${DATABASE_CONTAINER}" >/dev/null 2>&1 || true
    docker network rm "${NETWORK}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "${NETWORK}" >/dev/null
docker run -d --name "${DATABASE_CONTAINER}" --network "${NETWORK}" \
    -e POSTGRES_DB=home_search -e POSTGRES_USER=home_search -e POSTGRES_PASSWORD="${PASSWORD}" \
    postgis/postgis:16-3.4 >/dev/null

for _ in $(seq 1 40); do
    if docker exec "${DATABASE_CONTAINER}" pg_isready -U home_search -d home_search >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
docker exec "${DATABASE_CONTAINER}" pg_isready -U home_search -d home_search >/dev/null
for _ in $(seq 1 20); do
    if docker run --rm --network "${NETWORK}" postgres:16.3-alpine \
        pg_isready -h "${DATABASE_CONTAINER}" -U home_search -d home_search >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
docker exec -i \
    -e POSTGRES_USER=home_search \
    -e PROPERTY_RUNTIME_DB_PASSWORD=property-runtime-smoke-only \
    -e PROPERTY_MIGRATOR_DB_PASSWORD="${PASSWORD}" \
    -e ADMIN_RUNTIME_DB_PASSWORD=admin-runtime-smoke-only \
    -e ADMIN_MIGRATOR_DB_PASSWORD=admin-migrator-smoke-only \
    -e USER_RUNTIME_DB_PASSWORD=user-runtime-smoke-only \
    -e USER_MIGRATOR_DB_PASSWORD=user-migrator-smoke-only \
    "${DATABASE_CONTAINER}" bash -s < "${ROLE_INIT_SCRIPT}"
role_safety="$(docker exec "${DATABASE_CONTAINER}" psql -U home_search -d postgres -Atc \
    "SELECT rolsuper||'|'||rolcreatedb||'|'||rolcreaterole||'|'||rolreplication||'|'||rolbypassrls||'|'||pg_has_role('home_search_property_migrator','home_search','SET') FROM pg_roles WHERE rolname='home_search_property_migrator'")"
[[ "${role_safety}" == 'false|false|false|false|false|false' ]] || {
    printf 'property migrator 최소 권한 검증 실패: %s\n' "${role_safety}" >&2
    exit 1
}
if docker run --rm --network "${NETWORK}" -e PGPASSWORD="${PASSWORD}" postgres:16.3-alpine \
    psql -h "${DATABASE_CONTAINER}" -U home_search_property_migrator -d home_search_admin \
    -X -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null 2>&1; then
    printf 'property migrator가 admin database에 연결했습니다.\n' >&2
    exit 1
fi
: > "${EVIDENCE_FILE}"
export PROPERTY_MIGRATOR_JDBC_URL="jdbc:postgresql://${DATABASE_CONTAINER}:5432/home_search"
export PROPERTY_MIGRATOR_DB_USERNAME=home_search_property_migrator
export PROPERTY_MIGRATOR_DB_PASSWORD="${PASSWORD}"
export MIGRATION_DOCKER_NETWORK="${NETWORK}"
export MIGRATION_EVIDENCE_FILE="${EVIDENCE_FILE}"

"${SERVICE_ROOT}/ops/property-deployment-preflight.sh" before 8
"${SERVICE_ROOT}/ops/property-flyway.sh" migrate 8 >/dev/null
"${SERVICE_ROOT}/ops/property-deployment-preflight.sh" after 8
"${SERVICE_ROOT}/ops/property-flyway.sh" validate >/dev/null
installed_versions="$(docker exec "${DATABASE_CONTAINER}" psql -U home_search -d home_search \
    -v ON_ERROR_STOP=1 -Atc \
    "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version IS NOT NULL AND success")"
if [[ "${installed_versions}" != '1,2,4,5,6,7,8' ]]; then
    printf '예상하지 않은 property-data migration history: %s\n' "${installed_versions}" >&2
    exit 1
fi
