#!/usr/bin/env bash
set -Eeuo pipefail

compose_file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docker-compose.local.yml"
root="$(cd "$(dirname "${compose_file}")/.." && pwd)"
role_init_script="${root}/infra/postgres/init/10-create-service-databases-and-roles.sh"
ai_bootstrap_script="${root}/infra/postgres/bootstrap-ai-database.sh"
chatbot_compose_file="${root}/infra/docker-compose.chatbot.yml"
minio_importer_policy="${root}/infra/minio/ai-raw-importer-policy.template.json"

if grep -Fq -- '- ..:/workspace' "${compose_file}"; then
  echo "ERROR: runtime services must not mount the repository root" >&2
  exit 1
fi
if ! grep -Fq '127.0.0.1:${HOME_SEARCH_DB_PORT:-15432}:5432' "${compose_file}"; then
  echo "ERROR: local PostgreSQL must bind to loopback only" >&2
  exit 1
fi
if grep -Eq 'USER_(RUNTIME|MIGRATOR)_DB_PASSWORD:-' "${compose_file}"; then
  echo "ERROR: user database role passwords must not have repository-known defaults" >&2
  exit 1
fi
if grep -Eq 'PROPERTY_MIGRATOR_DB_PASSWORD:-' "${compose_file}"; then
  echo "ERROR: property migrator password must not have a repository-known default" >&2
  exit 1
fi
if grep -Eq 'AI_PROPERTY_READER_DB_PASSWORD:-' "${compose_file}"; then
  echo "ERROR: AI property reader password must not have a repository-known default" >&2
  exit 1
fi
if grep -Eq 'AI_DATA_(MIGRATOR|IMPORTER|RUNTIME)_DB_PASSWORD:-' "${compose_file}"; then
  echo "ERROR: AI data role passwords must not have repository-known defaults" >&2
  exit 1
fi
if grep -Eq 'POSTGRES_PASSWORD:.*HOME_SEARCH_DB_PASSWORD:-' "${compose_file}"; then
  echo "ERROR: PostgreSQL superuser password must not have a repository-known default" >&2
  exit 1
fi
if ! grep -Fq '127.0.0.1:${HOME_SEARCH_MINIO_API_PORT:-19000}:9000' "${compose_file}"; then
  echo "ERROR: local MinIO API must bind to loopback only" >&2
  exit 1
fi
if grep -Fq 'MINIO_ROOT_USER="$${AWS_ACCESS_KEY_ID}"' "${compose_file}"; then
  echo "ERROR: MinIO root and importer credentials must be separated" >&2
  exit 1
fi
if [[ ! -f "${minio_importer_policy}" ]] \
  || ! grep -Fq '"s3:GetObject"' "${minio_importer_policy}" \
  || ! grep -Fq '"s3:PutObject"' "${minio_importer_policy}" \
  || grep -Eq 'DeleteObject|DeleteBucket|s3:\*' "${minio_importer_policy}"; then
  echo "ERROR: MinIO importer policy must be limited to object get/put without delete" >&2
  exit 1
fi
if grep -Eq 'HOME_AI_RAW_S3|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY' "${chatbot_compose_file}"; then
  echo "ERROR: chatbot runtime must not receive raw S3 credentials" >&2
  exit 1
fi

if grep -Eq -- '--set=[^ ]*password' "${role_init_script}"; then
  echo "ERROR: database role password is exposed through a psql process argument" >&2
  exit 1
