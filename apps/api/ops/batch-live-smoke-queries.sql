\pset tuples_only on
\pset format unaligned
\pset fieldsep '|'

SELECT 'RTMS_RUN', count(*), COALESCE(max(created_at)::text, ''),
       count(*) FILTER (WHERE created_at >= :'started_at'::timestamptz),
       COALESCE(string_agg(DISTINCT status, ',' ORDER BY status), '')
FROM rtms_ingest_run;

SELECT 'NEWS_RUN', count(*), COALESCE(max(created_at)::text, ''),
       count(*) FILTER (WHERE created_at >= :'started_at'::timestamptz),
       COALESCE(string_agg(DISTINCT status, ',' ORDER BY status), '')
FROM news_collection_run;

SELECT 'NEWS_OBSERVATION', count(*), COALESCE(max(created_at)::text, ''),
       count(*) FILTER (WHERE created_at >= :'started_at'::timestamptz),
       COALESCE(string_agg(DISTINCT ingest_status, ',' ORDER BY ingest_status), '')
FROM news_article_observation;

SELECT 'NEWS_KEYWORD', count(*) FILTER (WHERE enabled), COALESCE(max(updated_at)::text, ''),
       count(*) FILTER (WHERE enabled AND next_due_at <= now()),
       COALESCE(string_agg(DISTINCT keyword_type, ',' ORDER BY keyword_type), '')
FROM news_collection_keyword;
