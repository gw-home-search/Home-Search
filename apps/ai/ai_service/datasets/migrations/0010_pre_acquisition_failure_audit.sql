CREATE OR REPLACE VIEW reference_read.acquisition_audit AS
SELECT acquisition.source_id, acquisition.acquisition_id,
       acquisition.status, acquisition.raw_row_count,
       acquisition.accepted_row_count, acquisition.rejected_row_count,
       acquisition.collected_at,
       COALESCE(
           array_agg(DISTINCT issue.reason_code)
               FILTER (WHERE issue.reason_code IS NOT NULL),
           '{}'
       ) AS reason_codes
FROM dataset_acquisition acquisition
LEFT JOIN dataset_quality_issue issue
  ON issue.acquisition_id = acquisition.acquisition_id
GROUP BY acquisition.acquisition_id
UNION ALL
SELECT item.source_id, NULL::uuid AS acquisition_id,
       item.status, 0::bigint AS raw_row_count,
       0::bigint AS accepted_row_count, 0::bigint AS rejected_row_count,
       item.started_at AS collected_at, item.reason_codes
FROM dataset_refresh_run_item item
WHERE item.acquisition_id IS NULL
  AND item.status = 'FAIL';
