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
            row_reference_date, attributes
        )
        SELECT %s, %s, row_data ->> 'facility_id',
               row_data ->> 'category', row_data ->> 'subcategory',
               row_data ->> 'name', row_data ->> 'status',
               row_data ->> 'road_address', row_data ->> 'lot_address',
               row_data ->> 'region_code', row_data ->> 'region_name',
               ST_SetSRID(ST_MakePoint(
                   (row_data ->> 'longitude')::double precision,
                   (row_data ->> 'latitude')::double precision
               ), 4326)::geography,
               row_data ->> 'original_crs',
               (row_data ->> 'original_x')::double precision,
               (row_data ->> 'original_y')::double precision,
               (row_data ->> 'reference_date')::date,
               '{}'::jsonb
        FROM dataset_staging_row
        WHERE acquisition_id = %s AND accepted = true
          AND row_data ->> 'fact_kind' = 'POINT'
        """,
        (publication_id, source_id, acquisition_id),
    )
    connection.execute(
        """
        INSERT INTO reference_projection.registry_fact(
            publication_id, source_id, fact_id, category, subcategory,
            name, status, road_address, lot_address, region_code,
            region_name, row_reference_date, attributes
        )
        SELECT %s, %s, row_data ->> 'facility_id',
               row_data ->> 'category', row_data ->> 'subcategory',
               row_data ->> 'name', row_data ->> 'status',
               row_data ->> 'road_address', row_data ->> 'lot_address',
               row_data ->> 'region_code', row_data ->> 'region_name',
               (row_data ->> 'reference_date')::date, '{}'::jsonb
        FROM dataset_staging_row
        WHERE acquisition_id = %s AND accepted = true
          AND row_data ->> 'fact_kind' = 'REGISTRY'
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
        SELECT %s, %s,
               COALESCE(NULLIF(row_data ->> 'region_code', ''), '__UNKNOWN__'),
               count(*),
               count(*) FILTER (WHERE row_data ->> 'fact_kind' = 'POINT'),
               count(*) FILTER (WHERE row_data ->> 'fact_kind' = 'REGISTRY'),
               count(*) FILTER (WHERE row_data ->> 'status' = 'OPEN'),
               0,
               count(*) FILTER (
                   WHERE NULLIF(row_data ->> 'region_code', '') IS NULL
               )
        FROM dataset_staging_row
        WHERE acquisition_id = %s AND accepted = true
        GROUP BY COALESCE(
            NULLIF(row_data ->> 'region_code', ''), '__UNKNOWN__'
        )
        """,
        (publication_id, source_id, acquisition_id),
    )
