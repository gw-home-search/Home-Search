CREATE TABLE reference.parcel_coordinate_snapshot_stage (
    run_id BIGINT NOT NULL REFERENCES reference.coordinate_snapshot_run (id) ON DELETE CASCADE,
    pnu VARCHAR(19) NOT NULL CHECK (pnu ~ '^[0-9]{19}$'),
    region_code VARCHAR(8) NOT NULL,
    chunk_code VARCHAR(8) NOT NULL,
    latitude NUMERIC(10, 7) NOT NULL CHECK (latitude BETWEEN 33 AND 39),
    longitude NUMERIC(10, 7) NOT NULL CHECK (longitude BETWEEN 124 AND 132),
    point geometry(Point, 4326) NOT NULL,
    geom geometry(MultiPolygon, 4326) NOT NULL,
    snapshot_version VARCHAR(64) NOT NULL,
    source_file TEXT NOT NULL,
    source_manifest TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, pnu),
    CONSTRAINT ck_parcel_coordinate_snapshot_stage_point_srid CHECK (ST_SRID(point) = 4326),
    CONSTRAINT ck_parcel_coordinate_snapshot_stage_geom_srid CHECK (ST_SRID(geom) = 4326),
    CONSTRAINT ck_parcel_coordinate_snapshot_stage_geom_valid CHECK (ST_IsValid(geom))
);

CREATE INDEX ix_parcel_coordinate_snapshot_stage_run_region
    ON reference.parcel_coordinate_snapshot_stage (run_id, region_code);

CREATE INDEX ix_parcel_coordinate_snapshot_stage_run_region_chunk
    ON reference.parcel_coordinate_snapshot_stage (run_id, region_code, chunk_code);

CREATE INDEX ix_parcel_coordinate_snapshot_stage_geom
    ON reference.parcel_coordinate_snapshot_stage USING GIST (geom);

CREATE INDEX ix_parcel_coordinate_snapshot_stage_point
    ON reference.parcel_coordinate_snapshot_stage USING GIST (point);

CREATE TABLE reference.coordinate_snapshot_region_checkpoint (
    run_id BIGINT NOT NULL REFERENCES reference.coordinate_snapshot_run (id) ON DELETE CASCADE,
    region_code VARCHAR(8) NOT NULL,
    snapshot_version VARCHAR(64) NOT NULL,
    source_format VARCHAR(64) NOT NULL,
    source_manifest TEXT NOT NULL,
    source_file_count INTEGER NOT NULL DEFAULT 0 CHECK (source_file_count >= 0),
    raw_feature_count BIGINT NOT NULL DEFAULT 0 CHECK (raw_feature_count >= 0),
    pnu_count BIGINT NOT NULL DEFAULT 0 CHECK (pnu_count >= 0),
    invalid_count BIGINT NOT NULL DEFAULT 0 CHECK (invalid_count >= 0),
    duplicate_pnu_count BIGINT NOT NULL DEFAULT 0 CHECK (duplicate_pnu_count >= 0),
    source_srid INTEGER NOT NULL,
    target_srid INTEGER NOT NULL,
    strict_region_match BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('STARTED', 'PASSED', 'FAILED')),
    failure_reason TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, region_code)
);

CREATE INDEX ix_coordinate_snapshot_region_checkpoint_status
    ON reference.coordinate_snapshot_region_checkpoint (run_id, status, region_code);

CREATE TABLE reference.coordinate_snapshot_stage_chunk_checkpoint (
    run_id BIGINT NOT NULL REFERENCES reference.coordinate_snapshot_run (id) ON DELETE CASCADE,
    region_code VARCHAR(8) NOT NULL,
    chunk_code VARCHAR(8) NOT NULL,
    snapshot_version VARCHAR(64) NOT NULL,
    source_format VARCHAR(64) NOT NULL,
    source_manifest TEXT NOT NULL,
    raw_feature_count BIGINT NOT NULL DEFAULT 0 CHECK (raw_feature_count >= 0),
    pnu_count BIGINT NOT NULL DEFAULT 0 CHECK (pnu_count >= 0),
    source_srid INTEGER NOT NULL,
    target_srid INTEGER NOT NULL,
    strict_region_match BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('STARTED', 'PASSED', 'FAILED')),
    failure_reason TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, region_code, chunk_code)
);

