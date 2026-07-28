#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

required() {
  local name="$1"
  [[ -n "${!name:-}" ]] || { echo "상태: Fail - ${name} 설정이 필요합니다." >&2; exit 1; }
}

random_hex() { openssl rand -hex 32; }

sql_literal() {
  local value="$1"
  value="${value//\'/\'\'}"
  printf '%s' "${value}"
}

pgpass_field() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//:/\\:}"
  printf '%s' "${value}"
}

put_if_empty() {
  local secret_arn="$1" value_file="$2"
  if read_secret_if_present "${secret_arn}" /dev/null; then
    return 0
  fi
  aws secretsmanager put-secret-value --secret-id "${secret_arn}" \
    --secret-string "file://${value_file}" >/dev/null
}

read_secret_if_present() {
  local secret_arn="$1" destination="$2" error_file
  error_file="$(mktemp "${tmp_dir}/secret-read-error.XXXXXX")"
  if aws secretsmanager get-secret-value --secret-id "${secret_arn}" \
      --query SecretString --output text >"${destination}" 2>"${error_file}"; then
    return 0
  fi
  if ! grep -Fq 'ResourceNotFoundException' "${error_file}"; then
    echo "상태: Fail - secret 조회에 실패했습니다: ${secret_arn}" >&2
    exit 1
  fi
  : >"${destination}"
  return 1
}

reconcile_admin_jwt_secrets() {
  local private_present=false public_present=false active_kid
  if read_secret_if_present "${ADMIN_JWT_SECRET_ARN}" "${tmp_dir}/admin-jwt-existing.json"; then
    private_present=true
  fi
  if read_secret_if_present "${ADMIN_JWT_PUBLIC_SECRET_ARN}" "${tmp_dir}/admin-jwt-public-existing.json"; then
    public_present=true
  fi
  if [[ "${private_present}" == 'false' && "${public_present}" == 'true' ]]; then
    echo '상태: Fail - admin JWT public secret만 존재하여 안전하게 private key를 복구할 수 없습니다.' >&2
    exit 1
  fi
  if [[ "${private_present}" == 'false' ]]; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
      -out "${tmp_dir}/admin-private-candidate.pem" 2>/dev/null
    jq -n --rawfile private "${tmp_dir}/admin-private-candidate.pem" \
      '{active_kid:"staging-1",private_key_pem:$private}' >"${tmp_dir}/admin-jwt-candidate.json"
    put_if_empty "${ADMIN_JWT_SECRET_ARN}" "${tmp_dir}/admin-jwt-candidate.json"
  fi

  read_secret "${ADMIN_JWT_SECRET_ARN}" "${tmp_dir}/admin-jwt-current.json"
  active_kid="$(jq -er '.active_kid | select(type == "string" and length > 0)' \
    "${tmp_dir}/admin-jwt-current.json")"
  jq -er '.private_key_pem | select(type == "string" and length > 0)' \
    "${tmp_dir}/admin-jwt-current.json" >"${tmp_dir}/admin-private-current.pem"
  openssl pkey -in "${tmp_dir}/admin-private-current.pem" -pubout \
    -out "${tmp_dir}/admin-public-derived.pem" 2>/dev/null
  jq -n --arg active_kid "${active_kid}" --rawfile public "${tmp_dir}/admin-public-derived.pem" \
    '{active_kid:$active_kid,public_key_pem:$public}' >"${tmp_dir}/admin-jwt-public-derived.json"

  put_if_empty "${ADMIN_JWT_PUBLIC_SECRET_ARN}" "${tmp_dir}/admin-jwt-public-derived.json"
  read_secret "${ADMIN_JWT_PUBLIC_SECRET_ARN}" "${tmp_dir}/admin-jwt-public-current.json"
  jq -er '.public_key_pem | select(type == "string" and length > 0)' \
    "${tmp_dir}/admin-jwt-public-current.json" >"${tmp_dir}/admin-public-current-raw.pem"
  openssl pkey -pubin -in "${tmp_dir}/admin-public-current-raw.pem" -pubout \
    -out "${tmp_dir}/admin-public-current.pem" 2>/dev/null
  read_secret "${ADMIN_JWT_SECRET_ARN}" "${tmp_dir}/admin-jwt-verification.json"
  active_kid="$(jq -er '.active_kid | select(type == "string" and length > 0)' \
    "${tmp_dir}/admin-jwt-verification.json")"
  jq -er '.private_key_pem | select(type == "string" and length > 0)' \
    "${tmp_dir}/admin-jwt-verification.json" >"${tmp_dir}/admin-private-verification.pem"
  openssl pkey -in "${tmp_dir}/admin-private-verification.pem" -pubout \
    -out "${tmp_dir}/admin-public-verification.pem" 2>/dev/null
  if [[ "$(jq -er '.active_kid | select(type == "string" and length > 0)' \
      "${tmp_dir}/admin-jwt-public-current.json")" != "${active_kid}" ]] \
      || ! cmp -s "${tmp_dir}/admin-public-verification.pem" "${tmp_dir}/admin-public-current.pem"; then
    echo '상태: Fail - admin JWT private/public secret의 kid 또는 key pair가 일치하지 않습니다.' >&2
    exit 1
  fi
}

