#!/usr/bin/env bash
if [[ $- == *x* ]]; then
  set +x
fi
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
BACKUP_PGPASS_FILE=""
BACKUP_LOGICALS=()

usage() {
  cat <<'EOF'
Usage:
  home-search-db-backup.sh --backup-all OUTPUT_DIR
  home-search-db-backup.sh --verify-restore MANIFEST_FILE
  home-search-db-backup.sh --verify-latest-s3 S3_URI
  home-search-db-backup.sh --data-validate-catalog CATALOG_FILE
  home-search-db-backup.sh --data-export OUTPUT_DIR
  home-search-db-backup.sh --data-import DATA_MANIFEST_FILE
  home-search-db-backup.sh --data-reconcile DATA_MANIFEST_FILE

Environment:
  HOME_BACKUP_PGHOST       Default PostgreSQL host (required unless every logical override is set).
  HOME_BACKUP_PGPORT       Default PostgreSQL port (default: 5432).
  HOME_BACKUP_PGUSER       Default backup role.
  HOME_BACKUP_PGPASSWORD   Default backup role password.
  HOME_BACKUP_<LOGICAL>_PGHOST/PGPORT/PGUSER/PGPASSWORD
                           Per property/admin/user/ai/coordinate override.
  HOME_BACKUP_TIMESTAMP    Optional deterministic UTC timestamp (YYYYmmddTHHMMSSZ).
  HOME_BACKUP_LOGICAL_DATABASES
                           Comma list from property,admin,user,ai,coordinate (default: property,admin,user,ai).
  HOME_BACKUP_S3_URI       Optional s3://bucket/prefix upload destination.
  HOME_BACKUP_REPO_ROOT    Migration source root (default: repository root).
  HOME_RESTORE_TMP_ROOT    Ephemeral restore parent (default: /tmp).

All five production databases are supported: property, admin, user, AI, and
coordinate. Restore verification creates a temporary PostgreSQL cluster and
never drops or overwrites an existing database.
EOF
}

mode="${1:-}"
argument="${2:-}"
if [[ "$#" -ne 2 || ( "${mode}" != "--backup-all" && "${mode}" != "--verify-restore" && "${mode}" != "--verify-latest-s3" && "${mode}" != "--data-validate-catalog" && "${mode}" != "--data-export" && "${mode}" != "--data-import" && "${mode}" != "--data-reconcile" ) ]]; then
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
    ai) printf '%s' 'apps/ai/ai_service/datasets/migrations' ;;
    coordinate) printf '%s' 'apps/source-data/src/main/resources/db/migration/coordinate-source' ;;
    *) return 2 ;;
  esac
}

database_name() {
  case "$1" in
    property) printf '%s' 'home_search' ;;
    admin) printf '%s' 'home_search_admin' ;;
    user) printf '%s' 'home_search_user' ;;
    ai) printf '%s' 'home_search_ai' ;;
    coordinate) printf '%s' 'home_search_coordinate_source' ;;
    *) return 2 ;;
  esac
}

history_table() {
  case "$1" in
    property) printf '%s' 'public.flyway_schema_history' ;;
    admin) printf '%s' 'admin.flyway_schema_history' ;;
    user) printf '%s' 'users.flyway_schema_history' ;;
    ai) printf '%s' 'public.ai_schema_history' ;;
    coordinate) printf '%s' 'reference.flyway_schema_history' ;;
    *) return 2 ;;
  esac
}

core_table() {
  case "$1" in
    property) printf '%s' 'public.raw_trade_ingest' ;;
    admin) printf '%s' 'admin.admin_account' ;;
    user) printf '%s' 'users.user_account' ;;
    ai) printf '%s' 'public.dataset_source' ;;
    coordinate) printf '%s' 'reference.parcel_coordinate_snapshot' ;;
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
  local kms_key_id="${HOME_BACKUP_KMS_KEY_ID:?Set HOME_BACKUP_KMS_KEY_ID when HOME_BACKUP_S3_URI is set}"
  command -v aws >/dev/null 2>&1 || { echo "ERROR: aws CLI is required for HOME_BACKUP_S3_URI." >&2; return 1; }
  destination="${destination%/}"
  [[ "${destination}" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[^/].*$ ]] \
    || { echo 'ERROR: HOME_BACKUP_S3_URI must include an explicit bucket prefix.' >&2; return 1; }
  local location="${destination#s3://}" bucket="${destination#s3://}" prefix
  bucket="${bucket%%/*}"
  prefix="${location#*/}"

  local artifact checksum_hex checksum_base64 content_length key head_size head_checksum
  for artifact in "${dump_file}" "${manifest_file}"; do
    checksum_hex="$(sha256_file "${artifact}")"
    checksum_base64="$(python3 - "${checksum_hex}" <<'PY'
