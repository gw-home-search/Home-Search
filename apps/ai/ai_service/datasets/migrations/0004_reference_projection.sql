CREATE SCHEMA IF NOT EXISTS reference_projection;
REVOKE ALL ON SCHEMA reference_projection FROM PUBLIC;

CREATE TABLE reference_projection.facility_point (
    publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id) ON DELETE RESTRICT,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    fact_id text NOT NULL,
    category text NOT NULL,
    subcategory text,
    name text NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'SUSPENDED', 'UNKNOWN')),
    road_address text,
    lot_address text,
    region_code text,
    region_name text,
    position geography(Point, 4326) NOT NULL,
    original_crs text NOT NULL,
    original_x double precision,
    original_y double precision,
    row_reference_date date,
    observed_at timestamptz,
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (publication_id, fact_id)
);

CREATE INDEX facility_point_position_gist
    ON reference_projection.facility_point USING gist(position);
CREATE INDEX facility_point_source_category_status_idx
    ON reference_projection.facility_point(source_id, category, status);
CREATE INDEX facility_point_publication_region_idx
    ON reference_projection.facility_point(publication_id, region_code);

CREATE TABLE reference_projection.registry_fact (
    publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id) ON DELETE RESTRICT,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    fact_id text NOT NULL,
    category text NOT NULL,
    subcategory text,
    name text NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'SUSPENDED', 'UNKNOWN')),
    road_address text,
    lot_address text,
    region_code text,
    region_name text,
    postal_code text,
    normalized_name_key text,
    normalized_address_key text,
    row_reference_date date,
    observed_at timestamptz,
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (publication_id, fact_id)
);

CREATE INDEX registry_fact_source_category_status_idx
    ON reference_projection.registry_fact(source_id, category, status);
CREATE INDEX registry_fact_publication_region_idx
    ON reference_projection.registry_fact(publication_id, region_code);
CREATE INDEX registry_fact_exact_match_idx
    ON reference_projection.registry_fact(
        publication_id, normalized_name_key, normalized_address_key, postal_code
    );

CREATE TABLE reference_projection.area_fact (
    publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id) ON DELETE RESTRICT,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    fact_id text NOT NULL,
    area_type text NOT NULL,
    name text NOT NULL,
    region_code text,
    region_name text,
    education_office_code text,
    education_office_name text,
    row_reference_date date,
    geometry geometry(MultiPolygon, 4326) NOT NULL,
    original_crs text NOT NULL,
    geometry_checksum char(64) NOT NULL,
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (publication_id, fact_id)
);

CREATE INDEX area_fact_geometry_gist
    ON reference_projection.area_fact USING gist(geometry);
CREATE INDEX area_fact_publication_region_idx
    ON reference_projection.area_fact(publication_id, region_code);

CREATE TABLE reference_projection.fact_relation (
    publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id) ON DELETE RESTRICT,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    from_fact_id text NOT NULL,
    to_fact_id text NOT NULL,
    relation_type text NOT NULL CHECK (relation_type IN (
        'ATTENDANCE_ZONE_SCHOOL',
        'MIDDLE_DISTRICT_SCHOOL',
        'HIGH_EQUALIZATION_GROUP_SCHOOL',
        'HIGH_NON_EQUALIZATION_REGION'
    )),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (publication_id, from_fact_id, to_fact_id, relation_type)
);

CREATE TABLE reference_projection.source_coverage (
    publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id) ON DELETE RESTRICT,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    region_code text NOT NULL,
    total_count bigint NOT NULL CHECK (total_count >= 0),
    spatial_count bigint NOT NULL CHECK (spatial_count >= 0),
    non_spatial_count bigint NOT NULL CHECK (non_spatial_count >= 0),
    open_count bigint NOT NULL CHECK (open_count >= 0),
    stale_row_count bigint NOT NULL CHECK (stale_row_count >= 0),
    unknown_region_count bigint NOT NULL CHECK (unknown_region_count >= 0),
    PRIMARY KEY (publication_id, region_code),
    CHECK (spatial_count + non_spatial_count = total_count)
);

