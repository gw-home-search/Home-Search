#!/usr/bin/env bash
set -Eeuo pipefail

DB_CONTAINER="${HOME_SEARCH_DB_CONTAINER:-home-search-postgis}"
DB_USER="${HOME_SEARCH_DB_USERNAME:-home_search}"
LEGACY_DB="${HOME_CLEAN_CUTOVER_LEGACY_DB:-home_search}"
CLEAN_DB="${HOME_CLEAN_CUTOVER_CLEAN_DB:-home_search_clean_codex_20260616}"
QUARANTINE_DB="${HOME_CLEAN_CUTOVER_QUARANTINE_DB:-home_search_legacy_before_clean_$(date +%Y%m%d%H%M%S)}"
BACKUP_FILE="${HOME_CLEAN_CUTOVER_BACKUP_FILE:-}"
CONFIRM_RENAME=""
CONFIRM_DROP_LEGACY=""
MODE="verify"
EXACT_COUNTS="${HOME_CLEAN_CUTOVER_EXACT_COUNTS:-false}"
ACCEPT_MAX_ID_EVIDENCE="${HOME_CLEAN_CUTOVER_ACCEPT_MAX_ID_EVIDENCE:-false}"

usage() {
  cat <<EOF
Usage:
  $0 [--verify] [--exact-counts]
  $0 --backup-legacy --backup-file /tmp/home_search_before_clean_cutover.dump
  $0 --quarantine-rename --backup-file /tmp/home_search_before_clean_cutover.dump \\
     --confirm-rename "home_search=>home_search_legacy_before_clean_YYYYMMDD,home_search_clean_codex_20260616=>home_search"
  $0 --drop-legacy --backup-file /tmp/home_search_before_clean_cutover.dump \\
     --confirm-drop-legacy "home_search_legacy_before_clean_YYYYMMDD"
  $0 --self-test

Environment:
  HOME_SEARCH_DB_CONTAINER=${DB_CONTAINER}
  HOME_SEARCH_DB_USERNAME=${DB_USER}
  HOME_CLEAN_CUTOVER_LEGACY_DB=${LEGACY_DB}
  HOME_CLEAN_CUTOVER_CLEAN_DB=${CLEAN_DB}
  HOME_CLEAN_CUTOVER_QUARANTINE_DB=${QUARANTINE_DB}
  HOME_CLEAN_CUTOVER_EXACT_COUNTS=${EXACT_COUNTS}
  HOME_CLEAN_CUTOVER_ACCEPT_MAX_ID_EVIDENCE=${ACCEPT_MAX_ID_EVIDENCE}

This script never removes Docker volumes.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --verify)
      MODE="verify"
      shift
      ;;
    --backup-legacy)
      MODE="backup"
      shift
      ;;
    --quarantine-rename)
      MODE="quarantine"
      shift
      ;;
    --drop-legacy)
      MODE="drop"
      shift
      ;;
    --backup-file)
      BACKUP_FILE="${2:-}"
      shift 2
      ;;
    --exact-counts)
      EXACT_COUNTS="true"
      shift
      ;;
    --accept-max-id-evidence)
      ACCEPT_MAX_ID_EVIDENCE="true"
      shift
      ;;
    --confirm-rename)
      CONFIRM_RENAME="${2:-}"
      shift 2
      ;;
    --confirm-drop-legacy)
      CONFIRM_DROP_LEGACY="${2:-}"
      shift 2
      ;;
    --self-test)
      MODE="self-test"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

