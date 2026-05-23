#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  HOME_COORDINATE_SHP_DIR=/coordinate-input ./ops/import-vworld-coordinate-snapshot.sh
  HOME_COORDINATE_SHP_DIR=/coordinate-input ./ops/import-vworld-coordinate-snapshot.sh --preflight-only
  ./ops/import-vworld-coordinate-snapshot.sh --self-test

Required:
  HOME_COORDINATE_SHP_DIR        Directory containing VWorld SHP files.

Database connection:
  PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD are consumed by psql.

Optional:
  HOME_COORDINATE_SNAPSHOT_VERSION       Snapshot label. Defaults to the single version in filenames.
  HOME_COORDINATE_INPUT_FORMAT           auto|vworld-al-d010|vworld-lsmd-cont-ldreg. Defaults to auto.
  HOME_COORDINATE_EXPECTED_REGIONS       Space-separated SIDO region list. Defaults to all 17 SIDO codes.
  HOME_COORDINATE_SOURCE_SRID            Defaults to 5186.
  HOME_COORDINATE_TARGET_SRID            Defaults to 4326.
  HOME_COORDINATE_SHP_ENCODING           Defaults to CP949.
  HOME_COORDINATE_REQUIRE_FULL_REGIONS   Defaults to true.
  HOME_COORDINATE_ALLOW_MIXED_VERSION    Defaults to false.
  HOME_COORDINATE_STRICT_REGION_MATCH    Defaults to true.
  HOME_COORDINATE_VALIDATE_PRJ           Defaults to true.
  HOME_COORDINATE_SYNC_PARCEL            Defaults to true.
  HOME_COORDINATE_KEEP_STAGING           Defaults to false.
EOF
}

PRE_FLIGHT_ONLY="false"
SELF_TEST="false"
SHP_DIR="${HOME_COORDINATE_SHP_DIR:-}"
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --preflight-only)
      PRE_FLIGHT_ONLY="true"
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
      if [[ -n "${SHP_DIR}" ]]; then
        echo "ERROR: HOME_COORDINATE_SHP_DIR was provided more than once" >&2
        exit 2
      fi
      SHP_DIR="$1"
      ;;
  esac
  shift
done

PSQL=(psql -X -v ON_ERROR_STOP=1)
SRC_SRID="${HOME_COORDINATE_SOURCE_SRID:-5186}"
DST_SRID="${HOME_COORDINATE_TARGET_SRID:-4326}"
ENCODING="${HOME_COORDINATE_SHP_ENCODING:-CP949}"
INPUT_FORMAT="${HOME_COORDINATE_INPUT_FORMAT:-auto}"
REQUIRE_FULL_REGIONS="${HOME_COORDINATE_REQUIRE_FULL_REGIONS:-true}"
ALLOW_MIXED_VERSION="${HOME_COORDINATE_ALLOW_MIXED_VERSION:-false}"
STRICT_REGION_MATCH="${HOME_COORDINATE_STRICT_REGION_MATCH:-true}"
VALIDATE_PRJ="${HOME_COORDINATE_VALIDATE_PRJ:-true}"
SYNC_PARCEL="${HOME_COORDINATE_SYNC_PARCEL:-true}"
KEEP_STAGING="${HOME_COORDINATE_KEEP_STAGING:-false}"
EXPECTED_REGIONS="${HOME_COORDINATE_EXPECTED_REGIONS:-11 26 27 28 29 30 31 36 41 43 44 46 47 48 50 51 52}"
LOCK_KEY="home_search_coordinate_snapshot_import"
LOCK_ACQUIRED="false"
RUN_ID=""
PARSED_FORMAT=""
PARSED_REGION_CODE=""
PARSED_VERSION_TOKEN=""

contains_token() {
  case " $1 " in
    *" $2 "*) return 0 ;;
    *) return 1 ;;
  esac
}

