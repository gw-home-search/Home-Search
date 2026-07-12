#!/usr/bin/env bash
set -Eeuo pipefail

compose_file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docker-compose.local.yml"
root="$(cd "$(dirname "${compose_file}")/.." && pwd)"
role_init_script="${root}/infra/postgres/init/10-create-service-databases-and-roles.sh"

if grep -Eq -- '--set=[^ ]*password' "${role_init_script}"; then
  echo "ERROR: database role password is exposed through a psql process argument" >&2
  exit 1
fi
for password_binding in \
  'property_runtime_password PROPERTY_RUNTIME_DB_PASSWORD' \
  'property_migrator_password PROPERTY_MIGRATOR_DB_PASSWORD' \
  'admin_runtime_password ADMIN_RUNTIME_DB_PASSWORD' \
  'admin_migrator_password ADMIN_MIGRATOR_DB_PASSWORD'; do
  if ! grep -Fq "\\getenv ${password_binding}" "${role_init_script}"; then
    echo "ERROR: database role password must be read from the environment inside psql: ${password_binding}" >&2
    exit 1
  fi
done

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
for runtime_config in \
  "${root}/apps/property-data/api/src/main/resources/application.yml" \
  "${root}/apps/property-data/batch/src/main/resources/application.yml" \
  "${root}/apps/admin/service/api/src/main/resources/application.yml"; do
  if ! grep -Eq 'flyway:[[:space:]]*$' "${runtime_config}" || ! grep -Eq 'enabled:[[:space:]]*false' "${runtime_config}"; then
    echo "ERROR: runtime Flyway auto-run guard is missing: ${runtime_config}" >&2
    exit 1
  fi
done
if grep -R -E 'VITE_API_SERVER_IP|localhost:8080|property-data' "${root}/apps/admin/web/src"; then
  echo "ERROR: admin-web contains a direct property-data dependency" >&2
  exit 1
fi
if grep -R -E 'ADMIN_DB_|ADMIN_SERVICE|localhost:8081' "${root}/apps/web/src"; then
  echo "ERROR: public web contains an admin-service dependency" >&2
  exit 1
fi

echo "service boundary passed: credentials are separated and admin API Flyway auto-run is disabled"
