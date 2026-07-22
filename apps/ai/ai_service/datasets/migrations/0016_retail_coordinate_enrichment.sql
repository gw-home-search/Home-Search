CREATE TABLE reference_projection.retail_coordinate_enrichment (
    publication_id uuid NOT NULL,
    fact_id text NOT NULL,
    pnu character varying(19) NOT NULL,
    position geography(Point, 4326) NOT NULL,
    coordinate_snapshot_version text NOT NULL,
    resolution_method text NOT NULL,
    resolved_at timestamptz NOT NULL,
    PRIMARY KEY (publication_id, fact_id),
    FOREIGN KEY (publication_id, fact_id)
        REFERENCES reference_projection.registry_fact(publication_id, fact_id)
        ON DELETE RESTRICT,
    CONSTRAINT retail_coordinate_enrichment_pnu_check
        CHECK (pnu ~ '^[0-9]{19}$'),
    CONSTRAINT retail_coordinate_enrichment_method_check
        CHECK (resolution_method = 'EXACT_LOT_PNU'),
    CONSTRAINT retail_coordinate_enrichment_point_srid_check
        CHECK (ST_SRID(position::geometry) = 4326),
    CONSTRAINT retail_coordinate_enrichment_latitude_check
        CHECK (ST_Y(position::geometry) BETWEEN 33 AND 39),
    CONSTRAINT retail_coordinate_enrichment_longitude_check
        CHECK (ST_X(position::geometry) BETWEEN 124 AND 132)
);

CREATE INDEX retail_coordinate_enrichment_position_gist
    ON reference_projection.retail_coordinate_enrichment USING gist(position);

CREATE TRIGGER retail_coordinate_enrichment_immutable
    BEFORE UPDATE OR DELETE ON reference_projection.retail_coordinate_enrichment
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();

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
  ON metadata.publication_id = point.publication_id
UNION ALL
SELECT registry.publication_id, registry.source_id, registry.fact_id,
       registry.category, registry.subcategory, registry.name, registry.status,
       registry.road_address, registry.lot_address, registry.region_code,
       registry.region_name,
       ST_Y(enrichment.position::geometry) AS latitude,
       ST_X(enrichment.position::geometry) AS longitude,
       enrichment.position, registry.row_reference_date, registry.observed_at,
       registry.attributes || jsonb_build_object(
           'coordinateResolutionMethod', enrichment.resolution_method,
           'coordinatePnu', enrichment.pnu,
           'coordinateSnapshotVersion', enrichment.coordinate_snapshot_version
       ),
       metadata.dataset_version, metadata.temporal_basis, metadata.source_date,
       metadata.observed_at AS dataset_observed_at, metadata.published_at,
       metadata.freshness_days
FROM reference_projection.retail_coordinate_enrichment enrichment
JOIN reference_projection.registry_fact registry
  ON registry.publication_id = enrichment.publication_id
 AND registry.fact_id = enrichment.fact_id
JOIN reference_read.active_source_metadata metadata
  ON metadata.publication_id = registry.publication_id;

CREATE OR REPLACE VIEW reference_read.source_coverage AS
SELECT coverage.publication_id, coverage.source_id, coverage.region_code,
       coverage.total_count,
       coverage.spatial_count + COALESCE(enrichment.count, 0) AS spatial_count,
       coverage.non_spatial_count - COALESCE(enrichment.count, 0) AS non_spatial_count,
       coverage.open_count, coverage.stale_row_count, coverage.unknown_region_count,
       metadata.dataset_version, metadata.temporal_basis, metadata.source_date,
       metadata.observed_at, metadata.published_at
FROM reference_projection.source_coverage coverage
JOIN reference_read.active_source_metadata metadata
  ON metadata.publication_id = coverage.publication_id
LEFT JOIN (
    SELECT item.publication_id, registry.region_code, count(*)::bigint AS count
    FROM reference_projection.retail_coordinate_enrichment item
    JOIN reference_projection.registry_fact registry
      ON registry.publication_id = item.publication_id
     AND registry.fact_id = item.fact_id
    GROUP BY item.publication_id, registry.region_code
) enrichment
  ON enrichment.publication_id = coverage.publication_id
 AND enrichment.region_code = coverage.region_code;

GRANT SELECT, INSERT ON reference_projection.retail_coordinate_enrichment
TO home_search_ai_importer;
REVOKE ALL ON reference_projection.retail_coordinate_enrichment
FROM home_search_ai_runtime;
GRANT SELECT ON reference_read.facility_point_fact, reference_read.source_coverage
TO home_search_ai_runtime;
