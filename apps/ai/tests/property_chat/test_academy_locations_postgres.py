from __future__ import annotations

from contextlib import contextmanager
from datetime import UTC, datetime
from types import SimpleNamespace

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

from ai_service.property_chat.academy_locations import (
    PostgresAcademyLocationRepository,
    _location,
    _validate_query,
)
from ai_service.property_chat.comparison import CandidatePoint


def test_academy_batch_keeps_spatial_lookup_correlated_for_each_candidate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    queries: list[str] = []

    class FakeResult:
        def __init__(self, *, rows=None, row=None):
            self._rows = rows
            self._row = row

        def fetchall(self):
            return self._rows

        def fetchone(self):
            return self._row

    class FakeConnection:
        info = SimpleNamespace(dbname="test", user="test")

        def execute(self, query, _params=None):
            queries.append(query)
            if "WITH candidates" in query:
                return FakeResult(rows=[{"complex_id": 1, "matched_count": 2}])
            return FakeResult(row={
                "total_count": 10,
                "spatial_count": 10,
                "dataset_version": "academy-v1",
                "observed_at": datetime(2026, 7, 20, tzinfo=UTC),
                "freshness_days": 45,
            })

    class FakePool:
        @staticmethod
        def check_connection(_connection):
            return None

        def __init__(self, **_kwargs):
            self._connection = FakeConnection()

        @contextmanager
        def connection(self):
            yield self._connection

        def close(self):
            return None

    monkeypatch.setattr(
        "ai_service.property_chat.academy_locations.ConnectionPool", FakePool
    )
    repository = PostgresAcademyLocationRepository(
        "postgresql://test:test@localhost/test",
        expected_database="test",
        expected_username="test",
    )

    result = repository.nearby_counts_batch(
        points=(CandidatePoint(1, 37.513, 127.082, "11710"),),
        radius_meters=800,
    )

    candidate_query = next(query for query in queries if "WITH candidates" in query)
    assert "LEFT JOIN LATERAL" in candidate_query
    assert "OFFSET 0" in candidate_query
    assert result is not None
    assert result[1].matched_count == 2


@pytest.fixture(scope="module")
def academy_location_postgres_dsn():
    with PostgresContainer("postgis/postgis:16-3.4") as postgres:
        dsn = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql")
        with psycopg.connect(dsn) as connection:
            connection.execute("CREATE EXTENSION IF NOT EXISTS postgis")
            connection.execute("CREATE SCHEMA reference_read")
            connection.execute(
                """
                CREATE TABLE reference_read.facility_point_fact (
                    publication_id uuid NOT NULL,
                    source_id text NOT NULL,
                    fact_id text PRIMARY KEY,
                    name text NOT NULL,
                    subcategory text NOT NULL,
                    status text NOT NULL,
                    road_address text,
                    lot_address text,
                    position geography(Point, 4326) NOT NULL,
                    dataset_version text NOT NULL,
                    dataset_observed_at timestamptz NOT NULL
                );
                CREATE TABLE reference_read.sbiz_academy_exact_match (
                    sbiz_publication_id uuid NOT NULL,
                    sbiz_fact_id text PRIMARY KEY,
                    registry_fact_id text,
                    registry_academy_name text,
                    registry_status text,
                    registry_dataset_version text,
                    registry_observed_at timestamptz
                );
                CREATE TABLE reference_read.active_source_metadata (
                    source_id text PRIMARY KEY,
                    publication_id uuid NOT NULL,
                    dataset_version text NOT NULL,
                    observed_at timestamptz,
                    freshness_days integer NOT NULL
                );
                CREATE TABLE reference_read.source_coverage (
                    publication_id uuid NOT NULL,
                    region_code text NOT NULL,
                    total_count bigint NOT NULL,
                    spatial_count bigint NOT NULL
                );
                INSERT INTO reference_read.active_source_metadata VALUES (
                    'place.sbiz-academy', '00000000-0000-0000-0000-000000000001',
                    'sbiz-v1', '2026-07-20T00:00:00Z', 45
                );
                INSERT INTO reference_read.source_coverage VALUES (
                    '00000000-0000-0000-0000-000000000001', '1171056600', 100, 100
                );
                INSERT INTO reference_read.facility_point_fact VALUES
                    ('00000000-0000-0000-0000-000000000001',
                     'place.sbiz-academy', 'exact', '가나다 학원', 'P10101', 'OPEN', '도로명', NULL,
                     ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                     'sbiz-v1', '2026-07-20T00:00:00Z'),
                    ('00000000-0000-0000-0000-000000000001',
                     'place.sbiz-academy', 'boundary', '경계 교습소', 'P10102', 'OPEN', NULL, '지번',
                     ST_Project(
                         ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                         800, 0
                     ),
                     'sbiz-v1', '2026-07-20T00:00:00Z'),
                    ('00000000-0000-0000-0000-000000000001',
                     'place.sbiz-academy', 'outside', '바깥 학원', 'P10101', 'OPEN', '도로명', NULL,
                     ST_Project(
                         ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                         801, 0
                     ),
                     'sbiz-v1', '2026-07-20T00:00:00Z');
                INSERT INTO reference_read.sbiz_academy_exact_match VALUES (
                    '00000000-0000-0000-0000-000000000001', 'exact', 'B10|1',
                    '가나다 학원', 'OPEN', 'neis-v1', '2026-07-19T00:00:00Z'
                );
                """
            )
        yield dsn


