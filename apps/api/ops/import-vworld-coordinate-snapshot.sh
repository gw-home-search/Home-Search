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
                                  Supports flat files or package layout:
                                  coordinate-input/AL_D010/<YYYYMMDD>/<sido>/
                                  coordinate-input/LSMD_CONT_LDREG/<YYYYMM>/<sido>/

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
  HOME_COORDINATE_RESUME_RUN_ID          Explicit coordinate_snapshot_run id to resume.
  HOME_COORDINATE_CHUNK_PREFIX_LENGTH    PNU prefix length for resumable chunks. Defaults to 5.
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
RESUME_RUN_ID="${HOME_COORDINATE_RESUME_RUN_ID:-}"
CHUNK_PREFIX_LENGTH="${HOME_COORDINATE_CHUNK_PREFIX_LENGTH:-5}"
EXPECTED_REGIONS="${HOME_COORDINATE_EXPECTED_REGIONS:-11 26 27 28 29 30 31 36 41 43 44 46 47 48 50 51 52}"
LOCK_KEY="home_search_coordinate_snapshot_import"
LOCK_ACQUIRED="false"
RUN_ID=""
CURRENT_REGION_CODE=""
CURRENT_CHUNK_CODE=""
CURRENT_REGION_MANIFEST=""
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

source_dir_for_format() {
  local format="$1"
  case "${format}" in
    vworld-al-d010)
      printf 'AL_D010'
      ;;
    vworld-lsmd-cont-ldreg)
      printf 'LSMD_CONT_LDREG'
      ;;
    *)
      return 1
      ;;
  esac
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

PACKAGE_LAYOUT_ERROR=""

validate_package_layout() {
  local relative_path="$1"
  local base_name="$2"
  local format="$3"
  local region_code="$4"
  local version_token="$5"
  local source_dir version_dir sido_dir file_name extra expected_source
  PACKAGE_LAYOUT_ERROR=""
  if [[ "${relative_path}" == "${base_name}" ]]; then
    return 0
  fi
  IFS="/" read -r source_dir version_dir sido_dir file_name extra <<<"${relative_path}"
  if [[ -z "${source_dir}" || -z "${version_dir}" || -z "${sido_dir}" || -z "${file_name}" ||
        -n "${extra}" || "${file_name}" != "${base_name}" ]]; then
    PACKAGE_LAYOUT_ERROR="unsupported coordinate-input package layout: ${relative_path}. Expected flat file or coordinate-input/AL_D010/<YYYYMMDD>/<sido>/<file>.shp / coordinate-input/LSMD_CONT_LDREG/<YYYYMM>/<sido>/<file>.shp"
    return 1
  fi
  expected_source="$(source_dir_for_format "${format}")"
  if [[ "${source_dir}" != "${expected_source}" ]]; then
    PACKAGE_LAYOUT_ERROR="coordinate-input package source mismatch for ${relative_path}: expected ${expected_source}, got ${source_dir}"
    return 1
  fi
  if [[ "${version_dir}" != "${version_token}" ]]; then
    PACKAGE_LAYOUT_ERROR="coordinate-input package version mismatch for ${relative_path}: expected ${version_token}, got ${version_dir}"
    return 1
  fi
  if [[ "${sido_dir}" != "${region_code}" ]]; then
    PACKAGE_LAYOUT_ERROR="coordinate-input package SIDO mismatch for ${relative_path}: expected ${region_code}, got ${sido_dir}"
    return 1
  fi
  return 0
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

assert_package_layout() {
  local relative_path="$1"
  local base_name
  base_name="$(basename "${relative_path}")"
  if ! parse_supported_filename "${base_name}"; then
    echo "self-test failed: parser rejected package path ${relative_path}" >&2
    exit 1
  fi
  if ! validate_package_layout "${relative_path}" "${base_name}" "${PARSED_FORMAT}" "${PARSED_REGION_CODE}" "${PARSED_VERSION_TOKEN}"; then
    echo "self-test failed: package layout rejected ${relative_path}: ${PACKAGE_LAYOUT_ERROR}" >&2
    exit 1
  fi
}

assert_rejected_package_layout() {
  local relative_path="$1"
  local base_name
  base_name="$(basename "${relative_path}")"
  if ! parse_supported_filename "${base_name}"; then
    echo "self-test failed: parser rejected rejection fixture ${relative_path}" >&2
    exit 1
  fi
  if validate_package_layout "${relative_path}" "${base_name}" "${PARSED_FORMAT}" "${PARSED_REGION_CODE}" "${PARSED_VERSION_TOKEN}"; then
    echo "self-test failed: invalid package layout was accepted: ${relative_path}" >&2
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
  assert_package_layout "AL_D010_11_20251204.shp"
  assert_package_layout "AL_D010/20251204/11/AL_D010_11_20251204.shp"
  assert_package_layout "AL_D010/20251204/41/AL_D010_41_20251204(3).shp"
  assert_package_layout "LSMD_CONT_LDREG/202512/36/LSMD_CONT_LDREG_36110_202512.shp"
  assert_rejected_package_layout "AL_D010/20251205/11/AL_D010_11_20251204.shp"
  assert_rejected_package_layout "backup/AL_D010/20251204/11/AL_D010_11_20251204.shp"
  echo "self-test passed: VWorld coordinate snapshot importer package layout"
}

ensure_shp2pgsql_runtime() {
  if command -v shp2pgsql >/dev/null 2>&1 && shp2pgsql >/dev/null 2>&1; then
    return 0
  fi
  if command -v apk >/dev/null 2>&1 && [[ "$(id -u)" == "0" ]]; then
    echo "INFO: installing gettext-libs for shp2pgsql runtime" >&2
    apk add --no-cache gettext-libs >/dev/null
  fi
  if ! command -v shp2pgsql >/dev/null 2>&1 || ! shp2pgsql >/dev/null 2>&1; then
    echo "ERROR: shp2pgsql is required on PATH and must be executable" >&2
    exit 2
  fi
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
if [[ ! "${CHUNK_PREFIX_LENGTH}" =~ ^[0-9]+$ || "${CHUNK_PREFIX_LENGTH}" -lt 2 || "${CHUNK_PREFIX_LENGTH}" -gt 8 ]]; then
  echo "ERROR: HOME_COORDINATE_CHUNK_PREFIX_LENGTH must be an integer between 2 and 8, got: ${CHUNK_PREFIX_LENGTH}" >&2
  exit 2
fi
if [[ ! -d "${SHP_DIR}" ]]; then
  echo "ERROR: HOME_COORDINATE_SHP_DIR does not exist: ${SHP_DIR}" >&2
  exit 2
fi

ensure_shp2pgsql_runtime
if [[ "${PRE_FLIGHT_ONLY}" != "true" ]] && ! command -v psql >/dev/null 2>&1; then
  echo "ERROR: psql is required on PATH" >&2
  exit 2
fi

release_lock() {
  if [[ "${LOCK_ACQUIRED}" == "true" ]]; then
    local unlock_written="false"
    if [[ -n "${LOCK_PSQL[1]+set}" ]]; then
      if printf "\\set lock_key '%s'\nSELECT pg_advisory_unlock(hashtext(:'lock_key'));\n\\q\n" "${LOCK_KEY}" >&"${LOCK_PSQL[1]}"; then
        unlock_written="true"
      fi
    fi
    if [[ "${unlock_written}" == "true" && -n "${LOCK_PSQL[0]+set}" ]]; then
      IFS= read -r _ <&"${LOCK_PSQL[0]}" || true
    fi
    if [[ -n "${LOCK_PSQL_PID:-}" ]]; then
      wait "${LOCK_PSQL_PID}" 2>/dev/null || true
    fi
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

discover_shp_files() {
  SHP_FILES=()
  SHP_RELATIVE_PATHS=()
  local -a candidate_paths=()
  local file relative_path base_name duplicate_basenames seen_basenames
  duplicate_basenames=""
  seen_basenames=""

  case "${INPUT_FORMAT}" in
    auto)
      while IFS= read -r file; do
        [[ -n "${file}" ]] && candidate_paths+=("${file#${SHP_DIR}/}")
      done < <(find "${SHP_DIR}" -maxdepth 1 -type f \( -name 'LSMD_CONT_LDREG_*.shp' -o -name 'AL_D010_*.shp' \))
      ;;
    vworld-al-d010)
      while IFS= read -r file; do
        [[ -n "${file}" ]] && candidate_paths+=("${file#${SHP_DIR}/}")
      done < <(find "${SHP_DIR}" -maxdepth 1 -type f -name 'AL_D010_*.shp')
      ;;
    vworld-lsmd-cont-ldreg)
      while IFS= read -r file; do
        [[ -n "${file}" ]] && candidate_paths+=("${file#${SHP_DIR}/}")
      done < <(find "${SHP_DIR}" -maxdepth 1 -type f -name 'LSMD_CONT_LDREG_*.shp')
      ;;
  esac

  if [[ "${INPUT_FORMAT}" == "auto" || "${INPUT_FORMAT}" == "vworld-al-d010" ]]; then
    if [[ -d "${SHP_DIR}/AL_D010" ]]; then
      while IFS= read -r file; do
        [[ -n "${file}" ]] && candidate_paths+=("${file#${SHP_DIR}/}")
      done < <(find "${SHP_DIR}/AL_D010" -mindepth 3 -maxdepth 3 -type f -name 'AL_D010_*.shp')
    fi
  fi
  if [[ "${INPUT_FORMAT}" == "auto" || "${INPUT_FORMAT}" == "vworld-lsmd-cont-ldreg" ]]; then
    if [[ -d "${SHP_DIR}/LSMD_CONT_LDREG" ]]; then
      while IFS= read -r file; do
        [[ -n "${file}" ]] && candidate_paths+=("${file#${SHP_DIR}/}")
      done < <(find "${SHP_DIR}/LSMD_CONT_LDREG" -mindepth 3 -maxdepth 3 -type f -name 'LSMD_CONT_LDREG_*.shp')
    fi
  fi

  if [[ "${#candidate_paths[@]}" -eq 0 ]]; then
    return 0
  fi

  while IFS= read -r relative_path; do
    [[ -n "${relative_path}" ]] || continue
    base_name="$(basename "${relative_path}")"
    if ! parse_supported_filename "${base_name}"; then
      echo "ERROR: unexpected VWorld cadastral filename: ${base_name}" >&2
      exit 2
    fi
    if [[ "${INPUT_FORMAT}" != "auto" && "${PARSED_FORMAT}" != "${INPUT_FORMAT}" ]]; then
      echo "ERROR: ${base_name} does not match HOME_COORDINATE_INPUT_FORMAT=${INPUT_FORMAT}" >&2
      exit 2
    fi
    if ! validate_package_layout "${relative_path}" "${base_name}" "${PARSED_FORMAT}" "${PARSED_REGION_CODE}" "${PARSED_VERSION_TOKEN}"; then
      echo "ERROR: ${PACKAGE_LAYOUT_ERROR}" >&2
      exit 2
    fi
    if contains_token "${seen_basenames}" "${base_name}"; then
      duplicate_basenames="$(append_unique_token "${duplicate_basenames}" "${base_name}")"
      continue
    fi
    seen_basenames="$(append_unique_token "${seen_basenames}" "${base_name}")"
    SHP_RELATIVE_PATHS+=("${relative_path}")
    SHP_FILES+=("${SHP_DIR}/${relative_path}")
  done < <(printf '%s\n' "${candidate_paths[@]}" | sort)

  if [[ -n "${duplicate_basenames}" ]]; then
    echo "ERROR: duplicate SHP basenames in coordinate input: ${duplicate_basenames}" >&2
    exit 2
  fi
}
trap release_lock EXIT

