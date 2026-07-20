ALTER TABLE dataset_acquisition ALTER COLUMN source_date DROP NOT NULL;

CREATE SCHEMA IF NOT EXISTS reference_read;
REVOKE ALL ON SCHEMA reference_read FROM PUBLIC;

CREATE OR REPLACE VIEW reference_read.school_location_fact AS
SELECT
    row.row_data ->> 'school_id' AS school_id,
    row.row_data ->> 'school_name' AS school_name,
    row.row_data ->> 'school_level' AS school_level,
    row.row_data ->> 'operating_status' AS operating_status,
    row.row_data ->> 'road_address' AS road_address,
    row.row_data ->> 'lot_address' AS lot_address,
    row.row_data ->> 'education_office_code' AS education_office_code,
    row.row_data ->> 'education_office_name' AS education_office_name,
    (row.row_data ->> 'latitude')::double precision AS latitude,
    (row.row_data ->> 'longitude')::double precision AS longitude,
    (row.row_data ->> 'reference_date')::date AS reference_date,
    publication.dataset_version,
    publication.published_at
FROM dataset_active_snapshot active
JOIN dataset_publication publication
  ON publication.publication_id = active.publication_id
JOIN dataset_snapshot_row row
  ON row.publication_id = publication.publication_id
WHERE active.source_id = 'edu.school-location';

REVOKE ALL ON reference_read.school_location_fact FROM PUBLIC;

GRANT USAGE ON SCHEMA public TO home_search_ai_importer;
GRANT SELECT, INSERT ON
    dataset_source,
    dataset_source_contract,
    dataset_raw_object,
    dataset_acquisition,
    dataset_staging_row,
    dataset_quality_issue,
    dataset_rejected_row,
    dataset_publication,
    dataset_snapshot_row,
    dataset_active_snapshot,
    dataset_activation_event
TO home_search_ai_importer;
GRANT UPDATE ON dataset_acquisition, dataset_active_snapshot TO home_search_ai_importer;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO home_search_ai_importer;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM home_search_ai_runtime;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM home_search_ai_runtime;
GRANT USAGE ON SCHEMA reference_read TO home_search_ai_runtime;
GRANT SELECT ON reference_read.school_location_fact TO home_search_ai_runtime;
