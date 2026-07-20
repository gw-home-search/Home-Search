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

REVOKE ALL ON ALL TABLES IN SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON ALL TABLES IN SCHEMA ai_read FROM PUBLIC, home_search_ai_reader;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA ai_read FROM PUBLIC, home_search_ai_reader;
GRANT USAGE ON SCHEMA ai_read TO home_search_ai_reader;
GRANT SELECT ON ai_read.complex_fact, ai_read.trade_fact, ai_read.region_fact TO home_search_ai_reader;