mark_failed() {
  local exit_code=$?
  local line_number="${1:-unknown}"
  trap - ERR
  if [[ -n "${RUN_ID}" ]]; then
    if [[ -n "${CURRENT_REGION_CODE}" ]]; then
      "${PSQL[@]}" \
        -v run_id="${RUN_ID}" \
        -v region_code="${CURRENT_REGION_CODE}" \
        -v chunk_code="${CURRENT_CHUNK_CODE}" \
        -v failure_reason="import failed near line ${line_number}" <<'SQL' >/dev/null || true
UPDATE reference.coordinate_snapshot_stage_chunk_checkpoint
SET status = 'FAILED',
    failure_reason = :'failure_reason',
    finished_at = now()
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND chunk_code = :'chunk_code'
  AND status = 'STARTED';
UPDATE reference.coordinate_snapshot_publish_chunk_checkpoint
SET status = 'FAILED',
    failure_reason = :'failure_reason',
    finished_at = now()
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND chunk_code = :'chunk_code'
  AND status = 'STARTED';
UPDATE reference.coordinate_snapshot_region_checkpoint
SET status = 'FAILED',
    failure_reason = :'failure_reason',
    finished_at = now()
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND status = 'STARTED';
UPDATE reference.coordinate_snapshot_publish_checkpoint
SET status = 'FAILED',
    failure_reason = :'failure_reason',
    finished_at = now()
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND status = 'STARTED';
SQL
    fi
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

collect_file_stats() {
  local source_format="$1"
  case "${source_format}" in
    vworld-al-d010)
      "${PSQL[@]}" -q -At -F $'\t' -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
WITH normalized AS (
    SELECT
        NULLIF(btrim(a2::text), '') AS pnu,
        NULLIF(btrim(a23::text), '') AS source_region_code,
        geom IS NULL AS geom_is_null
    FROM reference.land_parcel_file_raw
),
valid_raw AS (
    SELECT pnu
    FROM normalized
    WHERE pnu IS NOT NULL
      AND pnu::text ~ '^[0-9]{19}$'
      AND NOT geom_is_null
      AND (
          :'strict_region_match' <> 'true'
          OR (
              source_region_code IS NOT NULL
              AND left(pnu::text, 5) = left(source_region_code::text, 5)
          )
      )
),
duplicate_pnus AS (
    SELECT pnu
    FROM valid_raw
    GROUP BY pnu
    HAVING count(*) > 1
)
SELECT
    (SELECT count(*) FROM normalized)::text,
    (
        SELECT count(*)
        FROM normalized
        WHERE pnu IS NULL
           OR pnu::text !~ '^[0-9]{19}$'
           OR geom_is_null
           OR (
               :'strict_region_match' = 'true'
               AND (
                   source_region_code IS NULL
                   OR left(pnu::text, 5) <> left(source_region_code::text, 5)
               )
           )
    )::text,
    (SELECT count(*) FROM duplicate_pnus)::text;
SQL
      ;;
    vworld-lsmd-cont-ldreg)
      "${PSQL[@]}" -q -At -F $'\t' -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
