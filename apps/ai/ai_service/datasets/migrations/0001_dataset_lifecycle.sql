CREATE TABLE dataset_source (
    source_id text PRIMARY KEY,
    provider text NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE dataset_source_contract (
    contract_id uuid PRIMARY KEY,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    contract_fingerprint char(64) NOT NULL,
    contract jsonb NOT NULL,
    registered_at timestamptz NOT NULL,
    UNIQUE (source_id, contract_fingerprint)
);

CREATE TABLE dataset_raw_object (
    checksum char(64) PRIMARY KEY,
    content bytea NOT NULL,
    byte_length bigint NOT NULL CHECK (byte_length >= 0),
    collected_at timestamptz NOT NULL,
    CHECK (octet_length(content) = byte_length)
);

CREATE TABLE dataset_acquisition (
    acquisition_id uuid PRIMARY KEY,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    contract_id uuid NOT NULL REFERENCES dataset_source_contract(contract_id),
    checksum char(64) NOT NULL REFERENCES dataset_raw_object(checksum),
    source_date date NOT NULL,
    collected_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN (
        'ACQUIRED', 'VALIDATED', 'QUALITY_FAILED', 'PUBLISHED', 'PUBLICATION_FAILED'
    )),
    raw_row_count bigint NOT NULL DEFAULT 0 CHECK (raw_row_count >= 0),
    accepted_row_count bigint NOT NULL DEFAULT 0 CHECK (accepted_row_count >= 0),
    rejected_row_count bigint NOT NULL DEFAULT 0 CHECK (rejected_row_count >= 0),
    validated_at timestamptz,
    UNIQUE (source_id, checksum, contract_id)
);

CREATE TABLE dataset_staging_row (
    acquisition_id uuid NOT NULL REFERENCES dataset_acquisition(acquisition_id) ON DELETE RESTRICT,
    row_number bigint NOT NULL CHECK (row_number > 0),
    row_data jsonb NOT NULL,
    source_key text,
    accepted boolean NOT NULL,
    PRIMARY KEY (acquisition_id, row_number)
);

CREATE UNIQUE INDEX dataset_staging_row_source_key_unique
    ON dataset_staging_row(acquisition_id, source_key)
    WHERE source_key IS NOT NULL;

CREATE TABLE dataset_quality_issue (
    quality_issue_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    acquisition_id uuid NOT NULL REFERENCES dataset_acquisition(acquisition_id) ON DELETE RESTRICT,
    reason_code text NOT NULL,
    severity text NOT NULL CHECK (severity IN ('BLOCKING', 'WARNING')),
    row_number bigint,
    details jsonb NOT NULL,
    recorded_at timestamptz NOT NULL
);

CREATE INDEX dataset_quality_issue_acquisition_idx
    ON dataset_quality_issue(acquisition_id, reason_code);

CREATE TABLE dataset_rejected_row (
    acquisition_id uuid NOT NULL REFERENCES dataset_acquisition(acquisition_id) ON DELETE RESTRICT,
    row_number bigint NOT NULL CHECK (row_number > 0),
    reason_code text NOT NULL,
    row_data jsonb NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (acquisition_id, row_number, reason_code)
);

CREATE TABLE dataset_publication (
    publication_id uuid PRIMARY KEY,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    acquisition_id uuid NOT NULL UNIQUE REFERENCES dataset_acquisition(acquisition_id),
    dataset_version text NOT NULL,
    source_date date NOT NULL,
    published_at timestamptz NOT NULL,
    UNIQUE (source_id, dataset_version)
);

CREATE TABLE dataset_snapshot_row (
    publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id) ON DELETE RESTRICT,
    source_key text NOT NULL,
    row_data jsonb NOT NULL,
    PRIMARY KEY (publication_id, source_key)
);

CREATE TABLE dataset_active_snapshot (
    source_id text PRIMARY KEY REFERENCES dataset_source(source_id),
    publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id),
    activated_at timestamptz NOT NULL
);

CREATE TABLE dataset_activation_event (
    activation_event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id),
    action text NOT NULL CHECK (action IN ('PUBLISH', 'ROLLBACK')),
    activated_at timestamptz NOT NULL
);

CREATE INDEX dataset_activation_event_source_idx
    ON dataset_activation_event(source_id, activation_event_id);

CREATE OR REPLACE FUNCTION reject_raw_object_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'dataset raw objects are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER dataset_raw_object_immutable
    BEFORE UPDATE OR DELETE ON dataset_raw_object
    FOR EACH ROW EXECUTE FUNCTION reject_raw_object_mutation();