secret_bootstrap() {
  local name spec file_stem
  for name in \
    PROPERTY_RUNTIME_DB_SECRET_ARN PROPERTY_AI_READER_DB_SECRET_ARN \
    ADMIN_RUNTIME_DB_SECRET_ARN USER_RUNTIME_DB_SECRET_ARN COORDINATE_READER_DB_SECRET_ARN \
    PROPERTY_MIGRATOR_DB_SECRET_ARN ADMIN_MIGRATOR_DB_SECRET_ARN USER_MIGRATOR_DB_SECRET_ARN \
    COORDINATE_MIGRATOR_DB_SECRET_ARN COORDINATE_IMPORTER_DB_SECRET_ARN BACKUP_DB_SECRET_ARN \
    USER_JWT_SECRET_ARN ADMIN_JWT_SECRET_ARN ADMIN_JWT_PUBLIC_SECRET_ARN; do
    required "${name}"
  done

  for spec in \
    PROPERTY_RUNTIME_DB_SECRET_ARN:property-runtime \
    PROPERTY_AI_READER_DB_SECRET_ARN:property-ai-reader \
    ADMIN_RUNTIME_DB_SECRET_ARN:admin-runtime \
    USER_RUNTIME_DB_SECRET_ARN:user-runtime \
    COORDINATE_READER_DB_SECRET_ARN:coordinate-reader \
    PROPERTY_MIGRATOR_DB_SECRET_ARN:property-migrator \
    ADMIN_MIGRATOR_DB_SECRET_ARN:admin-migrator \
    USER_MIGRATOR_DB_SECRET_ARN:user-migrator \
    COORDINATE_MIGRATOR_DB_SECRET_ARN:coordinate-migrator \
    COORDINATE_IMPORTER_DB_SECRET_ARN:coordinate-importer \
    BACKUP_DB_SECRET_ARN:backup; do
    name="${spec%%:*}"
    file_stem="${spec#*:}"
    printf '{"password":"%s"}\n' "$(random_hex)" >"${tmp_dir}/${file_stem}.json"
    put_if_empty "${!name}" "${tmp_dir}/${file_stem}.json"
  done

  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "${tmp_dir}/user-private.pem" 2>/dev/null
  openssl pkey -in "${tmp_dir}/user-private.pem" -pubout -out "${tmp_dir}/user-public.pem" 2>/dev/null
  jq -n --rawfile private "${tmp_dir}/user-private.pem" --rawfile public "${tmp_dir}/user-public.pem" \
    '{active_kid:"staging-1",private_key_pem:$private,public_key_pem:$public}' >"${tmp_dir}/user-jwt.json"

  put_if_empty "${USER_JWT_SECRET_ARN}" "${tmp_dir}/user-jwt.json"
  reconcile_admin_jwt_secrets
  echo '상태: Pass - staging secret bootstrap을 idempotent하게 확인했습니다.'
}