WITH normalized AS (
    SELECT
        NULLIF(btrim(pnu::text), '') AS pnu,
        left(NULLIF(btrim(pnu::text), ''), 5) AS source_region_code,
        geom IS NULL AS geom_is_null
    FROM reference.land_parcel_file_raw
),
valid_raw AS (
    SELECT pnu
    FROM normalized
    WHERE pnu IS NOT NULL
      AND pnu::text ~ '^[0-9]{19}$'
      AND NOT geom_is_null
      AND (
          :'strict_region_match' <> 'true'
          OR (
              source_region_code IS NOT NULL
              AND left(pnu::text, 5) = left(source_region_code::text, 5)
          )
      )
),
duplicate_pnus AS (
    SELECT pnu
    FROM valid_raw
    GROUP BY pnu
    HAVING count(*) > 1
)
SELECT
    (SELECT count(*) FROM normalized)::text,
    (
        SELECT count(*)
        FROM normalized
        WHERE pnu IS NULL
           OR pnu::text !~ '^[0-9]{19}$'
           OR geom_is_null
           OR (
               :'strict_region_match' = 'true'
               AND (
                   source_region_code IS NULL
                   OR left(pnu::text, 5) <> left(source_region_code::text, 5)
               )
           )
    )::text,
    (SELECT count(*) FROM duplicate_pnus)::text;
SQL
      ;;
    *)
      echo "ERROR: unsupported source format for file stats: ${source_format}" >&2
      return 2
      ;;
  esac
}

file_size_bytes() {
  local file="$1"
  local size=""
  if size="$(stat -c '%s' "${file}" 2>/dev/null)"; then
    printf '%s' "${size}"
    return 0
  fi
  if size="$(stat -f '%z' "${file}" 2>/dev/null)"; then
    printf '%s' "${size}"
    return 0
  fi
  wc -c <"${file}" | tr -d '[:space:]'
}

region_source_manifest() {
  local region="$1"
  local manifest=""
  local file_index file relative_path sidecar_ext sidecar_path relative_sidecar size
  for file_index in "${!SHP_FILES[@]}"; do
    if [[ "${SHP_REGION_CODES[$file_index]}" != "${region}" ]]; then
      continue
    fi
    file="${SHP_FILES[$file_index]}"
    relative_path="${SHP_RELATIVE_PATHS[$file_index]}"
    for sidecar_ext in shp shx dbf prj; do
      sidecar_path="${file%.shp}.${sidecar_ext}"
      relative_sidecar="${relative_path%.shp}.${sidecar_ext}"
      if [[ -f "${sidecar_path}" ]]; then
        size="$(file_size_bytes "${sidecar_path}")"
        manifest="$(append_csv_token "${manifest}" "${relative_sidecar}:${size}")"
      fi
    done
  done
  printf '%s' "${manifest}"
}

SHP_FILES=()
SHP_RELATIVE_PATHS=()
SHP_REGION_CODES=()
discover_shp_files

if [[ "${#SHP_FILES[@]}" -eq 0 ]]; then
  echo "ERROR: no VWorld coordinate SHP files found in ${SHP_DIR} for input format ${INPUT_FORMAT}. Expected LSMD_CONT_LDREG_*.shp or AL_D010_*.shp." >&2
  exit 2
fi

SHP_FORMATS=()
seen_regions=""
seen_versions=""
seen_formats=""
file_names=""
relative_file_paths=""
part_counts=""
missing_sidecars=""
file_index=0
for file in "${SHP_FILES[@]}"; do
  relative_path="${SHP_RELATIVE_PATHS[$file_index]}"
  file_index="$((file_index + 1))"
  base_name="$(basename "${file}")"
  file_has_missing_sidecar="false"
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
  SHP_REGION_CODES+=("${region_code}")
  version_token="${PARSED_VERSION_TOKEN}"
  seen_regions="$(append_unique_token "${seen_regions}" "${region_code}")"
  seen_versions="$(append_unique_token "${seen_versions}" "${version_token}")"
  seen_formats="$(append_unique_token "${seen_formats}" "${PARSED_FORMAT}")"
  file_names="$(append_csv_token "${file_names}" "${base_name}")"
  relative_file_paths="$(append_csv_token "${relative_file_paths}" "${relative_path}")"
  part_counts="$(increment_part_count "${part_counts}" "${region_code}")"

  for sidecar_ext in shx dbf prj; do
    if [[ ! -f "${file%.shp}.${sidecar_ext}" ]]; then
      missing_sidecars="${missing_sidecars}${relative_path%.shp}.${sidecar_ext} "
      file_has_missing_sidecar="true"
    fi
  done
  if [[ "${file_has_missing_sidecar}" == "true" ]]; then
    continue
  fi
  if [[ -f "${file%.shp}.prj" ]]; then
    validate_prj_for_source_srid "${file%.shp}.prj" "${relative_path}"
  fi
  validate_file_columns "${file}" "${PARSED_FORMAT}" "${relative_path}"
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
  echo "coordinate snapshot preflight passed: source_format=${SOURCE_FORMAT}, version=${SNAPSHOT_VERSION}, files=${FILE_COUNT}, regions=${seen_regions}, expected_regions=${EXPECTED_REGIONS}, part_counts=${part_counts}, relative_file_paths=${relative_file_paths}"
  exit 0
fi

schema_ready="$("${PSQL[@]}" -At <<'SQL'
SELECT to_regclass('reference.coordinate_snapshot_run') IS NOT NULL
   AND to_regclass('reference.parcel_coordinate_snapshot') IS NOT NULL
   AND to_regclass('reference.parcel_coordinate_snapshot_stage') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_region_checkpoint') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_stage_chunk_checkpoint') IS NOT NULL
   AND to_regclass('reference.parcel_coordinate_snapshot_publish') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_publish_checkpoint') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_publish_chunk_checkpoint') IS NOT NULL;