append_unique_token() {
  local current="$1"
  local token="$2"
  if contains_token "${current}" "${token}"; then
    printf '%s' "${current}"
  elif [[ -z "${current}" ]]; then
    printf '%s' "${token}"
  else
    printf '%s %s' "${current}" "${token}"
  fi
}

append_csv_token() {
  local current="$1"
  local token="$2"
  if [[ -z "${current}" ]]; then
    printf '%s' "${token}"
  else
    printf '%s,%s' "${current}" "${token}"
  fi
}

increment_part_count() {
  local current="$1"
  local region="$2"
  local output=""
  local found="false"
  local token key value
  for token in ${current}; do
    key="${token%%=*}"
    value="${token#*=}"
    if [[ "${key}" == "${region}" ]]; then
      value="$((value + 1))"
      found="true"
    fi
    if [[ -z "${output}" ]]; then
      output="${key}=${value}"
    else
      output="${output} ${key}=${value}"
    fi
  done
  if [[ "${found}" != "true" ]]; then
    if [[ -z "${output}" ]]; then
      output="${region}=1"
    else
      output="${output} ${region}=1"
    fi
  fi
  printf '%s' "${output}"
}

normalize_region_code() {
  local region="$1"
  if [[ "${region}" == "36110" ]]; then
    printf '36'
  else
    printf '%s' "${region}"
  fi
}

parse_supported_filename() {
  local base_name="$1"
  PARSED_FORMAT=""
  PARSED_REGION_CODE=""
  PARSED_VERSION_TOKEN=""
  if [[ "${base_name}" =~ ^LSMD_CONT_LDREG_([0-9]+)_([0-9]{6})\.shp$ ]]; then
    PARSED_FORMAT="vworld-lsmd-cont-ldreg"
    PARSED_REGION_CODE="$(normalize_region_code "${BASH_REMATCH[1]}")"
    PARSED_VERSION_TOKEN="${BASH_REMATCH[2]}"
    return 0
  fi
  if [[ "${base_name}" =~ ^AL_D010_([0-9]{2})_([0-9]{8})(\([0-9]+\))?\.shp$ ]]; then
    PARSED_FORMAT="vworld-al-d010"
    PARSED_REGION_CODE="$(normalize_region_code "${BASH_REMATCH[1]}")"
    PARSED_VERSION_TOKEN="${BASH_REMATCH[2]}"
    return 0
  fi
  return 1
}

assert_parse_supported_filename() {
  local file_name="$1"
  local expected_format="$2"
  local expected_region="$3"
  local expected_version="$4"
  if ! parse_supported_filename "${file_name}"; then
    echo "self-test failed: parser rejected ${file_name}" >&2
    exit 1
  fi
  if [[ "${PARSED_FORMAT}" != "${expected_format}" ||
        "${PARSED_REGION_CODE}" != "${expected_region}" ||
        "${PARSED_VERSION_TOKEN}" != "${expected_version}" ]]; then
    echo "self-test failed: parser mismatch for ${file_name}" >&2
    exit 1
  fi
}

run_self_test() {
  assert_parse_supported_filename "LSMD_CONT_LDREG_11_202512.shp" "vworld-lsmd-cont-ldreg" "11" "202512"
  assert_parse_supported_filename "LSMD_CONT_LDREG_36110_202512.shp" "vworld-lsmd-cont-ldreg" "36" "202512"
  assert_parse_supported_filename "AL_D010_11_20251204.shp" "vworld-al-d010" "11" "20251204"
  assert_parse_supported_filename "AL_D010_41_20251204(3).shp" "vworld-al-d010" "41" "20251204"
  if parse_supported_filename "AL_D010_41_20251204(3).dbf"; then
    echo "self-test failed: non-shp file was accepted" >&2
    exit 1
  fi
  echo "self-test passed: VWorld coordinate snapshot importer parser"
}

if [[ "${SELF_TEST}" == "true" ]]; then
  run_self_test
  exit 0
fi

case "${INPUT_FORMAT}" in
  auto|vworld-al-d010|vworld-lsmd-cont-ldreg) ;;
  *)
    echo "ERROR: unsupported HOME_COORDINATE_INPUT_FORMAT: ${INPUT_FORMAT}" >&2
    exit 2
    ;;
