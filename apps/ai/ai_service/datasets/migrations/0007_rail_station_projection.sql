CREATE TABLE reference_projection.rail_station_occurrence (
    publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id) ON DELETE RESTRICT,
    source_id text NOT NULL REFERENCES dataset_source(source_id),
    occurrence_id text NOT NULL,
    operator text NOT NULL,
    line_number text NOT NULL,
    line_name text NOT NULL,
    station_number text NOT NULL,
    station_name text NOT NULL,
    road_address text,
    position geography(Point, 4326) NOT NULL,
    transfer_lines text[] NOT NULL DEFAULT '{}',
    row_reference_date date NOT NULL,
    PRIMARY KEY (publication_id, occurrence_id)
);

CREATE INDEX rail_station_occurrence_position_gist
    ON reference_projection.rail_station_occurrence USING gist(position);

CREATE OR REPLACE VIEW reference_read.rail_station_occurrence AS
SELECT occurrence.publication_id, occurrence.occurrence_id,
       occurrence.operator, occurrence.line_number, occurrence.line_name,
       occurrence.station_number, occurrence.station_name,
       occurrence.road_address,
       ST_Y(occurrence.position::geometry) AS latitude,
       ST_X(occurrence.position::geometry) AS longitude,
       occurrence.position, occurrence.transfer_lines,
       occurrence.row_reference_date, metadata.dataset_version,
       metadata.source_date, metadata.published_at
FROM reference_projection.rail_station_occurrence occurrence
JOIN reference_read.active_source_metadata metadata
  ON metadata.publication_id = occurrence.publication_id
WHERE occurrence.source_id = 'transport.rail-station';

CREATE TRIGGER rail_station_occurrence_immutable
    BEFORE UPDATE OR DELETE ON reference_projection.rail_station_occurrence
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();

GRANT SELECT, INSERT ON reference_projection.rail_station_occurrence
TO home_search_ai_importer;
GRANT SELECT ON reference_read.rail_station_occurrence
TO home_search_ai_runtime;
