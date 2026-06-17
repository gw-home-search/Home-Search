#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./ops/coordinate-source-db-copy-cutover.sh --dump-source
  ./ops/coordinate-source-db-copy-cutover.sh --restore-copy
  ./ops/coordinate-source-db-copy-cutover.sh --copy-live-snapshot
  ./ops/coordinate-source-db-copy-cutover.sh --verify-live-snapshot
  ./ops/coordinate-source-db-copy-cutover.sh --verify-drop-readiness
  ./ops/coordinate-source-db-copy-cutover.sh --archive-import-worktables
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
  HOME_COORDINATE_SOURCE_DB_HOST  Defaults to PGHOST.
  HOME_COORDINATE_SOURCE_DB_PORT  Defaults to PGPORT.
  HOME_COORDINATE_SOURCE_DB_USER  Defaults to PGUSER.
  HOME_COORDINATE_SOURCE_DB_PASSWORD Defaults to PGPASSWORD.
  HOME_COORDINATE_TARGET_DB_HOST  Defaults to PGHOST.
  HOME_COORDINATE_TARGET_DB_PORT  Defaults to 15435.
  HOME_COORDINATE_TARGET_DB_USER  Defaults to PGUSER.
  HOME_COORDINATE_TARGET_DB_PASSWORD Defaults to PGPASSWORD.
  HOME_COORDINATE_SOURCE_DB_CONTAINER Optional source Postgres container for version-matched streaming.
  HOME_COORDINATE_TARGET_DB_CONTAINER Optional target Postgres container for version-matched streaming.
  HOME_COORDINATE_POSTGIS_TOOL_IMAGE  Defaults to postgis/postgis:16-3.4 for archive verification.
  HOME_COORDINATE_SOURCE_DUMP     Defaults to /tmp/<source>.dump.
  HOME_COORDINATE_WORKTABLE_DUMP  Defaults to /tmp/<source>-coordinate-worktables.dump.
  HOME_COORDINATE_REQUIRE_WORKTABLE_ARCHIVE Defaults to true for drop readiness.
  HOME_COORDINATE_SAMPLE_PNU      Optional 19 digit PNU sample for copy verification.

Safety:
  This script never drops a database, table, Docker volume, or row.
  Live copy intentionally excludes import worktables such as stage and publish.
  Archive worktable dumps preserve excluded import state before old source DB removal.
  Rollback is changing COORDINATE_SOURCE_DB_JDBC_URL back to the previous DB.
EOF
}

MODE=""
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --dump-source|--restore-copy|--copy-live-snapshot|--verify-live-snapshot|--verify-drop-readiness|--archive-import-worktables|--print-cutover-env|--self-test)
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
PGPASSWORD="${PGPASSWORD:-}"
SOURCE_DB="${HOME_COORDINATE_SOURCE_DB:-}"
TARGET_DB="${HOME_COORDINATE_TARGET_DB:-}"
DUMP_FILE="${HOME_COORDINATE_SOURCE_DUMP:-/tmp/${SOURCE_DB:-coordinate_source}.dump}"
WORKTABLE_DUMP_FILE="${HOME_COORDINATE_WORKTABLE_DUMP:-/tmp/${SOURCE_DB:-coordinate_source}-coordinate-worktables.dump}"
SAMPLE_PNU="${HOME_COORDINATE_SAMPLE_PNU:-}"
REQUIRE_WORKTABLE_ARCHIVE="${HOME_COORDINATE_REQUIRE_WORKTABLE_ARCHIVE:-true}"
SOURCE_HOST="${HOME_COORDINATE_SOURCE_DB_HOST:-${PGHOST}}"
SOURCE_PORT="${HOME_COORDINATE_SOURCE_DB_PORT:-${PGPORT}}"
SOURCE_USER="${HOME_COORDINATE_SOURCE_DB_USER:-${PGUSER}}"
SOURCE_PASSWORD="${HOME_COORDINATE_SOURCE_DB_PASSWORD:-${PGPASSWORD}}"
TARGET_HOST="${HOME_COORDINATE_TARGET_DB_HOST:-${PGHOST}}"
TARGET_PORT="${HOME_COORDINATE_TARGET_DB_PORT:-15435}"
TARGET_USER="${HOME_COORDINATE_TARGET_DB_USER:-${PGUSER}}"
TARGET_PASSWORD="${HOME_COORDINATE_TARGET_DB_PASSWORD:-${PGPASSWORD}}"
SOURCE_CONTAINER="${HOME_COORDINATE_SOURCE_DB_CONTAINER:-}"
TARGET_CONTAINER="${HOME_COORDINATE_TARGET_DB_CONTAINER:-}"
POSTGIS_TOOL_IMAGE="${HOME_COORDINATE_POSTGIS_TOOL_IMAGE:-postgis/postgis:16-3.4}"

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

