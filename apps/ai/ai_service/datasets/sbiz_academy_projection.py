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
            position, original_crs, original_x, original_y,
            observed_at, attributes
        )
        SELECT %s, %s, row_data ->> 'store_id', 'ACADEMY',
               row_data ->> 'small_category_code', row_data ->> 'name',
               'OPEN', row_data ->> 'road_address', row_data ->> 'lot_address',
               row_data ->> 'region_code',
               ST_SetSRID(ST_MakePoint(
                   (row_data ->> 'longitude')::double precision,
                   (row_data ->> 'latitude')::double precision
               ), 4326)::geography,
               'EPSG:4326',
               (row_data ->> 'longitude')::double precision,
               (row_data ->> 'latitude')::double precision,
               (row_data ->> 'observed_at')::timestamptz,
               jsonb_build_object(
                   'smallCategoryName', row_data ->> 'small_category_name'
               )
        FROM dataset_staging_row
        WHERE acquisition_id = %s AND accepted = true
        """,
        (publication_id, source_id, acquisition_id),
    )
    connection.execute(
        """
        INSERT INTO reference_projection.academy_exact_match(
            sbiz_publication_id, sbiz_fact_id,
            registry_publication_id, registry_fact_id
        )
        SELECT %s, row.row_data ->> 'store_id',
               (array_agg(registry.publication_id))[1],
               (array_agg(registry.fact_id))[1]
        FROM dataset_staging_row row
        JOIN dataset_active_snapshot active
          ON active.source_id = 'edu.academy-registry'
        JOIN reference_projection.registry_fact registry
          ON registry.publication_id = active.publication_id
         AND registry.normalized_name_key = row.row_data ->> 'name'
         AND registry.normalized_address_key = row.row_data ->> 'road_address'
         AND (
             registry.postal_code IS NULL
             OR NULLIF(row.row_data ->> 'postal_code', '') IS NULL
             OR registry.postal_code = row.row_data ->> 'postal_code'
         )
        WHERE row.acquisition_id = %s AND row.accepted = true
        GROUP BY row.row_data ->> 'store_id'
        HAVING count(*) = 1
        """,
        (publication_id, acquisition_id),
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
               count(*), count(*), 0, count(*), 0,
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