read_secret() {
  local secret_arn="$1" destination="$2"
  aws secretsmanager get-secret-value --secret-id "${secret_arn}" \
    --query SecretString --output text >"${destination}"
}

write_role_sql() {
  local file="$1" role="$2" password="$3"
  printf 'DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '\''%s'\'') THEN CREATE ROLE %s LOGIN; END IF; END $$;\n' "${role}" "${role}" >>"${file}"
  printf 'ALTER ROLE %s WITH LOGIN PASSWORD '\''%s'\'';\n' "${role}" "$(sql_literal "${password}")" >>"${file}"
  printf 'ALTER ROLE %s NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;\n' "${role}" >>"${file}"
}

prepare_pgpass() {
  local secret_file="$1" output="$2"
  local host port username password
  host="$(jq -r '.host' "${secret_file}")"
  port="$(jq -r '.port // 5432' "${secret_file}")"
  username="$(jq -r '.username' "${secret_file}")"
  password="$(jq -r '.password' "${secret_file}")"
  printf '%s:%s:*:%s:%s\n' "$(pgpass_field "${host}")" "${port}" "$(pgpass_field "${username}")" "$(pgpass_field "${password}")" >"${output}"
}

ensure_database() {
  local secret_file="$1" pgpass="$2" database="$3" owner="$4"
  local host port username exists
  host="$(jq -r '.host' "${secret_file}")"
  port="$(jq -r '.port // 5432' "${secret_file}")"
  username="$(jq -r '.username' "${secret_file}")"
  exists="$(PGPASSFILE="${pgpass}" psql -X -Atq -h "${host}" -p "${port}" -U "${username}" -d postgres \
    -c "SELECT 1 FROM pg_database WHERE datname = '${database}'")"
  if [[ "${exists}" != "1" ]]; then
    PGPASSFILE="${pgpass}" createdb -h "${host}" -p "${port}" -U "${username}" --owner "${owner}" "${database}"
  fi
  PGPASSFILE="${pgpass}" psql -X -q -h "${host}" -p "${port}" -U "${username}" -d postgres \
    -c "ALTER DATABASE ${database} OWNER TO ${owner}" >/dev/null
}

