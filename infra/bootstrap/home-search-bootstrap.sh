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
    jq -n --arg active_kid "${HOME_BOOTSTRAP_KEY_ID:-staging-1}" \
      --rawfile private "${tmp_dir}/admin-private-candidate.pem" \
      '{active_kid:$active_kid,private_key_pem:$private}' >"${tmp_dir}/admin-jwt-candidate.json"
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
  jq -n --arg active_kid "${HOME_BOOTSTRAP_KEY_ID:-staging-1}" \
    --rawfile private "${tmp_dir}/user-private.pem" --rawfile public "${tmp_dir}/user-public.pem" \
    '{active_kid:$active_kid,private_key_pem:$private,public_key_pem:$public}' >"${tmp_dir}/user-jwt.json"

  put_if_empty "${USER_JWT_SECRET_ARN}" "${tmp_dir}/user-jwt.json"
  reconcile_admin_jwt_secrets
  echo "상태: Pass - ${HOME_BOOTSTRAP_ENVIRONMENT:-staging} secret bootstrap을 idempotent하게 확인했습니다."
}

write_ai_database_candidate() {
  local output="$1" host="$2" username="$3" password
  password="$(random_hex)"
  jq -n --arg password "${password}" \
    --arg dsn "host=${host} port=5432 dbname=home_search_ai user=${username} password=${password} sslmode=require" \
    '{password:$password,dsn:$dsn}' >"${output}"
}

production_secret_bootstrap() {
  local name spec environment_name file_stem username
  local property_reader_password ai_runtime_password
  for name in \
    AI_MIGRATOR_DB_SECRET_ARN AI_IMPORTER_DB_SECRET_ARN AI_RUNTIME_DB_SECRET_ARN \
    AI_RUNTIME_SECRET_ARN PROPERTY_DB_HOST AI_DB_HOST; do
    required "${name}"
  done

  HOME_BOOTSTRAP_KEY_ID=production-1 HOME_BOOTSTRAP_ENVIRONMENT=production secret_bootstrap

  for spec in \
    AI_MIGRATOR_DB_SECRET_ARN:ai-migrator:home_search_ai_migrator \
    AI_IMPORTER_DB_SECRET_ARN:ai-importer:home_search_ai_importer \
    AI_RUNTIME_DB_SECRET_ARN:ai-runtime-db:home_search_ai_runtime; do
    environment_name="${spec%%:*}"
    spec="${spec#*:}"
    file_stem="${spec%%:*}"
    username="${spec#*:}"
    write_ai_database_candidate "${tmp_dir}/${file_stem}-candidate.json" "${AI_DB_HOST}" "${username}"
    put_if_empty "${!environment_name}" "${tmp_dir}/${file_stem}-candidate.json"
  done

  read_secret "${PROPERTY_AI_READER_DB_SECRET_ARN}" "${tmp_dir}/production-property-ai-reader.json"
  read_secret "${AI_RUNTIME_DB_SECRET_ARN}" "${tmp_dir}/production-ai-runtime-db.json"
  property_reader_password="$(jq -er '.password | select(type == "string" and length > 0)' \
    "${tmp_dir}/production-property-ai-reader.json")"
  ai_runtime_password="$(jq -er '.password | select(type == "string" and length > 0)' \
    "${tmp_dir}/production-ai-runtime-db.json")"
  jq -n \
    --arg property_dsn "host=${PROPERTY_DB_HOST} port=5432 dbname=home_search user=home_search_ai_reader password=${property_reader_password} sslmode=require" \
    --arg reference_dsn "host=${AI_DB_HOST} port=5432 dbname=home_search_ai user=home_search_ai_runtime password=${ai_runtime_password} sslmode=require" \
    '{property_dsn:$property_dsn,reference_dsn:$reference_dsn}' >"${tmp_dir}/ai-runtime-candidate.json"
  put_if_empty "${AI_RUNTIME_SECRET_ARN}" "${tmp_dir}/ai-runtime-candidate.json"

  for spec in \
    AI_MIGRATOR_DB_SECRET_ARN:password:dsn \
    AI_IMPORTER_DB_SECRET_ARN:password:dsn \
    AI_RUNTIME_DB_SECRET_ARN:password:dsn \
    AI_RUNTIME_SECRET_ARN:property_dsn:reference_dsn; do
    environment_name="${spec%%:*}"
    spec="${spec#*:}"
    read_secret "${!environment_name}" "${tmp_dir}/production-secret-validation.json"
    while [[ -n "${spec}" ]]; do
      name="${spec%%:*}"
      jq -e --arg key "${name}" '.[$key] | type == "string" and length > 0' \
        "${tmp_dir}/production-secret-validation.json" >/dev/null || {
        echo "상태: Fail - ${environment_name}에 ${name} 설정이 필요합니다." >&2
        exit 1
      }
      [[ "${spec}" == *:* ]] || break
      spec="${spec#*:}"
    done
  done
  echo '상태: Pass - production DB/JWT secret과 AI DSN을 idempotent하게 확인했습니다.'
}