esac

if [[ -z "${SHP_DIR}" ]]; then
  usage >&2
  exit 2
fi
if [[ ! -d "${SHP_DIR}" ]]; then
  echo "ERROR: HOME_COORDINATE_SHP_DIR does not exist: ${SHP_DIR}" >&2
  exit 2
fi

if ! command -v shp2pgsql >/dev/null 2>&1; then
  echo "ERROR: shp2pgsql is required on PATH" >&2
  exit 2
fi
if [[ "${PRE_FLIGHT_ONLY}" != "true" ]] && ! command -v psql >/dev/null 2>&1; then
  echo "ERROR: psql is required on PATH" >&2
  exit 2
fi

release_lock() {
  if [[ "${LOCK_ACQUIRED}" == "true" ]]; then
    printf "\\set lock_key '%s'\nSELECT pg_advisory_unlock(hashtext(:'lock_key'));\n\\q\n" "${LOCK_KEY}" >&"${LOCK_PSQL[1]}" || true
    IFS= read -r _ <&"${LOCK_PSQL[0]}" || true
    wait "${LOCK_PSQL_PID}" 2>/dev/null || true
    LOCK_ACQUIRED="false"
  fi
}

acquire_lock() {
  coproc LOCK_PSQL { psql -X -q -t -A -v ON_ERROR_STOP=1; }
  printf "\\set lock_key '%s'\nSELECT pg_try_advisory_lock(hashtext(:'lock_key'));\n" "${LOCK_KEY}" >&"${LOCK_PSQL[1]}"
  local lock_result
  IFS= read -r lock_result <&"${LOCK_PSQL[0]}"
  if [[ "${lock_result}" != "t" ]]; then
    printf "\\q\n" >&"${LOCK_PSQL[1]}" || true
    wait "${LOCK_PSQL_PID}" 2>/dev/null || true
    echo "ERROR: another coordinate snapshot import is already running." >&2
    exit 1
  fi
  LOCK_ACQUIRED="true"
}

validate_prj_for_source_srid() {
  local prj_file="$1"
  local base_name="$2"
  if [[ "${VALIDATE_PRJ}" != "true" ]]; then
    return 0
  fi
  if [[ "${SRC_SRID}" != "5186" ]]; then
    echo "ERROR: PRJ validation currently supports HOME_COORDINATE_SOURCE_SRID=5186 only. Set HOME_COORDINATE_VALIDATE_PRJ=false to bypass deliberately." >&2
    exit 2
  fi
  if ! LC_ALL=C grep -Eiq 'Korea[ _]2000|Korea_2000' "${prj_file}" ||
     ! LC_ALL=C grep -Eiq 'Central[ _]Belt|Korea_Central_Belt_2010|Central_Belt_2010' "${prj_file}" ||
     ! LC_ALL=C grep -Eiq 'GRS[_ ]?1980|GRS 1980' "${prj_file}"; then
    echo "ERROR: ${base_name%.shp}.prj does not look like VWorld EPSG:5186 Korea 2000 / Central Belt 2010." >&2
    exit 2
  fi
}

validate_file_columns() {
  local file="$1"
  local format="$2"
  local base_name="$3"
  local schema_sql
  if ! schema_sql="$(shp2pgsql -W "${ENCODING}" -s "${SRC_SRID}:${DST_SRID}" -p "${file}" "reference._coordinate_preflight" 2>/dev/null)"; then
    echo "ERROR: could not inspect SHP fields for ${base_name}" >&2
    exit 2
  fi
  case "${format}" in
    vworld-al-d010)
      # A2 -> pnu, A23 -> source_region_code
      if ! grep -q '"a2"' <<<"${schema_sql}" || ! grep -q '"a23"' <<<"${schema_sql}"; then
        echo "ERROR: ${base_name} is missing required AL_D010 fields A2/A23." >&2
        exit 2
      fi
      ;;
    vworld-lsmd-cont-ldreg)
      if ! grep -q '"pnu"' <<<"${schema_sql}"; then
        echo "ERROR: ${base_name} is missing required LSMD_CONT_LDREG pnu field." >&2
        exit 2
      fi
      ;;
  esac
}
trap release_lock EXIT

