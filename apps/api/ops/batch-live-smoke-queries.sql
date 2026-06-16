\pset tuples_only on
\pset format unaligned
\pset fieldsep '|'

SELECT 'RTMS_RUN', count(*), COALESCE(max(created_at)::text, ''),
       count(*) FILTER (WHERE created_at >= :'started_at'::timestamptz),
       COALESCE(string_agg(DISTINCT status, ',' ORDER BY status), '')
FROM rtms_ingest_run;