validate_secret_keys() {
  local environment_name="$1" keys="$2" key
  required "${environment_name}"
  if ! read_secret_if_present "${!environment_name}" "${tmp_dir}/readiness-secret.json"; then
    echo "상태: Fail - ${environment_name} 값이 아직 주입되지 않았습니다." >&2
    exit 1
  fi
  for key in ${keys}; do
    jq -e --arg key "${key}" '.[$key] | type == "string" and length > 0' \
      "${tmp_dir}/readiness-secret.json" >/dev/null || {
      echo "상태: Fail - ${environment_name}에 필수 key가 없습니다: ${key}" >&2
      exit 1
    }
  done
}

production_secret_readiness() {
  local spec environment_name keys
  for spec in \
    PROPERTY_RUNTIME_DB_SECRET_ARN:password PROPERTY_AI_READER_DB_SECRET_ARN:password \
    ADMIN_RUNTIME_DB_SECRET_ARN:password USER_RUNTIME_DB_SECRET_ARN:password \
    COORDINATE_READER_DB_SECRET_ARN:password PROPERTY_MIGRATOR_DB_SECRET_ARN:password \
    ADMIN_MIGRATOR_DB_SECRET_ARN:password USER_MIGRATOR_DB_SECRET_ARN:password \
    COORDINATE_MIGRATOR_DB_SECRET_ARN:password COORDINATE_IMPORTER_DB_SECRET_ARN:password \
    AI_MIGRATOR_DB_SECRET_ARN:'password dsn' AI_IMPORTER_DB_SECRET_ARN:'password dsn' \
    AI_RUNTIME_DB_SECRET_ARN:'password dsn' AI_RUNTIME_SECRET_ARN:'property_dsn reference_dsn' \
    BACKUP_DB_SECRET_ARN:password USER_JWT_SECRET_ARN:'active_kid private_key_pem public_key_pem' \
    ADMIN_JWT_SECRET_ARN:'active_kid private_key_pem' \
    ADMIN_JWT_PUBLIC_SECRET_ARN:'active_kid public_key_pem' \
    OPENAI_PROVIDER_SECRET_ARN:'api_key primary_model secondary_model' \
    OAUTH_PROVIDERS_SECRET_ARN:'google_client_id google_client_secret kakao_client_id kakao_client_secret naver_client_id naver_client_secret' \
    KAKAO_LOCAL_PROVIDER_SECRET_ARN:rest_api_key \
    PUBLIC_DATA_PROVIDERS_SECRET_ARN:apt_service_key; do
    environment_name="${spec%%:*}"
    keys="${spec#*:}"
    validate_secret_keys "${environment_name}" "${keys}"
  done
  echo '상태: Pass - production runtime/provider secret readiness를 확인했습니다.'
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
  printf 'ALTER ROLE %s NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;\n' "${role}" >>"${file}"
  printf 'DO $$\nDECLARE parent_role name;\nBEGIN\n  FOR parent_role IN\n    SELECT parent.rolname\n    FROM pg_auth_members membership\n    JOIN pg_roles parent ON parent.oid = membership.roleid\n    WHERE membership.member = '\''%s'\''::regrole\n  LOOP\n    EXECUTE format('\''REVOKE %%I FROM %s'\'', parent_role);\n  END LOOP;\nEND\n$$;\n' \
    "${role}" "${role}" >>"${file}"
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

production_db_bootstrap() {
  local name spec environment_name file_name logical master_file roles_file grants_file
  local host port username password_file
  for name in \
    PROPERTY_RDS_SECRET_ARN ADMIN_RDS_SECRET_ARN USER_RDS_SECRET_ARN AI_RDS_SECRET_ARN COORDINATE_RDS_SECRET_ARN \
    PROPERTY_RUNTIME_DB_SECRET_ARN PROPERTY_AI_READER_DB_SECRET_ARN ADMIN_RUNTIME_DB_SECRET_ARN \
    USER_RUNTIME_DB_SECRET_ARN COORDINATE_READER_DB_SECRET_ARN PROPERTY_MIGRATOR_DB_SECRET_ARN \
    ADMIN_MIGRATOR_DB_SECRET_ARN USER_MIGRATOR_DB_SECRET_ARN AI_MIGRATOR_DB_SECRET_ARN \
    AI_IMPORTER_DB_SECRET_ARN AI_RUNTIME_DB_SECRET_ARN COORDINATE_MIGRATOR_DB_SECRET_ARN \
    COORDINATE_IMPORTER_DB_SECRET_ARN BACKUP_DB_SECRET_ARN; do
    required "${name}"
  done

  for spec in \
    PROPERTY_RDS_SECRET_ARN:property-master ADMIN_RDS_SECRET_ARN:admin-master \
    USER_RDS_SECRET_ARN:user-master AI_RDS_SECRET_ARN:ai-master \
    COORDINATE_RDS_SECRET_ARN:coordinate-master \
    PROPERTY_RUNTIME_DB_SECRET_ARN:property-runtime PROPERTY_AI_READER_DB_SECRET_ARN:property-ai-reader \
    ADMIN_RUNTIME_DB_SECRET_ARN:admin-runtime USER_RUNTIME_DB_SECRET_ARN:user-runtime \
    COORDINATE_READER_DB_SECRET_ARN:coordinate-reader PROPERTY_MIGRATOR_DB_SECRET_ARN:property-migrator \
    ADMIN_MIGRATOR_DB_SECRET_ARN:admin-migrator USER_MIGRATOR_DB_SECRET_ARN:user-migrator \
    AI_MIGRATOR_DB_SECRET_ARN:ai-migrator AI_IMPORTER_DB_SECRET_ARN:ai-importer \
    AI_RUNTIME_DB_SECRET_ARN:ai-runtime COORDINATE_MIGRATOR_DB_SECRET_ARN:coordinate-migrator \
    COORDINATE_IMPORTER_DB_SECRET_ARN:coordinate-importer BACKUP_DB_SECRET_ARN:backup; do
    environment_name="${spec%%:*}"
    file_name="${spec#*:}"
    read_secret "${!environment_name}" "${tmp_dir}/${file_name}.json"
  done

  for logical in property admin user ai coordinate; do
    master_file="${tmp_dir}/${logical}-master.json"
    password_file="${tmp_dir}/${logical}-master.pgpass"
    prepare_pgpass "${master_file}" "${password_file}"
    host="$(jq -er '.host | select(type == "string" and length > 0)' "${master_file}")"
    port="$(jq -er '.port // 5432 | select(type == "number")' "${master_file}")"
    username="$(jq -er '.username | select(type == "string" and length > 0)' "${master_file}")"
    roles_file="${tmp_dir}/${logical}-production-roles.sql"
    grants_file="${tmp_dir}/${logical}-production-grants.sql"
    : >"${roles_file}"
    case "${logical}" in
      property)
        write_role_sql "${roles_file}" home_search_property_runtime "$(jq -er '.password' "${tmp_dir}/property-runtime.json")"
        write_role_sql "${roles_file}" home_search_ai_reader "$(jq -er '.password' "${tmp_dir}/property-ai-reader.json")"
        write_role_sql "${roles_file}" home_search_property_migrator "$(jq -er '.password' "${tmp_dir}/property-migrator.json")"
        write_role_sql "${roles_file}" home_search_backup "$(jq -er '.password' "${tmp_dir}/backup.json")"
        printf '%s\n' \
          'ALTER ROLE home_search_ai_reader NOINHERIT;' \
          'ALTER DATABASE home_search OWNER TO home_search_property_migrator;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE home_search FROM PUBLIC;' \
          'GRANT CONNECT ON DATABASE home_search TO home_search_property_runtime, home_search_property_migrator, home_search_ai_reader, home_search_backup;' \
          >"${grants_file}"
        ;;
      admin)
        write_role_sql "${roles_file}" home_search_admin_runtime "$(jq -er '.password' "${tmp_dir}/admin-runtime.json")"
        write_role_sql "${roles_file}" home_search_admin_migrator "$(jq -er '.password' "${tmp_dir}/admin-migrator.json")"
        write_role_sql "${roles_file}" home_search_backup "$(jq -er '.password' "${tmp_dir}/backup.json")"
        printf '%s\n' \
          'ALTER DATABASE home_search_admin OWNER TO home_search_admin_migrator;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE home_search_admin FROM PUBLIC;' \
          'GRANT CONNECT ON DATABASE home_search_admin TO home_search_admin_runtime, home_search_admin_migrator, home_search_backup;' \
          >"${grants_file}"
        ;;
      user)
        write_role_sql "${roles_file}" home_search_user_runtime "$(jq -er '.password' "${tmp_dir}/user-runtime.json")"
        write_role_sql "${roles_file}" home_search_user_migrator "$(jq -er '.password' "${tmp_dir}/user-migrator.json")"
        write_role_sql "${roles_file}" home_search_backup "$(jq -er '.password' "${tmp_dir}/backup.json")"
        printf '%s\n' \
          'ALTER DATABASE home_search_user OWNER TO home_search_user_migrator;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE home_search_user FROM PUBLIC;' \
          'GRANT CONNECT ON DATABASE home_search_user TO home_search_user_runtime, home_search_user_migrator, home_search_backup;' \
          >"${grants_file}"
        ;;
      ai)
        write_role_sql "${roles_file}" home_search_ai_migrator "$(jq -er '.password' "${tmp_dir}/ai-migrator.json")"
        write_role_sql "${roles_file}" home_search_ai_importer "$(jq -er '.password' "${tmp_dir}/ai-importer.json")"
        write_role_sql "${roles_file}" home_search_ai_runtime "$(jq -er '.password' "${tmp_dir}/ai-runtime.json")"
        write_role_sql "${roles_file}" home_search_backup "$(jq -er '.password' "${tmp_dir}/backup.json")"
        printf '%s\n' \
          'ALTER ROLE home_search_ai_migrator NOINHERIT;' \
          'ALTER ROLE home_search_ai_importer NOINHERIT;' \
          'ALTER ROLE home_search_ai_runtime NOINHERIT;' \
          'ALTER DATABASE home_search_ai OWNER TO home_search_ai_migrator;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE home_search_ai FROM PUBLIC;' \
          'GRANT CONNECT ON DATABASE home_search_ai TO home_search_ai_migrator, home_search_ai_importer, home_search_ai_runtime, home_search_backup;' \
          >"${grants_file}"
        ;;
      coordinate)
        write_role_sql "${roles_file}" home_search_coordinate_reader "$(jq -er '.password' "${tmp_dir}/coordinate-reader.json")"
        write_role_sql "${roles_file}" home_search_coordinate_migrator "$(jq -er '.password' "${tmp_dir}/coordinate-migrator.json")"
        write_role_sql "${roles_file}" home_search_coordinate_importer "$(jq -er '.password' "${tmp_dir}/coordinate-importer.json")"
        write_role_sql "${roles_file}" home_search_backup "$(jq -er '.password' "${tmp_dir}/backup.json")"
        printf '%s\n' \
          'ALTER ROLE home_search_coordinate_reader NOINHERIT;' \
          'ALTER ROLE home_search_coordinate_importer NOINHERIT;' \
          'ALTER DATABASE home_search_coordinate_source OWNER TO home_search_coordinate_migrator;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC;' \
          'REVOKE CONNECT, TEMPORARY ON DATABASE home_search_coordinate_source FROM PUBLIC;' \
          'GRANT CONNECT ON DATABASE home_search_coordinate_source TO home_search_coordinate_migrator, home_search_coordinate_importer, home_search_coordinate_reader, home_search_backup;' \
          >"${grants_file}"
        ;;
    esac
    PGPASSFILE="${password_file}" psql -X -q -v ON_ERROR_STOP=1 -h "${host}" -p "${port}" -U "${username}" -d postgres -f "${roles_file}" >/dev/null
    PGPASSFILE="${password_file}" psql -X -q -v ON_ERROR_STOP=1 -h "${host}" -p "${port}" -U "${username}" -d postgres -f "${grants_file}" >/dev/null
    if [[ "${logical}" == 'ai' ]]; then
      PGPASSFILE="${password_file}" psql -X -q -v ON_ERROR_STOP=1 -h "${host}" -p "${port}" -U "${username}" -d home_search_ai \
        -c 'CREATE EXTENSION IF NOT EXISTS postgis' >/dev/null
    else
      case "${logical}" in
        property) name=home_search ;;
        admin) name=home_search_admin ;;
        user) name=home_search_user ;;
        coordinate) name=home_search_coordinate_source ;;
      esac
      PGPASSFILE="${password_file}" psql -X -q -v ON_ERROR_STOP=1 -h "${host}" -p "${port}" -U "${username}" -d "${name}" -c 'SELECT 1' >/dev/null
    fi
  done
  echo '상태: Pass - Production 5개 RDS role/database 경계를 멱등 적용했습니다.'
}