SQL
)"
if [[ "${schema_ready}" != "t" ]]; then
  echo "ERROR: coordinate snapshot schema is missing. Run API Flyway migrations first." >&2
  exit 2
fi

acquire_lock

if [[ -n "${RESUME_RUN_ID}" ]]; then
  if [[ ! "${RESUME_RUN_ID}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: HOME_COORDINATE_RESUME_RUN_ID must be numeric, got: ${RESUME_RUN_ID}" >&2
    exit 2
  fi
  RUN_ID="$("${PSQL[@]}" -q -At \
    -v run_id="${RESUME_RUN_ID}" \
    -v snapshot_version="${SNAPSHOT_VERSION}" \
    -v source_srid="${SRC_SRID}" \
    -v target_srid="${DST_SRID}" <<'SQL'
UPDATE reference.coordinate_snapshot_run
SET status = 'STARTED',
    failure_reason = NULL,
    finished_at = NULL
WHERE id = (:'run_id')::bigint
  AND snapshot_version = :'snapshot_version'
  AND source_srid = (:'source_srid')::integer
  AND target_srid = (:'target_srid')::integer
  AND status IN ('STARTED', 'FAILED', 'PASSED')
RETURNING id;
SQL
  )"
  if [[ "${RUN_ID}" != "${RESUME_RUN_ID}" ]]; then
    echo "ERROR: HOME_COORDINATE_RESUME_RUN_ID=${RESUME_RUN_ID} is not resumable for version=${SNAPSHOT_VERSION}, source_srid=${SRC_SRID}, target_srid=${DST_SRID}" >&2
    exit 2
  fi
  echo "coordinate snapshot import resumed: run_id=${RUN_ID}, version=${SNAPSHOT_VERSION}, files=${FILE_COUNT}"
else
  RUN_ID="$("${PSQL[@]}" -q -At \
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
fi
if [[ ! "${RUN_ID}" =~ ^[0-9]+$ ]]; then
  echo "ERROR: coordinate_snapshot_run id must be numeric, got: ${RUN_ID}" >&2
  exit 2
fi

echo "coordinate snapshot import started: run_id=${RUN_ID}, version=${SNAPSHOT_VERSION}, files=${FILE_COUNT}, chunk_prefix_length=${CHUNK_PREFIX_LENGTH}"

"${PSQL[@]}" <<'SQL'
DROP TABLE IF EXISTS reference.land_parcel_file_raw;
DROP TABLE IF EXISTS reference.land_parcel_snapshot_raw_next;
DROP TABLE IF EXISTS reference.parcel_coordinate_snapshot_next;
SQL

RAW_FEATURE_COUNT=0
INVALID_COUNT=0
DUPLICATE_PNU_COUNT=0
PNU_COUNT=0

for region_code in ${EXPECTED_REGIONS}; do
  CURRENT_REGION_CODE="${region_code}"
  CURRENT_CHUNK_CODE=""
  CURRENT_REGION_MANIFEST="$(region_source_manifest "${region_code}")"
  checkpoint_row="$("${PSQL[@]}" -q -At -F $'\t' \
    -v run_id="${RUN_ID}" \
    -v region_code="${region_code}" \
    -v snapshot_version="${SNAPSHOT_VERSION}" \
    -v source_format="${SOURCE_FORMAT}" \
    -v source_manifest="${CURRENT_REGION_MANIFEST}" \
    -v source_srid="${SRC_SRID}" \
    -v target_srid="${DST_SRID}" \
    -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
SELECT raw_feature_count, pnu_count, invalid_count, duplicate_pnu_count
FROM reference.coordinate_snapshot_region_checkpoint
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND snapshot_version = :'snapshot_version'
  AND source_format = :'source_format'
  AND source_manifest = :'source_manifest'
  AND source_srid = (:'source_srid')::integer
  AND target_srid = (:'target_srid')::integer
  AND strict_region_match = (:'strict_region_match')::boolean
  AND status = 'PASSED'
  AND (
      pnu_count = 0
      OR EXISTS (
      SELECT 1
      FROM reference.parcel_coordinate_snapshot_stage
      WHERE run_id = (:'run_id')::bigint
        AND region_code = :'region_code'
      LIMIT 1
      )
  );
SQL
  )"
  if [[ -n "${checkpoint_row}" ]]; then
    IFS=$'\t' read -r checkpoint_raw_count checkpoint_pnu_count checkpoint_invalid_count checkpoint_duplicate_pnu_count <<<"${checkpoint_row}"
    RAW_FEATURE_COUNT="$((RAW_FEATURE_COUNT + checkpoint_raw_count))"
    PNU_COUNT="$((PNU_COUNT + checkpoint_pnu_count))"
    INVALID_COUNT="$((INVALID_COUNT + checkpoint_invalid_count))"
    DUPLICATE_PNU_COUNT="$((DUPLICATE_PNU_COUNT + checkpoint_duplicate_pnu_count))"
    echo "coordinate snapshot region import skipped: run_id=${RUN_ID}, region_code=${region_code}, reason=checkpoint_passed, rows=${checkpoint_pnu_count}"
    CURRENT_CHUNK_CODE=""
    continue
  fi

  "${PSQL[@]}" <<'SQL' >/dev/null
DROP TABLE IF EXISTS reference.land_parcel_file_raw;
DROP TABLE IF EXISTS reference.land_parcel_snapshot_raw_next;
CREATE UNLOGGED TABLE reference.land_parcel_snapshot_raw_next (
    pnu VARCHAR(19),
    source_region_code VARCHAR(8),
    source_file TEXT NOT NULL,
    geom geometry(MultiPolygon, 4326)
);
SQL

  region_file_count=0
  region_raw_count=0
  region_invalid_count=0
  region_duplicate_pnu_count=0
  for file_index in "${!SHP_FILES[@]}"; do
    file="${SHP_FILES[$file_index]}"
    source_region_for_file="${SHP_REGION_CODES[$file_index]}"
    if [[ "${source_region_for_file}" != "${region_code}" ]]; then
      continue
    fi
    base_name="$(basename "${file}")"
    source_format_for_file="${SHP_FORMATS[$file_index]}"
    region_file_count="$((region_file_count + 1))"
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

    file_stats="$(collect_file_stats "${source_format_for_file}")"
    IFS=$'\t' read -r file_feature_count file_invalid_count file_duplicate_pnu_count <<<"${file_stats}"
    if [[ ! "${file_feature_count}" =~ ^[0-9]+$ ||
          ! "${file_invalid_count}" =~ ^[0-9]+$ ||
          ! "${file_duplicate_pnu_count}" =~ ^[0-9]+$ ]]; then
      fail_run "invalid coordinate file stats for ${base_name}: ${file_stats}"
    fi
    RAW_FEATURE_COUNT="$((RAW_FEATURE_COUNT + file_feature_count))"
    INVALID_COUNT="$((INVALID_COUNT + file_invalid_count))"
    DUPLICATE_PNU_COUNT="$((DUPLICATE_PNU_COUNT + file_duplicate_pnu_count))"
    region_raw_count="$((region_raw_count + file_feature_count))"
    region_invalid_count="$((region_invalid_count + file_invalid_count))"
    region_duplicate_pnu_count="$((region_duplicate_pnu_count + file_duplicate_pnu_count))"
    echo "coordinate snapshot raw file imported: run_id=${RUN_ID}, file=${base_name}, region_code=${region_code}, rows=${file_feature_count}, invalid_rows=${file_invalid_count}, duplicate_pnus=${file_duplicate_pnu_count}, raw_rows_total=${RAW_FEATURE_COUNT}"
    "${PSQL[@]}" <<'SQL' >/dev/null
