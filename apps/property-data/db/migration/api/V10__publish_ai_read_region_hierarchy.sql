SET LOCAL lock_timeout = '5s';

DO $precondition$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'home_search_ai_reader') THEN
        RAISE EXCEPTION
            'V10 requires the home_search_ai_reader role to be bootstrapped before migration';
    END IF;
END
$precondition$;

CREATE VIEW ai_read.region_fact
WITH (security_barrier = true)
AS
SELECT
    region.id AS region_id,
    region.parent_id AS parent_region_id,
    region.code AS region_code,
    region.name AS region_name,
    region.region_type
FROM public.region region;

COMMENT ON VIEW ai_read.region_fact IS
    'Read-only administrative region hierarchy for AI application-side region resolution';

REVOKE ALL ON ai_read.region_fact FROM PUBLIC, home_search_ai_reader;
GRANT SELECT ON ai_read.region_fact TO home_search_ai_reader;