CREATE INDEX ix_coordinate_snapshot_stage_chunk_checkpoint_status
    ON reference.coordinate_snapshot_stage_chunk_checkpoint (run_id, status, region_code, chunk_code);

CREATE TABLE reference.parcel_coordinate_snapshot_publish (
    run_id BIGINT NOT NULL REFERENCES reference.coordinate_snapshot_run (id) ON DELETE CASCADE,
    pnu VARCHAR(19) NOT NULL CHECK (pnu ~ '^[0-9]{19}$'),
    region_code VARCHAR(8) NOT NULL,
    chunk_code VARCHAR(8) NOT NULL,
    latitude NUMERIC(10, 7) NOT NULL CHECK (latitude BETWEEN 33 AND 39),
    longitude NUMERIC(10, 7) NOT NULL CHECK (longitude BETWEEN 124 AND 132),
    point geometry(Point, 4326) NOT NULL,
    geom geometry(MultiPolygon, 4326) NOT NULL,
    snapshot_version VARCHAR(64) NOT NULL,
    source_file TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, pnu),
    CONSTRAINT ck_parcel_coordinate_snapshot_publish_point_srid CHECK (ST_SRID(point) = 4326),
    CONSTRAINT ck_parcel_coordinate_snapshot_publish_geom_srid CHECK (ST_SRID(geom) = 4326),
    CONSTRAINT ck_parcel_coordinate_snapshot_publish_geom_valid CHECK (ST_IsValid(geom))
);

CREATE INDEX ix_parcel_coordinate_snapshot_publish_run_region
    ON reference.parcel_coordinate_snapshot_publish (run_id, region_code);

CREATE INDEX ix_parcel_coordinate_snapshot_publish_run_region_chunk
    ON reference.parcel_coordinate_snapshot_publish (run_id, region_code, chunk_code);

CREATE INDEX ix_parcel_coordinate_snapshot_publish_geom
    ON reference.parcel_coordinate_snapshot_publish USING GIST (geom);

CREATE INDEX ix_parcel_coordinate_snapshot_publish_point
    ON reference.parcel_coordinate_snapshot_publish USING GIST (point);

CREATE TABLE reference.coordinate_snapshot_publish_checkpoint (
    run_id BIGINT NOT NULL REFERENCES reference.coordinate_snapshot_run (id) ON DELETE CASCADE,
    region_code VARCHAR(8) NOT NULL,
    source_manifest TEXT NOT NULL,
    row_count BIGINT NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('STARTED', 'PASSED', 'FAILED')),
    failure_reason TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, region_code)
);

CREATE INDEX ix_coordinate_snapshot_publish_checkpoint_status
    ON reference.coordinate_snapshot_publish_checkpoint (run_id, status, region_code);

CREATE TABLE reference.coordinate_snapshot_publish_chunk_checkpoint (
    run_id BIGINT NOT NULL REFERENCES reference.coordinate_snapshot_run (id) ON DELETE CASCADE,
    region_code VARCHAR(8) NOT NULL,
    chunk_code VARCHAR(8) NOT NULL,
    source_manifest TEXT NOT NULL,
    row_count BIGINT NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('STARTED', 'PASSED', 'FAILED')),
    failure_reason TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, region_code, chunk_code)
);

CREATE INDEX ix_coordinate_snapshot_publish_chunk_checkpoint_status
    ON reference.coordinate_snapshot_publish_chunk_checkpoint (run_id, status, region_code, chunk_code);