DROP TABLE IF EXISTS reference.land_parcel_file_raw;
SQL
  done

  if [[ "${region_file_count}" -eq 0 ]]; then
    echo "coordinate snapshot region import skipped: run_id=${RUN_ID}, region_code=${region_code}, reason=no_matching_file"
    CURRENT_CHUNK_CODE=""
    CURRENT_REGION_CODE=""
    CURRENT_REGION_MANIFEST=""
    continue
  fi

  "${PSQL[@]}" \
    -v run_id="${RUN_ID}" \
    -v region_code="${region_code}" \
    -v snapshot_version="${SNAPSHOT_VERSION}" \
    -v source_format="${SOURCE_FORMAT}" \
    -v source_manifest="${CURRENT_REGION_MANIFEST}" \
    -v source_file_count="${region_file_count}" \
    -v source_srid="${SRC_SRID}" \
    -v target_srid="${DST_SRID}" \
    -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL' >/dev/null
DELETE FROM reference.coordinate_snapshot_publish_checkpoint
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code';
DELETE FROM reference.coordinate_snapshot_publish_chunk_checkpoint
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code';
DELETE FROM reference.parcel_coordinate_snapshot_publish
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code';
DELETE FROM reference.parcel_coordinate_snapshot_stage
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND source_manifest <> :'source_manifest';
DELETE FROM reference.coordinate_snapshot_stage_chunk_checkpoint
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND source_manifest <> :'source_manifest';
INSERT INTO reference.coordinate_snapshot_region_checkpoint (
    run_id,
    region_code,
    snapshot_version,
    source_format,
    source_manifest,
    source_file_count,
    source_srid,
    target_srid,
    strict_region_match,
    status,
    started_at,
    finished_at
)
VALUES (
    (:'run_id')::bigint,
    :'region_code',
    :'snapshot_version',
    :'source_format',
    :'source_manifest',
    (:'source_file_count')::integer,
    (:'source_srid')::integer,
    (:'target_srid')::integer,
    (:'strict_region_match')::boolean,
    'STARTED',
    now(),
    NULL
)
ON CONFLICT (run_id, region_code) DO UPDATE
SET snapshot_version = EXCLUDED.snapshot_version,
    source_format = EXCLUDED.source_format,
    source_manifest = EXCLUDED.source_manifest,
    source_file_count = EXCLUDED.source_file_count,
    source_srid = EXCLUDED.source_srid,
    target_srid = EXCLUDED.target_srid,
    strict_region_match = EXCLUDED.strict_region_match,
    status = 'STARTED',
    raw_feature_count = 0,
    pnu_count = 0,
    invalid_count = 0,
    duplicate_pnu_count = 0,
    failure_reason = NULL,
    started_at = now(),
    finished_at = NULL;
SQL

  "${PSQL[@]}" <<'SQL'
ANALYZE reference.land_parcel_snapshot_raw_next;
SQL

  echo "coordinate snapshot region import started: run_id=${RUN_ID}, region_code=${region_code}, files=${region_file_count}, raw_rows=${region_raw_count}"
  chunk_codes="$("${PSQL[@]}" -q -At \
    -v strict_region_match="${STRICT_REGION_MATCH}" \
    -v region_code="${region_code}" \
    -v chunk_prefix_length="${CHUNK_PREFIX_LENGTH}" <<'SQL'
SELECT COALESCE(string_agg(chunk_code, ' ' ORDER BY chunk_code), '')
FROM (
    SELECT DISTINCT left(pnu::text, (:'chunk_prefix_length')::integer)::varchar(8) AS chunk_code
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
      AND left(pnu::text, 2) = :'region_code'
) chunks;
SQL
  )"
  for chunk_code in ${chunk_codes}; do
    CURRENT_CHUNK_CODE="${chunk_code}"
    stage_chunk_checkpoint_row="$("${PSQL[@]}" -q -At -F $'\t' \
      -v run_id="${RUN_ID}" \
      -v region_code="${region_code}" \
      -v chunk_code="${chunk_code}" \
      -v snapshot_version="${SNAPSHOT_VERSION}" \
      -v source_format="${SOURCE_FORMAT}" \
      -v source_manifest="${CURRENT_REGION_MANIFEST}" \
      -v source_srid="${SRC_SRID}" \
      -v target_srid="${DST_SRID}" \
      -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
SELECT raw_feature_count, pnu_count
FROM reference.coordinate_snapshot_stage_chunk_checkpoint
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND chunk_code = :'chunk_code'
  AND snapshot_version = :'snapshot_version'
  AND source_format = :'source_format'
  AND source_manifest = :'source_manifest'
  AND source_srid = (:'source_srid')::integer
  AND target_srid = (:'target_srid')::integer
  AND strict_region_match = (:'strict_region_match')::boolean
  AND status = 'PASSED'
  AND (
      pnu_count = 0
      OR EXISTS (
          SELECT 1
          FROM reference.parcel_coordinate_snapshot_stage
          WHERE run_id = (:'run_id')::bigint
            AND region_code = :'region_code'
            AND chunk_code = :'chunk_code'
          LIMIT 1
      )
  );
SQL
    )"
    if [[ -n "${stage_chunk_checkpoint_row}" ]]; then
      IFS=$'\t' read -r stage_chunk_raw_count stage_chunk_pnu_count <<<"${stage_chunk_checkpoint_row}"
      echo "coordinate snapshot stage chunk skipped: run_id=${RUN_ID}, region_code=${region_code}, chunk_code=${chunk_code}, reason=checkpoint_passed, rows=${stage_chunk_pnu_count}"
      continue
    fi

    echo "coordinate snapshot stage chunk started: run_id=${RUN_ID}, region_code=${region_code}, chunk_code=${chunk_code}"
    stage_chunk_result="$("${PSQL[@]}" -q -At -F $'\t' \
      -v snapshot_version="${SNAPSHOT_VERSION}" \
      -v run_id="${RUN_ID}" \
      -v strict_region_match="${STRICT_REGION_MATCH}" \
      -v source_manifest="${CURRENT_REGION_MANIFEST}" \
      -v source_format="${SOURCE_FORMAT}" \
      -v source_srid="${SRC_SRID}" \
      -v target_srid="${DST_SRID}" \
      -v region_code="${region_code}" \
      -v chunk_code="${chunk_code}" \
      -v chunk_prefix_length="${CHUNK_PREFIX_LENGTH}" <<'SQL'
