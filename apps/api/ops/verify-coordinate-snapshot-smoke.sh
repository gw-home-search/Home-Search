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
  echo "self-test passed: coordinate snapshot smoke verifier"
}

if [[ "${SELF_TEST}" == "true" ]]; then
  run_self_test
  exit 0
fi

require_unsigned_integer "HOME_COORDINATE_MIN_PNU_COUNT" "${MIN_PNU_COUNT}"
require_boolean "HOME_COORDINATE_REQUIRE_SYNC_PARCEL" "${REQUIRE_SYNC_PARCEL}"

if ! command -v psql >/dev/null 2>&1; then
  echo "ERROR: psql is required on PATH" >&2
  exit 2
fi

SCHEMA_READY="$("${PSQL[@]}" -At <<'SQL'
SELECT to_regclass('reference.coordinate_snapshot_run') IS NOT NULL
   AND to_regclass('reference.parcel_coordinate_snapshot') IS NOT NULL;
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
),
snapshot_counts AS (
    SELECT
        count(*)::bigint AS snapshot_count,
        count(*) FILTER (
            WHERE NOT (latitude BETWEEN 33 AND 39)
               OR NOT (longitude BETWEEN 124 AND 132)
        )::bigint AS out_of_bounds_count,
        count(*) FILTER (WHERE NOT (ST_SRID(point) = 4326))::bigint AS point_srid_mismatch_count,
        count(*) FILTER (WHERE NOT (ST_SRID(geom) = 4326))::bigint AS geom_srid_mismatch_count,
        count(*) FILTER (WHERE NOT ST_IsValid(geom))::bigint AS invalid_geom_count,
        COALESCE(string_agg(DISTINCT region_code, ' ' ORDER BY region_code), '') AS snapshot_regions
    FROM reference.parcel_coordinate_snapshot
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
    COALESCE(latest.report_json->>'syncParcel', ''),
    snapshot_counts.snapshot_count,
    snapshot_counts.out_of_bounds_count,
    snapshot_counts.point_srid_mismatch_count,
    snapshot_counts.geom_srid_mismatch_count,
    snapshot_counts.invalid_geom_count,
    snapshot_counts.snapshot_regions
FROM latest
CROSS JOIN snapshot_counts;
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
  SYNC_PARCEL \
  SNAPSHOT_COUNT \
  OUT_OF_BOUNDS_COUNT \
  POINT_SRID_MISMATCH_COUNT \
  GEOM_SRID_MISMATCH_COUNT \
  INVALID_GEOM_COUNT \
  SNAPSHOT_REGIONS <<<"${REPORT}"

EXPECTED_REGION_COUNT="$(token_count "${EXPECTED_REGIONS}")"

require_unsigned_integer "file_count" "${FILE_COUNT}"
require_unsigned_integer "region_count" "${REGION_COUNT}"
require_unsigned_integer "pnu_count" "${PNU_COUNT}"
require_unsigned_integer "snapshot_count" "${SNAPSHOT_COUNT}"
require_unsigned_integer "out_of_bounds_count" "${OUT_OF_BOUNDS_COUNT}"
require_unsigned_integer "point_srid_mismatch_count" "${POINT_SRID_MISMATCH_COUNT}"
require_unsigned_integer "geom_srid_mismatch_count" "${GEOM_SRID_MISMATCH_COUNT}"
require_unsigned_integer "invalid_geom_count" "${INVALID_GEOM_COUNT}"
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
  echo "ERROR: active parcel_coordinate_snapshot count=${SNAPSHOT_COUNT}, latest run pnu_count=${PNU_COUNT}." >&2
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
if [[ "${OUT_OF_BOUNDS_COUNT}" -ne 0 ]]; then
  echo "ERROR: parcel_coordinate_snapshot has ${OUT_OF_BOUNDS_COUNT} rows outside Korea WGS84 bounds." >&2
  exit 1
fi
if [[ "${POINT_SRID_MISMATCH_COUNT}" -ne 0 || "${GEOM_SRID_MISMATCH_COUNT}" -ne 0 ]]; then
  echo "ERROR: parcel_coordinate_snapshot has SRID mismatches: point=${POINT_SRID_MISMATCH_COUNT}, geom=${GEOM_SRID_MISMATCH_COUNT}." >&2
  exit 1
fi
if [[ "${INVALID_GEOM_COUNT}" -ne 0 ]]; then
  echo "ERROR: parcel_coordinate_snapshot has ${INVALID_GEOM_COUNT} invalid geometries." >&2
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
  if ! contains_token "${SNAPSHOT_REGIONS}" "${region}"; then
    echo "ERROR: active parcel_coordinate_snapshot is missing region_code ${region}." >&2
    exit 1
  fi
done

echo "coordinate snapshot smoke passed: run_id=${RUN_ID}, version=${SNAPSHOT_VERSION}, source_format=${SOURCE_FORMAT}, files=${FILE_COUNT}, regions=${REGION_COUNT}, raw_features=${RAW_FEATURE_COUNT}, pnu_count=${PNU_COUNT}, invalid_count=${INVALID_COUNT}, duplicate_pnu_count=${DUPLICATE_PNU_COUNT}, synced_parcel_count=${SYNCED_PARCEL_COUNT}, strict_region_match=${STRICT_REGION_MATCH}, sync_parcel=${SYNC_PARCEL}"
