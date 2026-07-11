\pset tuples_only on
\pset format unaligned
\pset fieldsep '|'

SELECT 'RTMS_RUN', count(*), COALESCE(max(created_at)::text, ''),
       count(*) FILTER (WHERE created_at >= :'started_at'::timestamptz),
       COALESCE(string_agg(DISTINCT status, ',' ORDER BY status), '')
FROM rtms_ingest_run;

SELECT 'BATCH_JOB_EXECUTION', count(*), COALESCE(max(CREATE_TIME)::text, ''),
       count(*) FILTER (WHERE CREATE_TIME >= :'started_at'::timestamp),
       COALESCE(string_agg(DISTINCT STATUS, ',' ORDER BY STATUS), '')
FROM batch.BATCH_JOB_EXECUTION;

SELECT 'BATCH_STEP_EXECUTION', count(*), COALESCE(max(CREATE_TIME)::text, ''),
       count(*) FILTER (WHERE CREATE_TIME >= :'started_at'::timestamp),
       COALESCE(string_agg(DISTINCT STATUS, ',' ORDER BY STATUS), '')
FROM batch.BATCH_STEP_EXECUTION;

SELECT 'CORRELATION',
       count(DISTINCT execution.JOB_EXECUTION_ID),
       count(run.id),
       count(*) FILTER (
           WHERE run.id IS NOT NULL
             AND run.execution_correlation_id IS NULL
       )
FROM batch.BATCH_JOB_EXECUTION_PARAMS parameter
JOIN batch.BATCH_JOB_EXECUTION execution
  ON execution.JOB_EXECUTION_ID = parameter.JOB_EXECUTION_ID
LEFT JOIN rtms_ingest_run run
  ON run.execution_correlation_id::text = parameter.PARAMETER_VALUE
WHERE parameter.PARAMETER_NAME = 'requestId'
  AND parameter.PARAMETER_VALUE = :'request_id';
