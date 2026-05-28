#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./ops/verify-coordinate-snapshot-smoke.sh
  HOME_COORDINATE_EXPECTED_REGIONS="11 26 ..." ./ops/verify-coordinate-snapshot-smoke.sh
  ./ops/verify-coordinate-snapshot-smoke.sh --self-test

Database connection:
  PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD are consumed by psql.

Optional:
  HOME_COORDINATE_EXPECTED_REGIONS      Space-separated SIDO region list. Defaults to all 17 SIDO codes.
  HOME_COORDINATE_MIN_PNU_COUNT         Minimum active snapshot PNU count. Defaults to 1.
  HOME_COORDINATE_REQUIRE_SYNC_PARCEL   Require synced_parcel_count > 0. Defaults to false.
  HOME_COORDINATE_VERIFY_ACTIVE_COUNT   Run exact active table count scan. Defaults to false.
EOF
}

SELF_TEST="false"
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --self-test)
      SELF_TEST="true"
      ;;
    -*)
      echo "ERROR: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      echo "ERROR: unexpected argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

PSQL=(psql -X -v ON_ERROR_STOP=1)
EXPECTED_REGIONS="${HOME_COORDINATE_EXPECTED_REGIONS:-11 26 27 28 29 30 31 36 41 43 44 46 47 48 50 51 52}"
MIN_PNU_COUNT="${HOME_COORDINATE_MIN_PNU_COUNT:-1}"
REQUIRE_SYNC_PARCEL="${HOME_COORDINATE_REQUIRE_SYNC_PARCEL:-false}"
VERIFY_ACTIVE_COUNT="${HOME_COORDINATE_VERIFY_ACTIVE_COUNT:-false}"

contains_token() {
  case " $1 " in
    *" $2 "*) return 0 ;;
    *) return 1 ;;
  esac
}

token_count() {
  wc -w <<<"$1" | tr -d ' '
}

require_unsigned_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: ${name} must be an unsigned integer: ${value}" >&2
    exit 2
  fi
}

require_boolean() {
  local name="$1"
  local value="$2"
  case "${value}" in
    true|false) ;;
    *)
      echo "ERROR: ${name} must be true or false: ${value}" >&2
      exit 2
      ;;
  esac
}

run_self_test() {
  local default_count
  default_count="$(token_count "${EXPECTED_REGIONS}")"
  if [[ "${default_count}" != "17" ]]; then
    echo "self-test failed: default expected SIDO region count must be 17, got ${default_count}" >&2
    exit 1
  fi
  if ! contains_token "${EXPECTED_REGIONS}" "11" || ! contains_token "${EXPECTED_REGIONS}" "52"; then
    echo "self-test failed: default expected regions must include 11 and 52" >&2
    exit 1
  fi
  require_unsigned_integer "HOME_COORDINATE_MIN_PNU_COUNT" "${MIN_PNU_COUNT}"
  require_boolean "HOME_COORDINATE_REQUIRE_SYNC_PARCEL" "${REQUIRE_SYNC_PARCEL}"
  require_boolean "HOME_COORDINATE_VERIFY_ACTIVE_COUNT" "${VERIFY_ACTIVE_COUNT}"
  echo "self-test passed: coordinate snapshot smoke verifier"
}

if [[ "${SELF_TEST}" == "true" ]]; then
  run_self_test
  exit 0
fi

require_unsigned_integer "HOME_COORDINATE_MIN_PNU_COUNT" "${MIN_PNU_COUNT}"
require_boolean "HOME_COORDINATE_REQUIRE_SYNC_PARCEL" "${REQUIRE_SYNC_PARCEL}"
require_boolean "HOME_COORDINATE_VERIFY_ACTIVE_COUNT" "${VERIFY_ACTIVE_COUNT}"

if ! command -v psql >/dev/null 2>&1; then
  echo "ERROR: psql is required on PATH" >&2
  exit 2
fi

SCHEMA_READY="$("${PSQL[@]}" -At <<'SQL'
SELECT to_regclass('reference.coordinate_snapshot_run') IS NOT NULL
   AND to_regclass('reference.parcel_coordinate_snapshot') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_publish_checkpoint') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_publish_chunk_checkpoint') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_stage_chunk_checkpoint') IS NOT NULL;