db_bootstrap() {
  local name
  for name in \
    PRIMARY_RDS_SECRET_ARN COORDINATE_RDS_SECRET_ARN \
    PROPERTY_RUNTIME_DB_SECRET_ARN PROPERTY_AI_READER_DB_SECRET_ARN \
    ADMIN_RUNTIME_DB_SECRET_ARN USER_RUNTIME_DB_SECRET_ARN COORDINATE_READER_DB_SECRET_ARN \
    PROPERTY_MIGRATOR_DB_SECRET_ARN ADMIN_MIGRATOR_DB_SECRET_ARN USER_MIGRATOR_DB_SECRET_ARN \
    COORDINATE_MIGRATOR_DB_SECRET_ARN COORDINATE_IMPORTER_DB_SECRET_ARN BACKUP_DB_SECRET_ARN; do
    required "${name}"
  done
  read_secret "${PRIMARY_RDS_SECRET_ARN}" "${tmp_dir}/primary-master.json"
  read_secret "${COORDINATE_RDS_SECRET_ARN}" "${tmp_dir}/coordinate-master.json"
  read_secret "${PROPERTY_RUNTIME_DB_SECRET_ARN}" "${tmp_dir}/property-runtime.json"
  read_secret "${PROPERTY_AI_READER_DB_SECRET_ARN}" "${tmp_dir}/property-ai-reader.json"
  read_secret "${ADMIN_RUNTIME_DB_SECRET_ARN}" "${tmp_dir}/admin-runtime.json"
  read_secret "${USER_RUNTIME_DB_SECRET_ARN}" "${tmp_dir}/user-runtime.json"
  read_secret "${COORDINATE_READER_DB_SECRET_ARN}" "${tmp_dir}/coordinate-reader.json"
  read_secret "${PROPERTY_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/property-migrator.json"
  read_secret "${ADMIN_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/admin-migrator.json"
  read_secret "${USER_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/user-migrator.json"
  read_secret "${COORDINATE_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/coordinate-migrator.json"
  read_secret "${COORDINATE_IMPORTER_DB_SECRET_ARN}" "${tmp_dir}/coordinate-importer.json"
  read_secret "${BACKUP_DB_SECRET_ARN}" "${tmp_dir}/backup.json"
  prepare_pgpass "${tmp_dir}/primary-master.json" "${tmp_dir}/primary.pgpass"
  prepare_pgpass "${tmp_dir}/coordinate-master.json" "${tmp_dir}/coordinate.pgpass"

  : >"${tmp_dir}/primary-roles.sql"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_property_runtime "$(jq -er '.password | select(type == "string" and length > 0)' "${tmp_dir}/property-runtime.json")"
  property_ai_reader_password="$(jq -er '.password | select(type == "string" and length > 0)' "${tmp_dir}/property-ai-reader.json")" || {
    echo '상태: Fail - property AI reader secret에 password 설정이 필요합니다.' >&2
    exit 1
  }
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_ai_reader "${property_ai_reader_password}"
  cat >>"${tmp_dir}/primary-roles.sql" <<'SQL'
ALTER ROLE home_search_ai_reader NOINHERIT;
DO $$
DECLARE
  parent_role name;
BEGIN
  FOR parent_role IN
    SELECT parent.rolname
    FROM pg_auth_members membership
    JOIN pg_roles parent ON parent.oid = membership.roleid
    WHERE membership.member = 'home_search_ai_reader'::regrole
  LOOP
    EXECUTE format('REVOKE %I FROM home_search_ai_reader', parent_role);
  END LOOP;
END
$$;
SQL
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_property_migrator "$(jq -er '.password' "${tmp_dir}/property-migrator.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_admin_runtime "$(jq -er '.password' "${tmp_dir}/admin-runtime.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_admin_migrator "$(jq -er '.password' "${tmp_dir}/admin-migrator.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_user_runtime "$(jq -er '.password' "${tmp_dir}/user-runtime.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_user_migrator "$(jq -er '.password' "${tmp_dir}/user-migrator.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_backup "$(jq -er '.password' "${tmp_dir}/backup.json")"

  primary_host="$(jq -r '.host' "${tmp_dir}/primary-master.json")"
  primary_port="$(jq -r '.port // 5432' "${tmp_dir}/primary-master.json")"
  primary_user="$(jq -r '.username' "${tmp_dir}/primary-master.json")"
  PGPASSFILE="${tmp_dir}/primary.pgpass" psql -X -q -h "${primary_host}" -p "${primary_port}" -U "${primary_user}" -d postgres -f "${tmp_dir}/primary-roles.sql" >/dev/null
  ensure_database "${tmp_dir}/primary-master.json" "${tmp_dir}/primary.pgpass" home_search home_search_property_migrator
  ensure_database "${tmp_dir}/primary-master.json" "${tmp_dir}/primary.pgpass" home_search_admin home_search_admin_migrator
  ensure_database "${tmp_dir}/primary-master.json" "${tmp_dir}/primary.pgpass" home_search_user home_search_user_migrator
  PGPASSFILE="${tmp_dir}/primary.pgpass" psql -X -q -h "${primary_host}" -p "${primary_port}" -U "${primary_user}" -d postgres <<'SQL'
REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE home_search FROM PUBLIC;
REVOKE CONNECT ON DATABASE home_search_admin FROM PUBLIC;
REVOKE CONNECT ON DATABASE home_search_user FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE home_search FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE home_search_admin FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE home_search_user FROM PUBLIC;
GRANT CONNECT ON DATABASE home_search TO home_search_property_runtime, home_search_property_migrator, home_search_ai_reader, home_search_backup;
GRANT CONNECT ON DATABASE home_search_admin TO home_search_admin_runtime, home_search_admin_migrator, home_search_backup;
GRANT CONNECT ON DATABASE home_search_user TO home_search_user_runtime, home_search_user_migrator, home_search_backup;
SQL

  : >"${tmp_dir}/coordinate-roles.sql"
  write_role_sql "${tmp_dir}/coordinate-roles.sql" home_search_coordinate_reader "$(jq -er '.password' "${tmp_dir}/coordinate-reader.json")"
  write_role_sql "${tmp_dir}/coordinate-roles.sql" home_search_coordinate_migrator "$(jq -er '.password' "${tmp_dir}/coordinate-migrator.json")"
  write_role_sql "${tmp_dir}/coordinate-roles.sql" home_search_coordinate_importer "$(jq -er '.password' "${tmp_dir}/coordinate-importer.json")"
  coordinate_host="$(jq -r '.host' "${tmp_dir}/coordinate-master.json")"
  coordinate_port="$(jq -r '.port // 5432' "${tmp_dir}/coordinate-master.json")"
  coordinate_user="$(jq -r '.username' "${tmp_dir}/coordinate-master.json")"
  PGPASSFILE="${tmp_dir}/coordinate.pgpass" psql -X -q -h "${coordinate_host}" -p "${coordinate_port}" -U "${coordinate_user}" -d postgres -f "${tmp_dir}/coordinate-roles.sql" >/dev/null
  ensure_database "${tmp_dir}/coordinate-master.json" "${tmp_dir}/coordinate.pgpass" home_search_coordinate_source home_search_coordinate_migrator
  PGPASSFILE="${tmp_dir}/coordinate.pgpass" psql -X -q -h "${coordinate_host}" -p "${coordinate_port}" -U "${coordinate_user}" -d postgres <<'SQL'
REVOKE CONNECT ON DATABASE home_search_coordinate_source FROM PUBLIC;
GRANT CONNECT ON DATABASE home_search_coordinate_source TO home_search_coordinate_migrator, home_search_coordinate_importer, home_search_coordinate_reader;
SQL
  echo '상태: Pass - logical database와 least-privilege role bootstrap을 확인했습니다.'
}

