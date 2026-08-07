from __future__ import annotations

import asyncio
from contextlib import contextmanager
from threading import Barrier, Lock

import pytest

from ai_service.readiness import RuntimeReadinessChecker
from ai_service.property_chat.academy_locations import PostgresAcademyLocationRepository
from ai_service.property_chat.postgres import PostgresPropertyFactRepository
from ai_service.property_chat.rail_stations import PostgresRailStationRepository


class ReadyRepository:
    def readiness_probe(self) -> None:
        return None


def _configure_openai(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME_AI_OPENAI_API_KEY", "test-key")
    monkeypatch.setenv("HOME_AI_OPENAI_PRIMARY_MODEL", "primary")
    monkeypatch.setenv("HOME_AI_OPENAI_SECONDARY_MODEL", "secondary")


def test_runtime_readiness_reports_ready_without_exposing_configuration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _configure_openai(monkeypatch)
    monkeypatch.setattr("ai_service.readiness.get_property_fact_repository", ReadyRepository)
    monkeypatch.setattr("ai_service.readiness.get_academy_location_repository", ReadyRepository)
    monkeypatch.setattr("ai_service.readiness.get_rail_station_repository", ReadyRepository)
    monkeypatch.setattr(
        "ai_service.readiness.get_enabled_reference_capabilities",
        lambda: frozenset({"academy_lookup", "rail_station_lookup"}),
    )

    result = asyncio.run(RuntimeReadinessChecker().check())

    assert result.status == "READY"
    assert result.checks == {
        "property": "ready", "academy": "ready", "rail": "ready", "openai": "configured",
    }


def test_runtime_readiness_distinguishes_optional_degraded_from_core_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _configure_openai(monkeypatch)
    monkeypatch.setattr("ai_service.readiness.get_property_fact_repository", ReadyRepository)
    monkeypatch.setattr(
        "ai_service.readiness.get_enabled_reference_capabilities",
        lambda: frozenset({"academy_lookup"}),
    )
    monkeypatch.setattr(
        "ai_service.readiness.get_academy_location_repository",
        lambda: (_ for _ in ()).throw(RuntimeError("private detail")),
    )

    degraded = asyncio.run(RuntimeReadinessChecker().check())
    assert degraded.status == "DEGRADED"
    assert degraded.checks["academy"] == "unavailable"
    assert degraded.checks["rail"] == "unavailable"

    monkeypatch.delenv("HOME_AI_OPENAI_API_KEY")
    not_ready = asyncio.run(RuntimeReadinessChecker().check())
    assert not_ready.status == "NOT_READY"
    assert not_ready.checks["openai"] == "not_configured"


def test_runtime_readiness_rejects_invalid_openai_settings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _configure_openai(monkeypatch)
    monkeypatch.setenv("HOME_AI_OPENAI_TIMEOUT_SECONDS", "not-a-number")
    monkeypatch.setattr("ai_service.readiness.get_property_fact_repository", ReadyRepository)
    monkeypatch.setattr(
        "ai_service.readiness.get_enabled_reference_capabilities", frozenset
    )

    result = asyncio.run(RuntimeReadinessChecker().check())

    assert result.status == "NOT_READY"
    assert result.checks["openai"] == "not_configured"


@pytest.mark.parametrize(
    "repository_type",
    (
        PostgresPropertyFactRepository,
        PostgresAcademyLocationRepository,
        PostgresRailStationRepository,
    ),
)
def test_repository_readiness_rejects_missing_active_read_data(repository_type) -> None:
    class EmptyResult:
        def fetchone(self):
            return None

    class FakeConnection:
        def execute(self, _query):
            return EmptyResult()

    class FakePool:
        @contextmanager
        def connection(self, *, timeout):
            assert timeout == 1.5
            yield FakeConnection()

    repository = object.__new__(repository_type)
    repository._pool = FakePool()

    with pytest.raises(RuntimeError, match="readiness"):
        repository.readiness_probe()


def test_runtime_readiness_probes_cached_property_repository(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class BrokenCachedRepository:
        def readiness_probe(self) -> None:
            raise OSError("database disconnected")

    _configure_openai(monkeypatch)
    cached = BrokenCachedRepository()
    monkeypatch.setattr(
        "ai_service.readiness.get_property_fact_repository", lambda: cached
    )
    monkeypatch.setattr(
        "ai_service.readiness.get_enabled_reference_capabilities", frozenset
    )

    result = asyncio.run(RuntimeReadinessChecker().check())

    assert result.status == "NOT_READY"
    assert result.checks["property"] == "not_ready"


def test_runtime_readiness_runs_enabled_repository_probes_concurrently(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    barrier = Barrier(3)
    calls: list[str] = []
    calls_lock = Lock()

    class CoordinatedRepository:
        def __init__(self, name: str) -> None:
            self.name = name

        def readiness_probe(self) -> None:
            with calls_lock:
                calls.append(self.name)
            barrier.wait(timeout=0.5)

    _configure_openai(monkeypatch)
    monkeypatch.setattr(
        "ai_service.readiness.get_property_fact_repository",
        lambda: CoordinatedRepository("property"),
    )
    monkeypatch.setattr(
        "ai_service.readiness.get_academy_location_repository",
        lambda: CoordinatedRepository("academy"),
    )
    monkeypatch.setattr(
        "ai_service.readiness.get_rail_station_repository",
        lambda: CoordinatedRepository("rail"),
    )
    monkeypatch.setattr(
        "ai_service.readiness.get_enabled_reference_capabilities",
        lambda: frozenset({"academy_lookup", "rail_station_lookup"}),
    )

    result = asyncio.run(RuntimeReadinessChecker().check())

    assert result.status == "READY"
    assert set(calls) == {"property", "academy", "rail"}
