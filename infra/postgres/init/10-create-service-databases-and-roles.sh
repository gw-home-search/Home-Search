#!/usr/bin/env bash
set -Eeuo pipefail

: "${PROPERTY_RUNTIME_DB_PASSWORD:?PROPERTY_RUNTIME_DB_PASSWORD is required}"
: "${PROPERTY_MIGRATOR_DB_PASSWORD:?PROPERTY_MIGRATOR_DB_PASSWORD is required}"
: "${ADMIN_RUNTIME_DB_PASSWORD:?ADMIN_RUNTIME_DB_PASSWORD is required}"
: "${ADMIN_MIGRATOR_DB_PASSWORD:?ADMIN_MIGRATOR_DB_PASSWORD is required}"
: "${USER_RUNTIME_DB_PASSWORD:?USER_RUNTIME_DB_PASSWORD is required}"
: "${USER_MIGRATOR_DB_PASSWORD:?USER_MIGRATOR_DB_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres <<'SQL'
\getenv property_runtime_password PROPERTY_RUNTIME_DB_PASSWORD
\getenv property_migrator_password PROPERTY_MIGRATOR_DB_PASSWORD
\getenv admin_runtime_password ADMIN_RUNTIME_DB_PASSWORD
\getenv admin_migrator_password ADMIN_MIGRATOR_DB_PASSWORD
\getenv user_runtime_password USER_RUNTIME_DB_PASSWORD
\getenv user_migrator_password USER_MIGRATOR_DB_PASSWORD
SELECT format('CREATE ROLE home_search_property_runtime LOGIN PASSWORD %L', :'property_runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_property_runtime') \gexec
SELECT format('CREATE ROLE home_search_property_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'property_migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_property_migrator') \gexec
SELECT format('CREATE ROLE home_search_admin_runtime LOGIN PASSWORD %L', :'admin_runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_admin_runtime') \gexec
SELECT format('CREATE ROLE home_search_admin_migrator LOGIN PASSWORD %L', :'admin_migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_admin_migrator') \gexec
SELECT format('CREATE ROLE home_search_user_runtime LOGIN PASSWORD %L', :'user_runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_user_runtime') \gexec
SELECT format('CREATE ROLE home_search_user_migrator LOGIN PASSWORD %L', :'user_migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_user_migrator') \gexec
ALTER ROLE home_search_property_migrator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SELECT 'REVOKE home_search FROM home_search_property_migrator'
WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search')
  AND pg_has_role('home_search_property_migrator', 'home_search', 'MEMBER') \gexec
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search')
     AND pg_has_role('home_search_property_migrator', 'home_search', 'SET') THEN
    RAISE EXCEPTION 'property migrator must not SET ROLE to bootstrap role';
  END IF;
END
$$;
SQL

if ! psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname='home_search_admin'" | grep -q 1; then
  createdb --username "${POSTGRES_USER}" --owner home_search_admin_migrator home_search_admin
fi

if ! psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname='home_search_user'" | grep -q 1; then
  createdb --username "${POSTGRES_USER}" --owner home_search_user_migrator home_search_user
fi

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres <<'SQL'
ALTER DATABASE home_search OWNER TO home_search_property_migrator;
REVOKE CONNECT ON DATABASE home_search FROM PUBLIC;
REVOKE CONNECT ON DATABASE home_search_admin FROM PUBLIC;
REVOKE CONNECT ON DATABASE home_search_user FROM PUBLIC;
GRANT CONNECT ON DATABASE home_search TO home_search_property_runtime, home_search_property_migrator;
GRANT CONNECT ON DATABASE home_search_admin TO home_search_admin_runtime, home_search_admin_migrator;
GRANT CONNECT ON DATABASE home_search_user TO home_search_user_runtime, home_search_user_migrator;
SQL