runtime_grants() {
  local name direct_passwords=false
  if [[ -n "${PROPERTY_MIGRATOR_DB_PASSWORD:-}" && -n "${ADMIN_MIGRATOR_DB_PASSWORD:-}" && -n "${USER_MIGRATOR_DB_PASSWORD:-}" ]]; then
    direct_passwords=true
  else
    for name in PROPERTY_MIGRATOR_DB_SECRET_ARN ADMIN_MIGRATOR_DB_SECRET_ARN USER_MIGRATOR_DB_SECRET_ARN; do
      required "${name}"
    done
  fi
  if [[ -n "${PROPERTY_DB_HOST:-}${ADMIN_DB_HOST:-}${USER_DB_HOST:-}" ]]; then
    for name in PROPERTY_DB_HOST PROPERTY_DB_PORT ADMIN_DB_HOST ADMIN_DB_PORT USER_DB_HOST USER_DB_PORT; do
      required "${name}"
    done
  else
    for name in PRIMARY_DB_HOST PRIMARY_DB_PORT; do
      required "${name}"
    done
  fi
  if [[ -n "${AI_MIGRATOR_DB_PASSWORD:-}" ]]; then
    for name in AI_DB_HOST AI_DB_PORT; do required "${name}"; done
  fi
  if [[ "${direct_passwords}" == 'false' ]]; then
    read_secret "${PROPERTY_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/property-migrator.json"
    read_secret "${ADMIN_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/admin-migrator.json"
    read_secret "${USER_MIGRATOR_DB_SECRET_ARN}" "${tmp_dir}/user-migrator.json"
  fi
  local host port logical database migrator password pgpass sql
  local -a logical_databases=(property admin user)
  if [[ -n "${AI_MIGRATOR_DB_PASSWORD:-}" ]]; then
    logical_databases+=(ai)
  fi
  for logical in "${logical_databases[@]}"; do
    case "${logical}" in
      property)
        host="${PROPERTY_DB_HOST:-${PRIMARY_DB_HOST}}"
        port="${PROPERTY_DB_PORT:-${PRIMARY_DB_PORT}}"
        database=home_search
        migrator=home_search_property_migrator
        if [[ "${direct_passwords}" == 'true' ]]; then password="${PROPERTY_MIGRATOR_DB_PASSWORD}"; else password="$(jq -er '.password' "${tmp_dir}/property-migrator.json")"; fi
        ;;
      admin)
        host="${ADMIN_DB_HOST:-${PRIMARY_DB_HOST}}"
        port="${ADMIN_DB_PORT:-${PRIMARY_DB_PORT}}"
        database=home_search_admin
        migrator=home_search_admin_migrator
        if [[ "${direct_passwords}" == 'true' ]]; then password="${ADMIN_MIGRATOR_DB_PASSWORD}"; else password="$(jq -er '.password' "${tmp_dir}/admin-migrator.json")"; fi
        ;;
      user)
        host="${USER_DB_HOST:-${PRIMARY_DB_HOST}}"
        port="${USER_DB_PORT:-${PRIMARY_DB_PORT}}"
        database=home_search_user
        migrator=home_search_user_migrator
        if [[ "${direct_passwords}" == 'true' ]]; then password="${USER_MIGRATOR_DB_PASSWORD}"; else password="$(jq -er '.password' "${tmp_dir}/user-migrator.json")"; fi
        ;;
      ai)
        host="${AI_DB_HOST:-${PRIMARY_DB_HOST}}"
        port="${AI_DB_PORT:-${PRIMARY_DB_PORT}}"
        database=home_search_ai
        migrator=home_search_ai_migrator
        password="${AI_MIGRATOR_DB_PASSWORD}"
        ;;
    esac
    pgpass="${tmp_dir}/${logical}.pgpass"
    printf '%s:%s:%s:%s:%s\n' "$(pgpass_field "${host}")" "${port}" "${database}" "${migrator}" "$(pgpass_field "${password}")" >"${pgpass}"
    sql="${tmp_dir}/${logical}-grants.sql"
    case "${logical}" in
      property) cat >"${sql}" <<'SQL'