runtime_grants() {
  local name
  for name in PROPERTY_MIGRATOR_DB_SECRET_ARN ADMIN_MIGRATOR_DB_SECRET_ARN USER_MIGRATOR_DB_SECRET_ARN; do
    required "${name}"
  done
  if [[ -n "${PROPERTY_DB_HOST:-}${ADMIN_DB_HOST:-}${USER_DB_HOST:-}" ]]; then
    for name in PROPERTY_DB_HOST PROPERTY_DB_PORT ADMIN_DB_HOST ADMIN_DB_PORT USER_DB_HOST USER_DB_PORT; do
      required "${name}"
    done
  else
    for name in PRIMARY_DB_HOST PRIMARY_DB_PORT; do
      required "${name}"
    done
  fi
  read_secret "${PROPERTY_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/property-migrator.json"
  read_secret "${ADMIN_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/admin-migrator.json"
  read_secret "${USER_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/user-migrator.json"
  local host port logical database migrator password pgpass sql
  for logical in property admin user; do
    case "${logical}" in
      property)
        host="${PROPERTY_DB_HOST:-${PRIMARY_DB_HOST}}"
        port="${PROPERTY_DB_PORT:-${PRIMARY_DB_PORT}}"
        database=home_search
        migrator=home_search_property_migrator
        password="$(jq -er '.password' "${tmp_dir}/property-migrator.json")"
        ;;
      admin)
        host="${ADMIN_DB_HOST:-${PRIMARY_DB_HOST}}"
        port="${ADMIN_DB_PORT:-${PRIMARY_DB_PORT}}"
        database=home_search_admin
        migrator=home_search_admin_migrator
        password="$(jq -er '.password' "${tmp_dir}/admin-migrator.json")"
        ;;
      user)
        host="${USER_DB_HOST:-${PRIMARY_DB_HOST}}"
        port="${USER_DB_PORT:-${PRIMARY_DB_PORT}}"
        database=home_search_user
        migrator=home_search_user_migrator
        password="$(jq -er '.password' "${tmp_dir}/user-migrator.json")"
        ;;
    esac
    pgpass="${tmp_dir}/${logical}.pgpass"
    printf '%s:%s:%s:%s:%s\n' "$(pgpass_field "${host}")" "${port}" "${database}" "${migrator}" "$(pgpass_field "${password}")" >"${pgpass}"
    sql="${tmp_dir}/${logical}-grants.sql"
    case "${logical}" in
      property) cat >"${sql}" <<'SQL'
