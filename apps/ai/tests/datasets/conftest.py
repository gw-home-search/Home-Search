from __future__ import annotations

from collections.abc import Iterator

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

from ai_service.datasets.postgres import PostgresDatasetRepository


@pytest.fixture(scope="session")
def postgres_dsn() -> Iterator[str]:
    with PostgresContainer("postgres:16-alpine") as postgres:
        dsn = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql")
        yield dsn


@pytest.fixture
def dataset_repository(postgres_dsn: str) -> Iterator[PostgresDatasetRepository]:
    repository = PostgresDatasetRepository(postgres_dsn)
    repository.migrate()
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute(
            "DROP TRIGGER IF EXISTS fixture_block_active_pointer ON dataset_active_snapshot"
        )
        connection.execute("DROP FUNCTION IF EXISTS fixture_block_active_pointer()")
        connection.execute(
            """
            TRUNCATE dataset_active_snapshot, dataset_activation_event, dataset_publication,
                     dataset_rejected_row, dataset_quality_issue,
                     dataset_snapshot_row, dataset_staging_row,
                     dataset_acquisition, dataset_raw_object, dataset_source
            RESTART IDENTITY CASCADE
            """
        )
    yield repository
    repository.close()