GRANT USAGE ON SCHEMA public, batch TO home_search_property_runtime, home_search_backup;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public, batch TO home_search_property_runtime;
REVOKE DELETE ON ALL TABLES IN SCHEMA public, batch FROM home_search_property_runtime;
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
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public, batch TO home_search_property_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public, batch TO home_search_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, batch GRANT SELECT, INSERT, UPDATE ON TABLES TO home_search_property_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, batch REVOKE DELETE ON TABLES FROM home_search_property_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, batch GRANT USAGE, SELECT ON SEQUENCES TO home_search_property_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, batch GRANT SELECT ON TABLES TO home_search_backup;
REVOKE ALL ON ALL TABLES IN SCHEMA public, batch FROM home_search_ai_reader;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public, batch FROM home_search_ai_reader;
REVOKE ALL ON SCHEMA public, batch FROM home_search_ai_reader;
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
      ai) cat >"${sql}" <<'SQL'
GRANT USAGE ON SCHEMA reference_read TO home_search_ai_runtime, home_search_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA reference_read TO home_search_ai_runtime, home_search_backup;
GRANT USAGE ON SCHEMA public, reference_projection TO home_search_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public, reference_projection TO home_search_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA reference_read GRANT SELECT ON TABLES TO home_search_ai_runtime, home_search_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, reference_projection GRANT SELECT ON TABLES TO home_search_backup;
SQL
        ;;
    esac
    PGSSLMODE=require PGPASSFILE="${pgpass}" psql -X -q -v ON_ERROR_STOP=1 \
      -h "${host}" -p "${port}" -U "${migrator}" -d "${database}" -f "${sql}" >/dev/null
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

