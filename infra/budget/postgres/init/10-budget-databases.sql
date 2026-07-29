\getenv property_runtime_password PROPERTY_RUNTIME_DB_PASSWORD
\getenv property_migrator_password PROPERTY_MIGRATOR_DB_PASSWORD
\getenv property_importer_password PROPERTY_IMPORTER_DB_PASSWORD
\getenv ai_property_reader_password AI_PROPERTY_READER_DB_PASSWORD
\getenv user_runtime_password USER_RUNTIME_DB_PASSWORD
\getenv user_migrator_password USER_MIGRATOR_DB_PASSWORD
\getenv admin_runtime_password ADMIN_RUNTIME_DB_PASSWORD
\getenv admin_migrator_password ADMIN_MIGRATOR_DB_PASSWORD
\getenv ai_runtime_password AI_DATA_RUNTIME_DB_PASSWORD
\getenv ai_migrator_password AI_DATA_MIGRATOR_DB_PASSWORD
\getenv ai_importer_password AI_DATA_IMPORTER_DB_PASSWORD
\getenv backup_password BACKUP_DB_PASSWORD

SELECT format('CREATE ROLE home_search_property_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'property_runtime_password') \gexec
SELECT format('CREATE ROLE home_search_property_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'property_migrator_password') \gexec
SELECT format('CREATE ROLE home_search_property_importer LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'property_importer_password') \gexec
SELECT format('CREATE ROLE home_search_ai_reader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'ai_property_reader_password') \gexec
SELECT format('CREATE ROLE home_search_user_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'user_runtime_password') \gexec
SELECT format('CREATE ROLE home_search_user_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'user_migrator_password') \gexec
SELECT format('CREATE ROLE home_search_admin_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'admin_runtime_password') \gexec
SELECT format('CREATE ROLE home_search_admin_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'admin_migrator_password') \gexec
SELECT format('CREATE ROLE home_search_ai_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'ai_runtime_password') \gexec
SELECT format('CREATE ROLE home_search_ai_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'ai_migrator_password') \gexec
SELECT format('CREATE ROLE home_search_ai_importer LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'ai_importer_password') \gexec
SELECT format('CREATE ROLE home_search_backup LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'backup_password') \gexec

ALTER ROLE home_search_property_runtime SET statement_timeout = '5s';
ALTER ROLE home_search_property_runtime SET lock_timeout = '2s';
ALTER ROLE home_search_property_runtime SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE home_search_user_runtime SET statement_timeout = '5s';
ALTER ROLE home_search_user_runtime SET lock_timeout = '2s';
ALTER ROLE home_search_user_runtime SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE home_search_admin_runtime SET statement_timeout = '5s';
ALTER ROLE home_search_admin_runtime SET lock_timeout = '2s';
ALTER ROLE home_search_admin_runtime SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE home_search_ai_runtime SET statement_timeout = '5s';
ALTER ROLE home_search_ai_runtime SET lock_timeout = '2s';
ALTER ROLE home_search_ai_runtime SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE home_search_ai_reader SET statement_timeout = '5s';
ALTER ROLE home_search_ai_reader SET lock_timeout = '2s';
ALTER ROLE home_search_ai_reader SET idle_in_transaction_session_timeout = '30s';

SELECT 'CREATE DATABASE home_search_user OWNER home_search_user_migrator'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'home_search_user') \gexec
SELECT 'CREATE DATABASE home_search_admin OWNER home_search_admin_migrator'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'home_search_admin') \gexec
SELECT 'CREATE DATABASE home_search_ai OWNER home_search_ai_migrator'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'home_search_ai') \gexec

ALTER DATABASE home_search OWNER TO home_search_property_migrator;
REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT, TEMPORARY ON DATABASE home_search FROM PUBLIC;
REVOKE CONNECT, TEMPORARY ON DATABASE home_search_user FROM PUBLIC;
REVOKE CONNECT, TEMPORARY ON DATABASE home_search_admin FROM PUBLIC;
REVOKE CONNECT, TEMPORARY ON DATABASE home_search_ai FROM PUBLIC;
GRANT CONNECT ON DATABASE home_search TO home_search_property_runtime, home_search_property_migrator, home_search_property_importer, home_search_ai_reader, home_search_backup;
GRANT CONNECT ON DATABASE home_search_user TO home_search_user_runtime, home_search_user_migrator, home_search_backup;
GRANT CONNECT ON DATABASE home_search_admin TO home_search_admin_runtime, home_search_admin_migrator, home_search_backup;
GRANT CONNECT ON DATABASE home_search_ai TO home_search_ai_runtime, home_search_ai_migrator, home_search_ai_importer, home_search_backup;
GRANT pg_read_all_data TO home_search_backup;

\connect home_search
CREATE EXTENSION IF NOT EXISTS postgis;
\connect home_search_ai
CREATE EXTENSION IF NOT EXISTS postgis;