BEGIN;
SET LOCAL jit = off;
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL work_mem = '128MB';

INSERT INTO reference.coordinate_snapshot_stage_chunk_checkpoint (
    run_id,
    region_code,
    chunk_code,
    snapshot_version,
    source_format,
    source_manifest,
    source_srid,
    target_srid,
    strict_region_match,
    status,
    started_at,
    finished_at
)
VALUES (
    (:'run_id')::bigint,
    :'region_code',
    :'chunk_code',
    :'snapshot_version',
    :'source_format',
    :'source_manifest',
    (:'source_srid')::integer,
    (:'target_srid')::integer,
    (:'strict_region_match')::boolean,
    'STARTED',
    now(),
    NULL
)
ON CONFLICT (run_id, region_code, chunk_code) DO UPDATE
SET snapshot_version = EXCLUDED.snapshot_version,
    source_format = EXCLUDED.source_format,
    source_manifest = EXCLUDED.source_manifest,
    source_srid = EXCLUDED.source_srid,
    target_srid = EXCLUDED.target_srid,
    strict_region_match = EXCLUDED.strict_region_match,
    raw_feature_count = 0,
    pnu_count = 0,
    status = 'STARTED',
    failure_reason = NULL,
    started_at = now(),
    finished_at = NULL;

DELETE FROM reference.parcel_coordinate_snapshot_publish
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND chunk_code = :'chunk_code';

DELETE FROM reference.coordinate_snapshot_publish_chunk_checkpoint
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND chunk_code = :'chunk_code';

DELETE FROM reference.parcel_coordinate_snapshot_stage
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND chunk_code = :'chunk_code';

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
      AND left(pnu::text, 2) = :'region_code'
      AND left(pnu::text, (:'chunk_prefix_length')::integer) = :'chunk_code'
),
raw_count AS (
    SELECT count(*)::bigint AS row_count
    FROM valid_raw
),
aggregated AS (
    SELECT
        pnu,
        left(pnu, 2) AS region_code,
        (:'chunk_code')::varchar(8) AS chunk_code,
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
        chunk_code,
        source_file,
        geom,
        ST_PointOnSurface(geom)::geometry(Point, 4326) AS point
    FROM aggregated
    WHERE NOT ST_IsEmpty(geom)
      AND ST_IsValid(geom)
),
inserted AS (
    INSERT INTO reference.parcel_coordinate_snapshot_stage (
        run_id,
        pnu,
        region_code,
        chunk_code,
        latitude,
        longitude,
        point,
        geom,
        snapshot_version,
        source_file,
        source_manifest,
        created_at,
        updated_at
    )
    SELECT
        (:'run_id')::bigint AS run_id,
        pnu,
        region_code,
        chunk_code,
        ST_Y(point)::numeric(10, 7) AS latitude,
        ST_X(point)::numeric(10, 7) AS longitude,
        point,
        geom,
        (:'snapshot_version')::varchar(64) AS snapshot_version,
        source_file,
        (:'source_manifest')::text AS source_manifest,
        now() AS created_at,
        now() AS updated_at
    FROM with_point
    WHERE ST_Y(point) BETWEEN 33 AND 39
      AND ST_X(point) BETWEEN 124 AND 132
    RETURNING 1
),
updated AS (
    UPDATE reference.coordinate_snapshot_stage_chunk_checkpoint
    SET status = 'PASSED',
        raw_feature_count = (SELECT row_count FROM raw_count),
        pnu_count = (SELECT count(*) FROM inserted),
        failure_reason = NULL,
        finished_at = now()
    WHERE run_id = (:'run_id')::bigint
      AND region_code = :'region_code'
      AND chunk_code = :'chunk_code'
    RETURNING raw_feature_count, pnu_count
)
SELECT raw_feature_count::text, pnu_count::text FROM updated;

COMMIT;
SQL
    )"
    IFS=$'\t' read -r stage_chunk_raw_count stage_chunk_pnu_count <<<"${stage_chunk_result}"
    if [[ ! "${stage_chunk_raw_count}" =~ ^[0-9]+$ || ! "${stage_chunk_pnu_count}" =~ ^[0-9]+$ ]]; then
      fail_run "invalid coordinate stage chunk result for region ${region_code}, chunk ${chunk_code}: ${stage_chunk_result}"
    fi
    echo "coordinate snapshot stage chunk passed: run_id=${RUN_ID}, region_code=${region_code}, chunk_code=${chunk_code}, raw_rows=${stage_chunk_raw_count}, rows=${stage_chunk_pnu_count}"
  done
  CURRENT_CHUNK_CODE=""
  region_row_count="$("${PSQL[@]}" -q -At \
    -v run_id="${RUN_ID}" \
    -v region_code="${region_code}" \
    -v snapshot_version="${SNAPSHOT_VERSION}" \
    -v source_format="${SOURCE_FORMAT}" \
    -v source_manifest="${CURRENT_REGION_MANIFEST}" \
    -v source_srid="${SRC_SRID}" \
    -v target_srid="${DST_SRID}" \
    -v strict_region_match="${STRICT_REGION_MATCH}" <<'SQL'
SELECT COALESCE(sum(pnu_count), 0)::text
FROM reference.coordinate_snapshot_stage_chunk_checkpoint
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND snapshot_version = :'snapshot_version'
  AND source_format = :'source_format'
  AND source_manifest = :'source_manifest'
  AND source_srid = (:'source_srid')::integer
  AND target_srid = (:'target_srid')::integer
  AND strict_region_match = (:'strict_region_match')::boolean
  AND status = 'PASSED';
SQL
  )"
  if [[ ! "${region_row_count}" =~ ^[0-9]+$ ]]; then
    fail_run "invalid coordinate region import row count for region ${region_code}: ${region_row_count}"
  fi
  PNU_COUNT="$((PNU_COUNT + region_row_count))"
  "${PSQL[@]}" \
    -v run_id="${RUN_ID}" \
    -v region_code="${region_code}" \
    -v raw_feature_count="${region_raw_count}" \
    -v pnu_count="${region_row_count}" \
    -v invalid_count="${region_invalid_count}" \
    -v duplicate_pnu_count="${region_duplicate_pnu_count}" <<'SQL' >/dev/null
