-- Compatibility migration for the controlled legacy baseline.
-- It is additive only: existing snapshot rows and legacy column definitions are preserved.

CREATE TABLE IF NOT EXISTS reference.coordinate_snapshot_region_checkpoint (
    run_id bigint NOT NULL REFERENCES reference.coordinate_snapshot_run(id) ON DELETE CASCADE,
    region_code character varying(8) NOT NULL,
    snapshot_version character varying(64) NOT NULL,
    source_format character varying(64) NOT NULL,
    source_manifest text NOT NULL,
    source_file_count integer NOT NULL DEFAULT 0,
    raw_feature_count bigint NOT NULL DEFAULT 0,
    pnu_count bigint NOT NULL DEFAULT 0,
    invalid_count bigint NOT NULL DEFAULT 0,
    duplicate_pnu_count bigint NOT NULL DEFAULT 0,
    source_srid integer NOT NULL,
    target_srid integer NOT NULL DEFAULT 4326,
    strict_region_match boolean NOT NULL DEFAULT true,
    status character varying(32) NOT NULL DEFAULT 'STARTED',
    failure_reason text,
    started_at timestamp with time zone NOT NULL DEFAULT now(),
    finished_at timestamp with time zone,
    PRIMARY KEY (run_id, region_code),
    CONSTRAINT coordinate_snapshot_region_checkpoint_region_code_check CHECK ((region_code)::text ~ '^[0-9]{2}$'::text),
    CONSTRAINT coordinate_snapshot_region_checkpoint_status_check CHECK ((status)::text = ANY (ARRAY['STARTED', 'PASSED', 'FAILED']::text[])),
    CONSTRAINT coordinate_snapshot_region_checkpoint_target_srid_check CHECK (target_srid = 4326)
);

CREATE TABLE IF NOT EXISTS reference.coordinate_snapshot_stage_chunk_checkpoint (
    run_id bigint NOT NULL REFERENCES reference.coordinate_snapshot_run(id) ON DELETE CASCADE,
    region_code character varying(8) NOT NULL,
    chunk_code character varying(8) NOT NULL,
    snapshot_version character varying(64) NOT NULL,
    source_format character varying(64) NOT NULL,
    source_manifest text NOT NULL,
    raw_feature_count bigint NOT NULL DEFAULT 0,
    pnu_count bigint NOT NULL DEFAULT 0,
    source_srid integer NOT NULL,
    target_srid integer NOT NULL DEFAULT 4326,
    strict_region_match boolean NOT NULL DEFAULT true,
    status character varying(32) NOT NULL DEFAULT 'STARTED',
    failure_reason text,
    started_at timestamp with time zone NOT NULL DEFAULT now(),
    finished_at timestamp with time zone,
    PRIMARY KEY (run_id, region_code, chunk_code),
    CONSTRAINT coordinate_snapshot_stage_chunk_checkpoint_region_code_check CHECK ((region_code)::text ~ '^[0-9]{2}$'::text),
    CONSTRAINT coordinate_snapshot_stage_chunk_checkpoint_status_check CHECK ((status)::text = ANY (ARRAY['STARTED', 'PASSED', 'FAILED']::text[])),
    CONSTRAINT coordinate_snapshot_stage_chunk_checkpoint_target_srid_check CHECK (target_srid = 4326)
);

CREATE TABLE IF NOT EXISTS reference.coordinate_snapshot_publish_checkpoint (
    run_id bigint NOT NULL REFERENCES reference.coordinate_snapshot_run(id) ON DELETE CASCADE,
    region_code character varying(8) NOT NULL,
    source_manifest text NOT NULL,
    row_count bigint NOT NULL DEFAULT 0,
    status character varying(32) NOT NULL DEFAULT 'STARTED',
    failure_reason text,
    started_at timestamp with time zone NOT NULL DEFAULT now(),
    finished_at timestamp with time zone,
    PRIMARY KEY (run_id, region_code),
    CONSTRAINT coordinate_snapshot_publish_checkpoint_region_code_check CHECK ((region_code)::text ~ '^[0-9]{2}$'::text),
    CONSTRAINT coordinate_snapshot_publish_checkpoint_status_check CHECK ((status)::text = ANY (ARRAY['STARTED', 'PASSED', 'FAILED']::text[]))
);