import base64
import sys

print(base64.b64encode(bytes.fromhex(sys.argv[1])).decode("ascii"))
PY
)"
    content_length="$(wc -c <"${artifact}" | tr -d ' ')"
    key="${prefix}/$(basename "${artifact}")"
    aws s3 cp "${artifact}" "s3://${bucket}/${key}" --only-show-errors \
      --checksum-algorithm SHA256 --sse aws:kms --sse-kms-key-id "${kms_key_id}"
    IFS=$'\t' read -r head_size head_checksum < <(
      aws s3api head-object --bucket "${bucket}" --key "${key}" --checksum-mode ENABLED \
        --query '[ContentLength,ChecksumSHA256]' --output text
    )
    [[ "${head_size}" == "${content_length}" && "${head_checksum}" == "${checksum_base64}" ]] || {
      echo "ERROR: uploaded backup checksum or size mismatch: $(basename "${artifact}")" >&2
      return 1
    }
  done
}

logical_connection_value() {
  local logical="$1"
  local field="$2"
  local upper variable fallback
  upper="$(printf '%s' "${logical}" | tr '[:lower:]' '[:upper:]')"
  variable="HOME_BACKUP_${upper}_${field}"
  case "${field}" in
    PGHOST) fallback="${HOME_BACKUP_PGHOST:-}" ;;
    PGPORT) fallback="${HOME_BACKUP_PGPORT:-5432}" ;;
    PGUSER) fallback="${HOME_BACKUP_PGUSER:-}" ;;
    PGPASSWORD) fallback="${HOME_BACKUP_PGPASSWORD:-}" ;;
    *) return 2 ;;
  esac
  printf '%s' "${!variable:-${fallback}}"
}

history_failed_count() {
  local logical="$1" host="$2" port="$3" user="$4" database="$5" history="$6"
  if [[ "${logical}" == 'ai' ]]; then
    printf '0'
  else
    psql -X -At -h "${host}" -p "${port}" -U "${user}" -d "${database}" \
      -c "SELECT count(*) FROM ${history} WHERE NOT success"
  fi
}

history_success_count() {
  local logical="$1" host="$2" port="$3" user="$4" database="$5" history="$6"
  if [[ "${logical}" == 'ai' ]]; then
    psql -X -At -h "${host}" -p "${port}" -U "${user}" -d "${database}" \
      -c "SELECT count(*) FROM ${history}"
  else
    psql -X -At -h "${host}" -p "${port}" -U "${user}" -d "${database}" \
      -c "SELECT count(*) FROM ${history} WHERE success"
  fi
}

cleanup_backup_pgpass() {
  if [[ -n "${BACKUP_PGPASS_FILE}" ]]; then
    unlink "${BACKUP_PGPASS_FILE}" 2>/dev/null || true
    BACKUP_PGPASS_FILE=""
  fi
}

configure_logicals() {
  local configured="${HOME_BACKUP_LOGICAL_DATABASES:-property,admin,user,ai}"
  local logical seen=','
  IFS=',' read -r -a BACKUP_LOGICALS <<<"${configured}"
  [[ "${#BACKUP_LOGICALS[@]}" -gt 0 ]] || { echo 'ERROR: backup logical database list is empty.' >&2; exit 2; }
  for logical in "${BACKUP_LOGICALS[@]}"; do
    case "${logical}" in property|admin|user|ai|coordinate) ;; *) echo "ERROR: invalid backup logical database: ${logical}" >&2; exit 2 ;; esac
    [[ "${seen}" != *",${logical},"* ]] || { echo "ERROR: duplicate backup logical database: ${logical}" >&2; exit 2; }
    seen+="${logical},"
  done
}

