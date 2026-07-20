from __future__ import annotations

from pathlib import Path

import psycopg
import pytest

from ai_service.datasets.migrate import migrate_from_environment
from ai_service.datasets.postgres import PostgresDatasetRepository, discover_migrations


class RecordingRepository:
    instances: list["RecordingRepository"] = []

    def __init__(self, dsn: str) -> None:
        self.dsn = dsn
        self.migrated = False
        self.closed = False
        self.instances.append(self)

    def migrate(self) -> None:
        self.migrated = True

    def close(self) -> None:
        self.closed = True


def test_migrator_requires_a_dedicated_dsn() -> None:
    with pytest.raises(RuntimeError, match="HOME_AI_MIGRATOR_DSN is required"):
        migrate_from_environment({})


def test_migrator_uses_the_dedicated_dsn_and_closes_repository() -> None:
    RecordingRepository.instances.clear()

    migrate_from_environment(
        {"HOME_AI_MIGRATOR_DSN": "postgresql://migrator@db/home_search_ai"},
        RecordingRepository,
    )

    repository = RecordingRepository.instances[-1]
    assert repository.dsn == "postgresql://migrator@db/home_search_ai"
    assert repository.migrated is True
    assert repository.closed is True


def test_default_migrator_enforces_dedicated_database_boundary(
    postgres_dsn: str,
) -> None:
    with pytest.raises(ValueError, match="expected database"):
        migrate_from_environment({"HOME_AI_MIGRATOR_DSN": postgres_dsn})


def test_migrations_are_discovered_in_numeric_order(tmp_path: Path) -> None:
    (tmp_path / "0002_second.sql").write_text("SELECT 2;", encoding="utf-8")
    (tmp_path / "0001_first.sql").write_text("SELECT 1;", encoding="utf-8")
    (tmp_path / "README.md").write_text("ignored", encoding="utf-8")

    migrations = discover_migrations(tmp_path)

    assert [(migration.version, migration.description) for migration in migrations] == [
        (1, "first"),
        (2, "second"),
    ]


def test_duplicate_migration_versions_are_rejected(tmp_path: Path) -> None:
    (tmp_path / "0001_first.sql").write_text("SELECT 1;", encoding="utf-8")
    (tmp_path / "0001_again.sql").write_text("SELECT 1;", encoding="utf-8")

    with pytest.raises(RuntimeError, match="duplicate AI migration version"):
        discover_migrations(tmp_path)


def test_applied_migration_checksum_mismatch_fails_closed(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    migrations = discover_migrations(
        Path(__file__).parents[2] / "ai_service" / "datasets" / "migrations"
    )
    expected_checksum = migrations[-1].checksum
    version = migrations[-1].version
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute(
            "UPDATE ai_schema_history SET checksum = %s WHERE version = %s",
            ("0" * 64, version),
        )
    try:
        with pytest.raises(RuntimeError, match="checksum mismatch"):
            dataset_repository.migrate()
    finally:
        with psycopg.connect(postgres_dsn) as connection:
            connection.execute(
                "UPDATE ai_schema_history SET checksum = %s WHERE version = %s",
                (expected_checksum, version),
            )


def test_failed_migration_does_not_record_history(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
    tmp_path: Path,
) -> None:
    del dataset_repository
    source_directory = Path(__file__).parents[2] / "ai_service" / "datasets" / "migrations"
    for migration in discover_migrations(source_directory):
        (tmp_path / migration.path.name).write_bytes(migration.path.read_bytes())
    (tmp_path / "9999_expected_failure.sql").write_text(
        "CREATE TABLE migration_must_roll_back(id integer); SELECT 1 / 0;",
        encoding="utf-8",
    )
    repository = PostgresDatasetRepository(
        postgres_dsn,
        migration_directory=tmp_path,
    )

    with pytest.raises(psycopg.errors.DivisionByZero):
        repository.migrate()

    with psycopg.connect(postgres_dsn) as connection:
        assert connection.execute(
            "SELECT 1 FROM ai_schema_history WHERE version = 9999"
        ).fetchone() is None
        assert connection.execute(
            "SELECT to_regclass('public.migration_must_roll_back')"
        ).fetchone()[0] is None


def test_acquisition_audit_includes_failed_run_without_acquisition(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    del dataset_repository
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute(
            """
            INSERT INTO dataset_source(source_id, provider, created_at)
            VALUES ('edu.academy-registry', 'NEIS', now())
            """
        )
        connection.execute(
            """
            INSERT INTO dataset_refresh_run(
                refresh_run_id, profile, trigger_type, started_at,
                finished_at, status
            ) VALUES (
                '00000000-0000-0000-0000-000000000020',
                'source:edu.academy-registry', 'MANUAL', now(), now(), 'FAIL'
            )
            """
        )
        connection.execute(
            """
            INSERT INTO dataset_refresh_run_item(
                refresh_run_id, source_id, started_at, finished_at,
                status, reason_codes
            ) VALUES (
                '00000000-0000-0000-0000-000000000020',
                'edu.academy-registry', now(), now(), 'FAIL',
                ARRAY['API_SERVER_ERROR']
            )
            """
        )
        row = connection.execute(
            """
            SELECT acquisition_id, status, raw_row_count,
                   accepted_row_count, rejected_row_count, reason_codes
            FROM reference_read.acquisition_audit
            WHERE source_id = 'edu.academy-registry'
            """
        ).fetchone()

    assert row == (None, "FAIL", 0, 0, 0, ["API_SERVER_ERROR"])


@pytest.mark.parametrize(
    ("expected_database", "expected_username", "message"),
    [
        ("home_search_ai", "test", "expected database"),
        ("test", "home_search_ai_migrator", "expected role"),
    ],
)
def test_dataset_repository_rejects_wrong_database_boundary(
    postgres_dsn: str,
    expected_database: str,
    expected_username: str,
    message: str,
) -> None:
    repository = PostgresDatasetRepository(
        postgres_dsn,
        expected_database=expected_database,
        expected_username=expected_username,
    )

    with pytest.raises(ValueError, match=message):
        repository.migrate()