INSERT INTO reference_projection.facility_point(
    publication_id, source_id, fact_id, category, subcategory, name, status,
    road_address, lot_address, position, original_crs, original_x, original_y,
    row_reference_date, attributes
)
SELECT publication.publication_id,
       publication.source_id,
       row.row_data ->> 'school_id',
       'SCHOOL',
       row.row_data ->> 'school_level',
       row.row_data ->> 'school_name',
       CASE row.row_data ->> 'operating_status'
           WHEN '운영' THEN 'OPEN'
           WHEN '폐교' THEN 'CLOSED'
           WHEN '휴교' THEN 'SUSPENDED'
           ELSE 'UNKNOWN'
       END,
       row.row_data ->> 'road_address',
       row.row_data ->> 'lot_address',
       ST_SetSRID(ST_MakePoint(
           (row.row_data ->> 'longitude')::double precision,
           (row.row_data ->> 'latitude')::double precision
       ), 4326)::geography,
       'EPSG:4326',
       (row.row_data ->> 'longitude')::double precision,
       (row.row_data ->> 'latitude')::double precision,
       (row.row_data ->> 'reference_date')::date,
       jsonb_build_object(
           'educationOfficeCode', row.row_data ->> 'education_office_code',
           'educationOfficeName', row.row_data ->> 'education_office_name'
       )
FROM dataset_publication publication
JOIN dataset_snapshot_row row ON row.publication_id = publication.publication_id
WHERE publication.source_id = 'edu.school-location'
ON CONFLICT DO NOTHING;

CREATE SCHEMA IF NOT EXISTS reference_read;
REVOKE ALL ON SCHEMA reference_read FROM PUBLIC;

CREATE OR REPLACE VIEW reference_read.active_source_metadata AS
SELECT publication.source_id,
       publication.publication_id,
       publication.dataset_version,
       publication.temporal_basis,
       publication.source_date,
       publication.observed_at,
       publication.published_at,
       publication.raw_checksum,
       publication.normalized_checksum,
       acquisition.contract_id,
       (contract.contract ->> 'freshness_days')::integer AS freshness_days
FROM dataset_active_snapshot active
JOIN dataset_publication publication ON publication.publication_id = active.publication_id
JOIN dataset_acquisition acquisition ON acquisition.acquisition_id = publication.acquisition_id
JOIN dataset_source_contract contract ON contract.contract_id = acquisition.contract_id;

CREATE OR REPLACE VIEW reference_read.facility_point_fact AS
SELECT point.publication_id, point.source_id, point.fact_id, point.category,
       point.subcategory, point.name, point.status, point.road_address,
       point.lot_address, point.region_code, point.region_name,
       ST_Y(point.position::geometry) AS latitude,
       ST_X(point.position::geometry) AS longitude,
       point.position, point.row_reference_date, point.observed_at,
       point.attributes, metadata.dataset_version, metadata.temporal_basis,
       metadata.source_date, metadata.observed_at AS dataset_observed_at,
       metadata.published_at, metadata.freshness_days
FROM reference_projection.facility_point point
JOIN reference_read.active_source_metadata metadata
  ON metadata.publication_id = point.publication_id;

CREATE OR REPLACE VIEW reference_read.registry_fact AS
SELECT registry.publication_id, registry.source_id, registry.fact_id,
       registry.category, registry.subcategory, registry.name, registry.status,
       registry.road_address, registry.lot_address, registry.region_code,
       registry.region_name, registry.row_reference_date, registry.observed_at,
       registry.attributes, metadata.dataset_version, metadata.temporal_basis,
       metadata.source_date, metadata.observed_at AS dataset_observed_at,
       metadata.published_at, metadata.freshness_days
