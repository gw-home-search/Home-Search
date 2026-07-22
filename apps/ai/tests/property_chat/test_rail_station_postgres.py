from __future__ import annotations

from datetime import date

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

from ai_service.property_chat.rail_stations import (
    PostgresRailStationRepository,
    _validate_query,
)
from ai_service.property_chat.comparison import CandidatePoint


@pytest.fixture(scope="module")
def rail_station_postgres_dsn():
    with PostgresContainer("postgis/postgis:16-3.4") as postgres:
        dsn = postgres.get_connection_url().replace(
            "postgresql+psycopg2", "postgresql"
        )
        with psycopg.connect(dsn) as connection:
            connection.execute("CREATE EXTENSION IF NOT EXISTS postgis")
            connection.execute("CREATE SCHEMA reference_read")
            connection.execute(
                """
                CREATE TABLE reference_read.rail_station_occurrence (
                    occurrence_id text PRIMARY KEY,
                    station_name text NOT NULL,
                    line_name text NOT NULL,
                    transfer_lines text[] NOT NULL,
                    latitude double precision NOT NULL,
                    longitude double precision NOT NULL,
                    position geography(Point, 4326) NOT NULL
                );
                CREATE TABLE reference_read.active_source_metadata (
                    source_id text PRIMARY KEY,
                    publication_id uuid NOT NULL,
                    dataset_version text NOT NULL,
                    source_date date,
                    freshness_days integer NOT NULL
                );
                CREATE TABLE reference_read.source_coverage (
                    publication_id uuid NOT NULL,
                    region_code text NOT NULL,
                    total_count bigint NOT NULL,
                    spatial_count bigint NOT NULL
                );
                INSERT INTO reference_read.active_source_metadata VALUES (
                    'transport.rail-station',
                    '00000000-0000-0000-0000-000000000001',
                    'rail-v1', '2026-06-30', 410
                );
                INSERT INTO reference_read.source_coverage VALUES (
                    '00000000-0000-0000-0000-000000000001', 'ALL', 4, 4
                );
                INSERT INTO reference_read.rail_station_occurrence VALUES
                    ('operator|2|station', '잠실', '2호선', '{}',
                     37.513, 127.082,
                     ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography),
                    ('operator|8|station', '잠실', '8호선', '{}',
                     37.513, 127.082,
                     ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography),
                    ('boundary', '경계', '경계선', '{}',
                     ST_Y(ST_Project(
                         ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                         1500, 0
                     )::geometry),
                     ST_X(ST_Project(
                         ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                         1500, 0
                     )::geometry),
                     ST_Project(
                         ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                         1500, 0
                     ));
                INSERT INTO reference_read.rail_station_occurrence VALUES
                    ('outside', '바깥', '바깥선', '{}',
                     ST_Y(ST_Project(
                         ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                         1501, 0
                     )::geometry),
                     ST_X(ST_Project(
                         ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                         1501, 0
                     )::geometry),
                     ST_Project(
                         ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                         1501, 0
                     ));
                """
            )
        yield dsn


def test_nearby_includes_boundary_and_merges_exact_station_occurrences(
    rail_station_postgres_dsn: str,
) -> None:
    repository = PostgresRailStationRepository(
        rail_station_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        result = repository.nearby(
            latitude=37.513,
            longitude=127.082,
            radius_meters=1500,
            limit=5,
        )
    finally:
        repository.close()

    assert [station.station_name for station in result.stations] == [
        "잠실",
        "경계",
    ]
    assert result.stations[0].lines == ("2호선", "8호선")
    assert result.stations[0].occurrence_ids == (
        "operator|2|station",
        "operator|8|station",
    )
    assert result.stations[1].distance_meters == 1500
    assert result.occurrence_count == 3
    assert result.dataset_version == "rail-v1"
    assert result.source_date == date(2026, 6, 30)
    assert result.coordinate_coverage == 1.0


def test_nearest_batch_uses_one_bounded_query_for_all_complexes(
    rail_station_postgres_dsn: str,
) -> None:
    repository = PostgresRailStationRepository(
        rail_station_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        results = repository.nearest_batch(
            points=(
                CandidatePoint(501, 37.513, 127.082, "11710"),
                CandidatePoint(502, 37.70, 127.30, "11710"),
            ),
            radius_meters=1500,
        )
    finally:
        repository.close()

    assert results is not None
    assert results[501].stations[0].station_name == "잠실"
    assert results[501].stations[0].lines == ("2호선", "8호선")
    assert results[502].stations == ()


def test_station_scope_resolver_uses_exact_name_and_merges_lines(
    rail_station_postgres_dsn: str,
) -> None:
    repository = PostgresRailStationRepository(
        rail_station_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        result = repository.resolve_station("잠실역")
        missing = repository.resolve_station("없는역")
    finally:
        repository.close()

    assert result is not None
    assert len(result.matches) == 1
    assert result.matches[0].station_name == "잠실"
    assert result.matches[0].lines == ("2호선", "8호선")
    assert result.matches[0].latitude == 37.513
    assert result.matches[0].longitude == 127.082
    assert missing is None


@pytest.mark.parametrize(
    "kwargs",
    [
        {"latitude": float("nan")},
        {"longitude": 133.0},
        {"radius_meters": 99},
        {"radius_meters": 3001},
        {"limit": 6},
    ],
)
def test_query_rejects_unsafe_bounds(kwargs: dict[str, object]) -> None:
    values: dict[str, object] = {
        "latitude": 37.5,
        "longitude": 127.0,
        "radius_meters": 1500,
        "limit": 5,
    }
    values.update(kwargs)

    with pytest.raises(ValueError):
        _validate_query(**values)  # type: ignore[arg-type]


def test_repository_rejects_empty_or_wrong_role_configuration(
    rail_station_postgres_dsn: str,
) -> None:
    with pytest.raises(ValueError):
        PostgresRailStationRepository("")
    with pytest.raises(ValueError):
        PostgresRailStationRepository(
            rail_station_postgres_dsn,
            expected_database="wrong",
            expected_username="test",
        )
    with pytest.raises(ValueError):
        PostgresRailStationRepository(
            rail_station_postgres_dsn,
            expected_database="test",
            expected_username="wrong",
        )
