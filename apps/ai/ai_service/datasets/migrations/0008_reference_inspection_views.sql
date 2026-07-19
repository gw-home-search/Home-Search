CREATE OR REPLACE VIEW reference_read.source_status AS
SELECT source.source_id, metadata.dataset_version,
       metadata.temporal_basis, metadata.source_date,
       metadata.observed_at, metadata.published_at
FROM dataset_source source
LEFT JOIN reference_read.active_source_metadata metadata
  ON metadata.source_id = source.source_id;

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
GROUP BY acquisition.acquisition_id;

GRANT SELECT ON reference_read.source_status,
                reference_read.acquisition_audit
TO home_search_ai_runtime;
