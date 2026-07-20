from __future__ import annotations

from datetime import UTC, datetime

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

from ai_service.property_chat.academy_registry import PostgresAcademyRegistryRepository


@pytest.fixture(scope="module")
def academy_postgres_dsn():
    with PostgresContainer("postgres:16-alpine") as postgres:
        dsn = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql")
        with psycopg.connect(dsn) as connection:
            connection.execute("CREATE SCHEMA reference_read")
            connection.execute(
                """
                CREATE TABLE reference_read.academy_registry_summary (
                    education_office_code text NOT NULL,
                    education_office_name text NOT NULL,
                    district_name text NOT NULL,
                    academy_type text NOT NULL,
                    status text NOT NULL,
                    registry_count bigint NOT NULL,
                    dataset_version text NOT NULL,
                    observed_at timestamptz NOT NULL,
                    published_at timestamptz NOT NULL,
                    freshness_days integer NOT NULL
                );
                INSERT INTO reference_read.academy_registry_summary VALUES
                    ('B10', '서울특별시교육청', '송파구', '학원', 'OPEN', 120, 'v1',
                     '2026-07-19T01:00:00Z', '2026-07-19T02:00:00Z', 45),
                    ('B10', '서울특별시교육청', '송파구', '교습소', 'CLOSED', 30, 'v1',
                     '2026-07-19T01:00:00Z', '2026-07-19T02:00:00Z', 45),
                    ('C10', '부산광역시교육청', '송파구', '학원', 'OPEN', 999, 'v1',
                     '2026-07-19T01:00:00Z', '2026-07-19T02:00:00Z', 45);
                """
            )
        yield dsn


def test_summary_uses_exact_education_office_and_district(
    academy_postgres_dsn: str,
) -> None:
    repository = PostgresAcademyRegistryRepository(
        academy_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        summary = repository.summary(
            education_office_name="서울특별시교육청", district_name="송파구"
        )
        missing = repository.summary(
            education_office_name="서울특별시교육청", district_name="없는구"
        )
    finally:
        repository.close()

    assert summary is not None
    assert summary.education_office_code == "B10"
    assert summary.total_count == 150
    assert summary.open_count == 120
    assert summary.observed_at == datetime(2026, 7, 19, 1, tzinfo=UTC)
    assert missing is None


def test_repository_rejects_unsafe_configuration_before_connecting() -> None:
    with pytest.raises(ValueError):
        PostgresAcademyRegistryRepository("")


@pytest.mark.parametrize(
    ("education_office_name", "district_name"),
    [(" 서울특별시교육청", "송파구"), ("서울특별시교육청", " ")],
)
def test_summary_rejects_noncanonical_region_query(
    academy_postgres_dsn: str,
    education_office_name: str,
    district_name: str,
) -> None:
    repository = PostgresAcademyRegistryRepository(
        academy_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        with pytest.raises(ValueError, match="region query"):
            repository.summary(
                education_office_name=education_office_name,
                district_name=district_name,
            )
    finally:
        repository.close()
