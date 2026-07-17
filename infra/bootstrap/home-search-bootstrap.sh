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
  if aws secretsmanager get-secret-value --secret-id "${secret_arn}" \
      --query SecretString --output text >/dev/null 2>&1; then
    return 0
  fi
  aws secretsmanager put-secret-value --secret-id "${secret_arn}" \
    --secret-string "file://${value_file}" >/dev/null
}

secret_bootstrap() {
  for name in DATABASE_RUNTIME_SECRET_ARN DATABASE_BOOTSTRAP_SECRET_ARN USER_JWT_SECRET_ARN ADMIN_JWT_SECRET_ARN; do required "${name}"; done

  printf '{"property_runtime":"%s","property_ai_reader":"%s","admin_runtime":"%s","user_runtime":"%s","coordinate_reader":"%s"}\n' \
    "$(random_hex)" "$(random_hex)" "$(random_hex)" "$(random_hex)" "$(random_hex)" >"${tmp_dir}/runtime.json"
  printf '{"property_migrator":"%s","admin_migrator":"%s","user_migrator":"%s","coordinate_migrator":"%s","coordinate_importer":"%s","backup":"%s"}\n' \
    "$(random_hex)" "$(random_hex)" "$(random_hex)" "$(random_hex)" "$(random_hex)" "$(random_hex)" >"${tmp_dir}/bootstrap.json"

  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "${tmp_dir}/user-private.pem" 2>/dev/null
  openssl pkey -in "${tmp_dir}/user-private.pem" -pubout -out "${tmp_dir}/user-public.pem" 2>/dev/null
  jq -n --rawfile private "${tmp_dir}/user-private.pem" --rawfile public "${tmp_dir}/user-public.pem" \
    '{active_kid:"staging-1",private_key_pem:$private,public_key_pem:$public}' >"${tmp_dir}/user-jwt.json"

  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "${tmp_dir}/admin-private.pem" 2>/dev/null
  openssl pkey -in "${tmp_dir}/admin-private.pem" -pubout -out "${tmp_dir}/admin-public.pem" 2>/dev/null
  jq -n --rawfile private "${tmp_dir}/admin-private.pem" --rawfile public "${tmp_dir}/admin-public.pem" \
    '{active_kid:"staging-1",private_key_pem:$private,public_key_pem:$public}' >"${tmp_dir}/admin-jwt.json"

  put_if_empty "${DATABASE_RUNTIME_SECRET_ARN}" "${tmp_dir}/runtime.json"
  put_if_empty "${DATABASE_BOOTSTRAP_SECRET_ARN}" "${tmp_dir}/bootstrap.json"
  put_if_empty "${USER_JWT_SECRET_ARN}" "${tmp_dir}/user-jwt.json"
  put_if_empty "${ADMIN_JWT_SECRET_ARN}" "${tmp_dir}/admin-jwt.json"
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
  for name in PRIMARY_RDS_SECRET_ARN COORDINATE_RDS_SECRET_ARN DATABASE_RUNTIME_SECRET_ARN DATABASE_BOOTSTRAP_SECRET_ARN; do required "${name}"; done
  read_secret "${PRIMARY_RDS_SECRET_ARN}" "${tmp_dir}/primary-master.json"
  read_secret "${COORDINATE_RDS_SECRET_ARN}" "${tmp_dir}/coordinate-master.json"
  read_secret "${DATABASE_RUNTIME_SECRET_ARN}" "${tmp_dir}/runtime.json"
  read_secret "${DATABASE_BOOTSTRAP_SECRET_ARN}" "${tmp_dir}/bootstrap.json"
  prepare_pgpass "${tmp_dir}/primary-master.json" "${tmp_dir}/primary.pgpass"
  prepare_pgpass "${tmp_dir}/coordinate-master.json" "${tmp_dir}/coordinate.pgpass"

  : >"${tmp_dir}/primary-roles.sql"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_property_runtime "$(jq -r '.property_runtime' "${tmp_dir}/runtime.json")"
  property_ai_reader_password="$(jq -er '.property_ai_reader | select(type == "string" and length > 0)' "${tmp_dir}/runtime.json")" || {
    echo '상태: Fail - runtime secret에 property_ai_reader 설정이 필요합니다.' >&2
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
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_property_migrator "$(jq -r '.property_migrator' "${tmp_dir}/bootstrap.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_admin_runtime "$(jq -r '.admin_runtime' "${tmp_dir}/runtime.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_admin_migrator "$(jq -r '.admin_migrator' "${tmp_dir}/bootstrap.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_user_runtime "$(jq -r '.user_runtime' "${tmp_dir}/runtime.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_user_migrator "$(jq -r '.user_migrator' "${tmp_dir}/bootstrap.json")"
  write_role_sql "${tmp_dir}/primary-roles.sql" home_search_backup "$(jq -r '.backup' "${tmp_dir}/bootstrap.json")"

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
  write_role_sql "${tmp_dir}/coordinate-roles.sql" home_search_coordinate_reader "$(jq -r '.coordinate_reader' "${tmp_dir}/runtime.json")"
  write_role_sql "${tmp_dir}/coordinate-roles.sql" home_search_coordinate_migrator "$(jq -r '.coordinate_migrator' "${tmp_dir}/bootstrap.json")"
  write_role_sql "${tmp_dir}/coordinate-roles.sql" home_search_coordinate_importer "$(jq -r '.coordinate_importer' "${tmp_dir}/bootstrap.json")"
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
  for name in PRIMARY_RDS_SECRET_ARN DATABASE_BOOTSTRAP_SECRET_ARN; do required "${name}"; done
  read_secret "${PRIMARY_RDS_SECRET_ARN}" "${tmp_dir}/primary-master.json"
  read_secret "${DATABASE_BOOTSTRAP_SECRET_ARN}" "${tmp_dir}/bootstrap.json"
  local host port logical database migrator password pgpass sql
  host="$(jq -r '.host' "${tmp_dir}/primary-master.json")"
  port="$(jq -r '.port // 5432' "${tmp_dir}/primary-master.json")"
  for logical in property admin user; do
    case "${logical}" in
      property) database=home_search; migrator=home_search_property_migrator; password="$(jq -r '.property_migrator' "${tmp_dir}/bootstrap.json")" ;;
      admin) database=home_search_admin; migrator=home_search_admin_migrator; password="$(jq -r '.admin_migrator' "${tmp_dir}/bootstrap.json")" ;;
      user) database=home_search_user; migrator=home_search_user_migrator; password="$(jq -r '.user_migrator' "${tmp_dir}/bootstrap.json")" ;;
    esac
    pgpass="${tmp_dir}/${logical}.pgpass"
    printf '%s:%s:%s:%s:%s\n' "$(pgpass_field "${host}")" "${port}" "${database}" "${migrator}" "$(pgpass_field "${password}")" >"${pgpass}"
    sql="${tmp_dir}/${logical}-grants.sql"
    case "${logical}" in
      property) cat >"${sql}" <<'SQL'
GRANT USAGE ON SCHEMA public, reference, batch TO home_search_property_runtime, home_search_backup;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public, reference, batch TO home_search_property_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public, reference, batch TO home_search_property_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public, reference, batch TO home_search_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, reference, batch GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO home_search_property_runtime;
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