fi
for password_binding in \
  'property_runtime_password PROPERTY_RUNTIME_DB_PASSWORD' \
  'property_migrator_password PROPERTY_MIGRATOR_DB_PASSWORD' \
  'ai_property_reader_password AI_PROPERTY_READER_DB_PASSWORD' \
  'ai_data_migrator_password AI_DATA_MIGRATOR_DB_PASSWORD' \
  'ai_data_importer_password AI_DATA_IMPORTER_DB_PASSWORD' \
  'ai_data_runtime_password AI_DATA_RUNTIME_DB_PASSWORD' \
  'admin_runtime_password ADMIN_RUNTIME_DB_PASSWORD' \
  'admin_migrator_password ADMIN_MIGRATOR_DB_PASSWORD' \
  'user_runtime_password USER_RUNTIME_DB_PASSWORD' \
  'user_migrator_password USER_MIGRATOR_DB_PASSWORD'; do
  if ! grep -Fq "\\getenv ${password_binding}" "${role_init_script}"; then
    echo "ERROR: database role password must be read from the environment inside psql: ${password_binding}" >&2
    exit 1
  fi
done
for required_ai_data_guard in \
  'CREATE ROLE home_search_ai_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' \
  'CREATE ROLE home_search_ai_importer LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' \
  'CREATE ROLE home_search_ai_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' \
  'ALTER DATABASE home_search_ai OWNER TO home_search_ai_migrator' \
  'GRANT CONNECT ON DATABASE home_search_ai TO home_search_ai_migrator, home_search_ai_importer, home_search_ai_runtime'; do
  if ! grep -Fq "${required_ai_data_guard}" "${role_init_script}"; then
    echo "ERROR: AI data database least-privilege guard is missing: ${required_ai_data_guard}" >&2
    exit 1
  fi
done
for ai_role in home_search_ai_migrator home_search_ai_importer home_search_ai_runtime; do
  if ! grep -Fq "membership.member = '${ai_role}'::regrole" "${role_init_script}"; then
    echo "ERROR: AI data role membership revocation is missing: ${ai_role}" >&2
    exit 1
  fi
done
for required_ai_reader_guard in \
  'CREATE ROLE home_search_ai_reader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' \
  'ALTER ROLE home_search_ai_reader NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' \
  "membership.member = 'home_search_ai_reader'::regrole" \
  'GRANT CONNECT ON DATABASE home_search TO home_search_property_runtime, home_search_property_migrator, home_search_ai_reader'; do
  if ! grep -Fq "${required_ai_reader_guard}" "${role_init_script}"; then
    echo "ERROR: AI property reader least-privilege guard is missing: ${required_ai_reader_guard}" >&2
    exit 1
  fi
done
if grep -Fq 'GRANT home_search TO home_search_property_migrator' "${role_init_script}"; then
  echo "ERROR: property migrator must not inherit or SET ROLE to the bootstrap superuser" >&2
  exit 1
fi
for required_property_migrator_guard in \
  'ALTER ROLE home_search_property_migrator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' \
  "pg_has_role('home_search_property_migrator', 'home_search', 'SET')"; do
  if ! grep -Fq "${required_property_migrator_guard}" "${role_init_script}"; then
    echo "ERROR: property migrator least-privilege guard is missing: ${required_property_migrator_guard}" >&2
    exit 1
  fi
done
for database in home_search home_search_admin home_search_user home_search_ai; do
  if ! grep -Fq "REVOKE CONNECT ON DATABASE ${database} FROM PUBLIC" "${role_init_script}"; then
    echo "ERROR: PUBLIC database connect must be revoked: ${database}" >&2
    exit 1
  fi
  if ! grep -Fq "REVOKE TEMPORARY ON DATABASE ${database} FROM PUBLIC" "${role_init_script}"; then
    echo "ERROR: PUBLIC temporary object creation must be revoked: ${database}" >&2
    exit 1
  fi
done
if ! grep -Fq 'REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC' "${role_init_script}"; then
  echo 'ERROR: PUBLIC access to the postgres maintenance database must be revoked' >&2
  exit 1
fi

if [[ ! -x "${ai_bootstrap_script}" ]]; then
  echo "ERROR: existing-volume AI database bootstrap must be executable" >&2
  exit 1
fi
if grep -Eq -- '--env[=[:space:]]+[A-Z0-9_]+=' "${ai_bootstrap_script}"; then
  echo "ERROR: AI database bootstrap exposes a secret through a docker process argument" >&2
  exit 1