mark_failed() {
  local exit_code=$?
  local line_number="${1:-unknown}"
  trap - ERR
  if [[ -n "${RUN_ID}" ]]; then
    "${PSQL[@]}" \
      -v run_id="${RUN_ID}" \
      -v failure_reason="import failed near line ${line_number}" <<'SQL' >/dev/null || true
UPDATE reference.coordinate_snapshot_run
SET status = 'FAILED',
    failure_reason = :'failure_reason',
    finished_at = now()
WHERE id = (:'run_id')::bigint
  AND status = 'STARTED';
SQL
  fi
  release_lock
  exit "${exit_code}"
}
if [[ "${PRE_FLIGHT_ONLY}" != "true" ]]; then
  trap 'mark_failed ${LINENO}' ERR
fi

fail_run() {
  local message="$1"
  echo "ERROR: ${message}" >&2
  if [[ -n "${RUN_ID}" ]]; then
    "${PSQL[@]}" \
      -v run_id="${RUN_ID}" \
      -v failure_reason="${message}" <<'SQL' >/dev/null || true
UPDATE reference.coordinate_snapshot_run
SET status = 'FAILED',
    failure_reason = :'failure_reason',
    finished_at = now()
WHERE id = (:'run_id')::bigint
  AND status = 'STARTED';
SQL
  fi
  release_lock
  exit 1
}

SHP_FILES=()
case "${INPUT_FORMAT}" in
  auto)
    find_expr=(find "${SHP_DIR}" -maxdepth 1 -type f \( -name 'LSMD_CONT_LDREG_*.shp' -o -name 'AL_D010_*.shp' \))
    ;;
  vworld-al-d010)
    find_expr=(find "${SHP_DIR}" -maxdepth 1 -type f -name 'AL_D010_*.shp')
    ;;
  vworld-lsmd-cont-ldreg)
    find_expr=(find "${SHP_DIR}" -maxdepth 1 -type f -name 'LSMD_CONT_LDREG_*.shp')
    ;;
esac
while IFS= read -r file; do
  [[ -n "${file}" ]] && SHP_FILES+=("${file}")
done < <("${find_expr[@]}" | sort)

if [[ "${#SHP_FILES[@]}" -eq 0 ]]; then
  echo "ERROR: no VWorld coordinate SHP files found in ${SHP_DIR} for input format ${INPUT_FORMAT}. Expected LSMD_CONT_LDREG_*.shp or AL_D010_*.shp." >&2
  exit 2
fi

SHP_FORMATS=()
seen_regions=""
seen_versions=""
seen_formats=""
file_names=""
part_counts=""
missing_sidecars=""
for file in "${SHP_FILES[@]}"; do
  base_name="$(basename "${file}")"
  if ! parse_supported_filename "${base_name}"; then
    echo "ERROR: unexpected VWorld cadastral filename: ${base_name}" >&2
    exit 2
  fi
  if [[ "${INPUT_FORMAT}" != "auto" && "${PARSED_FORMAT}" != "${INPUT_FORMAT}" ]]; then
    echo "ERROR: ${base_name} does not match HOME_COORDINATE_INPUT_FORMAT=${INPUT_FORMAT}" >&2
    exit 2
  fi
  SHP_FORMATS+=("${PARSED_FORMAT}")
  region_code="${PARSED_REGION_CODE}"
  version_token="${PARSED_VERSION_TOKEN}"
  seen_regions="$(append_unique_token "${seen_regions}" "${region_code}")"
  seen_versions="$(append_unique_token "${seen_versions}" "${version_token}")"
  seen_formats="$(append_unique_token "${seen_formats}" "${PARSED_FORMAT}")"
  file_names="$(append_csv_token "${file_names}" "${base_name}")"
  part_counts="$(increment_part_count "${part_counts}" "${region_code}")"

  for sidecar_ext in shx dbf prj; do
    if [[ ! -f "${file%.shp}.${sidecar_ext}" ]]; then
      missing_sidecars="${missing_sidecars}${base_name%.shp}.${sidecar_ext} "
    fi
  done
  validate_prj_for_source_srid "${file%.shp}.prj" "${base_name}"
  validate_file_columns "${file}" "${PARSED_FORMAT}" "${base_name}"
