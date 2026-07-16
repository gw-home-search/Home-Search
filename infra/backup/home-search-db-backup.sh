#!/usr/bin/env bash
if [[ $- == *x* ]]; then
  set +x
fi
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
BACKUP_PGPASS_FILE=""

usage() {
  cat <<'EOF'
Usage:
  home-search-db-backup.sh --backup-all OUTPUT_DIR
  home-search-db-backup.sh --verify-restore MANIFEST_FILE

Environment:
  HOME_BACKUP_PGHOST       Source PostgreSQL host (required for backup).
  HOME_BACKUP_PGPORT       Source PostgreSQL port (default: 5432).
  HOME_BACKUP_PGUSER       Backup role (required for backup).
  HOME_BACKUP_PGPASSWORD   Backup role password (required for backup).
  HOME_BACKUP_TIMESTAMP    Optional deterministic UTC timestamp (YYYYmmddTHHMMSSZ).
  HOME_BACKUP_S3_URI       Optional s3://bucket/prefix upload destination.
  HOME_BACKUP_REPO_ROOT    Migration source root (default: repository root).
  HOME_RESTORE_TMP_ROOT    Ephemeral restore parent (default: /tmp).

Only property, admin, and user databases are supported. Coordinate-source is
intentionally excluded. Restore verification creates a temporary PostgreSQL
cluster and never drops or overwrites an existing database.
EOF
}

mode="${1:-}"
argument="${2:-}"
if [[ "$#" -ne 2 || ( "${mode}" != "--backup-all" && "${mode}" != "--verify-restore" ) ]]; then
  usage >&2
  exit 2
fi

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

sha256_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}

escape_pgpass_field() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//:/\\:}"
  printf '%s' "${value}"
}

migration_dir() {
  case "$1" in
    property) printf '%s' 'apps/property-data/db/migration/api' ;;
    admin) printf '%s' 'apps/admin/service/migration/src/main/resources/db/migration/admin' ;;
    user) printf '%s' 'apps/user/service/db/migration/user' ;;
    *) return 2 ;;
  esac
}

database_name() {
  case "$1" in
    property) printf '%s' 'home_search' ;;
    admin) printf '%s' 'home_search_admin' ;;
    user) printf '%s' 'home_search_user' ;;
    *) return 2 ;;
  esac
}

history_table() {
  case "$1" in
    property) printf '%s' 'public.flyway_schema_history' ;;
    admin) printf '%s' 'admin.flyway_schema_history' ;;
    user) printf '%s' 'users.flyway_schema_history' ;;
    *) return 2 ;;
  esac
}

core_table() {
  case "$1" in
    property) printf '%s' 'public.raw_trade_ingest' ;;
    admin) printf '%s' 'admin.admin_account' ;;
    user) printf '%s' 'users.user_account' ;;
    *) return 2 ;;
  esac
}

migration_checksum() {
  local logical="$1"
  local root="${HOME_BACKUP_REPO_ROOT:-${repo_root}}"
  local relative directory file
  relative="$(migration_dir "${logical}")"
  directory="${root}/${relative}"
  [[ -d "${directory}" ]] || { echo "ERROR: migration directory is missing: ${relative}" >&2; return 1; }
  (
    cd "${root}"
    find "${relative}" -type f -name '*.sql' | LC_ALL=C sort | while IFS= read -r file; do
      printf '%s\t%s\n' "${file}" "$(sha256_file "${file}")"
    done
  ) | sha256_stream
}

migration_count() {
  local root="${HOME_BACKUP_REPO_ROOT:-${repo_root}}"
  find "${root}/$(migration_dir "$1")" -type f -name '*.sql' | wc -l | tr -d ' '
}

manifest_value() {
  local manifest="$1"
  local key="$2"
  awk -F '\t' -v wanted="${key}" '$1 == wanted { count += 1; value = substr($0, length($1) + 2) } END { if (count == 1) print value; else exit 2 }' "${manifest}"
}

validate_manifest_token() {
  [[ "$2" =~ ^$3$ ]] || { echo "ERROR: invalid manifest ${1}: ${2}" >&2; return 1; }
}

write_manifest() {
  local path="$1"
  shift
  : > "${path}"
  while [[ "$#" -gt 0 ]]; do
    printf '%s\t%s\n' "$1" "$2" >> "${path}"
    shift 2
  done
}

publish_artifacts() {
  local dump_file="$1"
  local manifest_file="$2"
  local destination="${HOME_BACKUP_S3_URI:-}"
  [[ -n "${destination}" ]] || return 0
  command -v aws >/dev/null 2>&1 || { echo "ERROR: aws CLI is required for HOME_BACKUP_S3_URI." >&2; return 1; }
  destination="${destination%/}"
  aws s3 cp "${dump_file}" "${destination}/$(basename "${dump_file}")" --only-show-errors
  aws s3 cp "${manifest_file}" "${destination}/$(basename "${manifest_file}")" --only-show-errors
}

