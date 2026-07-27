#!/usr/bin/env bash
set -euo pipefail

readonly SERVICE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SERVICE_ROOT}/../.." && pwd)"
readonly ROLE_INIT_SCRIPT="${REPOSITORY_ROOT}/infra/postgres/init/10-create-service-databases-and-roles.sh"
readonly SUFFIX="${PPID}-$$"
readonly NETWORK="home-search-property-flyway-${SUFFIX}"
readonly DATABASE_CONTAINER="home-search-property-flyway-db-${SUFFIX}"
readonly PASSWORD="flyway-smoke-only"
readonly AI_READER_PASSWORD="ai-property-reader-smoke-only"
readonly AI_DATA_MIGRATOR_PASSWORD="ai-data-migrator-smoke-only"
readonly AI_DATA_IMPORTER_PASSWORD="ai-data-importer-smoke-only"
readonly AI_DATA_RUNTIME_PASSWORD="ai-data-runtime-smoke-only"
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
docker exec -i "${DATABASE_CONTAINER}" psql -U home_search -d postgres -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
CREATE ROLE ai_reader_stale_parent NOLOGIN;
CREATE ROLE home_search_ai_reader LOGIN PASSWORD 'stale-ai-reader-password';
GRANT ai_reader_stale_parent TO home_search_ai_reader;
ALTER ROLE home_search_ai_reader INHERIT SUPERUSER CREATEDB CREATEROLE REPLICATION BYPASSRLS;
SQL
docker exec -i \
    -e POSTGRES_USER=home_search \
    -e PROPERTY_RUNTIME_DB_PASSWORD=property-runtime-smoke-only \
    -e PROPERTY_MIGRATOR_DB_PASSWORD="${PASSWORD}" \
    -e AI_PROPERTY_READER_DB_PASSWORD="${AI_READER_PASSWORD}" \
    -e AI_DATA_MIGRATOR_DB_PASSWORD="${AI_DATA_MIGRATOR_PASSWORD}" \
    -e AI_DATA_IMPORTER_DB_PASSWORD="${AI_DATA_IMPORTER_PASSWORD}" \
    -e AI_DATA_RUNTIME_DB_PASSWORD="${AI_DATA_RUNTIME_PASSWORD}" \
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
ai_data_role_safety="$(docker exec "${DATABASE_CONTAINER}" psql -U home_search -d postgres -Atc \
    "SELECT count(*)||'|'||bool_and(rolcanlogin AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls)||'|'||bool_or(EXISTS (SELECT 1 FROM pg_auth_members WHERE member = roles.oid)) FROM pg_roles roles WHERE rolname IN ('home_search_ai_migrator','home_search_ai_importer','home_search_ai_runtime')")"
[[ "${ai_data_role_safety}" == '3|true|false' ]] || {
    printf 'AI data role 최소 권한 검증 실패: %s\n' "${ai_data_role_safety}" >&2
    exit 1
}
ai_database_owner="$(docker exec "${DATABASE_CONTAINER}" psql -U home_search -d postgres -Atc \
    "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='home_search_ai'")"
[[ "${ai_database_owner}" == 'home_search_ai_migrator' ]] || {
    printf 'home_search_ai owner 검증 실패: %s\n' "${ai_database_owner}" >&2
    exit 1
}
docker run --rm --network "${NETWORK}" -e PGPASSWORD="${AI_DATA_RUNTIME_PASSWORD}" postgres:16.3-alpine \
    psql -h "${DATABASE_CONTAINER}" -U home_search_ai_runtime -d home_search_ai \
    -X -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null
if docker run --rm --network "${NETWORK}" -e PGPASSWORD="${AI_DATA_RUNTIME_PASSWORD}" postgres:16.3-alpine \
    psql -h "${DATABASE_CONTAINER}" -U home_search_ai_runtime -d home_search \
    -X -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null 2>&1; then
    printf 'AI data runtime이 property database에 연결했습니다.\n' >&2
    exit 1
fi
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

