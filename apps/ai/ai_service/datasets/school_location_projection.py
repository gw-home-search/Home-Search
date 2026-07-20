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
            name, status, road_address, lot_address, position,
            original_crs, original_x, original_y, row_reference_date,
            attributes
        )
        SELECT %s, %s, row_data ->> 'school_id', 'SCHOOL',
               row_data ->> 'school_level', row_data ->> 'school_name',
               CASE row_data ->> 'operating_status'
                   WHEN '운영' THEN 'OPEN'
                   WHEN '폐교' THEN 'CLOSED'
                   WHEN '휴교' THEN 'SUSPENDED'
                   ELSE 'UNKNOWN'
               END,
               row_data ->> 'road_address', row_data ->> 'lot_address',
               ST_SetSRID(ST_MakePoint(
                   (row_data ->> 'longitude')::double precision,
                   (row_data ->> 'latitude')::double precision
               ), 4326)::geography,
               'EPSG:4326',
               (row_data ->> 'longitude')::double precision,
               (row_data ->> 'latitude')::double precision,
               (row_data ->> 'reference_date')::date,
               jsonb_build_object(
                   'educationOfficeCode', row_data ->> 'education_office_code',
                   'educationOfficeName', row_data ->> 'education_office_name'
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
        SELECT %s, %s, '__UNKNOWN__', count(*), count(*), 0,
               count(*) FILTER (WHERE row_data ->> 'operating_status' = '운영'),
               0, count(*)
        FROM dataset_staging_row
        WHERE acquisition_id = %s AND accepted = true
        """,
        (publication_id, source_id, acquisition_id),
    )
