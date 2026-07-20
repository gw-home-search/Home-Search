ALTER TABLE dataset_raw_object
    ALTER COLUMN content DROP NOT NULL,
    ADD COLUMN storage_backend text,
    ADD COLUMN object_key text,
    ADD COLUMN object_version_id text,
    ADD COLUMN content_type text,
    ADD COLUMN checksum_algorithm text NOT NULL DEFAULT 'SHA256';

UPDATE dataset_raw_object
SET storage_backend = 'INLINE_DB',
    content_type = 'application/octet-stream'
WHERE storage_backend IS NULL;

ALTER TABLE dataset_raw_object
    ALTER COLUMN storage_backend SET NOT NULL,
    ADD CONSTRAINT dataset_raw_object_storage_backend_check
        CHECK (storage_backend IN ('INLINE_DB', 'S3')),
    ADD CONSTRAINT dataset_raw_object_checksum_algorithm_check
        CHECK (checksum_algorithm = 'SHA256'),
    ADD CONSTRAINT dataset_raw_object_storage_shape_check CHECK (
        (storage_backend = 'INLINE_DB'
            AND content IS NOT NULL
            AND object_key IS NULL
            AND object_version_id IS NULL
            AND octet_length(content) = byte_length)
        OR
        (storage_backend = 'S3'
            AND content IS NULL
            AND object_key IS NOT NULL
            AND content_type IS NOT NULL)
    );

ALTER TABLE dataset_raw_object
    DROP CONSTRAINT dataset_raw_object_check;

ALTER TABLE dataset_acquisition
    DROP CONSTRAINT dataset_acquisition_source_id_checksum_contract_id_key,
    DROP CONSTRAINT dataset_acquisition_status_check,
    ADD COLUMN temporal_basis text,
    ADD COLUMN observed_at timestamptz,
    ADD COLUMN normalized_checksum char(64),
    ADD COLUMN normalization_schema_version text;

UPDATE dataset_acquisition
SET temporal_basis = 'SOURCE_DATE'
WHERE temporal_basis IS NULL;

ALTER TABLE dataset_acquisition
    ALTER COLUMN temporal_basis SET NOT NULL,
    ADD CONSTRAINT dataset_acquisition_temporal_basis_check
        CHECK (temporal_basis IN ('SOURCE_DATE', 'OBSERVED_AT')),
    ADD CONSTRAINT dataset_acquisition_temporal_value_check CHECK (
        (temporal_basis = 'SOURCE_DATE' AND source_date IS NOT NULL AND observed_at IS NULL)
        OR
        (temporal_basis = 'OBSERVED_AT' AND source_date IS NULL AND observed_at IS NOT NULL)
    ),
    ADD CONSTRAINT dataset_acquisition_status_check CHECK (status IN (
        'INCOMPLETE', 'ACQUIRED', 'PARSE_FAILED', 'QUALITY_FAILED', 'VALIDATED',
        'NO_CHANGE', 'PUBLISHED', 'PUBLICATION_FAILED'
    )),
    ADD CONSTRAINT dataset_acquisition_source_raw_unique UNIQUE (source_id, checksum);

DROP INDEX dataset_staging_row_source_key_unique;
CREATE INDEX dataset_staging_row_source_key_idx
    ON dataset_staging_row(acquisition_id, source_key)
    WHERE source_key IS NOT NULL;

ALTER TABLE dataset_rejected_row
    ALTER COLUMN row_data DROP NOT NULL,
    ADD COLUMN source_key text,
    ADD COLUMN field_name text,
    ADD COLUMN evidence jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE dataset_publication
    ALTER COLUMN source_date DROP NOT NULL,
    ADD COLUMN raw_checksum char(64),
    ADD COLUMN normalized_checksum char(64),
    ADD COLUMN temporal_basis text,
    ADD COLUMN observed_at timestamptz,
    ADD COLUMN normalization_schema_version text;

UPDATE dataset_publication publication
SET raw_checksum = acquisition.checksum,
    normalized_checksum = acquisition.checksum,
    temporal_basis = 'SOURCE_DATE',
    normalization_schema_version = 'legacy-v1'
FROM dataset_acquisition acquisition
WHERE acquisition.acquisition_id = publication.acquisition_id;

ALTER TABLE dataset_publication
    ALTER COLUMN raw_checksum SET NOT NULL,
    ALTER COLUMN normalized_checksum SET NOT NULL,
    ALTER COLUMN temporal_basis SET NOT NULL,
    ALTER COLUMN normalization_schema_version SET NOT NULL,
    ADD CONSTRAINT dataset_publication_temporal_basis_check
        CHECK (temporal_basis IN ('SOURCE_DATE', 'OBSERVED_AT')),
    ADD CONSTRAINT dataset_publication_temporal_value_check CHECK (
        (temporal_basis = 'SOURCE_DATE' AND source_date IS NOT NULL AND observed_at IS NULL)
        OR
        (temporal_basis = 'OBSERVED_AT' AND source_date IS NULL AND observed_at IS NOT NULL)
    );

CREATE TABLE dataset_refresh_run (
    refresh_run_id uuid PRIMARY KEY,
    profile text NOT NULL,
    trigger_type text NOT NULL CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    status text NOT NULL CHECK (status IN ('RUNNING', 'PASS', 'PARTIAL', 'FAIL'))
);

CREATE TABLE dataset_refresh_run_item (
    refresh_run_id uuid NOT NULL REFERENCES dataset_refresh_run(refresh_run_id) ON DELETE RESTRICT,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    acquisition_id uuid REFERENCES dataset_acquisition(acquisition_id),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    status text NOT NULL CHECK (status IN ('RUNNING', 'PASS', 'NO_CHANGE', 'FAIL')),
    reason_codes text[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (refresh_run_id, source_id)
);

CREATE OR REPLACE FUNCTION reject_publication_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'dataset publications and projection rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER dataset_publication_immutable
    BEFORE UPDATE OR DELETE ON dataset_publication
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();

CREATE TRIGGER dataset_snapshot_row_immutable
    BEFORE UPDATE OR DELETE ON dataset_snapshot_row
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();

GRANT SELECT, INSERT ON dataset_refresh_run, dataset_refresh_run_item
TO home_search_ai_importer;
GRANT UPDATE ON dataset_refresh_run, dataset_refresh_run_item
TO home_search_ai_importer;