UPDATE reference.coordinate_snapshot_region_checkpoint
SET status = 'PASSED',
    raw_feature_count = (:'raw_feature_count')::bigint,
    pnu_count = (:'pnu_count')::bigint,
    invalid_count = (:'invalid_count')::bigint,
    duplicate_pnu_count = (:'duplicate_pnu_count')::bigint,
    failure_reason = NULL,
    finished_at = now()
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code';
SQL
  echo "coordinate snapshot region import passed: run_id=${RUN_ID}, region_code=${region_code}, rows=${region_row_count}"
  if [[ "${KEEP_STAGING}" != "true" ]]; then
    "${PSQL[@]}" <<'SQL' >/dev/null
DROP TABLE IF EXISTS reference.land_parcel_snapshot_raw_next;
SQL
  fi
  CURRENT_REGION_CODE=""
  CURRENT_REGION_MANIFEST=""
done

"${PSQL[@]}" -v run_id="${RUN_ID}" <<'SQL'
ANALYZE reference.parcel_coordinate_snapshot_stage;
SQL

if [[ "${PNU_COUNT}" -eq 0 ]]; then
  fail_run "no valid coordinate rows were produced from SHP input"
fi

echo "coordinate snapshot staging totals: run_id=${RUN_ID}, raw_feature_count=${RAW_FEATURE_COUNT}, pnu_count=${PNU_COUNT}, invalid_count=${INVALID_COUNT}, duplicate_pnu_count=${DUPLICATE_PNU_COUNT}"

if [[ "${KEEP_STAGING}" != "true" ]]; then
  echo "coordinate snapshot raw staging cleanup started: run_id=${RUN_ID}"
  "${PSQL[@]}" <<'SQL' >/dev/null
DROP TABLE IF EXISTS reference.land_parcel_file_raw;
DROP TABLE IF EXISTS reference.land_parcel_snapshot_raw_next;
SQL
fi

PUBLISH_COUNT=0
for region_code in ${EXPECTED_REGIONS}; do
  CURRENT_REGION_CODE="${region_code}"
  CURRENT_CHUNK_CODE=""
  CURRENT_REGION_MANIFEST="$(region_source_manifest "${region_code}")"
  publish_checkpoint_row="$("${PSQL[@]}" -q -At -F $'\t' \
    -v run_id="${RUN_ID}" \
    -v region_code="${region_code}" \
    -v source_manifest="${CURRENT_REGION_MANIFEST}" <<'SQL'
SELECT pc.row_count
FROM reference.coordinate_snapshot_publish_checkpoint pc
JOIN reference.coordinate_snapshot_region_checkpoint rc
  ON rc.run_id = pc.run_id
 AND rc.region_code = pc.region_code
WHERE pc.run_id = (:'run_id')::bigint
  AND pc.region_code = :'region_code'
  AND pc.source_manifest = :'source_manifest'
  AND pc.status = 'PASSED'
  AND rc.status = 'PASSED'
  AND pc.row_count = rc.pnu_count
  AND (
      pc.row_count = 0
      OR EXISTS (
      SELECT 1
      FROM reference.parcel_coordinate_snapshot_publish
      WHERE run_id = (:'run_id')::bigint
        AND region_code = :'region_code'
      LIMIT 1
      )
  );
SQL
  )"
  if [[ -n "${publish_checkpoint_row}" ]]; then
    PUBLISH_COUNT="$((PUBLISH_COUNT + publish_checkpoint_row))"
    echo "coordinate snapshot publish region skipped: run_id=${RUN_ID}, region_code=${region_code}, reason=checkpoint_passed, rows=${publish_checkpoint_row}"
    CURRENT_CHUNK_CODE=""
    CURRENT_REGION_CODE=""
    CURRENT_REGION_MANIFEST=""
    continue
  fi

  echo "coordinate snapshot publish region started: run_id=${RUN_ID}, region_code=${region_code}"
  "${PSQL[@]}" \
    -v run_id="${RUN_ID}" \
    -v region_code="${region_code}" \
    -v source_manifest="${CURRENT_REGION_MANIFEST}" <<'SQL' >/dev/null
INSERT INTO reference.coordinate_snapshot_publish_checkpoint (
    run_id,
    region_code,
    source_manifest,
    status,
    started_at,
    finished_at
)
VALUES (
    (:'run_id')::bigint,
    :'region_code',
    :'source_manifest',
    'STARTED',
    now(),
    NULL
)
ON CONFLICT (run_id, region_code) DO UPDATE
SET source_manifest = EXCLUDED.source_manifest,
    row_count = 0,
    status = 'STARTED',
    failure_reason = NULL,
    started_at = now(),
    finished_at = NULL;
SQL

  publish_chunk_codes="$("${PSQL[@]}" -q -At \
    -v run_id="${RUN_ID}" \
    -v region_code="${region_code}" \
    -v source_manifest="${CURRENT_REGION_MANIFEST}" <<'SQL'
SELECT COALESCE(string_agg(sc.chunk_code, ' ' ORDER BY sc.chunk_code), '')
FROM reference.coordinate_snapshot_stage_chunk_checkpoint sc
JOIN reference.coordinate_snapshot_region_checkpoint rc
  ON rc.run_id = sc.run_id
 AND rc.region_code = sc.region_code
WHERE sc.run_id = (:'run_id')::bigint
  AND sc.region_code = :'region_code'
  AND sc.source_manifest = :'source_manifest'
  AND sc.status = 'PASSED'
  AND rc.status = 'PASSED';
SQL
  )"
  for chunk_code in ${publish_chunk_codes}; do
    CURRENT_CHUNK_CODE="${chunk_code}"
    publish_chunk_checkpoint_row="$("${PSQL[@]}" -q -At -F $'\t' \
      -v run_id="${RUN_ID}" \
      -v region_code="${region_code}" \
      -v chunk_code="${chunk_code}" \
      -v source_manifest="${CURRENT_REGION_MANIFEST}" <<'SQL'
SELECT pc.row_count
FROM reference.coordinate_snapshot_publish_chunk_checkpoint pc
JOIN reference.coordinate_snapshot_stage_chunk_checkpoint sc
  ON sc.run_id = pc.run_id
 AND sc.region_code = pc.region_code
 AND sc.chunk_code = pc.chunk_code
WHERE pc.run_id = (:'run_id')::bigint
  AND pc.region_code = :'region_code'
  AND pc.chunk_code = :'chunk_code'
  AND pc.source_manifest = :'source_manifest'
  AND pc.status = 'PASSED'
  AND sc.status = 'PASSED'
  AND pc.row_count = sc.pnu_count
  AND (
      pc.row_count = 0
      OR EXISTS (
          SELECT 1
          FROM reference.parcel_coordinate_snapshot_publish
          WHERE run_id = (:'run_id')::bigint
            AND region_code = :'region_code'
            AND chunk_code = :'chunk_code'
          LIMIT 1
      )
  );