done

if [[ -n "${missing_sidecars}" ]]; then
  echo "ERROR: missing required SHP sidecar files: ${missing_sidecars}" >&2
  exit 2
fi

missing_regions=""
if [[ "${REQUIRE_FULL_REGIONS}" == "true" ]]; then
  for expected_region in ${EXPECTED_REGIONS}; do
    if ! contains_token "${seen_regions}" "${expected_region}"; then
      missing_regions="$(append_unique_token "${missing_regions}" "${expected_region}")"
    fi
  done
  if [[ -n "${missing_regions}" ]]; then
    echo "ERROR: missing required VWorld SIDO SHP region codes: ${missing_regions}" >&2
    exit 2
  fi
fi

if [[ "${ALLOW_MIXED_VERSION}" != "true" && "${seen_versions}" == *" "* ]]; then
  echo "ERROR: mixed SHP filename versions are not allowed: ${seen_versions}" >&2
  exit 2
fi

if [[ "${seen_formats}" == *" "* ]]; then
  echo "ERROR: mixed SHP source formats are not allowed in one import: ${seen_formats}" >&2
  exit 2
fi

SNAPSHOT_VERSION="${HOME_COORDINATE_SNAPSHOT_VERSION:-${seen_versions// /+}}"
FILE_COUNT="${#SHP_FILES[@]}"
REGION_COUNT="$(wc -w <<<"${seen_regions}" | tr -d ' ')"
SOURCE_FORMAT="${seen_formats}"

if [[ "${PRE_FLIGHT_ONLY}" == "true" ]]; then
  echo "coordinate snapshot preflight passed: source_format=${SOURCE_FORMAT}, version=${SNAPSHOT_VERSION}, files=${FILE_COUNT}, regions=${seen_regions}, expected_regions=${EXPECTED_REGIONS}, part_counts=${part_counts}"
  exit 0
fi

schema_ready="$("${PSQL[@]}" -At <<'SQL'
SELECT to_regclass('reference.coordinate_snapshot_run') IS NOT NULL
   AND to_regclass('reference.parcel_coordinate_snapshot') IS NOT NULL;
SQL
)"
if [[ "${schema_ready}" != "t" ]]; then
  echo "ERROR: coordinate snapshot schema is missing. Run API Flyway migrations first." >&2
  exit 2
fi

acquire_lock

RUN_ID="$("${PSQL[@]}" -At \
  -v snapshot_version="${SNAPSHOT_VERSION}" \
  -v source_dir="${SHP_DIR}" \
  -v source_srid="${SRC_SRID}" \
  -v target_srid="${DST_SRID}" <<'SQL'
INSERT INTO reference.coordinate_snapshot_run (
    snapshot_version,
    source_dir,
    source_srid,
    target_srid,
    status
)
VALUES (
    :'snapshot_version',
    :'source_dir',
    (:'source_srid')::integer,
    (:'target_srid')::integer,
    'STARTED'
)
RETURNING id;
SQL
)"

echo "coordinate snapshot import started: run_id=${RUN_ID}, version=${SNAPSHOT_VERSION}, files=${FILE_COUNT}"

"${PSQL[@]}" <<'SQL'
DROP TABLE IF EXISTS reference.land_parcel_file_raw;
DROP TABLE IF EXISTS reference.land_parcel_snapshot_raw_next;
DROP TABLE IF EXISTS reference.parcel_coordinate_snapshot_next;