SQL
)"
if [[ "${SCHEMA_READY}" != "t" ]]; then
  echo "ERROR: coordinate snapshot schema is missing. Run API Flyway migrations first." >&2
  exit 2
fi

REPORT="$("${PSQL[@]}" -At -F '|' <<'SQL'
WITH latest AS (
    SELECT *
    FROM reference.coordinate_snapshot_run
    WHERE status = 'PASSED'
    ORDER BY finished_at DESC NULLS LAST, started_at DESC, id DESC
    LIMIT 1
)
SELECT
    latest.id,
    latest.snapshot_version,
    COALESCE(latest.report_json->>'sourceFormat', ''),
    latest.file_count,
    latest.region_count,
    latest.raw_feature_count,
    latest.pnu_count,
    latest.invalid_count,
    latest.duplicate_pnu_count,
    latest.synced_parcel_count,
    COALESCE(latest.report_json->>'missingRegions', ''),
    COALESCE(latest.report_json->>'seenRegions', ''),
    latest.source_srid,
    latest.target_srid,
    COALESCE(latest.report_json->>'strictRegionMatch', ''),
    COALESCE(latest.report_json->>'syncParcel', '')
FROM latest;
SQL
)"

if [[ -z "${REPORT}" ]]; then
  echo "ERROR: no PASSED coordinate snapshot run found." >&2
  exit 1
fi

IFS='|' read -r \
  RUN_ID \
  SNAPSHOT_VERSION \
  SOURCE_FORMAT \
  FILE_COUNT \
  REGION_COUNT \
  RAW_FEATURE_COUNT \
  PNU_COUNT \
  INVALID_COUNT \
  DUPLICATE_PNU_COUNT \
  SYNCED_PARCEL_COUNT \
  MISSING_REGIONS \
  SEEN_REGIONS \
  SOURCE_SRID \
  TARGET_SRID \
  STRICT_REGION_MATCH \
  SYNC_PARCEL <<<"${REPORT}"

CONSTRAINT_REPORT="$("${PSQL[@]}" -At -F '|' <<'SQL'
WITH required_coordinate_constraints(name, expected_check) AS (
    VALUES
        ('parcel_coordinate_snapshot_pkey', 'pnu primary key'),
        ('parcel_coordinate_snapshot_pnu_check', 'pnu format'),
        ('parcel_coordinate_snapshot_latitude_check', 'latitude BETWEEN 33 AND 39'),
        ('parcel_coordinate_snapshot_longitude_check', 'longitude BETWEEN 124 AND 132'),
        ('ck_parcel_coordinate_snapshot_point_srid', 'ST_SRID(point) = 4326'),
        ('ck_parcel_coordinate_snapshot_geom_srid', 'ST_SRID(geom) = 4326'),
        ('ck_parcel_coordinate_snapshot_geom_valid', 'ST_IsValid(geom)')
),
constraint_state AS (
    SELECT
        required_coordinate_constraints.name,
        COALESCE(pg_constraint.convalidated, false) AS validated
    FROM required_coordinate_constraints
    LEFT JOIN pg_constraint
      ON pg_constraint.conrelid = 'reference.parcel_coordinate_snapshot'::regclass
     AND pg_constraint.conname = required_coordinate_constraints.name
)
SELECT
    count(*) FILTER (WHERE validated)::integer,
    COALESCE(string_agg(name, ' ' ORDER BY name) FILTER (WHERE NOT validated), '')
FROM constraint_state;
SQL
)"
IFS='|' read -r VALIDATED_CONSTRAINT_COUNT INVALID_CONSTRAINTS <<<"${CONSTRAINT_REPORT}"

PUBLISH_REPORT="$("${PSQL[@]}" -v run_id="${RUN_ID}" -At -F '|' <<'SQL'
SELECT
    count(*) FILTER (WHERE status = 'PASSED')::integer,
    count(*) FILTER (WHERE status <> 'PASSED')::integer,
    COALESCE(sum(row_count) FILTER (WHERE status = 'PASSED'), 0)::bigint,
    COALESCE(string_agg(region_code, ' ' ORDER BY region_code) FILTER (WHERE status = 'PASSED'), '')
