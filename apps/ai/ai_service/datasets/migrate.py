from __future__ import annotations

import os
from collections.abc import Callable, Mapping
from typing import Protocol

from .postgres import PostgresDatasetRepository


class MigrationRepository(Protocol):
    def migrate(self) -> None: ...

    def close(self) -> None: ...


def _migrator_repository(dsn: str) -> PostgresDatasetRepository:
    return PostgresDatasetRepository(
        dsn,
        expected_database="home_search_ai",
        expected_username="home_search_ai_migrator",
    )


def migrate_from_environment(
    environment: Mapping[str, str],
    repository_factory: Callable[[str], MigrationRepository] = _migrator_repository,
) -> None:
    dsn = environment.get("HOME_AI_MIGRATOR_DSN")
    if not dsn:
        raise RuntimeError("HOME_AI_MIGRATOR_DSN is required")
    repository = repository_factory(dsn)
    try:
        repository.migrate()
    finally:
        repository.close()


def main() -> None:
    migrate_from_environment(os.environ)


if __name__ == "__main__":
    main()