require_docker_stream_containers() {
  if [[ -z "${SOURCE_CONTAINER}" || -z "${TARGET_CONTAINER}" ]]; then
    echo "ERROR: HOME_COORDINATE_SOURCE_DB_CONTAINER and HOME_COORDINATE_TARGET_DB_CONTAINER are required for this streaming mode." >&2
    exit 2
  fi
  require_tool docker
}

psql_db() {
  local db="$1"
  shift
  PGPASSWORD="${PGPASSWORD}" psql -X -v ON_ERROR_STOP=1 -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${db}" "$@"
}

psql_source_db() {
  local db="$1"
  shift
  PGPASSWORD="${SOURCE_PASSWORD}" psql -X -v ON_ERROR_STOP=1 \
    -h "${SOURCE_HOST}" -p "${SOURCE_PORT}" -U "${SOURCE_USER}" -d "${db}" "$@"
}

psql_target_db() {
  local db="$1"
  shift
  PGPASSWORD="${TARGET_PASSWORD}" psql -X -v ON_ERROR_STOP=1 \
    -h "${TARGET_HOST}" -p "${TARGET_PORT}" -U "${TARGET_USER}" -d "${db}" "$@"
}

database_exists() {
  local db="$1"
  psql_db postgres -At -v db="${db}" <<'SQL'
SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db');
SQL
}

source_database_exists() {
  local db="$1"
  psql_source_db postgres -At -v db="${db}" <<'SQL'
SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db');
SQL
}

target_database_exists() {
  local db="$1"
  psql_target_db postgres -At -v db="${db}" <<'SQL'
SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db');
SQL
}

target_ready_for_live_restore() {
  psql_target_db "${TARGET_DB}" -At <<'SQL'
SELECT to_regclass('reference.coordinate_snapshot_run') IS NULL
   AND to_regclass('reference.parcel_coordinate_snapshot') IS NULL;
SQL
}

ensure_target_reference_schema() {
  psql_target_db "${TARGET_DB}" <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
CREATE SCHEMA IF NOT EXISTS reference;
SQL
}

live_schema_fingerprint_sql() {
  cat <<'SQL'
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
  AND c.relname IN (
      'coordinate_snapshot_run',
      'coordinate_snapshot_run_pkey',
      'ix_coordinate_snapshot_run_status_started',
      'parcel_coordinate_snapshot',
      'parcel_coordinate_snapshot_pkey',
      'ix_parcel_coordinate_snapshot_region_code',
      'ix_parcel_coordinate_snapshot_point',
      'ix_parcel_coordinate_snapshot_geom'
  )
ORDER BY n.nspname, c.relname, a.attnum;
SQL
}

source_live_schema_fingerprint() {
  psql_source_db "${SOURCE_DB}" -At -F '|' < <(live_schema_fingerprint_sql)
}

target_live_schema_fingerprint() {
  psql_target_db "${TARGET_DB}" -At -F '|' < <(live_schema_fingerprint_sql)
}

latest_run_sql() {
  cat <<'SQL'
SELECT
    id,
    status,
    pnu_count,
    raw_feature_count,
    snapshot_version,
    source_srid,
    target_srid,
    COALESCE(finished_at::text, '')
FROM reference.coordinate_snapshot_run
WHERE status = 'PASSED'
ORDER BY finished_at DESC NULLS LAST, started_at DESC, id DESC
LIMIT 1;
SQL
}

source_latest_run() {
  psql_source_db "${SOURCE_DB}" -At -F '|' < <(latest_run_sql)
}

target_latest_run() {
  psql_target_db "${TARGET_DB}" -At -F '|' < <(latest_run_sql)
}

target_snapshot_count() {
  psql_target_db "${TARGET_DB}" -At <<'SQL'
SELECT count(*) FROM reference.parcel_coordinate_snapshot;
SQL
}

source_database_size() {
  psql_source_db postgres -At -v db="${SOURCE_DB}" <<'SQL'
SELECT pg_size_pretty(pg_database_size(:'db'));
SQL
}

