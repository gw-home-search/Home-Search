#!/usr/bin/env bash
set -Eeuo pipefail

: "${SOURCE_MIGRATOR_DB_PASSWORD:?SOURCE_MIGRATOR_DB_PASSWORD is required}"
: "${SOURCE_IMPORTER_DB_PASSWORD:?SOURCE_IMPORTER_DB_PASSWORD is required}"
: "${COORDINATE_READER_DB_PASSWORD:?COORDINATE_READER_DB_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres <<'SQL'
\getenv migrator_password SOURCE_MIGRATOR_DB_PASSWORD
\getenv importer_password SOURCE_IMPORTER_DB_PASSWORD
\getenv reader_password COORDINATE_READER_DB_PASSWORD
SELECT format('CREATE ROLE home_search_coordinate_migrator LOGIN PASSWORD %L', :'migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_coordinate_migrator') \gexec
SELECT format('CREATE ROLE home_search_coordinate_importer LOGIN PASSWORD %L', :'importer_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_coordinate_importer') \gexec
SELECT format('CREATE ROLE home_search_coordinate_reader LOGIN PASSWORD %L', :'reader_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_coordinate_reader') \gexec
ALTER DATABASE home_search_coordinate_source OWNER TO home_search_coordinate_migrator;
GRANT CONNECT ON DATABASE home_search_coordinate_source
    TO home_search_coordinate_migrator, home_search_coordinate_importer, home_search_coordinate_reader;
SQL

# This second connection makes the script safe for both a fresh database and a
# controlled legacy adoption. No row data is changed: only object ownership and
# grants inside the coordinate-source database are reconciled.
psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname home_search_coordinate_source <<'SQL'
GRANT USAGE, CREATE ON SCHEMA public TO home_search_coordinate_migrator;

SELECT 'ALTER SCHEMA reference OWNER TO home_search_coordinate_migrator'
WHERE to_regnamespace('reference') IS NOT NULL \gexec
SELECT 'ALTER SCHEMA geo_enrichment OWNER TO home_search_coordinate_migrator'
WHERE to_regnamespace('geo_enrichment') IS NOT NULL \gexec

DO $$
DECLARE
    object record;
BEGIN
    FOR object IN
        SELECT schemaname, tablename
        FROM pg_tables
        WHERE schemaname IN ('reference', 'geo_enrichment')
    LOOP
        EXECUTE format('ALTER TABLE %I.%I OWNER TO home_search_coordinate_migrator',
            object.schemaname, object.tablename);
    END LOOP;

    FOR object IN
        SELECT sequence_schema, sequence_name
        FROM information_schema.sequences
        WHERE sequence_schema IN ('reference', 'geo_enrichment')
    LOOP
        EXECUTE format('ALTER SEQUENCE %I.%I OWNER TO home_search_coordinate_migrator',
            object.sequence_schema, object.sequence_name);
    END LOOP;
END $$;

DO $$
BEGIN
    IF to_regnamespace('reference') IS NOT NULL THEN
        GRANT USAGE ON SCHEMA reference TO home_search_coordinate_importer, home_search_coordinate_reader;
        GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA reference TO home_search_coordinate_importer;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA reference TO home_search_coordinate_importer;
        GRANT SELECT ON ALL TABLES IN SCHEMA reference TO home_search_coordinate_reader;
        IF to_regclass('reference.flyway_schema_history') IS NOT NULL THEN
            REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE reference.flyway_schema_history FROM home_search_coordinate_importer;
            GRANT SELECT ON TABLE reference.flyway_schema_history TO home_search_coordinate_importer;
        END IF;
    END IF;
    IF to_regnamespace('geo_enrichment') IS NOT NULL THEN
        GRANT USAGE ON SCHEMA geo_enrichment TO home_search_coordinate_reader;
        GRANT SELECT ON ALL TABLES IN SCHEMA geo_enrichment TO home_search_coordinate_reader;
    END IF;
END $$;

SELECT 'ALTER DEFAULT PRIVILEGES FOR ROLE home_search_coordinate_migrator IN SCHEMA reference GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLES TO home_search_coordinate_importer'
WHERE to_regnamespace('reference') IS NOT NULL \gexec
SELECT 'ALTER DEFAULT PRIVILEGES FOR ROLE home_search_coordinate_migrator IN SCHEMA reference GRANT USAGE, SELECT ON SEQUENCES TO home_search_coordinate_importer'
WHERE to_regnamespace('reference') IS NOT NULL \gexec
SELECT 'ALTER DEFAULT PRIVILEGES FOR ROLE home_search_coordinate_migrator IN SCHEMA reference GRANT SELECT ON TABLES TO home_search_coordinate_reader'
WHERE to_regnamespace('reference') IS NOT NULL \gexec
SELECT 'ALTER DEFAULT PRIVILEGES FOR ROLE home_search_coordinate_migrator IN SCHEMA geo_enrichment GRANT SELECT ON TABLES TO home_search_coordinate_reader'
WHERE to_regnamespace('geo_enrichment') IS NOT NULL \gexec
SQL
