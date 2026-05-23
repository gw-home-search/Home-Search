#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  HOME_COORDINATE_SHP_DIR=/coordinate-input ./ops/import-vworld-coordinate-snapshot.sh

Required:
  HOME_COORDINATE_SHP_DIR        Directory containing VWorld LSMD_CONT_LDREG_*.shp files.

Database connection:
  PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD are consumed by psql/shp2pgsql.

Optional:
  HOME_COORDINATE_SNAPSHOT_VERSION       Snapshot label. Defaults to the single YYYYMM in filenames.
  HOME_COORDINATE_SOURCE_SRID            Defaults to 5186.
  HOME_COORDINATE_TARGET_SRID            Defaults to 4326.
  HOME_COORDINATE_SHP_ENCODING           Defaults to CP949.
  HOME_COORDINATE_REQUIRE_FULL_REGIONS   Defaults to true.
  HOME_COORDINATE_ALLOW_MIXED_VERSION    Defaults to false.
  HOME_COORDINATE_VALIDATE_PRJ           Defaults to true.
  HOME_COORDINATE_SYNC_PARCEL            Defaults to true.
  HOME_COORDINATE_KEEP_STAGING           Defaults to false.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

SHP_DIR="${HOME_COORDINATE_SHP_DIR:-${1:-}}"
if [[ -z "${SHP_DIR}" ]]; then
  usage >&2
  exit 2
fi
if [[ ! -d "${SHP_DIR}" ]]; then
  echo "ERROR: HOME_COORDINATE_SHP_DIR does not exist: ${SHP_DIR}" >&2
  exit 2
fi

for required_command in psql shp2pgsql; do
  if ! command -v "${required_command}" >/dev/null 2>&1; then
    echo "ERROR: ${required_command} is required on PATH" >&2
    exit 2
  fi
done

PSQL=(psql -X -v ON_ERROR_STOP=1)
SRC_SRID="${HOME_COORDINATE_SOURCE_SRID:-5186}"
DST_SRID="${HOME_COORDINATE_TARGET_SRID:-4326}"
ENCODING="${HOME_COORDINATE_SHP_ENCODING:-CP949}"
REQUIRE_FULL_REGIONS="${HOME_COORDINATE_REQUIRE_FULL_REGIONS:-true}"
ALLOW_MIXED_VERSION="${HOME_COORDINATE_ALLOW_MIXED_VERSION:-false}"
VALIDATE_PRJ="${HOME_COORDINATE_VALIDATE_PRJ:-true}"
SYNC_PARCEL="${HOME_COORDINATE_SYNC_PARCEL:-true}"
KEEP_STAGING="${HOME_COORDINATE_KEEP_STAGING:-false}"
EXPECTED_REGIONS="11 26 27 28 29 30 31 36 41 43 44 46 47 48 50 51 52"
LOCK_KEY="home_search_coordinate_snapshot_import"
LOCK_ACQUIRED="false"
RUN_ID=""

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

normalize_region_code() {
  local region="$1"
  if [[ "${region}" == "36110" ]]; then
    printf '36'
  else
    printf '%s' "${region}"
  fi
}

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
trap 'mark_failed ${LINENO}' ERR

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

SHP_FILES=()
while IFS= read -r file; do
  [[ -n "${file}" ]] && SHP_FILES+=("${file}")
done < <(find "${SHP_DIR}" -maxdepth 1 -type f -name 'LSMD_CONT_LDREG_*.shp' | sort)

if [[ "${#SHP_FILES[@]}" -eq 0 ]]; then
  echo "ERROR: no LSMD_CONT_LDREG_*.shp files found in ${SHP_DIR}" >&2
  exit 2
fi

seen_regions=""
seen_versions=""
missing_sidecars=""
for file in "${SHP_FILES[@]}"; do
  base_name="$(basename "${file}")"
  if [[ ! "${base_name}" =~ ^LSMD_CONT_LDREG_([0-9]+)_([0-9]{6})\.shp$ ]]; then
    echo "ERROR: unexpected VWorld cadastral filename: ${base_name}" >&2
    exit 2
  fi
  region_code="$(normalize_region_code "${BASH_REMATCH[1]}")"
  version_token="${BASH_REMATCH[2]}"
  seen_regions="$(append_unique_token "${seen_regions}" "${region_code}")"
  seen_versions="$(append_unique_token "${seen_versions}" "${version_token}")"

  for sidecar_ext in shx dbf prj; do
    if [[ ! -f "${file%.shp}.${sidecar_ext}" ]]; then
      missing_sidecars="${missing_sidecars}${base_name%.shp}.${sidecar_ext} "
    fi
  done
  validate_prj_for_source_srid "${file%.shp}.prj" "${base_name}"
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

SNAPSHOT_VERSION="${HOME_COORDINATE_SNAPSHOT_VERSION:-${seen_versions// /+}}"
FILE_COUNT="${#SHP_FILES[@]}"
REGION_COUNT="$(wc -w <<<"${seen_regions}" | tr -d ' ')"

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
SQL

first_file="true"
for file in "${SHP_FILES[@]}"; do
  base_name="$(basename "${file}")"
  echo "importing ${base_name}"
  "${PSQL[@]}" <<'SQL' >/dev/null
DROP TABLE IF EXISTS reference.land_parcel_file_raw;
SQL
  shp2pgsql -W "${ENCODING}" -D -s "${SRC_SRID}:${DST_SRID}" \
    "${file}" "reference.land_parcel_file_raw" \
    | "${PSQL[@]}" >/dev/null

  if [[ "${first_file}" == "true" ]]; then
    "${PSQL[@]}" -v source_file="${base_name}" <<'SQL'
CREATE TABLE reference.land_parcel_snapshot_raw_next AS
SELECT *, (:'source_file')::text AS source_file
FROM reference.land_parcel_file_raw;
SQL
    first_file="false"
  else
    "${PSQL[@]}" -v source_file="${base_name}" <<'SQL'
INSERT INTO reference.land_parcel_snapshot_raw_next
SELECT *, (:'source_file')::text AS source_file
FROM reference.land_parcel_file_raw;
SQL
  fi
done

"${PSQL[@]}" <<'SQL'
ANALYZE reference.land_parcel_snapshot_raw_next;
SQL

"${PSQL[@]}" \
  -v snapshot_version="${SNAPSHOT_VERSION}" \
  -v run_id="${RUN_ID}" <<'SQL'
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
INVALID_COUNT="$("${PSQL[@]}" -At <<'SQL'
SELECT count(*)
FROM reference.land_parcel_snapshot_raw_next
WHERE pnu IS NULL
   OR pnu::text !~ '^[0-9]{19}$'
   OR geom IS NULL;
SQL
)"
DUPLICATE_PNU_COUNT="$("${PSQL[@]}" -At <<'SQL'
SELECT count(*)
FROM (
    SELECT pnu
    FROM reference.land_parcel_snapshot_raw_next
    WHERE pnu IS NOT NULL
      AND pnu::text ~ '^[0-9]{19}$'
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
    -v missing_regions="${missing_regions}" <<'SQL'
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
        'expectedRegions', :'expected_regions',
        'seenRegions', :'seen_regions',
        'missingRegions', :'missing_regions',
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
    -v missing_regions="${missing_regions}" <<'SQL'
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
        'expectedRegions', :'expected_regions',
        'seenRegions', :'seen_regions',
        'missingRegions', :'missing_regions',
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
