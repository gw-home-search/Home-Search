from __future__ import annotations

import pytest

from ai_service.datasets.migrate import migrate_from_environment


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
