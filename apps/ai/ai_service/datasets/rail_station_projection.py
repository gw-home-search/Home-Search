from __future__ import annotations

from uuid import UUID

from .projection import ProjectionConnection


def write_projection(
    connection: ProjectionConnection,
    publication_id: UUID,
    acquisition_id: UUID,
    source_id: str,
) -> None:
    connection.execute(
        """
        INSERT INTO reference_projection.rail_station_occurrence(
            publication_id, source_id, occurrence_id, operator,
            line_number, line_name, station_number, station_name,
            road_address, position, transfer_lines, row_reference_date
        )
        SELECT %s, %s, row_data ->> 'station_occurrence_id',
               row_data ->> 'operator', row_data ->> 'line_number',
               row_data ->> 'line_name', row_data ->> 'station_number',
               row_data ->> 'station_name', row_data ->> 'road_address',
               ST_SetSRID(ST_MakePoint(
                   (row_data ->> 'longitude')::double precision,
                   (row_data ->> 'latitude')::double precision
               ), 4326)::geography,
               ARRAY(
                   SELECT jsonb_array_elements_text(
                       row_data -> 'transfer_lines'
                   )
               ),
               (row_data ->> 'reference_date')::date
        FROM dataset_staging_row
        WHERE acquisition_id = %s AND accepted = true
        """,
        (publication_id, source_id, acquisition_id),
    )
    connection.execute(
        """
        INSERT INTO reference_projection.source_coverage(
            publication_id, source_id, region_code, total_count,
            spatial_count, non_spatial_count, open_count,
            stale_row_count, unknown_region_count
        )
        SELECT %s, %s, '__NATIONWIDE__', count(*), count(*), 0,
               count(*), 0, 0
        FROM dataset_staging_row
        WHERE acquisition_id = %s AND accepted = true
        """,
        (publication_id, source_id, acquisition_id),
    )