run_data_migration() {
  local operation="$1"
  local value="$2"
  local root="${HOME_BACKUP_REPO_ROOT:-${repo_root}}"
  local tool="${root}/infra/migration/data_only_migration.py"
  local catalog="${HOME_MIGRATION_CATALOG:-${root}/infra/migration/data-only-allowlist.json}"
  [[ -f "${tool}" && -f "${catalog}" ]] || { echo 'ERROR: data-only migration tool/catalog is missing.' >&2; exit 2; }
  case "${operation}" in
    validate-catalog) python3 "${tool}" --catalog "${value}" validate-catalog ;;
    export)
      local -a export_arguments=(--catalog "${catalog}" export --output "${value}")
      if [[ -n "${HOME_MIGRATION_S3_URI:-}" || -n "${HOME_MIGRATION_KMS_KEY_ID:-}" ]]; then
        export_arguments+=(--s3-uri "${HOME_MIGRATION_S3_URI:?Set HOME_MIGRATION_S3_URI}" --kms-key-id "${HOME_MIGRATION_KMS_KEY_ID:?Set HOME_MIGRATION_KMS_KEY_ID}")
      fi
      python3 "${tool}" "${export_arguments[@]}"
      ;;
    import) python3 "${tool}" --catalog "${catalog}" import --manifest "${value}" ;;
    reconcile)
      python3 "${tool}" --catalog "${catalog}" reconcile --manifest "${value}" \
        --report "${HOME_MIGRATION_RECONCILIATION_REPORT:?Set HOME_MIGRATION_RECONCILIATION_REPORT}"
      ;;
  esac
}

backup_all() {
  local output_dir="$1"
  local timestamp="${HOME_BACKUP_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
  [[ "${timestamp}" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || { echo "ERROR: invalid HOME_BACKUP_TIMESTAMP." >&2; exit 2; }
  mkdir -p "${output_dir}"

  local pgpass_file
  pgpass_file="$(mktemp "${TMPDIR:-/tmp}/home-search-backup-pgpass.XXXXXX")"
  BACKUP_PGPASS_FILE="${pgpass_file}"
  chmod 600 "${pgpass_file}"
  : > "${pgpass_file}"
  export PGPASSFILE="${pgpass_file}"
  trap cleanup_backup_pgpass EXIT

  local logical database history core version failed_count history_count row_count host port user password
  local dump_file manifest_file dump_checksum migrations_checksum migrations_count
  for logical in "${BACKUP_LOGICALS[@]}"; do
    host="$(logical_connection_value "${logical}" PGHOST)"
    port="$(logical_connection_value "${logical}" PGPORT)"
    user="$(logical_connection_value "${logical}" PGUSER)"
    password="$(logical_connection_value "${logical}" PGPASSWORD)"
    [[ -n "${host}" && -n "${user}" && -n "${password}" ]] \
      || { echo "ERROR: incomplete backup connection for ${logical}." >&2; exit 2; }
    database="$(database_name "${logical}")"
    printf '%s:%s:%s:%s:%s\n' \
      "$(escape_pgpass_field "${host}")" "$(escape_pgpass_field "${port}")" \
      "$(escape_pgpass_field "${database}")" "$(escape_pgpass_field "${user}")" \
      "$(escape_pgpass_field "${password}")" >> "${pgpass_file}"
    history="$(history_table "${logical}")"
    core="$(core_table "${logical}")"
    version="$(psql -X -At -h "${host}" -p "${port}" -U "${user}" -d "${database}" -c 'SHOW server_version')"
    validate_manifest_token postgres_version "${version}" '[0-9]+([.][0-9]+)*'
    failed_count="$(history_failed_count "${logical}" "${host}" "${port}" "${user}" "${database}" "${history}")"
    [[ "${failed_count}" == "0" ]] || { echo "ERROR: ${logical} has failed Flyway history rows." >&2; exit 1; }
    history_count="$(history_success_count "${logical}" "${host}" "${port}" "${user}" "${database}" "${history}")"
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
    printf '{"metric":"backup_success","value":1,"logical_name":"%s"}\n' "${logical}"
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
  case "${logical}" in property|admin|user|ai|coordinate) ;; *) echo "ERROR: unsupported logical database: ${logical}" >&2; exit 1 ;; esac
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
  if [[ "${logical}" == 'ai' ]]; then
    history_count="$(psql -X -At -h "${socket_dir}" -U postgres -d "${restored_db}" -c "SELECT count(*) FROM ${history}")"
  else
    history_count="$(psql -X -At -h "${socket_dir}" -U postgres -d "${restored_db}" -c "SELECT count(*) FROM ${history} WHERE success")"
  fi
  row_count="$(psql -X -At -h "${socket_dir}" -U postgres -d "${restored_db}" -c "SELECT count(*) FROM ${core}")"
  [[ "${history_count}" == "${expected_history_count}" ]] || { echo "ERROR: restored migration history count mismatch." >&2; exit 1; }
  [[ "${row_count}" == "${expected_row_count}" ]] || { echo "ERROR: restored core table row count mismatch." >&2; exit 1; }
  duration="$(( $(date +%s) - start_seconds ))"
  echo "restore_verification_status=success logical_name=${logical} row_count=${row_count} duration_seconds=${duration}"
  printf '{"metric":"restore_success","value":1,"logical_name":"%s"}\n' "${logical}"
  printf '{"metric":"restore_duration_seconds","value":%s,"logical_name":"%s"}\n' "${duration}" "${logical}"
  restore_cleanup "${cluster_dir}" "${task_dir}"
  trap - EXIT
}

