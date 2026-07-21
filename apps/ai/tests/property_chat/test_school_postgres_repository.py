from __future__ import annotations

from datetime import date

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

from ai_service.property_chat.school_postgres import PostgresSchoolFactRepository
from ai_service.property_chat.comparison import CandidatePoint


@pytest.fixture(scope="module")
def reference_postgres_dsn():
    with PostgresContainer("postgres:16-alpine") as postgres:
        dsn = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql")
        with psycopg.connect(dsn) as connection:
            connection.execute("CREATE SCHEMA reference_read")
            connection.execute(
                """
                CREATE TABLE reference_read.school_location_fact (
                    school_id text PRIMARY KEY,
                    school_name text NOT NULL,
                    school_level text NOT NULL,
                    operating_status text NOT NULL,
                    road_address text,
                    lot_address text,
                    education_office_code text NOT NULL,
                    education_office_name text NOT NULL,
                    latitude double precision NOT NULL,
                    longitude double precision NOT NULL,
                    reference_date date NOT NULL,
                    dataset_version text NOT NULL,
                    published_at timestamptz NOT NULL
                );
                INSERT INTO reference_read.school_location_fact VALUES
                    ('boundary', '경계초등학교', 'ELEMENTARY', '운영', '도로명', NULL,
                     '7010000', '서울특별시교육청', 37.52019457, 127.082, '2026-03-20', 'v1', now()),
                    ('outside', '바깥초등학교', 'ELEMENTARY', '운영', '도로명', NULL,
                     '7010000', '서울특별시교육청', 37.52021, 127.082, '2026-03-20', 'v1', now()),
                    ('closed', '폐교초등학교', 'ELEMENTARY', '폐교', '도로명', NULL,
                     '7010000', '서울특별시교육청', 37.5131, 127.082, '2026-03-20', 'v1', now()),
                    ('middle', '가까운중학교', 'MIDDLE', '운영', '도로명', NULL,
                     '7010000', '서울특별시교육청', 37.5131, 127.082, '2026-03-20', 'v1', now());
                """
            )
        yield dsn


def test_nearby_school_query_includes_boundary_and_excludes_outside_closed_and_other_level(
    reference_postgres_dsn: str,
) -> None:
    repository = PostgresSchoolFactRepository(
        reference_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        result = repository.nearby_schools(
            latitude=37.513,
            longitude=127.082,
            school_levels=("ELEMENTARY",),
            radius_meters=800,
            limit=5,
        )
        snapshot = repository.active_snapshot()
    finally:
        repository.close()

    assert [school.school_id for school in result.schools] == ["boundary"]
    assert result.matched_count == 1
    assert result.schools[0].distance_meters == 800
    assert snapshot is not None
    assert snapshot.source_date == date(2026, 3, 20)


def test_school_batch_returns_nearest_school_per_requested_level(
    reference_postgres_dsn: str,
) -> None:
    repository = PostgresSchoolFactRepository(
        reference_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        batch = repository.nearest_by_level_batch(
            points=(CandidatePoint(1, 37.513, 127.082, "11710"),),
            school_levels=("ELEMENTARY", "MIDDLE"), radius_meters=1500,
        )
    finally:
        repository.close()

    assert batch is not None
    _, results = batch
    assert {school.school_level for school in results[1].schools} == {
        "ELEMENTARY", "MIDDLE",
    }


@pytest.mark.parametrize(
    ("dsn", "options", "message"),
    [
        (" ", {}, "reference DSN"),
        ("postgresql://unused", {"expected_database": " "}, "database boundary"),
        ("postgresql://unused", {"expected_username": " "}, "database boundary"),
        (
            "postgresql://unused",
            {"min_pool_size": 0, "max_pool_size": 5},
            "pool size",
        ),
    ],
)
def test_repository_rejects_unsafe_configuration_before_connecting(
    dsn: str, options: dict[str, object], message: str
) -> None:
    with pytest.raises(ValueError, match=message):
        PostgresSchoolFactRepository(dsn, **options)  # type: ignore[arg-type]


def test_repository_rejects_wrong_database_boundary(reference_postgres_dsn: str) -> None:
    with pytest.raises(ValueError, match="expected database"):
        PostgresSchoolFactRepository(
            reference_postgres_dsn,
            expected_database="home_search_ai",
            expected_username="test",
        )


@pytest.mark.parametrize(
    "kwargs",
    [
        {"latitude": float("nan"), "longitude": 127.0, "school_levels": ("ELEMENTARY",), "radius_meters": 800, "limit": 5},
        {"latitude": 37.5, "longitude": 127.0, "school_levels": (), "radius_meters": 800, "limit": 5},
        {"latitude": 37.5, "longitude": 127.0, "school_levels": ("UNKNOWN",), "radius_meters": 800, "limit": 5},
        {"latitude": 37.5, "longitude": 127.0, "school_levels": ("ELEMENTARY",), "radius_meters": 99, "limit": 5},
        {"latitude": 37.5, "longitude": 127.0, "school_levels": ("ELEMENTARY",), "radius_meters": 800, "limit": 6},
    ],
)
def test_repository_rejects_unsafe_query_before_sql(
    reference_postgres_dsn: str, kwargs: dict[str, object]
) -> None:
    repository = PostgresSchoolFactRepository(
        reference_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        with pytest.raises(ValueError):
            repository.nearby_schools(**kwargs)  # type: ignore[arg-type]
    finally:
        repository.close()