SQL
    )"
    if [[ -n "${publish_chunk_checkpoint_row}" ]]; then
      echo "coordinate snapshot publish chunk skipped: run_id=${RUN_ID}, region_code=${region_code}, chunk_code=${chunk_code}, reason=checkpoint_passed, rows=${publish_chunk_checkpoint_row}"
      continue
    fi

    echo "coordinate snapshot publish chunk started: run_id=${RUN_ID}, region_code=${region_code}, chunk_code=${chunk_code}"
    publish_chunk_row_count="$("${PSQL[@]}" -q -At \
      -v run_id="${RUN_ID}" \
      -v region_code="${region_code}" \
      -v chunk_code="${chunk_code}" \
      -v source_manifest="${CURRENT_REGION_MANIFEST}" <<'SQL'
BEGIN;

INSERT INTO reference.coordinate_snapshot_publish_chunk_checkpoint (
    run_id,
    region_code,
    chunk_code,
    source_manifest,
    status,
    started_at,
    finished_at
)
VALUES (
    (:'run_id')::bigint,
    :'region_code',
    :'chunk_code',
    :'source_manifest',
    'STARTED',
    now(),
    NULL
)
ON CONFLICT (run_id, region_code, chunk_code) DO UPDATE
SET source_manifest = EXCLUDED.source_manifest,
    row_count = 0,
    status = 'STARTED',
    failure_reason = NULL,
    started_at = now(),
    finished_at = NULL;

DELETE FROM reference.parcel_coordinate_snapshot_publish
WHERE run_id = (:'run_id')::bigint
  AND region_code = :'region_code'
  AND chunk_code = :'chunk_code';

WITH inserted AS (
    INSERT INTO reference.parcel_coordinate_snapshot_publish (
        run_id,
        pnu,
        region_code,
        chunk_code,
        latitude,
        longitude,
        point,
        geom,
        snapshot_version,
        source_file,
        created_at,
        updated_at
    )
    SELECT
        run_id,
        pnu,
        region_code,
        chunk_code,
        latitude,
        longitude,
        point,
        geom,
        snapshot_version,
        source_file,
        created_at,
        updated_at
    FROM reference.parcel_coordinate_snapshot_stage
    WHERE run_id = (:'run_id')::bigint
      AND region_code = :'region_code'
      AND chunk_code = :'chunk_code'
    RETURNING 1
),
updated AS (
    UPDATE reference.coordinate_snapshot_publish_chunk_checkpoint
    SET status = 'PASSED',
        row_count = (SELECT count(*) FROM inserted),
        failure_reason = NULL,
        finished_at = now()
    WHERE run_id = (:'run_id')::bigint
      AND region_code = :'region_code'
      AND chunk_code = :'chunk_code'
    RETURNING row_count
)
SELECT row_count FROM updated;

COMMIT;
SQL
    )"
    if [[ ! "${publish_chunk_row_count}" =~ ^[0-9]+$ ]]; then
      fail_run "invalid coordinate publish chunk row count for region ${region_code}, chunk ${chunk_code}: ${publish_chunk_row_count}"
    fi
    echo "coordinate snapshot publish chunk passed: run_id=${RUN_ID}, region_code=${region_code}, chunk_code=${chunk_code}, rows=${publish_chunk_row_count}"
  done
  CURRENT_CHUNK_CODE=""
  publish_row_count="$("${PSQL[@]}" -q -At \
    -v run_id="${RUN_ID}" \
    -v region_code="${region_code}" \
    -v source_manifest="${CURRENT_REGION_MANIFEST}" <<'SQL'
WITH expected AS (
    SELECT pnu_count
    FROM reference.coordinate_snapshot_region_checkpoint
    WHERE run_id = (:'run_id')::bigint
      AND region_code = :'region_code'
      AND source_manifest = :'source_manifest'
      AND status = 'PASSED'
),
published AS (
    SELECT COALESCE(sum(row_count), 0)::bigint AS row_count
    FROM reference.coordinate_snapshot_publish_chunk_checkpoint
    WHERE run_id = (:'run_id')::bigint
      AND region_code = :'region_code'
      AND source_manifest = :'source_manifest'
      AND status = 'PASSED'
),
updated AS (
    UPDATE reference.coordinate_snapshot_publish_checkpoint
    SET status = 'PASSED',
        row_count = (SELECT row_count FROM published),
        failure_reason = CASE
            WHEN (SELECT row_count FROM published) = (SELECT pnu_count FROM expected)
            THEN NULL
            ELSE 'publish chunk row count does not match staged region pnu_count'
        END,
        finished_at = now()
    WHERE run_id = (:'run_id')::bigint
      AND region_code = :'region_code'
      AND (SELECT row_count FROM published) = (SELECT pnu_count FROM expected)
    RETURNING row_count
)
SELECT row_count FROM updated;
SQL
  )"
  if [[ ! "${publish_row_count}" =~ ^[0-9]+$ ]]; then
    fail_run "invalid coordinate publish row count for region ${region_code}: ${publish_row_count}"
  fi
  PUBLISH_COUNT="$((PUBLISH_COUNT + publish_row_count))"
  echo "coordinate snapshot publish region passed: run_id=${RUN_ID}, region_code=${region_code}, rows=${publish_row_count}"
  CURRENT_REGION_CODE=""
  CURRENT_REGION_MANIFEST=""
done

"${PSQL[@]}" -v run_id="${RUN_ID}" <<'SQL'
ANALYZE reference.parcel_coordinate_snapshot_publish;
SQL

if [[ "${PUBLISH_COUNT}" -ne "${PNU_COUNT}" ]]; then
  fail_run "published coordinate rows ${PUBLISH_COUNT} did not match staged pnu_count ${PNU_COUNT}"
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
	    -v relative_file_paths="${relative_file_paths}" \
	    -v part_counts="${part_counts}" \
	    -v source_srid="${SRC_SRID}" \
	    -v target_srid="${DST_SRID}" \
	    -v chunk_prefix_length="${CHUNK_PREFIX_LENGTH}" \
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
FROM reference.parcel_coordinate_snapshot_publish
WHERE run_id = (:'run_id')::bigint;

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
        'relativeFilePaths', to_jsonb(string_to_array(:'relative_file_paths', ',')),
        'partCounts', :'part_counts',
        'sourceSrid', (:'source_srid')::integer,
        'targetSrid', (:'target_srid')::integer,
        'chunkPrefixLength', (:'chunk_prefix_length')::integer,
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
	    -v relative_file_paths="${relative_file_paths}" \
	    -v part_counts="${part_counts}" \
	    -v source_srid="${SRC_SRID}" \
	    -v target_srid="${DST_SRID}" \
	    -v chunk_prefix_length="${CHUNK_PREFIX_LENGTH}" \
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
FROM reference.parcel_coordinate_snapshot_publish
WHERE run_id = (:'run_id')::bigint;

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
        'relativeFilePaths', to_jsonb(string_to_array(:'relative_file_paths', ',')),
        'partCounts', :'part_counts',
        'sourceSrid', (:'source_srid')::integer,
        'targetSrid', (:'target_srid')::integer,
        'chunkPrefixLength', (:'chunk_prefix_length')::integer,
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
