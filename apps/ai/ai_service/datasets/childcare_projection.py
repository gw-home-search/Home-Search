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
        INSERT INTO reference_projection.facility_point(
            publication_id, source_id, fact_id, category, subcategory,
            name, status, road_address, lot_address, region_code,
            region_name, position, original_crs, original_x, original_y,
            row_reference_date, observed_at, attributes
        )
        SELECT %s, %s, row_data ->> 'center_id', 'CHILDCARE',
               row_data ->> 'center_type', row_data ->> 'center_name',
               row_data ->> 'operating_status',
               row_data ->> 'road_address', row_data ->> 'lot_address',
               row_data ->> 'region_code', row_data ->> 'region_name',
               ST_SetSRID(ST_MakePoint(
                   (row_data ->> 'longitude')::double precision,
                   (row_data ->> 'latitude')::double precision
               ), 4326)::geography,
               'EPSG:4326',
               (row_data ->> 'longitude')::double precision,
               (row_data ->> 'latitude')::double precision,
               (row_data ->> 'reference_date')::date,
               (row_data ->> 'observed_at')::timestamptz,
               jsonb_build_object(
                   'capacity', (row_data ->> 'capacity')::integer,
                   'address', row_data ->> 'address'
               )
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
        SELECT %s, %s, row_data ->> 'region_code', count(*), count(*), 0,
               count(*) FILTER (
                   WHERE row_data ->> 'operating_status' = 'OPEN'
               ),
               0, 0
        FROM dataset_staging_row
        WHERE acquisition_id = %s AND accepted = true
        GROUP BY row_data ->> 'region_code'
        """,
        (publication_id, source_id, acquisition_id),
    )