fi
for required_bootstrap_env in \
  '--env AI_DATA_MIGRATOR_DB_PASSWORD' \
  '--env AI_DATA_IMPORTER_DB_PASSWORD' \
  '--env AI_DATA_RUNTIME_DB_PASSWORD' \
  '--env AI_DATABASE_ONLY'; do
  if ! grep -Fq -- "${required_bootstrap_env}" "${ai_bootstrap_script}"; then
    echo "ERROR: existing-volume AI database bootstrap environment is missing: ${required_bootstrap_env}" >&2
    exit 1
  fi
done
if ! grep -A40 '^  ai:' "${chatbot_compose_file}" | grep -Fq 'HOME_AI_REFERENCE_DSN:'; then
  echo "ERROR: AI runtime reference DSN is missing" >&2
  exit 1
fi
if ! grep -A40 '^  ai:' "${chatbot_compose_file}" | grep -Fq 'HOME_AI_ENABLED_REFERENCE_CAPABILITIES:'; then
  echo "ERROR: AI runtime reference capability allowlist is missing" >&2
  exit 1
fi
if grep -A40 '^  ai:' "${chatbot_compose_file}" | grep -Eq \
    'HOME_AI_(MIGRATOR|IMPORTER)_DSN|HOME_AI_DATA_GO_KR_SERVICE_KEY|AI_DATA_(MIGRATOR|IMPORTER|RUNTIME)_DB_PASSWORD'; then
  echo "ERROR: AI runtime receives a migration, import, API key, or database role password secret" >&2
  exit 1
fi

if grep -Eq 'COORDINATE_SOURCE_DB_(USERNAME|PASSWORD):.*HOME_SEARCH_DB_' "${compose_file}"; then
  echo "ERROR: coordinate reader credential falls back to operational DB credential" >&2
  exit 1
fi
if grep -R -E 'COORDINATE_SOURCE_DB_(USERNAME|PASSWORD).*\$\{DB_(USERNAME|PASSWORD)' \
    "${root}/apps/property-data/api/src/main/resources" \
    "${root}/apps/property-data/batch/src/main/resources" \
    "${root}/apps/property-data/ops"; then
  echo "ERROR: property-data still contains an operational credential fallback for coordinate source" >&2
  exit 1
fi
if grep -A20 '^  admin-service:' "${compose_file}" | grep -Eq 'HOME_SEARCH_DB_|COORDINATE_SOURCE_DB_|MIGRATION'; then
  echo "ERROR: admin-service receives a foreign or migrator credential" >&2
  exit 1
fi
if grep -A45 '^  api:' "${compose_file}" | grep -Eq 'ADMIN_DB_|ADMIN_RUNTIME|ADMIN_MIGRATOR'; then
  echo "ERROR: property-data API receives an admin credential" >&2
  exit 1
fi
if grep -A30 '^  user-service:' "${compose_file}" | grep -Eq 'HOME_SEARCH_DB_|ADMIN_DB_|MIGRATION_DB_'; then
  echo "ERROR: user-service receives a foreign or migrator credential" >&2
  exit 1
fi
user_runtime_config="${root}/apps/user/service/app/src/main/resources/application.yml"
if ! grep -A55 '^  user-service:' "${compose_file}" | grep -Fq 'env_file:'; then
  echo "ERROR: user-service runtime environment source is missing" >&2
  exit 1
fi
for required_user_setting in \
  GOOGLE_OAUTH_CLIENT_ID KAKAO_OAUTH_CLIENT_ID NAVER_OAUTH_CLIENT_ID \
  USER_ALLOWED_ORIGIN USER_OAUTH_SUCCESS_REDIRECT USER_OAUTH_FAILURE_REDIRECT \
  USER_JWT_ACTIVE_KID USER_JWT_PRIVATE_KEY_PATH USER_JWT_ACTIVE_PUBLIC_KEY_PATH; do
  if ! grep -Fq "\${${required_user_setting}}" "${user_runtime_config}" \
      && ! grep -A55 '^  user-service:' "${compose_file}" | grep -Fq "${required_user_setting}:"; then
    echo "ERROR: user-service runtime setting is missing: ${required_user_setting}" >&2
    exit 1
  fi