GRANT USAGE ON SCHEMA public, reference, batch TO home_search_property_runtime, home_search_backup;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public, reference, batch TO home_search_property_runtime;
REVOKE DELETE ON ALL TABLES IN SCHEMA public, reference, batch FROM home_search_property_runtime;
GRANT DELETE ON TABLE market_news_collection_execution,
                      market_news_collection_work_unit,
                      market_news_raw_item,
                      market_news_article,
                      market_news_relation,
                      market_news_snapshot,
                      market_news_snapshot_item,
                      market_news_major_complex_selection,
                      market_news_quality_review_snapshot,
                      market_news_quality_review_set,
                      market_news_quality_label
TO home_search_property_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public, reference, batch TO home_search_property_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public, reference, batch TO home_search_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, reference, batch GRANT SELECT, INSERT, UPDATE ON TABLES TO home_search_property_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, reference, batch REVOKE DELETE ON TABLES FROM home_search_property_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, reference, batch GRANT USAGE, SELECT ON SEQUENCES TO home_search_property_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, reference, batch GRANT SELECT ON TABLES TO home_search_backup;
REVOKE ALL ON ALL TABLES IN SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON ALL TABLES IN SCHEMA ai_read FROM PUBLIC, home_search_ai_reader;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA ai_read FROM PUBLIC, home_search_ai_reader;
GRANT USAGE ON SCHEMA ai_read TO home_search_ai_reader;
GRANT SELECT ON ai_read.complex_fact, ai_read.trade_fact TO home_search_ai_reader;
SQL
        ;;
      admin) cat >"${sql}" <<'SQL'
GRANT USAGE ON SCHEMA admin TO home_search_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA admin TO home_search_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA admin GRANT SELECT ON TABLES TO home_search_backup;
SQL
        ;;
      user) cat >"${sql}" <<'SQL'
GRANT USAGE ON SCHEMA users TO home_search_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA users TO home_search_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA users GRANT SELECT ON TABLES TO home_search_backup;
SQL
        ;;
    esac
    PGPASSFILE="${pgpass}" psql -X -q -v ON_ERROR_STOP=1 -h "${host}" -p "${port}" -U "${migrator}" -d "${database}" -f "${sql}" >/dev/null
  done
  echo '상태: Pass - runtime 및 backup 최소 권한을 migration 이후 적용했습니다.'
}

materialize_keys() {
  required KEY_OUTPUT_DIRECTORY
  if [[ -z "${PRIVATE_KEY_PEM:-}" && -z "${PUBLIC_KEY_PEM:-}" ]]; then
    echo '상태: Fail - PRIVATE_KEY_PEM 또는 PUBLIC_KEY_PEM 설정이 필요합니다.' >&2
    exit 1
  fi
  mkdir -p "${KEY_OUTPUT_DIRECTORY}"
  if [[ -n "${PRIVATE_KEY_PEM:-}" ]]; then
    printf '%s' "${PRIVATE_KEY_PEM}" >"${KEY_OUTPUT_DIRECTORY}/private.pem"
    chmod 0600 "${KEY_OUTPUT_DIRECTORY}/private.pem"
  fi
  if [[ -n "${PUBLIC_KEY_PEM:-}" ]]; then
    printf '%s' "${PUBLIC_KEY_PEM}" >"${KEY_OUTPUT_DIRECTORY}/public.pem"
    chmod 0600 "${KEY_OUTPUT_DIRECTORY}/public.pem"
  fi
}

case "${1:-}" in
  secret-bootstrap) secret_bootstrap ;;
  db-bootstrap) db_bootstrap ;;
  runtime-grants) runtime_grants ;;
  materialize-keys) materialize_keys ;;
  *) echo '사용법: home-search-bootstrap secret-bootstrap|db-bootstrap|runtime-grants|materialize-keys' >&2; exit 64 ;;
esac