verify_latest_s3() {
  local uri="$1"
  [[ "${uri}" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9](/.*)?$ ]] \
    || { echo 'ERROR: HOME_BACKUP_S3_URI must be a valid s3 URI.' >&2; exit 2; }
  local without_scheme bucket prefix logical key key_dir dump_key task_dir manifest dump_name timestamp set_timestamp='' created_epoch now_epoch age
  without_scheme="${uri#s3://}"
  bucket="${without_scheme%%/*}"
  if [[ "${without_scheme}" == */* ]]; then
    prefix="${without_scheme#*/}"
    prefix="${prefix%/}/"
  else
    prefix=''
  fi
  [[ "${prefix}" =~ ^[A-Za-z0-9._/-]*$ && "${prefix}" != *'..'* ]] \
    || { echo 'ERROR: S3 prefix contains unsupported characters.' >&2; exit 2; }

  for logical in "${BACKUP_LOGICALS[@]}"; do
    key="$(aws s3api list-objects-v2 --bucket "${bucket}" --prefix "${prefix}${logical}-" \
      --query "sort_by(Contents[?ends_with(Key, '.manifest.tsv')], &LastModified)[-1].Key" --output text)"
    [[ "${key}" != "None" && "${key}" =~ ^${prefix}${logical}-[0-9]{8}T[0-9]{6}Z[.]manifest[.]tsv$ ]] \
      || { echo "ERROR: latest ${logical} backup manifest was not found." >&2; exit 1; }
    task_dir="$(mktemp -d "${HOME_RESTORE_TMP_ROOT:-/tmp}/home-search-s3.XXXXXX")"
    manifest="${task_dir}/$(basename "${key}")"
    aws s3 cp "s3://${bucket}/${key}" "${manifest}" --only-show-errors
    dump_name="$(manifest_value "${manifest}" dump_file)"
    validate_manifest_token dump_file "${dump_name}" '[A-Za-z0-9._-]+'
    if [[ "${key}" == */* ]]; then key_dir="${key%/*}/"; else key_dir=''; fi
    dump_key="${key_dir}${dump_name}"
    aws s3 cp "s3://${bucket}/${dump_key}" "${task_dir}/${dump_name}" --only-show-errors
    timestamp="$(manifest_value "${manifest}" created_at)"
    validate_manifest_token created_at "${timestamp}" '[0-9]{8}T[0-9]{6}Z'
    if [[ -z "${set_timestamp}" ]]; then set_timestamp="${timestamp}"; fi
    [[ "${timestamp}" == "${set_timestamp}" ]] \
      || { echo 'ERROR: latest logical backups do not share one backup timestamp.' >&2; exit 1; }
    created_epoch="$(date -u -d "${timestamp:0:8} ${timestamp:9:2}:${timestamp:11:2}:${timestamp:13:2} UTC" +%s)"
    now_epoch="$(date -u +%s)"
    age="$((now_epoch - created_epoch))"
    (( age >= 0 )) || { echo 'ERROR: backup timestamp is in the future.' >&2; exit 1; }
    echo "backup_age_seconds=${age} logical_name=${logical}"
    printf '{"metric":"backup_age_seconds","value":%s,"logical_name":"%s"}\n' "${age}" "${logical}"
    verify_restore "${manifest}"
    find "${task_dir}" -depth -delete
  done
}

case "${mode}" in
  --backup-all) configure_logicals; backup_all "${argument}" ;;
  --verify-restore) verify_restore "${argument}" ;;
  --verify-latest-s3) configure_logicals; verify_latest_s3 "${argument}" ;;
  --data-validate-catalog) run_data_migration validate-catalog "${argument}" ;;
  --data-export) run_data_migration export "${argument}" ;;
  --data-import) run_data_migration import "${argument}" ;;
  --data-reconcile) run_data_migration reconcile "${argument}" ;;
esac