target_database_size() {
  psql_target_db postgres -At -v db="${TARGET_DB}" <<'SQL'
SELECT pg_size_pretty(pg_database_size(:'db'));
SQL
}

source_active_connection_count() {
  psql_source_db postgres -At -v db="${SOURCE_DB}" <<'SQL'
SELECT numbackends
FROM pg_stat_database
WHERE datname = :'db';
SQL
}

coordinate_source_relation_inventory() {
  psql_source_db "${SOURCE_DB}" -At -F '|' <<'SQL'
WITH expected(relname) AS (
  VALUES
    ('coordinate_snapshot_run'),
    ('parcel_coordinate_snapshot'),
    ('coordinate_snapshot_region_checkpoint'),
    ('coordinate_snapshot_stage_chunk_checkpoint'),
    ('coordinate_snapshot_publish_checkpoint'),
    ('coordinate_snapshot_publish_chunk_checkpoint'),
    ('parcel_coordinate_snapshot_stage'),
    ('parcel_coordinate_snapshot_publish')
),
resolved AS (
  SELECT
      expected.relname,
      to_regclass(format('reference.%I', expected.relname)) AS relation_oid
  FROM expected
)
SELECT
    resolved.relname,
    COALESCE(relation_oid::text, 'missing') AS relation_name,
    COALESCE(pg_size_pretty(pg_total_relation_size(relation_oid)), '') AS total_size,
    COALESCE(pg_class.reltuples::bigint::text, '') AS estimated_rows
FROM resolved
LEFT JOIN pg_class ON pg_class.oid = resolved.relation_oid
ORDER BY relname;
SQL
}

verify_chunked_worktable_archive() {
  local archive_dir="$1"
  local publish_manifest="${archive_dir}/publish-manifest.tsv"
  local stage_manifest="${archive_dir}/stage-manifest.tsv"
  local checksum_manifest="${archive_dir}/SHA256SUMS"
  if [[ ! -f "${publish_manifest}" || ! -f "${stage_manifest}" || ! -f "${checksum_manifest}" ]]; then
    echo "ERROR: chunked worktable archive is missing manifest files: ${archive_dir}" >&2
    exit 1
  fi

  local expected_publish expected_stage actual_publish actual_stage
  expected_publish="$(wc -l < "${publish_manifest}" | tr -d ' ')"
  expected_stage="$(wc -l < "${stage_manifest}" | tr -d ' ')"
  actual_publish="$(find "${archive_dir}/publish" -name '*.csv.gz' -type f | wc -l | tr -d ' ')"
  actual_stage="$(find "${archive_dir}/stage" -name '*.csv.gz' -type f | wc -l | tr -d ' ')"
  if [[ "${expected_publish}" != "${actual_publish}" ]]; then
    echo "ERROR: chunked publish archive count=${actual_publish}, expected=${expected_publish}." >&2
    exit 1
  fi
  if [[ "${expected_stage}" != "${actual_stage}" ]]; then
    echo "ERROR: chunked stage archive count=${actual_stage}, expected=${expected_stage}." >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "${archive_dir}" && sha256sum -c SHA256SUMS >/dev/null)
  elif command -v shasum >/dev/null 2>&1; then
    (cd "${archive_dir}" && shasum -a 256 -c SHA256SUMS >/dev/null)
  else
    echo "ERROR: sha256sum or shasum is required to verify chunked worktable archive checksums." >&2
    exit 1
  fi
  echo "drop_readiness_worktable_archive=${archive_dir}"
  echo "drop_readiness_worktable_archive_publish_chunks=${actual_publish}"
  echo "drop_readiness_worktable_archive_stage_chunks=${actual_stage}"
  echo "drop_readiness_worktable_archive_checksum=passed"
}

verify_worktable_archive() {
  if [[ -f "${WORKTABLE_DUMP_FILE}" ]]; then
    pg_restore -l "${WORKTABLE_DUMP_FILE}" >/dev/null
    echo "drop_readiness_worktable_archive=${WORKTABLE_DUMP_FILE}"
    return 0
  fi
  if [[ -d "${WORKTABLE_DUMP_FILE}" ]]; then
    verify_chunked_worktable_archive "${WORKTABLE_DUMP_FILE}"
    return 0
  fi
  echo "ERROR: coordinate import worktable archive is missing: ${WORKTABLE_DUMP_FILE}" >&2
  echo "Run --archive-import-worktables before old source DB removal, provide a chunked archive directory, or set HOME_COORDINATE_REQUIRE_WORKTABLE_ARCHIVE=false after an explicit data-retention decision." >&2
  exit 1
}