budget_parameter_name() {
  required BUDGET_PARAMETER_PREFIX
  printf '%s/%s' "${BUDGET_PARAMETER_PREFIX%/}" "$1"
}

budget_read_parameter() {
  local suffix="$1" destination="$2" parameter_name response
  parameter_name="$(budget_parameter_name "${suffix}")"
  response="${tmp_dir}/budget-parameter-response.json"
  aws ssm get-parameter \
    --name "${parameter_name}" \
    --with-decryption \
    --output json >"${response}"
  jq -er '.Parameter.Value | select(type == "string" and length > 0)' \
    "${response}" >"${destination}"
}

budget_put_if_unset() {
  local suffix="$1" value_file="$2" current parameter_name request
  current="${tmp_dir}/budget-current"
  budget_read_parameter "${suffix}" "${current}"
  if ! grep -qx 'UNSET' "${current}"; then
    return 0
  fi
  parameter_name="$(budget_parameter_name "${suffix}")"
  request="${tmp_dir}/budget-put-parameter.json"
  jq -n --arg name "${parameter_name}" --rawfile value "${value_file}" \
    '{Name:$name,Type:"SecureString",Value:$value,Overwrite:true}' >"${request}"
  aws ssm put-parameter --cli-input-json "file://${request}" >/dev/null
}