FROM reference_projection.registry_fact registry
JOIN reference_read.active_source_metadata metadata
  ON metadata.publication_id = registry.publication_id;

CREATE OR REPLACE VIEW reference_read.school_zone_fact AS
SELECT area.publication_id, area.source_id, area.fact_id AS zone_id,
       area.area_type AS zone_type, area.name AS zone_name, area.region_code,
       area.region_name, area.education_office_code, area.education_office_name,
       area.row_reference_date, area.geometry, area.attributes,
       metadata.dataset_version, metadata.temporal_basis, metadata.source_date,
       metadata.observed_at AS dataset_observed_at, metadata.published_at
FROM reference_projection.area_fact area
JOIN reference_read.active_source_metadata metadata
  ON metadata.publication_id = area.publication_id
WHERE area.source_id = 'edu.school-zone';

CREATE OR REPLACE VIEW reference_read.school_zone_school_fact AS
SELECT relation.publication_id, relation.from_fact_id AS zone_id,
       relation.to_fact_id AS school_id, relation.relation_type,
       relation.attributes, metadata.dataset_version, metadata.source_date,
       metadata.published_at
FROM reference_projection.fact_relation relation
JOIN reference_read.active_source_metadata metadata
  ON metadata.publication_id = relation.publication_id
WHERE relation.source_id = 'edu.school-zone';

CREATE OR REPLACE VIEW reference_read.source_coverage AS
SELECT coverage.*, metadata.dataset_version, metadata.temporal_basis,
       metadata.source_date, metadata.observed_at, metadata.published_at
FROM reference_projection.source_coverage coverage
JOIN reference_read.active_source_metadata metadata
  ON metadata.publication_id = coverage.publication_id;

CREATE OR REPLACE VIEW reference_read.school_location_fact AS
SELECT fact.fact_id AS school_id,
       fact.name AS school_name,
       fact.subcategory AS school_level,
       CASE fact.status
           WHEN 'OPEN' THEN '운영'
           WHEN 'CLOSED' THEN '폐교'
           WHEN 'SUSPENDED' THEN '휴교'
           ELSE '미상'
       END AS operating_status,
       fact.road_address,
       fact.lot_address,
       fact.attributes ->> 'educationOfficeCode' AS education_office_code,
       fact.attributes ->> 'educationOfficeName' AS education_office_name,
       fact.latitude,
       fact.longitude,
       fact.row_reference_date AS reference_date,
       fact.dataset_version,
       fact.published_at
FROM reference_read.facility_point_fact fact
WHERE fact.source_id = 'edu.school-location';

CREATE TRIGGER facility_point_immutable
    BEFORE UPDATE OR DELETE ON reference_projection.facility_point
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();
CREATE TRIGGER registry_fact_immutable
    BEFORE UPDATE OR DELETE ON reference_projection.registry_fact
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();
CREATE TRIGGER area_fact_immutable
    BEFORE UPDATE OR DELETE ON reference_projection.area_fact
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();
CREATE TRIGGER fact_relation_immutable
    BEFORE UPDATE OR DELETE ON reference_projection.fact_relation
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();
CREATE TRIGGER source_coverage_immutable
    BEFORE UPDATE OR DELETE ON reference_projection.source_coverage
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();

GRANT USAGE ON SCHEMA reference_projection TO home_search_ai_importer;
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA reference_projection TO home_search_ai_importer;

REVOKE ALL ON ALL TABLES IN SCHEMA reference_projection FROM home_search_ai_runtime;
GRANT USAGE ON SCHEMA reference_read TO home_search_ai_runtime;
GRANT SELECT ON
    reference_read.active_source_metadata,
    reference_read.facility_point_fact,
    reference_read.registry_fact,
    reference_read.school_zone_fact,
    reference_read.school_zone_school_fact,
    reference_read.source_coverage,
    reference_read.school_location_fact
TO home_search_ai_runtime;