FROM reference.coordinate_snapshot_publish_checkpoint
WHERE run_id = (:'run_id')::bigint;
SQL
)"
IFS='|' read -r PUBLISH_REGION_COUNT PUBLISH_FAILED_REGION_COUNT PUBLISH_ROW_COUNT PUBLISH_REGIONS <<<"${PUBLISH_REPORT}"

PUBLISH_CHUNK_REPORT="$("${PSQL[@]}" -v run_id="${RUN_ID}" -At -F '|' <<'SQL'
SELECT
    count(*) FILTER (WHERE status = 'PASSED')::integer,
    count(*) FILTER (WHERE status <> 'PASSED')::integer,
    COALESCE(sum(row_count) FILTER (WHERE status = 'PASSED'), 0)::bigint
FROM reference.coordinate_snapshot_publish_chunk_checkpoint
WHERE run_id = (:'run_id')::bigint;
SQL
)"
IFS='|' read -r PUBLISH_CHUNK_COUNT PUBLISH_FAILED_CHUNK_COUNT PUBLISH_CHUNK_ROW_COUNT <<<"${PUBLISH_CHUNK_REPORT}"

STAGE_CHUNK_REPORT="$("${PSQL[@]}" -v run_id="${RUN_ID}" -At -F '|' <<'SQL'
SELECT
    count(*) FILTER (WHERE status = 'PASSED')::integer,
    count(*) FILTER (WHERE status <> 'PASSED')::integer,
    COALESCE(sum(pnu_count) FILTER (WHERE status = 'PASSED'), 0)::bigint
FROM reference.coordinate_snapshot_stage_chunk_checkpoint
WHERE run_id = (:'run_id')::bigint;
SQL
)"
IFS='|' read -r STAGE_CHUNK_COUNT STAGE_FAILED_CHUNK_COUNT STAGE_CHUNK_PNU_COUNT <<<"${STAGE_CHUNK_REPORT}"

ACTIVE_COUNT_MODE="checkpoint"
if [[ "${VERIFY_ACTIVE_COUNT}" == "true" ]]; then
  ACTIVE_COUNT_MODE="exact"
  SNAPSHOT_COUNT="$("${PSQL[@]}" -q -At <<'SQL'
SET max_parallel_workers_per_gather = 0;
SELECT count(*)::bigint
FROM reference.parcel_coordinate_snapshot;
SQL
)"
else
  SNAPSHOT_COUNT="${PUBLISH_ROW_COUNT}"
fi

SAMPLE_VIOLATION_COUNT="$("${PSQL[@]}" -q -At <<'SQL'
SET max_parallel_workers_per_gather = 0;
SELECT count(*)::bigint
FROM (
    SELECT latitude, longitude, point, geom
    FROM reference.parcel_coordinate_snapshot
    ORDER BY pnu
    LIMIT 5
) AS active_sample
WHERE NOT (latitude BETWEEN 33 AND 39)
   OR NOT (longitude BETWEEN 124 AND 132)
   OR NOT (ST_SRID(point) = 4326)
   OR NOT (ST_SRID(geom) = 4326)
   OR NOT ST_IsValid(geom);
SQL
)"

EXPECTED_REGION_COUNT="$(token_count "${EXPECTED_REGIONS}")"
REQUIRED_CONSTRAINT_COUNT="7"