budget_write_random_parameter() {
  local suffix="$1" candidate="${tmp_dir}/budget-random"
  random_hex >"${candidate}"
  budget_put_if_unset "${suffix}" "${candidate}"
}

budget_reconcile_key_pair() {
  local namespace="$1" private_suffix="$2" public_suffix="$3"
  local private_current="${tmp_dir}/${namespace}-private-current"
  local public_current="${tmp_dir}/${namespace}-public-current"
  budget_read_parameter "${private_suffix}" "${private_current}"
  budget_read_parameter "${public_suffix}" "${public_current}"
  if grep -qx 'UNSET' "${private_current}" && ! grep -qx 'UNSET' "${public_current}"; then
    echo "상태: Fail - ${namespace} public key만 설정되어 private key를 안전하게 복구할 수 없습니다." >&2
    exit 1
  fi
  if ! grep -qx 'UNSET' "${private_current}" && grep -qx 'UNSET' "${public_current}"; then
    echo "상태: Fail - ${namespace} private key만 설정되어 public key drift를 자동 수정하지 않습니다." >&2
    exit 1
  fi
  if grep -qx 'UNSET' "${private_current}"; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
      -out "${tmp_dir}/${namespace}-private-candidate.pem" 2>/dev/null
    openssl pkey -in "${tmp_dir}/${namespace}-private-candidate.pem" -pubout \
      -out "${tmp_dir}/${namespace}-public-candidate.pem" 2>/dev/null
    budget_put_if_unset "${private_suffix}" "${tmp_dir}/${namespace}-private-candidate.pem"
    budget_put_if_unset "${public_suffix}" "${tmp_dir}/${namespace}-public-candidate.pem"
  fi
}