def test_nearby_includes_exact_800_meter_boundary_and_exact_registry_evidence(
    academy_location_postgres_dsn: str,
) -> None:
    repository = PostgresAcademyLocationRepository(
        academy_location_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        result = repository.nearby(
            latitude=37.513,
            longitude=127.082,
            radius_meters=800,
            limit=5,
        )
    finally:
        repository.close()

    assert [location.store_id for location in result.locations] == [
        "exact",
        "boundary",
    ]
    assert result.locations[0].registry_match is not None
    assert result.locations[0].registry_match.registry_fact_id == "B10|1"
    assert result.locations[1].distance_meters == 800
    assert result.coordinate_coverage == 1.0
    assert result.verified_zero is False


def test_academy_batch_counts_all_candidates_without_individual_queries(
    academy_location_postgres_dsn: str,
) -> None:
    repository = PostgresAcademyLocationRepository(
        academy_location_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        results = repository.nearby_counts_batch(
            points=(CandidatePoint(1, 37.513, 127.082, "11710"),),
            radius_meters=800,
        )
    finally:
        repository.close()

    assert results is not None
    assert results[1].matched_count == 2
    assert results[1].coordinate_coverage == 1.0


def test_nearby_returns_unverified_zero_without_loading_exact_evidence(
    academy_location_postgres_dsn: str,
) -> None:
    repository = PostgresAcademyLocationRepository(
        academy_location_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        result = repository.nearby(
            latitude=33.5,
            longitude=126.5,
            radius_meters=800,
            limit=5,
        )
    finally:
        repository.close()

    assert result.locations == ()
    assert result.matched_count == 0
    assert result.verified_zero is False


def test_nearby_rejects_mixed_publications(
    academy_location_postgres_dsn: str,
) -> None:
    with psycopg.connect(academy_location_postgres_dsn) as connection:
        connection.execute(
            """
            INSERT INTO reference_read.facility_point_fact VALUES (
                '00000000-0000-0000-0000-000000000002',
                'place.sbiz-academy', 'mixed', '혼합 학원', 'P10101', 'OPEN', '도로명', NULL,
                ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                'sbiz-v2', '2026-07-20T00:00:00Z'
            )
            """
        )

    repository = PostgresAcademyLocationRepository(
        academy_location_postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        with pytest.raises(RuntimeError, match="publication is inconsistent"):
            repository.nearby(
                latitude=37.513,
                longitude=127.082,
                radius_meters=800,
                limit=5,
            )
    finally:
        repository.close()
        with psycopg.connect(academy_location_postgres_dsn) as connection:
            connection.execute(
                "DELETE FROM reference_read.facility_point_fact WHERE fact_id = 'mixed'"
            )


@pytest.mark.parametrize(
    "kwargs",
    [
        {"latitude": float("nan")},
        {"longitude": 133.0},
        {"radius_meters": 99},
        {"limit": 6},
    ],
)
def test_query_rejects_unsafe_bounds(kwargs: dict[str, object]) -> None:
    values: dict[str, object] = {
        "latitude": 37.5,
        "longitude": 127.0,
        "radius_meters": 800,
        "limit": 5,
    }
    values.update(kwargs)

    with pytest.raises(ValueError):
        _validate_query(**values)  # type: ignore[arg-type]


def test_repository_rejects_empty_configuration() -> None:
    with pytest.raises(ValueError):
        PostgresAcademyLocationRepository("")


@pytest.mark.parametrize(
    ("expected_database", "expected_username"),
    [("wrong-database", "test"), ("test", "wrong-role")],
)
def test_repository_rejects_wrong_database_or_role(
    academy_location_postgres_dsn: str,
    expected_database: str,
    expected_username: str,
) -> None:
    with pytest.raises(ValueError):
        PostgresAcademyLocationRepository(
            academy_location_postgres_dsn,
            expected_database=expected_database,
            expected_username=expected_username,
        )


def test_location_rejects_incomplete_or_unknown_registry_evidence() -> None:
    row: dict[str, object] = {
        "sbiz_fact_id": "store-1",
        "name": "가나다 학원",
        "small_category_code": "P10101",
        "status": "OPEN",
        "address": None,
        "distance_meters": 10.0,
        "dataset_version": "sbiz-v1",
        "observed_at": datetime(2026, 7, 20, tzinfo=UTC),
        "registry_fact_id": None,
        "registry_match": "EXACT",
        "registry_academy_name": "가나다 학원",
        "registry_status": "OPEN",
        "registry_dataset_version": "neis-v1",
        "registry_observed_at": datetime(2026, 7, 19, tzinfo=UTC),
    }

    with pytest.raises(RuntimeError, match="evidence is incomplete"):
        _location(row)

    row["registry_match"] = "FUZZY"
    with pytest.raises(RuntimeError, match="match type is invalid"):
        _location(row)
