#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./ops/coordinate-source-db-copy-cutover.sh --dump-source
  ./ops/coordinate-source-db-copy-cutover.sh --restore-copy
  ./ops/coordinate-source-db-copy-cutover.sh --verify-copy
  ./ops/coordinate-source-db-copy-cutover.sh --print-cutover-env
  ./ops/coordinate-source-db-copy-cutover.sh --self-test

Required environment:
  HOME_COORDINATE_SOURCE_DB       Existing coordinate source DB name.
  HOME_COORDINATE_TARGET_DB       New coordinate source DB name.

Optional environment:
  PGHOST                          Defaults to localhost.
  PGPORT                          Defaults to 15432.
  PGUSER                          Defaults to home_search.
  PGPASSWORD                      Passed to PostgreSQL tools.
  HOME_COORDINATE_SOURCE_DUMP     Defaults to /tmp/<source>.dump.
  HOME_COORDINATE_SAMPLE_PNU      Optional 19 digit PNU sample for copy verification.

Safety:
  This script never drops a database, table, Docker volume, or row.
  Rollback is changing COORDINATE_SOURCE_DB_JDBC_URL back to the previous DB.
EOF
}

MODE=""
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --dump-source|--restore-copy|--verify-copy|--print-cutover-env|--self-test)
      if [[ -n "${MODE}" ]]; then
        echo "ERROR: only one mode can be selected." >&2
        usage >&2
        exit 2
      fi
      MODE="${1#--}"
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ -z "${MODE}" ]]; then
  echo "ERROR: mode is required." >&2
  usage >&2
  exit 2
fi

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGUSER="${PGUSER:-home_search}"
SOURCE_DB="${HOME_COORDINATE_SOURCE_DB:-}"
TARGET_DB="${HOME_COORDINATE_TARGET_DB:-}"
DUMP_FILE="${HOME_COORDINATE_SOURCE_DUMP:-/tmp/${SOURCE_DB:-coordinate_source}.dump}"
SAMPLE_PNU="${HOME_COORDINATE_SAMPLE_PNU:-}"

require_database_names() {
  if [[ -z "${SOURCE_DB}" || -z "${TARGET_DB}" ]]; then
    echo "ERROR: HOME_COORDINATE_SOURCE_DB and HOME_COORDINATE_TARGET_DB are required." >&2
    exit 2
  fi
  if [[ "${SOURCE_DB}" == "${TARGET_DB}" ]]; then
    echo "ERROR: source and target DB names must differ." >&2
    exit 2
  fi
}

require_tool() {
  local tool="$1"
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "ERROR: ${tool} is required on PATH." >&2
    exit 2
  fi
}

psql_db() {
  local db="$1"
  shift
  psql -X -v ON_ERROR_STOP=1 -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${db}" "$@"
}

database_exists() {
  local db="$1"
  psql_db postgres -At -v db="${db}" <<'SQL'
SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db');
SQL
}

schema_fingerprint() {
  local db="$1"
  psql_db "${db}" -At -F '|' <<'SQL'
SELECT
    n.nspname,
    c.relname,
    c.relkind,
    COALESCE(a.attname, ''),
    COALESCE(format_type(a.atttypid, a.atttypmod), '')
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
WHERE n.nspname = 'reference'
  AND c.relkind IN ('r', 'i')
ORDER BY n.nspname, c.relname, a.attnum;
SQL
}

relation_estimates() {
  local db="$1"
  psql_db "${db}" -At -F '|' <<'SQL'
SELECT
    n.nspname || '.' || c.relname,
    c.reltuples::bigint,
    pg_total_relation_size(c.oid)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'reference'
  AND c.relkind = 'r'
ORDER BY c.relname;
SQL
}

sample_lookup() {
  local db="$1"
  if [[ -z "${SAMPLE_PNU}" ]]; then
    echo "sample-pnu-not-set"
    return 0
  fi
  psql_db "${db}" -At -F '|' -v pnu="${SAMPLE_PNU}" <<'SQL'
SET enable_seqscan = off;
SELECT pnu, latitude, longitude, ST_AsText(geom)
FROM reference.parcel_coordinate_snapshot
WHERE pnu = :'pnu';
SQL
}

