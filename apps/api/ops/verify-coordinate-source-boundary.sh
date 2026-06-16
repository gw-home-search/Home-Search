#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./ops/verify-coordinate-source-boundary.sh --operational
  ./ops/verify-coordinate-source-boundary.sh --source
  ./ops/verify-coordinate-source-boundary.sh --geo
  ./ops/verify-coordinate-source-boundary.sh --self-test

Database connection:
  PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD are consumed by psql.

Optional:
  HOME_COORDINATE_SAMPLE_PNU    19 digit PNU used for indexed source lookup evidence.

Safety:
  This verifier is read-only. It never drops, truncates, or rewrites data.
  Source DB checks avoid nationwide count(*) scans.
EOF
}

MODE=""
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --operational|--source|--geo|--self-test)
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

PSQL=(psql -X -v ON_ERROR_STOP=1)
SAMPLE_PNU="${HOME_COORDINATE_SAMPLE_PNU:-}"

require_psql() {
  if ! command -v psql >/dev/null 2>&1; then
    echo "ERROR: psql is required on PATH" >&2
    exit 2
  fi
}

run_self_test() {
  case "${SAMPLE_PNU}" in
    ""|[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
    *)
      echo "self-test failed: HOME_COORDINATE_SAMPLE_PNU must be blank or 19 digits." >&2
      exit 1
      ;;
  esac
  if grep -Eq 'count\(\*\).*reference\.parcel_coordinate_snapshot' "$0"; then
    echo "self-test failed: source verifier must not use nationwide count(*) scans." >&2
    exit 1
  fi
  echo "self-test passed: coordinate source boundary verifier"
}

verify_operational() {
  local source_presence
  source_presence="$("${PSQL[@]}" -At <<'SQL'
SELECT string_agg(table_name, ', ' ORDER BY table_name)
FROM (
    VALUES
        ('reference.parcel_coordinate_snapshot'),
        ('reference.parcel_coordinate_snapshot_stage'),
        ('reference.parcel_coordinate_snapshot_publish'),
        ('reference.coordinate_snapshot_run'),
        ('reference.coordinate_snapshot_region_checkpoint'),
        ('reference.coordinate_snapshot_stage_chunk_checkpoint'),
        ('reference.coordinate_snapshot_publish_checkpoint'),
        ('reference.coordinate_snapshot_publish_chunk_checkpoint')
) AS source_tables(table_name)
WHERE to_regclass(table_name) IS NOT NULL;
SQL
)"
  if [[ -n "${source_presence}" ]]; then
    echo "ERROR: operational DB still owns coordinate source tables: ${source_presence}" >&2
    exit 1
  fi

  local operational_ready
  operational_ready="$("${PSQL[@]}" -At <<'SQL'
SELECT to_regclass('public.parcel') IS NOT NULL
   AND to_regclass('public.complex') IS NOT NULL
   AND to_regclass('public.trade') IS NOT NULL
   AND to_regclass('public.complex_display_coordinate') IS NOT NULL
   AND to_regclass('public.building_footprint_snapshot') IS NOT NULL;
SQL
)"
  if [[ "${operational_ready}" != "t" ]]; then
    echo "ERROR: operational coordinate projection tables are missing." >&2
    exit 1
  fi

  echo "coordinate boundary passed: operational DB has no reference coordinate source tables"
}

verify_source() {
  local schema_ready
  schema_ready="$("${PSQL[@]}" -At <<'SQL'
SELECT to_regclass('reference.coordinate_snapshot_run') IS NOT NULL
   AND to_regclass('reference.parcel_coordinate_snapshot') IS NOT NULL
   AND to_regclass('reference.parcel_coordinate_snapshot_stage') IS NOT NULL
   AND to_regclass('reference.parcel_coordinate_snapshot_publish') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_publish_checkpoint') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_publish_chunk_checkpoint') IS NOT NULL
   AND to_regclass('reference.coordinate_snapshot_stage_chunk_checkpoint') IS NOT NULL;