require_unsigned_integer "file_count" "${FILE_COUNT}"
require_unsigned_integer "region_count" "${REGION_COUNT}"
require_unsigned_integer "pnu_count" "${PNU_COUNT}"
require_unsigned_integer "snapshot_count" "${SNAPSHOT_COUNT}"
require_unsigned_integer "validated_constraint_count" "${VALIDATED_CONSTRAINT_COUNT}"
require_unsigned_integer "publish_region_count" "${PUBLISH_REGION_COUNT}"
require_unsigned_integer "publish_failed_region_count" "${PUBLISH_FAILED_REGION_COUNT}"
require_unsigned_integer "publish_row_count" "${PUBLISH_ROW_COUNT}"
require_unsigned_integer "publish_chunk_count" "${PUBLISH_CHUNK_COUNT}"
require_unsigned_integer "publish_failed_chunk_count" "${PUBLISH_FAILED_CHUNK_COUNT}"
require_unsigned_integer "publish_chunk_row_count" "${PUBLISH_CHUNK_ROW_COUNT}"
require_unsigned_integer "stage_chunk_count" "${STAGE_CHUNK_COUNT}"
require_unsigned_integer "stage_failed_chunk_count" "${STAGE_FAILED_CHUNK_COUNT}"
require_unsigned_integer "stage_chunk_pnu_count" "${STAGE_CHUNK_PNU_COUNT}"
require_unsigned_integer "sample_violation_count" "${SAMPLE_VIOLATION_COUNT}"
require_unsigned_integer "synced_parcel_count" "${SYNCED_PARCEL_COUNT}"

case "${SOURCE_FORMAT}" in
  vworld-al-d010|vworld-lsmd-cont-ldreg) ;;
  *)
    echo "ERROR: latest coordinate snapshot sourceFormat is unsupported: ${SOURCE_FORMAT}" >&2
    exit 1
    ;;
esac

if [[ "${REGION_COUNT}" -ne "${EXPECTED_REGION_COUNT}" ]]; then
  echo "ERROR: latest coordinate_snapshot_run.region_count=${REGION_COUNT}, expected ${EXPECTED_REGION_COUNT}." >&2
  exit 1
fi
if [[ "${FILE_COUNT}" -lt "${EXPECTED_REGION_COUNT}" ]]; then
  echo "ERROR: latest coordinate_snapshot_run.file_count=${FILE_COUNT}, expected at least ${EXPECTED_REGION_COUNT}." >&2
  exit 1
fi
if [[ "${PNU_COUNT}" -lt "${MIN_PNU_COUNT}" ]]; then
  echo "ERROR: latest coordinate_snapshot_run.pnu_count=${PNU_COUNT}, expected at least ${MIN_PNU_COUNT}." >&2
  exit 1
fi
if [[ "${SNAPSHOT_COUNT}" -ne "${PNU_COUNT}" ]]; then
  echo "ERROR: coordinate snapshot count=${SNAPSHOT_COUNT}, latest run pnu_count=${PNU_COUNT}, active_count_mode=${ACTIVE_COUNT_MODE}." >&2
  exit 1
fi
if [[ "${VALIDATED_CONSTRAINT_COUNT}" -ne "${REQUIRED_CONSTRAINT_COUNT}" ]]; then
  echo "ERROR: active parcel_coordinate_snapshot validated constraints=${VALIDATED_CONSTRAINT_COUNT}, expected ${REQUIRED_CONSTRAINT_COUNT}. Missing/invalid: ${INVALID_CONSTRAINTS}" >&2
  exit 1
fi
if [[ "${PUBLISH_REGION_COUNT}" -ne "${EXPECTED_REGION_COUNT}" || "${PUBLISH_FAILED_REGION_COUNT}" -ne 0 ]]; then
  echo "ERROR: publish checkpoint region status mismatch: passed=${PUBLISH_REGION_COUNT}, failed=${PUBLISH_FAILED_REGION_COUNT}, expected=${EXPECTED_REGION_COUNT}." >&2
  exit 1
fi
if [[ "${PUBLISH_ROW_COUNT}" -ne "${PNU_COUNT}" || "${PUBLISH_CHUNK_ROW_COUNT}" -ne "${PNU_COUNT}" || "${STAGE_CHUNK_PNU_COUNT}" -ne "${PNU_COUNT}" ]]; then
  echo "ERROR: checkpoint row counts do not match latest pnu_count=${PNU_COUNT}: publish_regions=${PUBLISH_ROW_COUNT}, publish_chunks=${PUBLISH_CHUNK_ROW_COUNT}, stage_chunks=${STAGE_CHUNK_PNU_COUNT}." >&2
  exit 1