quote_ident() {
  local value="$1"
  if [[ ! "${value}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "ERROR: unsafe database identifier: ${value}" >&2
    exit 2
  fi
  printf '"%s"' "${value}"
}

psql_db() {
  local database="$1"
  shift
  docker exec -i "${DB_CONTAINER}" psql -X -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${database}" "$@"
}

psql_scalar() {
  local database="$1"
  local sql="$2"
  psql_db "${database}" -tAc "${sql}"
}

require_database_exists() {
  local database="$1"
  quote_ident "${database}" >/dev/null
  local exists
  exists="$(psql_scalar postgres "SELECT count(*) FROM pg_database WHERE datname = '${database}'")"
  if [[ "${exists}" != "1" ]]; then
    echo "상태: Fail"
    echo "차단 사유: database not found: ${database}"
    exit 1
  fi
}

require_backup_file() {
  if [[ -z "${BACKUP_FILE}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: --backup-file is required"
    exit 2
  fi
  if [[ ! -s "${BACKUP_FILE}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: backup file is missing or empty: ${BACKUP_FILE}"
    exit 1
  fi
}

table_stats_sql() {
  cat <<'SQL'
SELECT 'complex', count(*)::text, coalesce(min(id), 0)::text, coalesce(max(id), 0)::text FROM complex
UNION ALL SELECT 'parcel', count(*)::text, coalesce(min(id), 0)::text, coalesce(max(id), 0)::text FROM parcel
UNION ALL SELECT 'raw_trade_ingest', count(*)::text, coalesce(min(id), 0)::text, coalesce(max(id), 0)::text FROM raw_trade_ingest
UNION ALL SELECT 'region', count(*)::text, coalesce(min(id), 0)::text, coalesce(max(id), 0)::text FROM region
UNION ALL SELECT 'trade', count(*)::text, coalesce(min(id), 0)::text, coalesce(max(id), 0)::text FROM trade
UNION ALL SELECT 'trade_match_evidence', count(*)::text, coalesce(min(id), 0)::text, coalesce(max(id), 0)::text FROM trade_match_evidence
UNION ALL SELECT 'trade_source_key_registry', count(*)::text, coalesce(min(id), 0)::text, coalesce(max(id), 0)::text FROM trade_source_key_registry
ORDER BY 1
SQL
}

max_id_stats_sql() {
  cat <<'SQL'
SELECT 'complex', coalesce(max(id), 0)::text FROM complex
UNION ALL SELECT 'parcel', coalesce(max(id), 0)::text FROM parcel
UNION ALL SELECT 'raw_trade_ingest', coalesce(max(id), 0)::text FROM raw_trade_ingest
UNION ALL SELECT 'region', coalesce(max(id), 0)::text FROM region
UNION ALL SELECT 'trade', coalesce(max(id), 0)::text FROM trade
UNION ALL SELECT 'trade_match_evidence', coalesce(max(id), 0)::text FROM trade_match_evidence
UNION ALL SELECT 'trade_source_key_registry', coalesce(max(id), 0)::text FROM trade_source_key_registry
ORDER BY 1
SQL
}

expected_flyway_versions() {
  local migration_glob="src/main/resources/db/migration/api/V*.sql"
  local migration_dir="src/main/resources/db/migration/api"
  local versions
  if [[ ! -d "${migration_dir}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: API Flyway migration directory not found: ${migration_dir}"
    exit 1
  fi
  versions="$(
    find "${migration_dir}" -maxdepth 1 -type f -name 'V*.sql' -exec basename {} \; \
      | sed -E 's/^V([0-9]+)__.*/\1/' \
      | sort -n \
      | awk 'BEGIN { sep = "" } /^[0-9]+$/ { printf "%s%s:true", sep, $1; sep = "," } END { print "" }'
  )"
  if [[ -z "${versions}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: no API Flyway migrations found: ${migration_glob}"
    exit 1
  fi
  printf "%s\n" "${versions}"
}

write_table_stats() {
  local database="$1"
  local output_file="$2"
  table_stats_sql | psql_db "${database}" -F '|' -At > "${output_file}"
}

write_max_id_stats() {
  local database="$1"
  local output_file="$2"
  max_id_stats_sql | psql_db "${database}" -F '|' -At > "${output_file}"
}

verify_clean_db() {
  require_database_exists "${LEGACY_DB}"
  require_database_exists "${CLEAN_DB}"

  local flyway_versions expected_versions
  expected_versions="$(expected_flyway_versions)"
  flyway_versions="$(psql_scalar "${CLEAN_DB}" "
    SELECT string_agg(version || ':' || success::text, ',' ORDER BY installed_rank)
    FROM flyway_schema_history
    WHERE version IS NOT NULL
  ")"
  if [[ "${flyway_versions}" != "${expected_versions}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: clean DB Flyway history mismatch: actual=${flyway_versions}, expected=${expected_versions}"
    exit 1
  fi

  local excluded_count
  excluded_count="$(psql_scalar "${CLEAN_DB}" "
    SELECT count(*)
    FROM (
      VALUES
        ('public.news_article_observation'),
        ('public.news_signal_feature'),
        ('public.news_collection_run'),
        ('public.rtms_backfill_job'),
        ('public.rtms_backfill_chunk'),
        ('public.rtms_backfill_chunk_run'),
        ('public.complex_relation_case'),
        ('public.complex_relation_case_complex'),
        ('public.backup_ambiguous_complex_20260613'),
        ('public.backup_complex_metadata_20260612')
    ) AS target(name)
    WHERE to_regclass(name) IS NOT NULL
  ")"
  if [[ "${excluded_count}" != "0" ]]; then
    echo "상태: Fail"
    echo "차단 사유: excluded cleanup tables exist in clean DB: ${excluded_count}"
    exit 1
  fi

  local unvalidated_fk
  unvalidated_fk="$(psql_scalar "${CLEAN_DB}" "
    SELECT count(*)
    FROM pg_constraint
    WHERE contype = 'f'
      AND NOT convalidated
  ")"
  if [[ "${unvalidated_fk}" != "0" ]]; then
    echo "상태: Fail"
    echo "차단 사유: clean DB has unvalidated foreign keys: ${unvalidated_fk}"
    exit 1
  fi

  local temp_dir legacy_stats clean_stats verification_mode
  temp_dir="$(mktemp -d)"
  trap "rm -rf '${temp_dir}'" RETURN
  legacy_stats="${temp_dir}/legacy.stats"
  clean_stats="${temp_dir}/clean.stats"
  if [[ "${EXACT_COUNTS}" == "true" ]]; then
    verification_mode="exact-counts"
    write_table_stats "${LEGACY_DB}" "${legacy_stats}"
    write_table_stats "${CLEAN_DB}" "${clean_stats}"
  else
    verification_mode="max-id"
    write_max_id_stats "${LEGACY_DB}" "${legacy_stats}"
    write_max_id_stats "${CLEAN_DB}" "${clean_stats}"
  fi
  if ! diff -u "${legacy_stats}" "${clean_stats}" >&2; then
    echo "상태: Fail"
    echo "차단 사유: source and clean ${verification_mode} stats differ"
    exit 1
  fi

  echo "상태: Pass"
  echo "검증 근거 확인: mode=${verification_mode}, flyway=${flyway_versions}, excludedTables=${excluded_count}, unvalidatedFk=${unvalidated_fk}"
  echo "검증 근거 확인: stats=$(tr '\n' ';' < "${clean_stats}")"
  if [[ "${EXACT_COUNTS}" == "true" ]]; then
    echo "검증 공백: API runtime cutover smoke는 clean DB JDBC URL로 별도 실행 필요"
  else
    echo "검증 공백: exact row count는 --exact-counts로 별도 실행 필요, API runtime cutover smoke 필요"
  fi
}

backup_legacy_db() {
  require_database_exists "${LEGACY_DB}"
  if [[ -z "${BACKUP_FILE}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: --backup-file is required"
    exit 2
  fi
  if [[ -e "${BACKUP_FILE}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: backup file already exists: ${BACKUP_FILE}"
    exit 1
  fi
  docker exec -i "${DB_CONTAINER}" pg_dump -U "${DB_USER}" -d "${LEGACY_DB}" -Fc > "${BACKUP_FILE}"
  cat "${BACKUP_FILE}" | docker exec -i "${DB_CONTAINER}" pg_restore -l >/dev/null
  echo "상태: Pass"
  echo "검증 근거 확인: backup=${BACKUP_FILE}"
}

require_no_active_database_connections() {
  local active
  active="$(psql_scalar postgres "
    SELECT count(*)
    FROM pg_stat_activity
    WHERE datname IN ('${LEGACY_DB}', '${CLEAN_DB}', '${QUARANTINE_DB}')
  ")"
  if [[ "${active}" != "0" ]]; then
    echo "상태: Fail"
    echo "차단 사유: active DB connections must be closed before rename/drop: ${active}"
    exit 1
  fi
}

quarantine_rename() {
  require_database_exists "${LEGACY_DB}"
  require_database_exists "${CLEAN_DB}"
  require_backup_file
  if [[ "${EXACT_COUNTS}" != "true" && "${ACCEPT_MAX_ID_EVIDENCE}" != "true" ]]; then
    echo "상태: Fail"
    echo "차단 사유: quarantine rename requires --exact-counts or --accept-max-id-evidence"
    exit 2
  fi
  verify_clean_db >/dev/null
  local expected="${LEGACY_DB}=>${QUARANTINE_DB},${CLEAN_DB}=>${LEGACY_DB}"
  if [[ "${CONFIRM_RENAME}" != "${expected}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: --confirm-rename must equal: ${expected}"
    exit 2
  fi
  require_no_active_database_connections
  psql_db postgres -c "ALTER DATABASE $(quote_ident "${LEGACY_DB}") RENAME TO $(quote_ident "${QUARANTINE_DB}")"
  psql_db postgres -c "ALTER DATABASE $(quote_ident "${CLEAN_DB}") RENAME TO $(quote_ident "${LEGACY_DB}")"
  echo "상태: Pass"
  echo "검증 근거 확인: renamed ${LEGACY_DB}=>${QUARANTINE_DB}, ${CLEAN_DB}=>${LEGACY_DB}"
  echo "검증 공백: API restart and public endpoint smoke required before any DROP DATABASE"
}

drop_legacy_db() {
  require_backup_file
  if [[ -z "${CONFIRM_DROP_LEGACY}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: --confirm-drop-legacy is required"
    exit 2
  fi
  require_database_exists "${CONFIRM_DROP_LEGACY}"
  if [[ ! "${CONFIRM_DROP_LEGACY}" =~ ^home_search_legacy_before_clean_[0-9]{8,14}$ ]]; then
    echo "상태: Fail"
    echo "차단 사유: --confirm-drop-legacy must be a quarantined legacy database name"
    exit 2
  fi
  if [[ "${CONFIRM_DROP_LEGACY}" == "${LEGACY_DB}" || "${CONFIRM_DROP_LEGACY}" == "${CLEAN_DB}" ]]; then
    echo "상태: Fail"
    echo "차단 사유: refusing to drop active legacy or clean DB name"
    exit 2
  fi
  local active
  active="$(psql_scalar postgres "
    SELECT count(*)
    FROM pg_stat_activity
    WHERE datname = '${CONFIRM_DROP_LEGACY}'
  ")"
  if [[ "${active}" != "0" ]]; then
    echo "상태: Fail"
    echo "차단 사유: active legacy DB connections must be closed before drop: ${active}"
    exit 1
  fi
  psql_db postgres -c "DROP DATABASE $(quote_ident "${CONFIRM_DROP_LEGACY}")"
  echo "상태: Pass"
  echo "검증 근거 확인: dropped=${CONFIRM_DROP_LEGACY}"
}

self_test() {
  quote_ident "home_search" >/dev/null
  quote_ident "home_search_clean_codex_20260616" >/dev/null
  if ( quote_ident "home-search" ) >/dev/null 2>&1; then
    echo "self-test failed: unsafe identifier accepted" >&2
    exit 1
  fi
  table_stats_sql | grep -q "raw_trade_ingest"
  table_stats_sql | grep -q "trade_source_key_registry"
  table_stats_sql | grep -q "trade_match_evidence"
  max_id_stats_sql | grep -q "raw_trade_ingest"
  expected_flyway_versions | grep -q "1:true"
  expected_flyway_versions | grep -q "3:true"
  echo "self-test passed: clean DB cutover verifier"
}

validate_config() {
  quote_ident "${LEGACY_DB}" >/dev/null
  quote_ident "${CLEAN_DB}" >/dev/null
  quote_ident "${QUARANTINE_DB}" >/dev/null
}

case "${MODE}" in
  verify)
    validate_config
    verify_clean_db
    ;;
  backup)
    validate_config
    backup_legacy_db
    ;;
  quarantine)
    validate_config
    quarantine_rename
    ;;
  drop)
    validate_config
    drop_legacy_db
    ;;
  self-test)
    self_test
    ;;
esac