run_self_test() {
  case "${SAMPLE_PNU}" in
    ""|[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
    *)
      echo "self-test failed: HOME_COORDINATE_SAMPLE_PNU must be blank or 19 digits." >&2
      exit 1
      ;;
  esac
  if grep -Eq '(^|[[:space:]])(dropdb|DROP DATABASE|DROP TABLE|TRUNCATE|docker volume rm|docker compose down -v)([[:space:]]|$)' "$0"; then
    echo "self-test failed: destructive command token found." >&2
    exit 1
  fi
  echo "self-test passed: coordinate source DB copy/cutover helper"
}

dump_source() {
  require_database_names
  require_tool pg_dump
  if [[ "$(database_exists "${SOURCE_DB}")" != "t" ]]; then
    echo "ERROR: source DB does not exist: ${SOURCE_DB}" >&2
    exit 1
  fi
  pg_dump -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${SOURCE_DB}" \
    --format=custom \
    --no-owner \
    --no-acl \
    --file="${DUMP_FILE}"
  pg_restore -l "${DUMP_FILE}" >/dev/null
  echo "coordinate source dump created: ${DUMP_FILE}"
}

restore_copy() {
  require_database_names
  require_tool createdb
  require_tool pg_restore
  if [[ ! -f "${DUMP_FILE}" ]]; then
    echo "ERROR: dump file does not exist: ${DUMP_FILE}" >&2
    exit 1
  fi
  if [[ "$(database_exists "${TARGET_DB}")" == "t" ]]; then
    echo "ERROR: target DB already exists; refusing overwrite: ${TARGET_DB}" >&2
    exit 1
  fi
  createdb -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" "${TARGET_DB}"
  pg_restore -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${TARGET_DB}" \
    --no-owner \
    --no-acl \
    "${DUMP_FILE}"
  echo "coordinate source copy restored without deleting source: ${TARGET_DB}"
}

verify_copy() {
  require_database_names
  require_tool psql
  if [[ "$(database_exists "${SOURCE_DB}")" != "t" ]]; then
    echo "ERROR: source DB does not exist: ${SOURCE_DB}" >&2
    exit 1
  fi
  if [[ "$(database_exists "${TARGET_DB}")" != "t" ]]; then
    echo "ERROR: target DB does not exist: ${TARGET_DB}" >&2
    exit 1
  fi

  local source_schema target_schema source_estimates target_estimates source_sample target_sample
  source_schema="$(schema_fingerprint "${SOURCE_DB}")"
  target_schema="$(schema_fingerprint "${TARGET_DB}")"
  if [[ "${source_schema}" != "${target_schema}" ]]; then
    echo "ERROR: source and target coordinate source schema fingerprints differ." >&2
    exit 1
  fi

  source_estimates="$(relation_estimates "${SOURCE_DB}")"
  target_estimates="$(relation_estimates "${TARGET_DB}")"
  if [[ -z "${source_estimates}" || -z "${target_estimates}" ]]; then
    echo "ERROR: relation estimate evidence is empty." >&2
    exit 1
  fi

  source_sample="$(sample_lookup "${SOURCE_DB}")"
  target_sample="$(sample_lookup "${TARGET_DB}")"
  if [[ "${source_sample}" != "${target_sample}" ]]; then
    echo "ERROR: source and target sample lookup differ for pnu=${SAMPLE_PNU}." >&2
    exit 1
  fi

  echo "coordinate source copy verification passed: source=${SOURCE_DB}, target=${TARGET_DB}"
  echo "source estimates:"
  echo "${source_estimates}"
  echo "target estimates:"
  echo "${target_estimates}"
}

print_cutover_env() {
  require_database_names
  echo "COORDINATE_SOURCE_DB_JDBC_URL=jdbc:postgresql://${PGHOST}:${PGPORT}/${TARGET_DB}"
  echo "COORDINATE_SOURCE_DB_USERNAME=${PGUSER}"
  echo "COORDINATE_SOURCE_DB_READ_ONLY=true"
  echo "rollback: set COORDINATE_SOURCE_DB_JDBC_URL back to jdbc:postgresql://${PGHOST}:${PGPORT}/${SOURCE_DB}"
}

case "${MODE}" in
  self-test) run_self_test ;;
  dump-source) dump_source ;;
  restore-copy) restore_copy ;;
  verify-copy) verify_copy ;;
  print-cutover-env) print_cutover_env ;;
  *)
    echo "ERROR: unsupported mode: ${MODE}" >&2
    exit 2
    ;;
esac