CREATE TABLE IF NOT EXISTS reference.coordinate_snapshot_publish_chunk_checkpoint (
    run_id bigint NOT NULL REFERENCES reference.coordinate_snapshot_run(id) ON DELETE CASCADE,
    region_code character varying(8) NOT NULL,
    chunk_code character varying(8) NOT NULL,
    source_manifest text NOT NULL,
    row_count bigint NOT NULL DEFAULT 0,
    status character varying(32) NOT NULL DEFAULT 'STARTED',
    failure_reason text,
    started_at timestamp with time zone NOT NULL DEFAULT now(),
    finished_at timestamp with time zone,
    PRIMARY KEY (run_id, region_code, chunk_code),
    CONSTRAINT coordinate_snapshot_publish_chunk_checkpoint_region_code_check CHECK ((region_code)::text ~ '^[0-9]{2}$'::text),
    CONSTRAINT coordinate_snapshot_publish_chunk_checkpoint_status_check CHECK ((status)::text = ANY (ARRAY['STARTED', 'PASSED', 'FAILED']::text[]))
);

CREATE TABLE IF NOT EXISTS reference.parcel_coordinate_snapshot_stage (
    run_id bigint NOT NULL REFERENCES reference.coordinate_snapshot_run(id) ON DELETE CASCADE,
    pnu character varying(19) NOT NULL,
    region_code character varying(10) NOT NULL,
    chunk_code character varying(8) NOT NULL,
    latitude numeric(10,7) NOT NULL,
    longitude numeric(10,7) NOT NULL,
    point public.geometry(Point,4326) NOT NULL,
    geom public.geometry(MultiPolygon,4326) NOT NULL,
    snapshot_version character varying(64) NOT NULL,
    source_file text NOT NULL,
    source_manifest text NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, pnu),
    CONSTRAINT parcel_coordinate_snapshot_stage_pnu_check CHECK ((pnu)::text ~ '^[0-9]{19}$'::text),
    CONSTRAINT parcel_coordinate_snapshot_stage_latitude_check CHECK (latitude BETWEEN 33 AND 39),
    CONSTRAINT parcel_coordinate_snapshot_stage_longitude_check CHECK (longitude BETWEEN 124 AND 132),
    CONSTRAINT ck_parcel_coordinate_snapshot_stage_point_srid CHECK (public.ST_SRID(point) = 4326),
    CONSTRAINT ck_parcel_coordinate_snapshot_stage_geom_srid CHECK (public.ST_SRID(geom) = 4326),
    CONSTRAINT ck_parcel_coordinate_snapshot_stage_geom_valid CHECK (public.ST_IsValid(geom))
);

CREATE TABLE IF NOT EXISTS reference.parcel_coordinate_snapshot_publish (
    run_id bigint NOT NULL REFERENCES reference.coordinate_snapshot_run(id) ON DELETE CASCADE,
    pnu character varying(19) NOT NULL,
    region_code character varying(10) NOT NULL,
    chunk_code character varying(8) NOT NULL,
    latitude numeric(10,7) NOT NULL,
    longitude numeric(10,7) NOT NULL,
    point public.geometry(Point,4326) NOT NULL,
    geom public.geometry(MultiPolygon,4326) NOT NULL,
    snapshot_version character varying(64) NOT NULL,
    source_file text NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, pnu),
    CONSTRAINT parcel_coordinate_snapshot_publish_pnu_check CHECK ((pnu)::text ~ '^[0-9]{19}$'::text),
    CONSTRAINT parcel_coordinate_snapshot_publish_latitude_check CHECK (latitude BETWEEN 33 AND 39),
    CONSTRAINT parcel_coordinate_snapshot_publish_longitude_check CHECK (longitude BETWEEN 124 AND 132),
    CONSTRAINT ck_parcel_coordinate_snapshot_publish_point_srid CHECK (public.ST_SRID(point) = 4326),
    CONSTRAINT ck_parcel_coordinate_snapshot_publish_geom_srid CHECK (public.ST_SRID(geom) = 4326),
    CONSTRAINT ck_parcel_coordinate_snapshot_publish_geom_valid CHECK (public.ST_IsValid(geom))
);

