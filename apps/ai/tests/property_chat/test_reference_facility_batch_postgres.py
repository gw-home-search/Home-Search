from __future__ import annotations

from datetime import date

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

from ai_service.property_chat.comparison import CandidatePoint
from ai_service.property_chat.reference_facilities import PostgresPointFacilityRepository


@pytest.fixture(scope="module")
def facility_postgres_dsn():
    with PostgresContainer("postgis/postgis:16-3.4") as postgres:
        dsn = postgres.get_connection_url().replace(
            "postgresql+psycopg2", "postgresql"
        )
        with psycopg.connect(dsn) as connection:
            connection.execute("CREATE EXTENSION IF NOT EXISTS postgis")
            connection.execute("CREATE SCHEMA reference_read")
            connection.execute(
                """
                CREATE TABLE reference_read.facility_point_fact (
                    fact_id text PRIMARY KEY,
                    source_id text NOT NULL,
                    name text NOT NULL,
                    category text NOT NULL,
                    subcategory text,
                    status text NOT NULL,
                    road_address text,
                    lot_address text,
                    position geography(Point, 4326) NOT NULL,
                    dataset_version text NOT NULL,
                    source_date date,
                    dataset_observed_at timestamptz
                );
                CREATE TABLE reference_read.active_source_metadata (
                    source_id text PRIMARY KEY,
                    publication_id uuid NOT NULL,
                    dataset_version text NOT NULL,
                    source_date date,
                    observed_at timestamptz
                );
                CREATE TABLE reference_read.source_coverage (
                    publication_id uuid NOT NULL,
                    region_code text NOT NULL,
                    total_count bigint NOT NULL,
                    spatial_count bigint NOT NULL
                );
                INSERT INTO reference_read.active_source_metadata VALUES (
                    'retail.large-store',
                    '00000000-0000-0000-0000-000000000001',
                    'retail-v1', '2026-06-30', NULL
                );
                INSERT INTO reference_read.source_coverage VALUES (
                    '00000000-0000-0000-0000-000000000001', 'ALL', 1, 1
                );
                INSERT INTO reference_read.facility_point_fact VALUES (
                    'store-1', 'retail.large-store', '롯데마트', 'RETAIL',
                    'LARGE_MART', 'OPEN', '서울 송파구', NULL,
                    ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                    'retail-v1', '2026-06-30', NULL
                );
                """
            )
        yield dsn


def test_retail_nearest_batch_returns_one_result_map_without_candidate_queries(
    facility_postgres_dsn: str,
) -> None:
    repository = PostgresPointFacilityRepository(
        facility_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        results = repository.nearest_batch(
            source_id="retail.large-store",
            category="LARGE_STORE",
            points=(
                CandidatePoint(501, 37.513, 127.082, "11710"),
                CandidatePoint(502, 37.70, 127.30, "11710"),
            ),
            radius_meters=1000,
        )
    finally:
        repository.close()

    assert results is not None
    assert results[501].facilities[0].name == "롯데마트"
    assert results[501].facilities[0].distance_meters == 0
    assert results[502].facilities == ()
    assert results[502].dataset_version == "retail-v1"