CREATE TABLE reference.land_parcel_snapshot_raw_next (
    pnu VARCHAR(19),
    source_region_code VARCHAR(8),
    source_file TEXT NOT NULL,
    geom geometry(MultiPolygon, 4326)
);
SQL

file_index=0
for file in "${SHP_FILES[@]}"; do
  base_name="$(basename "${file}")"
  source_format_for_file="${SHP_FORMATS[$file_index]}"
  file_index="$((file_index + 1))"
  echo "importing ${base_name}"
  "${PSQL[@]}" <<'SQL' >/dev/null
DROP TABLE IF EXISTS reference.land_parcel_file_raw;
SQL
  shp2pgsql -W "${ENCODING}" -D -s "${SRC_SRID}:${DST_SRID}" \
    "${file}" "reference.land_parcel_file_raw" \
    | "${PSQL[@]}" >/dev/null

  if [[ "${source_format_for_file}" == "vworld-al-d010" ]]; then
    "${PSQL[@]}" -v source_file="${base_name}" <<'SQL'
INSERT INTO reference.land_parcel_snapshot_raw_next (
    pnu,
    source_region_code,
    source_file,
    geom
)
SELECT
    NULLIF(btrim(a2::text), '')::varchar(19),
    NULLIF(btrim(a23::text), '')::varchar(8),
    (:'source_file')::text,
    geom::geometry(MultiPolygon, 4326)
FROM reference.land_parcel_file_raw;
SQL
  else
    "${PSQL[@]}" -v source_file="${base_name}" <<'SQL'
INSERT INTO reference.land_parcel_snapshot_raw_next (
    pnu,
    source_region_code,
    source_file,
    geom
)
SELECT
    NULLIF(btrim(pnu::text), '')::varchar(19),
    left(NULLIF(btrim(pnu::text), ''), 5)::varchar(8),
    (:'source_file')::text,
    geom::geometry(MultiPolygon, 4326)
FROM reference.land_parcel_file_raw;
SQL
  fi
done

"${PSQL[@]}" <<'SQL'
ANALYZE reference.land_parcel_snapshot_raw_next;
SQL

"${PSQL[@]}" \
  -v snapshot_version="${SNAPSHOT_VERSION}" \
  -v run_id="${RUN_ID}" \
  -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
CREATE TABLE reference.parcel_coordinate_snapshot_next AS
WITH valid_raw AS (
    SELECT
        pnu::varchar(19) AS pnu,
        source_file,
        geom
    FROM reference.land_parcel_snapshot_raw_next
    WHERE pnu IS NOT NULL
      AND pnu::text ~ '^[0-9]{19}$'
      AND geom IS NOT NULL
      AND (
          :'strict_region_match' <> 'true'
          OR (
              source_region_code IS NOT NULL
              AND left(pnu::text, 5) = left(source_region_code::text, 5)
          )
      )
),
aggregated AS (
    SELECT
        pnu,
        left(pnu, 2) AS region_code,
        min(source_file) AS source_file,
        ST_Multi(
            ST_CollectionExtract(
                ST_UnaryUnion(ST_Collect(ST_MakeValid(geom))),
                3
            )
        )::geometry(MultiPolygon, 4326) AS geom
    FROM valid_raw
    GROUP BY pnu
),
with_point AS (
    SELECT
        pnu,
        region_code,
        source_file,
        geom,
        ST_PointOnSurface(geom)::geometry(Point, 4326) AS point
    FROM aggregated
    WHERE NOT ST_IsEmpty(geom)
      AND ST_IsValid(geom)
)
SELECT
    pnu,
    region_code,
    ST_Y(point)::numeric(10, 7) AS latitude,
    ST_X(point)::numeric(10, 7) AS longitude,
    point,
    geom,
    (:'snapshot_version')::varchar(64) AS snapshot_version,
    source_file,
    (:'run_id')::bigint AS run_id,
    now() AS created_at,
    now() AS updated_at
FROM with_point
WHERE ST_Y(point) BETWEEN 33 AND 39
  AND ST_X(point) BETWEEN 124 AND 132;