CREATE INDEX IF NOT EXISTS ix_coordinate_snapshot_region_checkpoint_status ON reference.coordinate_snapshot_region_checkpoint (run_id, status, region_code);
CREATE INDEX IF NOT EXISTS ix_coordinate_snapshot_stage_chunk_checkpoint_status ON reference.coordinate_snapshot_stage_chunk_checkpoint (run_id, status, region_code, chunk_code);
CREATE INDEX IF NOT EXISTS ix_coordinate_snapshot_publish_checkpoint_status ON reference.coordinate_snapshot_publish_checkpoint (run_id, status, region_code);
CREATE INDEX IF NOT EXISTS ix_coordinate_snapshot_publish_chunk_checkpoint_status ON reference.coordinate_snapshot_publish_chunk_checkpoint (run_id, status, region_code, chunk_code);
CREATE INDEX IF NOT EXISTS ix_parcel_coordinate_snapshot_stage_run_region ON reference.parcel_coordinate_snapshot_stage (run_id, region_code);
CREATE INDEX IF NOT EXISTS ix_parcel_coordinate_snapshot_stage_run_region_chunk ON reference.parcel_coordinate_snapshot_stage (run_id, region_code, chunk_code);
CREATE INDEX IF NOT EXISTS ix_parcel_coordinate_snapshot_stage_point ON reference.parcel_coordinate_snapshot_stage USING gist (point);
CREATE INDEX IF NOT EXISTS ix_parcel_coordinate_snapshot_stage_geom ON reference.parcel_coordinate_snapshot_stage USING gist (geom);
CREATE INDEX IF NOT EXISTS ix_parcel_coordinate_snapshot_publish_run_region ON reference.parcel_coordinate_snapshot_publish (run_id, region_code);
CREATE INDEX IF NOT EXISTS ix_parcel_coordinate_snapshot_publish_run_region_chunk ON reference.parcel_coordinate_snapshot_publish (run_id, region_code, chunk_code);
CREATE INDEX IF NOT EXISTS ix_parcel_coordinate_snapshot_publish_point ON reference.parcel_coordinate_snapshot_publish USING gist (point);
CREATE INDEX IF NOT EXISTS ix_parcel_coordinate_snapshot_publish_geom ON reference.parcel_coordinate_snapshot_publish USING gist (geom);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_coordinate_importer') THEN
        GRANT USAGE, CREATE ON SCHEMA reference TO home_search_coordinate_importer;
        GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA reference TO home_search_coordinate_importer;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA reference TO home_search_coordinate_importer;
        ALTER DEFAULT PRIVILEGES IN SCHEMA reference GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLES TO home_search_coordinate_importer;
        ALTER DEFAULT PRIVILEGES IN SCHEMA reference GRANT USAGE, SELECT ON SEQUENCES TO home_search_coordinate_importer;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='home_search_coordinate_reader') THEN
        GRANT USAGE ON SCHEMA reference TO home_search_coordinate_reader;
        GRANT SELECT ON ALL TABLES IN SCHEMA reference TO home_search_coordinate_reader;
        ALTER DEFAULT PRIVILEGES IN SCHEMA reference GRANT SELECT ON TABLES TO home_search_coordinate_reader;
    END IF;
END $$;