sample_lookup_sql() {
  if [[ -z "${SAMPLE_PNU}" ]]; then
    echo "sample-pnu-not-set"
    return 0
  fi
  cat <<'SQL'
SET enable_seqscan = off;
SELECT pnu, latitude, longitude, ST_AsText(geom)
FROM reference.parcel_coordinate_snapshot
WHERE pnu = :'pnu';
SQL
}

source_sample_lookup() {
  if [[ -z "${SAMPLE_PNU}" ]]; then
    echo "sample-pnu-not-set"
    return 0
  fi
  psql_source_db "${SOURCE_DB}" -At -F '|' -v pnu="${SAMPLE_PNU}" < <(sample_lookup_sql)
}

target_sample_lookup() {
  if [[ -z "${SAMPLE_PNU}" ]]; then
    echo "sample-pnu-not-set"
    return 0
  fi
  psql_target_db "${TARGET_DB}" -At -F '|' -v pnu="${SAMPLE_PNU}" < <(sample_lookup_sql)
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
  if ! grep -q -- "--copy-live-snapshot" "$0" || ! grep -q -- "--verify-live-snapshot" "$0"; then
    echo "self-test failed: live snapshot modes are missing." >&2
    exit 1
  fi
  echo "self-test passed: coordinate source DB copy/cutover helper"
}

dump_source() {
  require_database_names
  require_tool pg_dump
  require_tool pg_restore
  if [[ "$(source_database_exists "${SOURCE_DB}")" != "t" ]]; then
    echo "ERROR: source DB does not exist: ${SOURCE_DB}" >&2
    exit 1
  fi
  PGPASSWORD="${SOURCE_PASSWORD}" pg_dump \
    -h "${SOURCE_HOST}" -p "${SOURCE_PORT}" -U "${SOURCE_USER}" -d "${SOURCE_DB}" \
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
  if [[ "$(target_database_exists "${TARGET_DB}")" == "t" ]]; then
    echo "ERROR: target DB already exists; refusing overwrite: ${TARGET_DB}" >&2
    exit 1
  fi
  PGPASSWORD="${TARGET_PASSWORD}" createdb \
    -h "${TARGET_HOST}" -p "${TARGET_PORT}" -U "${TARGET_USER}" "${TARGET_DB}"
  PGPASSWORD="${TARGET_PASSWORD}" pg_restore \
    -h "${TARGET_HOST}" -p "${TARGET_PORT}" -U "${TARGET_USER}" -d "${TARGET_DB}" \
    --no-owner \
    --no-acl \
    "${DUMP_FILE}"
  echo "coordinate source copy restored without deleting source: ${TARGET_DB}"
}

copy_live_snapshot() {
  require_database_names
  require_tool psql
  if [[ "$(source_database_exists "${SOURCE_DB}")" != "t" ]]; then
    echo "ERROR: source DB does not exist: ${SOURCE_DB}" >&2
    exit 1
  fi
  if [[ "$(target_database_exists "${TARGET_DB}")" != "t" ]]; then
    echo "ERROR: target DB does not exist: ${TARGET_DB}" >&2
    exit 1
  fi
  if [[ "$(target_ready_for_live_restore)" != "t" ]]; then
    echo "ERROR: target DB already owns live coordinate source tables; refusing live restore: ${TARGET_DB}" >&2
    exit 1
  fi

  ensure_target_reference_schema
  if [[ -n "${SOURCE_CONTAINER}" || -n "${TARGET_CONTAINER}" ]]; then
    require_docker_stream_containers
    docker exec -e PGPASSWORD="${SOURCE_PASSWORD}" "${SOURCE_CONTAINER}" \
      pg_dump -U "${SOURCE_USER}" -d "${SOURCE_DB}" \
        --format=plain \
        --no-owner \
        --no-acl \
        --table=reference.coordinate_snapshot_run \
        --table=reference.parcel_coordinate_snapshot \
        --file=- \
    | docker exec -i -e PGPASSWORD="${TARGET_PASSWORD}" "${TARGET_CONTAINER}" \
      psql -v ON_ERROR_STOP=1 --single-transaction -U "${TARGET_USER}" -d "${TARGET_DB}"
  else
    require_tool pg_dump
    PGPASSWORD="${SOURCE_PASSWORD}" pg_dump \
      -h "${SOURCE_HOST}" -p "${SOURCE_PORT}" -U "${SOURCE_USER}" -d "${SOURCE_DB}" \
      --format=plain \
      --no-owner \
      --no-acl \
      --table=reference.coordinate_snapshot_run \
      --table=reference.parcel_coordinate_snapshot \
      --file=- \
    | PGPASSWORD="${TARGET_PASSWORD}" psql -v ON_ERROR_STOP=1 \
      --single-transaction \
      -h "${TARGET_HOST}" -p "${TARGET_PORT}" -U "${TARGET_USER}" -d "${TARGET_DB}"
  fi

  psql_target_db "${TARGET_DB}" <<'SQL'
ANALYZE reference.coordinate_snapshot_run;
ANALYZE reference.parcel_coordinate_snapshot;
SQL
  echo "coordinate live snapshot copy restored without deleting source: source=${SOURCE_DB}, target=${TARGET_DB}"
}

