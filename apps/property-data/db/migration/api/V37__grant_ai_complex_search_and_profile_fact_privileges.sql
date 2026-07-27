SET LOCAL lock_timeout = '5s';

DO $precondition$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'home_search_ai_reader') THEN
        RAISE EXCEPTION
            'V37 requires the home_search_ai_reader role to be bootstrapped before migration';
    END IF;
END
$precondition$;

REVOKE ALL ON ALL TABLES IN SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON ai_read.complex_search_fact, ai_read.complex_profile_fact
FROM PUBLIC, home_search_ai_reader;

GRANT USAGE ON SCHEMA ai_read TO home_search_ai_reader;
GRANT SELECT ON ai_read.complex_search_fact, ai_read.complex_profile_fact
TO home_search_ai_reader;
