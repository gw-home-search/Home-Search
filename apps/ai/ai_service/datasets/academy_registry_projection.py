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
        INSERT INTO reference_projection.registry_fact(
            publication_id, source_id, fact_id, category, subcategory,
            name, status, road_address, region_name, postal_code,
            normalized_name_key, normalized_address_key, observed_at,
            attributes
        )
        SELECT %s, %s, row_data ->> 'academy_id',
               'ACADEMY_REGISTRY', row_data ->> 'academy_type',
               row_data ->> 'academy_name', row_data ->> 'status',
               row_data ->> 'road_address', row_data ->> 'district_name',
               row_data ->> 'postal_code', row_data ->> 'normalized_name_key',
               row_data ->> 'normalized_address_key',
               (row_data ->> 'observed_at')::timestamptz,
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
        SELECT %s, %s, '__UNKNOWN__', count(*), 0, count(*),
               count(*) FILTER (WHERE row_data ->> 'status' = 'OPEN'),
               0, count(*)
        FROM dataset_staging_row
        WHERE acquisition_id = %s AND accepted = true
        """,
        (publication_id, source_id, acquisition_id),
    )