fi
if [[ "${PUBLISH_FAILED_CHUNK_COUNT}" -ne 0 || "${STAGE_FAILED_CHUNK_COUNT}" -ne 0 ]]; then
  echo "ERROR: coordinate snapshot checkpoint has failed chunks: publish=${PUBLISH_FAILED_CHUNK_COUNT}, stage=${STAGE_FAILED_CHUNK_COUNT}." >&2
  exit 1
fi
if [[ "${PUBLISH_CHUNK_COUNT}" -lt "${EXPECTED_REGION_COUNT}" || "${STAGE_CHUNK_COUNT}" -lt "${EXPECTED_REGION_COUNT}" ]]; then
  echo "ERROR: coordinate snapshot checkpoint chunk counts are too small: publish=${PUBLISH_CHUNK_COUNT}, stage=${STAGE_CHUNK_COUNT}, expected at least ${EXPECTED_REGION_COUNT}." >&2
  exit 1
fi
if [[ "${SAMPLE_VIOLATION_COUNT}" -ne 0 ]]; then
  echo "ERROR: active parcel_coordinate_snapshot sample has ${SAMPLE_VIOLATION_COUNT} coordinate/SRID/geometry violations." >&2
  exit 1
fi
if [[ -n "${MISSING_REGIONS}" ]]; then
  echo "ERROR: latest coordinate snapshot report_json missingRegions is not empty: ${MISSING_REGIONS}" >&2
  exit 1
fi
if [[ "${SOURCE_SRID}" != "5186" || "${TARGET_SRID}" != "4326" ]]; then
  echo "ERROR: latest coordinate snapshot SRID mismatch: source=${SOURCE_SRID}, target=${TARGET_SRID}." >&2
  exit 1
fi
if [[ "${REQUIRE_SYNC_PARCEL}" == "true" && "${SYNCED_PARCEL_COUNT}" -le 0 ]]; then
  echo "ERROR: HOME_COORDINATE_REQUIRE_SYNC_PARCEL=true but synced_parcel_count=${SYNCED_PARCEL_COUNT}." >&2
  exit 1
fi

for region in ${EXPECTED_REGIONS}; do
  if ! contains_token "${SEEN_REGIONS}" "${region}"; then
    echo "ERROR: latest coordinate_snapshot_run.report_json seenRegions is missing ${region}." >&2
    exit 1
  fi
  if ! contains_token "${PUBLISH_REGIONS}" "${region}"; then
    echo "ERROR: publish checkpoint is missing region_code ${region}." >&2
    exit 1
  fi

  ACTIVE_REGION_PRESENT="$("${PSQL[@]}" -v region="${region}" -q -At <<'SQL'
SET max_parallel_workers_per_gather = 0;
SET enable_seqscan = off;
SELECT EXISTS (
    SELECT 1
    FROM reference.parcel_coordinate_snapshot
    WHERE region_code = :'region'
    LIMIT 1
);
SQL
)"
  if [[ "${ACTIVE_REGION_PRESENT}" != "t" ]]; then
    echo "ERROR: active parcel_coordinate_snapshot is missing region_code ${region}." >&2
    exit 1
  fi
done

echo "coordinate snapshot smoke passed: run_id=${RUN_ID}, version=${SNAPSHOT_VERSION}, source_format=${SOURCE_FORMAT}, files=${FILE_COUNT}, regions=${REGION_COUNT}, raw_features=${RAW_FEATURE_COUNT}, pnu_count=${PNU_COUNT}, invalid_count=${INVALID_COUNT}, duplicate_pnu_count=${DUPLICATE_PNU_COUNT}, synced_parcel_count=${SYNCED_PARCEL_COUNT}, strict_region_match=${STRICT_REGION_MATCH}, sync_parcel=${SYNC_PARCEL}, publish_chunks=${PUBLISH_CHUNK_COUNT}, stage_chunks=${STAGE_CHUNK_COUNT}, constraints_validated=${VALIDATED_CONSTRAINT_COUNT}, active_count_mode=${ACTIVE_COUNT_MODE}"