budget_secret_bootstrap() {
  local suffix property_password ai_password ai_migrator_password
  required BUDGET_PARAMETER_PREFIX
  for suffix in \
    postgres/superuser-password \
    postgres/property-runtime-password \
    postgres/property-migrator-password \
    postgres/property-importer-password \
    postgres/property-ai-reader-password \
    postgres/user-runtime-password \
    postgres/user-migrator-password \
    postgres/admin-runtime-password \
    postgres/admin-migrator-password \
    postgres/ai-runtime-password \
    postgres/ai-migrator-password \
    postgres/ai-importer-password \
    postgres/backup-password \
    valkey/admin-password \
    valkey/property-password \
    valkey/bff-password \
    edge/certificate-passphrase; do
    budget_write_random_parameter "${suffix}"
  done

  budget_reconcile_key_pair user user/jwt-private-key-pem user/jwt-public-key-pem
  budget_reconcile_key_pair admin admin/jwt-private-key-pem admin/jwt-public-key-pem

  budget_read_parameter postgres/property-ai-reader-password "${tmp_dir}/property-reader-password"
  budget_read_parameter postgres/ai-runtime-password "${tmp_dir}/ai-runtime-password"
  budget_read_parameter postgres/ai-migrator-password "${tmp_dir}/ai-migrator-password"
  property_password="$(<"${tmp_dir}/property-reader-password")"
  ai_password="$(<"${tmp_dir}/ai-runtime-password")"
  ai_migrator_password="$(<"${tmp_dir}/ai-migrator-password")"
  printf 'postgresql://home_search_ai_reader:%s@%s:%s/home_search?sslmode=require' \
    "${property_password}" "${BUDGET_DB_HOST:-172.31.255.1}" "${BUDGET_DB_PORT:-15432}" \
    >"${tmp_dir}/property-dsn"
  printf 'postgresql://home_search_ai_runtime:%s@%s:%s/home_search_ai?sslmode=require' \
    "${ai_password}" "${BUDGET_DB_HOST:-172.31.255.1}" "${BUDGET_DB_PORT:-15432}" \
    >"${tmp_dir}/reference-dsn"
  printf 'postgresql://home_search_ai_migrator:%s@%s:%s/home_search_ai?sslmode=require' \
    "${ai_migrator_password}" "${BUDGET_DB_HOST:-172.31.255.1}" "${BUDGET_DB_PORT:-15432}" \
    >"${tmp_dir}/migrator-dsn"
  budget_put_if_unset ai/property-dsn "${tmp_dir}/property-dsn"
  budget_put_if_unset ai/reference-dsn "${tmp_dir}/reference-dsn"
  budget_put_if_unset ai/migrator-dsn "${tmp_dir}/migrator-dsn"

  echo '상태: Pass - budget-production 생성형 SSM parameter를 최초 1회 멱등 설정했습니다.'
}