"${SERVICE_ROOT}/ops/property-deployment-preflight.sh" before 37
"${SERVICE_ROOT}/ops/property-flyway.sh" migrate 37 >/dev/null
"${SERVICE_ROOT}/ops/property-deployment-preflight.sh" after 37
"${SERVICE_ROOT}/ops/property-flyway.sh" validate >/dev/null
installed_versions="$(docker exec "${DATABASE_CONTAINER}" psql -U home_search -d home_search \
    -v ON_ERROR_STOP=1 -Atc \
    "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version IS NOT NULL AND success")"
if [[ "${installed_versions}" != '1,2,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37' ]]; then
    printf '예상하지 않은 property-data migration history: %s\n' "${installed_versions}" >&2
    exit 1
fi

ai_reader_safety="$(docker exec "${DATABASE_CONTAINER}" psql -U home_search -d postgres -Atc \
    "SELECT rolcanlogin||'|'||rolinherit||'|'||rolsuper||'|'||rolcreatedb||'|'||rolcreaterole||'|'||rolreplication||'|'||rolbypassrls||'|'||EXISTS (SELECT 1 FROM pg_auth_members WHERE member = 'home_search_ai_reader'::regrole) FROM pg_roles WHERE rolname='home_search_ai_reader'")"
[[ "${ai_reader_safety}" == 'true|false|false|false|false|false|false|false' ]] || {
    printf 'AI reader 최소 권한 검증 실패: %s\n' "${ai_reader_safety}" >&2
    exit 1
}
ai_fact_count="$(docker run --rm --network "${NETWORK}" -e PGPASSWORD="${AI_READER_PASSWORD}" postgres:16.3-alpine \
    psql -h "${DATABASE_CONTAINER}" -U home_search_ai_reader -d home_search -X -At \
    -v ON_ERROR_STOP=1 -c 'SELECT count(*) FROM ai_read.complex_fact')"
[[ "${ai_fact_count}" == '0' ]] || {
    printf 'AI reader view 조회 검증 실패: %s\n' "${ai_fact_count}" >&2
    exit 1
}
if docker run --rm --network "${NETWORK}" -e PGPASSWORD="${AI_READER_PASSWORD}" postgres:16.3-alpine \
    psql -h "${DATABASE_CONTAINER}" -U home_search_ai_reader -d home_search -X \
    -v ON_ERROR_STOP=1 -c 'SELECT count(*) FROM public.complex' >/dev/null 2>&1; then
    printf 'AI reader가 public.complex를 조회했습니다.\n' >&2
    exit 1
fi
if docker run --rm --network "${NETWORK}" -e PGPASSWORD="${AI_READER_PASSWORD}" postgres:16.3-alpine \
    psql -h "${DATABASE_CONTAINER}" -U home_search_ai_reader -d home_search -X \
    -v ON_ERROR_STOP=1 -c 'CREATE TABLE ai_read.forbidden_write (id bigint)' >/dev/null 2>&1; then
    printf 'AI reader가 ai_read schema에 객체를 생성했습니다.\n' >&2
    exit 1
fi
if docker run --rm --network "${NETWORK}" -e PGPASSWORD="${AI_READER_PASSWORD}" postgres:16.3-alpine \
    psql -h "${DATABASE_CONTAINER}" -U home_search_ai_reader -d home_search -X \
    -v ON_ERROR_STOP=1 -c 'CREATE TEMP TABLE forbidden_temp_write (id bigint)' >/dev/null 2>&1; then
    printf 'AI reader가 임시 테이블을 생성했습니다.\n' >&2
    exit 1
fi
if docker run --rm --network "${NETWORK}" -e PGPASSWORD="${AI_READER_PASSWORD}" postgres:16.3-alpine \
    psql -h "${DATABASE_CONTAINER}" -U home_search_ai_reader -d home_search_admin -X \
    -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null 2>&1; then
    printf 'AI reader가 admin database에 연결했습니다.\n' >&2
    exit 1
fi
if docker run --rm --network "${NETWORK}" -e PGPASSWORD="${AI_READER_PASSWORD}" postgres:16.3-alpine \
    psql -h "${DATABASE_CONTAINER}" -U home_search_ai_reader -d postgres -X \
    -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null 2>&1; then
    printf 'AI reader가 postgres database에 연결했습니다.\n' >&2
    exit 1
fi