cleanup_backup_pgpass() {
  if [[ -n "${BACKUP_PGPASS_FILE}" ]]; then
    unlink "${BACKUP_PGPASS_FILE}" 2>/dev/null || true
    BACKUP_PGPASS_FILE=""
  fi
}

backup_all() {
  local output_dir="$1"
  local host="${HOME_BACKUP_PGHOST:?Set HOME_BACKUP_PGHOST}"
  local port="${HOME_BACKUP_PGPORT:-5432}"
  local user="${HOME_BACKUP_PGUSER:?Set HOME_BACKUP_PGUSER}"
  local password="${HOME_BACKUP_PGPASSWORD:?Set HOME_BACKUP_PGPASSWORD}"
  local timestamp="${HOME_BACKUP_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
  [[ "${timestamp}" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || { echo "ERROR: invalid HOME_BACKUP_TIMESTAMP." >&2; exit 2; }
  mkdir -p "${output_dir}"

  local pgpass_file
  pgpass_file="$(mktemp "${TMPDIR:-/tmp}/home-search-backup-pgpass.XXXXXX")"
  BACKUP_PGPASS_FILE="${pgpass_file}"
  chmod 600 "${pgpass_file}"
  printf '*:*:*:*:%s\n' "$(escape_pgpass_field "${password}")" > "${pgpass_file}"
  export PGPASSFILE="${pgpass_file}"
  trap cleanup_backup_pgpass EXIT

  local logical database history core version failed_count history_count row_count
  local dump_file manifest_file dump_checksum migrations_checksum migrations_count
  for logical in property admin user; do
    database="$(database_name "${logical}")"
    history="$(history_table "${logical}")"
    core="$(core_table "${logical}")"
    version="$(psql -X -At -h "${host}" -p "${port}" -U "${user}" -d "${database}" -c 'SHOW server_version')"
    validate_manifest_token postgres_version "${version}" '[0-9]+([.][0-9]+)*'
    failed_count="$(psql -X -At -h "${host}" -p "${port}" -U "${user}" -d "${database}" -c "SELECT count(*) FROM ${history} WHERE NOT success")"
    [[ "${failed_count}" == "0" ]] || { echo "ERROR: ${logical} has failed Flyway history rows." >&2; exit 1; }
    history_count="$(psql -X -At -h "${host}" -p "${port}" -U "${user}" -d "${database}" -c "SELECT count(*) FROM ${history} WHERE success")"
    row_count="$(psql -X -At -h "${host}" -p "${port}" -U "${user}" -d "${database}" -c "SELECT count(*) FROM ${core}")"
    validate_manifest_token history_success_count "${history_count}" '[0-9]+'
    validate_manifest_token row_count "${row_count}" '[0-9]+'

    dump_file="${output_dir}/${logical}-${timestamp}.dump"
    manifest_file="${output_dir}/${logical}-${timestamp}.manifest.tsv"
    if [[ -e "${dump_file}" || -e "${manifest_file}" ]]; then
      echo "ERROR: immutable backup artifact already exists for ${logical} at ${timestamp}." >&2
      exit 1
    fi
    pg_dump -h "${host}" -p "${port}" -U "${user}" -d "${database}" \
      --format=custom --no-owner --no-acl --file="${dump_file}"
    pg_restore -l "${dump_file}" >/dev/null
    dump_checksum="$(sha256_file "${dump_file}")"
    migrations_checksum="$(migration_checksum "${logical}")"
    migrations_count="$(migration_count "${logical}")"
    write_manifest "${manifest_file}" \
      format_version 1 \
      logical_name "${logical}" \
      database_name "${database}" \
      created_at "${timestamp}" \
      postgres_version "${version}" \
      dump_file "$(basename "${dump_file}")" \
      dump_sha256 "${dump_checksum}" \
      migration_sha256 "${migrations_checksum}" \
      migration_count "${migrations_count}" \
      history_table "${history}" \
      history_success_count "${history_count}" \
      row_count_table "${core}" \
      row_count "${row_count}"
    publish_artifacts "${dump_file}" "${manifest_file}"
    echo "backup_status=success logical_name=${logical} manifest=${manifest_file}"
  done

  cleanup_backup_pgpass
  trap - EXIT
}

restore_cleanup() {
  local cluster_dir="$1"
  local task_dir="$2"
  if [[ -f "${cluster_dir}/postmaster.pid" ]]; then
    pg_ctl -D "${cluster_dir}" -m fast -w stop >/dev/null 2>&1 || true
  fi
  if [[ -d "${task_dir}" && "${task_dir}" == "${HOME_RESTORE_TMP_ROOT:-/tmp}"/home-search-restore.* ]]; then
    find "${task_dir}" -depth -delete
  fi
}

verify_restore() {
  local manifest="$1"
  [[ -f "${manifest}" ]] || { echo "ERROR: manifest is missing: ${manifest}" >&2; exit 2; }
  local format logical database timestamp dump_name expected_dump_checksum expected_migration_checksum
  local expected_migration_count history expected_history_count core expected_row_count
  format="$(manifest_value "${manifest}" format_version)"
  logical="$(manifest_value "${manifest}" logical_name)"
  database="$(manifest_value "${manifest}" database_name)"
  timestamp="$(manifest_value "${manifest}" created_at)"
  dump_name="$(manifest_value "${manifest}" dump_file)"
  expected_dump_checksum="$(manifest_value "${manifest}" dump_sha256)"
  expected_migration_checksum="$(manifest_value "${manifest}" migration_sha256)"
  expected_migration_count="$(manifest_value "${manifest}" migration_count)"
  history="$(manifest_value "${manifest}" history_table)"
  expected_history_count="$(manifest_value "${manifest}" history_success_count)"
  core="$(manifest_value "${manifest}" row_count_table)"
  expected_row_count="$(manifest_value "${manifest}" row_count)"

  [[ "${format}" == "1" ]] || { echo "ERROR: unsupported manifest format." >&2; exit 1; }
  case "${logical}" in property|admin|user) ;; *) echo "ERROR: unsupported logical database: ${logical}" >&2; exit 1 ;; esac
  [[ "${database}" == "$(database_name "${logical}")" ]] || { echo "ERROR: manifest database mismatch." >&2; exit 1; }
  [[ "${history}" == "$(history_table "${logical}")" && "${core}" == "$(core_table "${logical}")" ]] \
    || { echo "ERROR: manifest invariant table mismatch." >&2; exit 1; }
  validate_manifest_token created_at "${timestamp}" '[0-9]{8}T[0-9]{6}Z'
  validate_manifest_token dump_file "${dump_name}" '[A-Za-z0-9._-]+'
  validate_manifest_token dump_sha256 "${expected_dump_checksum}" '[0-9a-f]{64}'
  validate_manifest_token migration_sha256 "${expected_migration_checksum}" '[0-9a-f]{64}'
  validate_manifest_token migration_count "${expected_migration_count}" '[0-9]+'
  validate_manifest_token history_success_count "${expected_history_count}" '[0-9]+'
  validate_manifest_token row_count "${expected_row_count}" '[0-9]+'

  local dump_file="$(dirname "${manifest}")/${dump_name}"
  [[ -f "${dump_file}" ]] || { echo "ERROR: dump is missing: ${dump_file}" >&2; exit 1; }
  [[ "$(sha256_file "${dump_file}")" == "${expected_dump_checksum}" ]] \
    || { echo "ERROR: dump checksum mismatch." >&2; exit 1; }
  [[ "$(migration_checksum "${logical}")" == "${expected_migration_checksum}" ]] \
    || { echo "ERROR: migration checksum mismatch." >&2; exit 1; }
  [[ "$(migration_count "${logical}")" == "${expected_migration_count}" ]] \
    || { echo "ERROR: migration count mismatch." >&2; exit 1; }

  local task_dir cluster_dir socket_dir restored_db start_seconds duration history_count row_count
  task_dir="$(mktemp -d "${HOME_RESTORE_TMP_ROOT:-/tmp}/home-search-restore.XXXXXX")"
  cluster_dir="${task_dir}/cluster"
  socket_dir="${task_dir}/socket"
  mkdir -p "${socket_dir}"
  trap 'restore_cleanup "${cluster_dir}" "${task_dir}"' EXIT
  start_seconds="$(date +%s)"
  initdb -D "${cluster_dir}" --auth=trust --username=postgres >/dev/null
  pg_ctl -D "${cluster_dir}" -o "-c listen_addresses='' -c unix_socket_directories='${socket_dir}'" -w start >/dev/null
  restored_db="restore_${logical}_${timestamp%Z}"
  createdb -h "${socket_dir}" -U postgres "${restored_db}"
  pg_restore -h "${socket_dir}" -U postgres -d "${restored_db}" \
    --exit-on-error --no-owner --no-acl "${dump_file}"
  history_count="$(psql -X -At -h "${socket_dir}" -U postgres -d "${restored_db}" -c "SELECT count(*) FROM ${history} WHERE success")"
  row_count="$(psql -X -At -h "${socket_dir}" -U postgres -d "${restored_db}" -c "SELECT count(*) FROM ${core}")"
  [[ "${history_count}" == "${expected_history_count}" ]] || { echo "ERROR: restored migration history count mismatch." >&2; exit 1; }
  [[ "${row_count}" == "${expected_row_count}" ]] || { echo "ERROR: restored core table row count mismatch." >&2; exit 1; }
  duration="$(( $(date +%s) - start_seconds ))"
  echo "restore_verification_status=success logical_name=${logical} row_count=${row_count} duration_seconds=${duration}"
  restore_cleanup "${cluster_dir}" "${task_dir}"
  trap - EXIT
}

case "${mode}" in
  --backup-all) backup_all "${argument}" ;;
  --verify-restore) verify_restore "${argument}" ;;
esac