budget_secret_readiness() {
  local suffix value_file="${tmp_dir}/budget-readiness-value" missing=()
  required BUDGET_PARAMETER_PREFIX
  for suffix in \
    postgres/superuser-password \
    postgres/property-runtime-password \
    postgres/property-migrator-password \
    postgres/property-importer-password \
    postgres/property-ai-reader-password \
    postgres/user-runtime-password \
    postgres/user-migrator-password \
    postgres/admin-runtime-password \
    postgres/admin-migrator-password \
    postgres/ai-runtime-password \
    postgres/ai-migrator-password \
    postgres/ai-importer-password \
    postgres/backup-password \
    valkey/admin-password \
    valkey/property-password \
    valkey/bff-password \
    edge/certificate-passphrase \
    user/jwt-private-key-pem \
    user/jwt-public-key-pem \
    admin/jwt-private-key-pem \
    admin/jwt-public-key-pem \
    ai/property-dsn \
    ai/reference-dsn \
    ai/migrator-dsn \
    property/kakao-rest-api-key \
    user/oauth/google-client-id \
    user/oauth/google-client-secret \
    user/oauth/kakao-client-id \
    user/oauth/kakao-client-secret \
    user/oauth/naver-client-id \
    user/oauth/naver-client-secret \
    ai/openai-api-key \
    ai/openai-primary-model \
    ai/openai-secondary-model; do
    budget_read_parameter "${suffix}" "${value_file}"
    if grep -qx 'UNSET' "${value_file}"; then
      missing+=("${suffix}")
    fi
  done
  if (( ${#missing[@]} > 0 )); then
    printf '상태: Fail - readiness에 필요한 SSM parameter가 UNSET입니다: %s\n' "${missing[*]}" >&2
    exit 1
  fi
  echo '상태: Pass - budget-production runtime parameter readiness를 확인했습니다.'
}

budget_importer_grants() {
  local name logical database migrator importer host port password sql pgpass schema table relation
  local allowlist="${BUDGET_DATA_ONLY_ALLOWLIST:-/opt/home-search/infra/migration/data-only-allowlist.json}"
  for name in PROPERTY_DB_HOST PROPERTY_DB_PORT PROPERTY_MIGRATOR_DB_PASSWORD \
    AI_DB_HOST AI_DB_PORT AI_MIGRATOR_DB_PASSWORD; do
    required "${name}"
  done
  [[ -f "${allowlist}" && ! -L "${allowlist}" ]] || {
    echo '상태: Fail - reviewed data-only allowlist 파일이 필요합니다.' >&2
    exit 1
  }
  jq -e '
    .formatVersion == 1
    and ([.datasets[].logicalDatabase] | unique) == ["property", "reference"]
    and ([.datasets[] | select(.logicalDatabase == "property")] | length) == 46
    and ([.datasets[] | select(.logicalDatabase == "reference")] | length) == 21
    and all(.datasets[]; (.table | startswith("building_register") | not))
  ' \
    "${allowlist}" >/dev/null

  for logical in property reference; do
    case "${logical}" in
      property)
        database=home_search
        migrator=home_search_property_migrator
        importer=home_search_property_importer
        host="${PROPERTY_DB_HOST}"
        port="${PROPERTY_DB_PORT}"
        password="${PROPERTY_MIGRATOR_DB_PASSWORD}"
        ;;
      reference)
        database=home_search_ai
        migrator=home_search_ai_migrator
        importer=home_search_ai_importer
        host="${AI_DB_HOST}"
        port="${AI_DB_PORT}"
        password="${AI_MIGRATOR_DB_PASSWORD}"
        ;;
    esac

    sql="${tmp_dir}/budget-${logical}-importer-grants.sql"
    pgpass="${tmp_dir}/budget-${logical}-importer.pgpass"
    printf '%s:%s:%s:%s:%s\n' \
      "$(pgpass_field "${host}")" "${port}" "${database}" "${migrator}" \
      "$(pgpass_field "${password}")" >"${pgpass}"
    chmod 0600 "${pgpass}"
    {
      printf 'GRANT TEMPORARY ON DATABASE %s TO %s;\n' "${database}" "${importer}"
      printf 'CREATE SCHEMA IF NOT EXISTS home_migration;\n'
      printf 'REVOKE ALL ON SCHEMA home_migration FROM PUBLIC;\n'
      printf 'GRANT USAGE, CREATE ON SCHEMA home_migration TO %s;\n' "${importer}"
      while IFS= read -r schema; do
        [[ "${schema}" =~ ^[a-z_][a-z0-9_]*$ ]] || {
          echo '상태: Fail - allowlist schema identifier가 안전하지 않습니다.' >&2
          exit 1
        }
        printf 'GRANT USAGE ON SCHEMA "%s" TO %s;\n' "${schema}" "${importer}"
      done < <(jq -r --arg logical "${logical}" \
        '[.datasets[] | select(.logicalDatabase == $logical) | .schema] | unique[]' "${allowlist}")
      while IFS=$'\t' read -r schema table; do
        [[ "${schema}" =~ ^[a-z_][a-z0-9_]*$ && "${table}" =~ ^[a-z_][a-z0-9_]*$ ]] || {
          echo '상태: Fail - allowlist table identifier가 안전하지 않습니다.' >&2
          exit 1
        }
        relation="\"${schema}\".\"${table}\""
        printf 'GRANT SELECT, INSERT, UPDATE ON TABLE %s TO %s;\n' "${relation}" "${importer}"
        printf "SELECT format('GRANT USAGE, SELECT, UPDATE ON SEQUENCE %%s TO %s', pg_get_serial_sequence('%s', 'id')) WHERE pg_get_serial_sequence('%s', 'id') IS NOT NULL \\gexec\n" \
          "${importer}" "${relation}" "${relation}"
      done < <(jq -r --arg logical "${logical}" \
        '.datasets[] | select(.logicalDatabase == $logical) | [.schema,.table] | @tsv' "${allowlist}")
    } >"${sql}"
    PGSSLMODE=require PGPASSFILE="${pgpass}" psql -X -q -v ON_ERROR_STOP=1 \
      -h "${host}" -p "${port}" -U "${migrator}" -d "${database}" -f "${sql}" >/dev/null
  done
  echo '상태: Pass - reviewed data-only allowlist table에만 importer 권한을 적용했습니다.'
}

case "${1:-}" in
  secret-bootstrap) secret_bootstrap ;;
  production-secret-bootstrap) production_secret_bootstrap ;;
  production-secret-readiness) production_secret_readiness ;;
  db-bootstrap) db_bootstrap ;;
  production-db-bootstrap) production_db_bootstrap ;;
  runtime-grants) runtime_grants ;;
  materialize-keys) materialize_keys ;;
  budget-secret-bootstrap) budget_secret_bootstrap ;;
  budget-secret-readiness) budget_secret_readiness ;;
  budget-importer-grants) budget_importer_grants ;;
  *) echo '사용법: home-search-bootstrap secret-bootstrap|production-secret-bootstrap|production-secret-readiness|db-bootstrap|production-db-bootstrap|runtime-grants|materialize-keys|budget-secret-bootstrap|budget-secret-readiness|budget-importer-grants' >&2; exit 64 ;;
esac