ANALYZE reference.parcel_coordinate_snapshot_next;
SQL

RAW_FEATURE_COUNT="$("${PSQL[@]}" -At -c "SELECT count(*) FROM reference.land_parcel_snapshot_raw_next")"
INVALID_COUNT="$("${PSQL[@]}" -At -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
SELECT count(*)
FROM reference.land_parcel_snapshot_raw_next
WHERE pnu IS NULL
   OR pnu::text !~ '^[0-9]{19}$'
   OR geom IS NULL
   OR (
       :'strict_region_match' = 'true'
       AND (
           source_region_code IS NULL
           OR left(pnu::text, 5) <> left(source_region_code::text, 5)
       )
   );
SQL
)"
DUPLICATE_PNU_COUNT="$("${PSQL[@]}" -At -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
SELECT count(*)
FROM (
    SELECT pnu
    FROM reference.land_parcel_snapshot_raw_next
    WHERE pnu IS NOT NULL
      AND pnu::text ~ '^[0-9]{19}$'
      AND (
          :'strict_region_match' <> 'true'
          OR (
              source_region_code IS NOT NULL
              AND left(pnu::text, 5) = left(source_region_code::text, 5)
          )
      )
    GROUP BY pnu
    HAVING count(*) > 1
) duplicates;
SQL
)"
PNU_COUNT="$("${PSQL[@]}" -At -c "SELECT count(*) FROM reference.parcel_coordinate_snapshot_next")"

if [[ "${PNU_COUNT}" -eq 0 ]]; then
  fail_run "no valid coordinate rows were produced from SHP input"
fi

if [[ "${SYNC_PARCEL}" == "true" ]]; then
	  "${PSQL[@]}" \
	    -v run_id="${RUN_ID}" \
	    -v file_count="${FILE_COUNT}" \
	    -v region_count="${REGION_COUNT}" \
	    -v raw_feature_count="${RAW_FEATURE_COUNT}" \
	    -v pnu_count="${PNU_COUNT}" \
	    -v invalid_count="${INVALID_COUNT}" \
	    -v duplicate_pnu_count="${DUPLICATE_PNU_COUNT}" \
	    -v expected_regions="${EXPECTED_REGIONS}" \
	    -v seen_regions="${seen_regions}" \
	    -v missing_regions="${missing_regions}" \
	    -v source_format="${SOURCE_FORMAT}" \
	    -v file_names="${file_names}" \
	    -v part_counts="${part_counts}" \
	    -v source_srid="${SRC_SRID}" \
	    -v target_srid="${DST_SRID}" \
	    -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
BEGIN;

TRUNCATE reference.parcel_coordinate_snapshot;

INSERT INTO reference.parcel_coordinate_snapshot (
    pnu,
    region_code,
    latitude,
    longitude,
    point,
    geom,
    snapshot_version,
    source_file,
    run_id,
    created_at,
    updated_at
)
SELECT
    pnu,
    region_code,
    latitude,
    longitude,
    point,
    geom,
    snapshot_version,
    source_file,
    run_id,
    created_at,
    updated_at
FROM reference.parcel_coordinate_snapshot_next;

CREATE TEMP TABLE coordinate_sync_result ON COMMIT DROP AS
WITH updated AS (
    UPDATE parcel p
    SET latitude = s.latitude,
        longitude = s.longitude,
        geom = s.geom,
        updated_at = now()
    FROM reference.parcel_coordinate_snapshot s
    WHERE p.pnu = s.pnu
    RETURNING p.id
)
SELECT count(*)::bigint AS synced_count
FROM updated;