SQL
)"
  if [[ "${schema_ready}" != "t" ]]; then
    echo "ERROR: coordinate source DB schema is missing required reference tables." >&2
    exit 1
  fi

  local relation_report
  relation_report="$("${PSQL[@]}" -At -F '|' <<'SQL'
SELECT
    n.nspname || '.' || c.relname,
    c.reltuples::bigint,
    pg_total_relation_size(c.oid)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind = 'r'
  AND n.nspname = 'reference'
  AND c.relname IN (
      'parcel_coordinate_snapshot',
      'parcel_coordinate_snapshot_stage',
      'parcel_coordinate_snapshot_publish',
      'coordinate_snapshot_run',
      'coordinate_snapshot_region_checkpoint',
      'coordinate_snapshot_stage_chunk_checkpoint',
      'coordinate_snapshot_publish_checkpoint',
      'coordinate_snapshot_publish_chunk_checkpoint'
  )
ORDER BY c.relname;
SQL
)"
  if [[ -z "${relation_report}" ]]; then
    echo "ERROR: coordinate source relation estimate report is empty." >&2
    exit 1
  fi

  local constraint_report
  constraint_report="$("${PSQL[@]}" -At -F '|' <<'SQL'
WITH required(name) AS (
    VALUES
        ('parcel_coordinate_snapshot_pkey'),
        ('parcel_coordinate_snapshot_pnu_check'),
        ('parcel_coordinate_snapshot_latitude_check'),
        ('parcel_coordinate_snapshot_longitude_check'),
        ('ck_parcel_coordinate_snapshot_point_srid'),
        ('ck_parcel_coordinate_snapshot_geom_srid'),
        ('ck_parcel_coordinate_snapshot_geom_valid')
),
state AS (
    SELECT required.name, COALESCE(pg_constraint.convalidated, false) AS validated
    FROM required
    LEFT JOIN pg_constraint
      ON pg_constraint.conrelid = 'reference.parcel_coordinate_snapshot'::regclass
     AND pg_constraint.conname = required.name
)
SELECT
    count(*) FILTER (WHERE validated)::integer,
    COALESCE(string_agg(name, ' ' ORDER BY name) FILTER (WHERE NOT validated), '')
FROM state;
SQL
)"
  local validated missing
  IFS='|' read -r validated missing <<<"${constraint_report}"
  if [[ "${validated}" != "7" ]]; then
    echo "ERROR: coordinate source constraints are not fully validated: ${missing}" >&2
    exit 1
  fi

  if [[ -n "${SAMPLE_PNU}" ]]; then
    local sample_found
    sample_found="$("${PSQL[@]}" -v pnu="${SAMPLE_PNU}" -At <<'SQL'
SET enable_seqscan = off;
SELECT EXISTS (
    SELECT 1
    FROM reference.parcel_coordinate_snapshot
    WHERE pnu = :'pnu'
    LIMIT 1
);
SQL
)"
    echo "coordinate source sample lookup: pnu=${SAMPLE_PNU}, found=${sample_found}"
  fi

  echo "coordinate boundary passed: source DB owns reference coordinate snapshot tables"
  echo "${relation_report}"
}

verify_geo() {
  local geo_ready
  geo_ready="$("${PSQL[@]}" -At <<'SQL'
SELECT to_regclass('geo_enrichment.vworld_wfs_footprint_cache') IS NOT NULL;
SQL
)"
  if [[ "${geo_ready}" != "t" ]]; then
    echo "ERROR: geo enrichment DB schema is missing vworld_wfs_footprint_cache." >&2
    exit 1
  fi
  echo "coordinate boundary passed: geo enrichment DB owns VWorld WFS raw/cache table"
}

if [[ "${MODE}" == "self-test" ]]; then
  run_self_test
  exit 0
fi

require_psql
case "${MODE}" in
  operational) verify_operational ;;
  source) verify_source ;;
  geo) verify_geo ;;
  *)
    echo "ERROR: unsupported mode: ${MODE}" >&2
    exit 2
    ;;
esac
