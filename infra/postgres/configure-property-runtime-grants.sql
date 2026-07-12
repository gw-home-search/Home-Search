\set ON_ERROR_STOP on

SELECT current_database() = 'home_search' AS expected_database \gset
\if :expected_database
\else
  \echo 'ERROR: configure-property-runtime-grants.sql must run against home_search'
  \quit 2
\endif

GRANT USAGE ON SCHEMA public, reference, batch TO home_search_property_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public, reference, batch TO home_search_property_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public, reference, batch TO home_search_property_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, reference, batch
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO home_search_property_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, reference, batch
    GRANT USAGE, SELECT ON SEQUENCES TO home_search_property_runtime;