UPDATE reference.coordinate_snapshot_run
SET status = 'PASSED',
    file_count = (:'file_count')::integer,
    region_count = (:'region_count')::integer,
    raw_feature_count = (:'raw_feature_count')::bigint,
    pnu_count = (:'pnu_count')::bigint,
    invalid_count = (:'invalid_count')::bigint,
    duplicate_pnu_count = (:'duplicate_pnu_count')::bigint,
    synced_parcel_count = (SELECT synced_count FROM coordinate_sync_result),
    report_json = jsonb_build_object(
        'sourceFormat', :'source_format',
        'expectedRegions', :'expected_regions',
        'seenRegions', :'seen_regions',
        'missingRegions', :'missing_regions',
        'fileNames', to_jsonb(string_to_array(:'file_names', ',')),
        'partCounts', :'part_counts',
        'sourceSrid', (:'source_srid')::integer,
        'targetSrid', (:'target_srid')::integer,
        'strictRegionMatch', :'strict_region_match',
        'syncParcel', true
    ),
    finished_at = now()
WHERE id = (:'run_id')::bigint;

COMMIT;
SQL
else
	  "${PSQL[@]}" \
	    -v run_id="${RUN_ID}" \
	    -v file_count="${FILE_COUNT}" \
	    -v region_count="${REGION_COUNT}" \
	    -v raw_feature_count="${RAW_FEATURE_COUNT}" \
	    -v pnu_count="${PNU_COUNT}" \
	    -v invalid_count="${INVALID_COUNT}" \
	    -v duplicate_pnu_count="${DUPLICATE_PNU_COUNT}" \
	    -v expected_regions="${EXPECTED_REGIONS}" \
	    -v seen_regions="${seen_regions}" \
	    -v missing_regions="${missing_regions}" \
	    -v source_format="${SOURCE_FORMAT}" \
	    -v file_names="${file_names}" \
	    -v part_counts="${part_counts}" \
	    -v source_srid="${SRC_SRID}" \
	    -v target_srid="${DST_SRID}" \
	    -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
BEGIN;

TRUNCATE reference.parcel_coordinate_snapshot;

INSERT INTO reference.parcel_coordinate_snapshot (
    pnu,
    region_code,
    latitude,
    longitude,
    point,
    geom,
    snapshot_version,
    source_file,
    run_id,
    created_at,
    updated_at
)
SELECT
    pnu,
    region_code,
    latitude,
    longitude,
    point,
    geom,
    snapshot_version,
    source_file,
    run_id,
    created_at,
    updated_at
FROM reference.parcel_coordinate_snapshot_next;

UPDATE reference.coordinate_snapshot_run
SET status = 'PASSED',
    file_count = (:'file_count')::integer,
    region_count = (:'region_count')::integer,
    raw_feature_count = (:'raw_feature_count')::bigint,
    pnu_count = (:'pnu_count')::bigint,
    invalid_count = (:'invalid_count')::bigint,
    duplicate_pnu_count = (:'duplicate_pnu_count')::bigint,
    synced_parcel_count = 0,
    report_json = jsonb_build_object(
        'sourceFormat', :'source_format',
        'expectedRegions', :'expected_regions',
        'seenRegions', :'seen_regions',
        'missingRegions', :'missing_regions',
        'fileNames', to_jsonb(string_to_array(:'file_names', ',')),
        'partCounts', :'part_counts',
        'sourceSrid', (:'source_srid')::integer,
        'targetSrid', (:'target_srid')::integer,
        'strictRegionMatch', :'strict_region_match',
        'syncParcel', false
    ),
    finished_at = now()
WHERE id = (:'run_id')::bigint;

COMMIT;
SQL
fi

if [[ "${KEEP_STAGING}" != "true" ]]; then
  "${PSQL[@]}" <<'SQL' >/dev/null
DROP TABLE IF EXISTS reference.land_parcel_file_raw;
DROP TABLE IF EXISTS reference.land_parcel_snapshot_raw_next;
DROP TABLE IF EXISTS reference.parcel_coordinate_snapshot_next;
SQL
fi

trap - ERR
release_lock
echo "coordinate snapshot import passed: run_id=${RUN_ID}, pnu_count=${PNU_COUNT}, invalid_count=${INVALID_COUNT}, duplicate_pnu_count=${DUPLICATE_PNU_COUNT}"