verify_live_snapshot() {
  require_database_names
  require_tool psql
  if [[ "$(source_database_exists "${SOURCE_DB}")" != "t" ]]; then
    echo "ERROR: source DB does not exist: ${SOURCE_DB}" >&2
    exit 1
  fi
  if [[ "$(target_database_exists "${TARGET_DB}")" != "t" ]]; then
    echo "ERROR: target DB does not exist: ${TARGET_DB}" >&2
    exit 1
  fi

  local source_schema target_schema source_run target_run target_count source_sample target_sample
  source_schema="$(source_live_schema_fingerprint)"
  target_schema="$(target_live_schema_fingerprint)"
  if [[ "${source_schema}" != "${target_schema}" ]]; then
    echo "ERROR: source and target live coordinate schema fingerprints differ." >&2
    exit 1
  fi

  source_run="$(source_latest_run)"
  target_run="$(target_latest_run)"
  if [[ -z "${source_run}" || -z "${target_run}" ]]; then
    echo "ERROR: source or target PASSED coordinate snapshot run evidence is empty." >&2
    exit 1
  fi
  if [[ "${source_run}" != "${target_run}" ]]; then
    echo "ERROR: source and target latest PASSED coordinate snapshot run differ." >&2
    echo "source=${source_run}" >&2
    echo "target=${target_run}" >&2
    exit 1
  fi

  local run_id status pnu_count raw_feature_count snapshot_version source_srid target_srid finished_at
  IFS='|' read -r run_id status pnu_count raw_feature_count snapshot_version source_srid target_srid finished_at <<<"${source_run}"
  target_count="$(target_snapshot_count)"
  if [[ "${target_count}" != "${pnu_count}" ]]; then
    echo "ERROR: target parcel_coordinate_snapshot count=${target_count}, expected latest run pnu_count=${pnu_count}." >&2
    exit 1
  fi

  source_sample="$(source_sample_lookup)"
  target_sample="$(target_sample_lookup)"
  if [[ "${source_sample}" != "${target_sample}" ]]; then
    echo "ERROR: source and target sample lookup differ for pnu=${SAMPLE_PNU}." >&2
    exit 1
  fi

  echo "coordinate live snapshot verification passed: source=${SOURCE_DB}, target=${TARGET_DB}"
  echo "latest_run=${source_run}"
  echo "target_parcel_coordinate_snapshot_count=${target_count}"
  if [[ -n "${SAMPLE_PNU}" ]]; then
    echo "sample=${target_sample}"
  fi
}

verify_drop_readiness() {
  require_database_names
  require_tool psql
  require_tool pg_restore
  if [[ "${REQUIRE_WORKTABLE_ARCHIVE}" != "true" && "${REQUIRE_WORKTABLE_ARCHIVE}" != "false" ]]; then
    echo "ERROR: HOME_COORDINATE_REQUIRE_WORKTABLE_ARCHIVE must be true or false." >&2
    exit 2
  fi

  verify_live_snapshot

  local source_size target_size source_connections
  source_size="$(source_database_size)"
  target_size="$(target_database_size)"
  source_connections="$(source_active_connection_count)"
  echo "drop_readiness_source_db=${SOURCE_DB}"
  echo "drop_readiness_target_db=${TARGET_DB}"
  echo "drop_readiness_source_db_size=${source_size}"
  echo "drop_readiness_target_db_size=${target_size}"
  echo "drop_readiness_source_active_connections=${source_connections}"
  echo "drop_readiness_source_relation_inventory_begin"
  coordinate_source_relation_inventory
  echo "drop_readiness_source_relation_inventory_end"

  if [[ "${source_connections}" != "0" ]]; then
    echo "ERROR: source DB still has active connections: ${source_connections}" >&2
    exit 1
  fi

  if [[ "${REQUIRE_WORKTABLE_ARCHIVE}" == "true" ]]; then
    verify_worktable_archive
  else
    echo "drop_readiness_worktable_archive=not-required"
  fi

  echo "coordinate source drop readiness passed without deleting source DB: source=${SOURCE_DB}, target=${TARGET_DB}"
}