done
if ! grep -A55 '^  user-service:' "${compose_file}" | grep -Fq '/run/keys/user-signing-private:ro'; then
  echo "ERROR: user signing private key must be mounted read-only" >&2
  exit 1
fi
if ! grep -A55 '^  user-service:' "${compose_file}" | grep -Fq '../apps/user/service/app/build/libs/user-service-app.jar:/app/user-service-app.jar:ro'; then
  echo "ERROR: user-service must mount only its runtime artifact" >&2
  exit 1
fi
if grep -A55 '^  user-service:' "${compose_file}" | grep -Eq '/workspace|db/migration|\.env:'; then
  echo "ERROR: user-service runtime mount exposes source, migrations, or dotenv files" >&2
  exit 1
fi
for runtime_config in \
  "${root}/apps/property-data/api/src/main/resources/application.yml" \
  "${root}/apps/property-data/batch/src/main/resources/application.yml" \
  "${root}/apps/admin/service/api/src/main/resources/application.yml"; do
  if ! grep -Eq 'flyway:[[:space:]]*$' "${runtime_config}" || ! grep -Eq 'enabled:[[:space:]]*false' "${runtime_config}"; then
    echo "ERROR: runtime Flyway auto-run guard is missing: ${runtime_config}" >&2
    exit 1
  fi
done
if ! grep -Eq 'enabled:[[:space:]]*false' "${root}/apps/user/service/app/src/main/resources/application.yml"; then
  echo "ERROR: user runtime Flyway auto-run guard is missing" >&2
  exit 1
fi
for removed_migration_module in \
  "${root}/apps/user/service/migration" \
  "${root}/apps/property-data/migration"; do
  if [[ -e "${removed_migration_module}" ]]; then
    echo "ERROR: removed migration module still exists: ${removed_migration_module}" >&2
    exit 1
  fi
done
for removed_database_source_set in \
  "${root}/apps/user/service/core/src/database" \
  "${root}/apps/user/service/core/src/databaseTest" \
  "${root}/apps/property-data/core/src/database" \
  "${root}/apps/property-data/core/src/databaseTest"; do
  if [[ -e "${removed_database_source_set}" ]]; then
    echo "ERROR: removed database source set still exists: ${removed_database_source_set}" >&2
    exit 1
  fi
done
if ! grep -Fq '../apps/user/service/db/migration/user:/flyway/sql:ro' "${compose_file}"; then
  echo "ERROR: user Flyway SQL catalog must be mounted read-only" >&2
  exit 1
fi
if ! grep -Fq '../apps/property-data/db/migration/api:/flyway/sql:ro' "${compose_file}"; then
  echo "ERROR: property Flyway SQL catalog must be mounted read-only" >&2
  exit 1
fi
if grep -A4 '^  user-flyway:' "${compose_file}" | grep -Eq 'profiles:.*user'; then
  echo "ERROR: user runtime profile must not auto-select the Flyway one-shot service" >&2
  exit 1
fi
if grep -Eq "include .*['\"]migration['\"]" \
    "${root}/apps/user/service/settings.gradle"; then
  echo "ERROR: settings.gradle still includes a migration module" >&2
  exit 1
fi
if grep -R -E 'VITE_API_SERVER_IP|localhost:8080|property-data' "${root}/apps/admin/web/src"; then
  echo "ERROR: admin-web contains a direct property-data dependency" >&2
  exit 1
fi
if grep -R -E 'ADMIN_DB_|ADMIN_SERVICE|localhost:8081' "${root}/apps/web/src"; then
  echo "ERROR: public web contains an admin-service dependency" >&2
  exit 1
fi

echo "service boundary passed: credentials are separated and runtime Flyway auto-run is disabled"