archive_import_worktables() {
  require_database_names
  if [[ "$(source_database_exists "${SOURCE_DB}")" != "t" ]]; then
    echo "ERROR: source DB does not exist: ${SOURCE_DB}" >&2
    exit 1
  fi
  if [[ -n "${SOURCE_CONTAINER}" ]]; then
    require_tool docker
    docker exec -e PGPASSWORD="${SOURCE_PASSWORD}" "${SOURCE_CONTAINER}" \
      pg_dump -U "${SOURCE_USER}" -d "${SOURCE_DB}" \
        --format=custom \
        --no-owner \
        --no-acl \
        --table=reference.coordinate_snapshot_region_checkpoint \
        --table=reference.coordinate_snapshot_stage_chunk_checkpoint \
        --table=reference.coordinate_snapshot_publish_checkpoint \
        --table=reference.coordinate_snapshot_publish_chunk_checkpoint \
        --table=reference.parcel_coordinate_snapshot_stage \
        --table=reference.parcel_coordinate_snapshot_publish \
        --file=- > "${WORKTABLE_DUMP_FILE}"
    docker run --rm \
      -v "$(dirname "${WORKTABLE_DUMP_FILE}"):/archive:ro" \
      "${POSTGIS_TOOL_IMAGE}" \
      pg_restore -l "/archive/$(basename "${WORKTABLE_DUMP_FILE}")" >/dev/null
  else
    require_tool pg_dump
    require_tool pg_restore
    PGPASSWORD="${SOURCE_PASSWORD}" pg_dump \
      -h "${SOURCE_HOST}" -p "${SOURCE_PORT}" -U "${SOURCE_USER}" -d "${SOURCE_DB}" \
      --format=custom \
      --no-owner \
      --no-acl \
      --table=reference.coordinate_snapshot_region_checkpoint \
      --table=reference.coordinate_snapshot_stage_chunk_checkpoint \
      --table=reference.coordinate_snapshot_publish_checkpoint \
      --table=reference.coordinate_snapshot_publish_chunk_checkpoint \
      --table=reference.parcel_coordinate_snapshot_stage \
      --table=reference.parcel_coordinate_snapshot_publish \
      --file="${WORKTABLE_DUMP_FILE}"
    pg_restore -l "${WORKTABLE_DUMP_FILE}" >/dev/null
  fi
  echo "coordinate import worktable archive created: ${WORKTABLE_DUMP_FILE}"
  echo "live target does not need stage or publish worktables; keep this archive before removing the old source DB."
}

print_cutover_env() {
  require_database_names
  echo "COORDINATE_SOURCE_DB_JDBC_URL=jdbc:postgresql://${TARGET_HOST}:${TARGET_PORT}/${TARGET_DB}"
  echo "COORDINATE_SOURCE_DB_USERNAME=${TARGET_USER}"
  echo "COORDINATE_SOURCE_DB_READ_ONLY=true"
  echo "rollback: set COORDINATE_SOURCE_DB_JDBC_URL back to jdbc:postgresql://${SOURCE_HOST}:${SOURCE_PORT}/${SOURCE_DB}"
}

case "${MODE}" in
  self-test) run_self_test ;;
  dump-source) dump_source ;;
  restore-copy) restore_copy ;;
  copy-live-snapshot) copy_live_snapshot ;;
  verify-live-snapshot) verify_live_snapshot ;;
  verify-drop-readiness) verify_drop_readiness ;;
  archive-import-worktables) archive_import_worktables ;;
  print-cutover-env) print_cutover_env ;;
  *)
    echo "ERROR: unsupported mode: ${MODE}" >&2
    exit 2
    ;;
esac
