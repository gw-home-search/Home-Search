from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID

import pytest

from ai_service.datasets import retail_coordinate_enrichment as enrichment_module
from ai_service.datasets.retail_coordinate_enrichment import (
    AdministrativeRegionPath,
    ParcelCoordinate,
    PostgresCoordinateSnapshotRepository,
    PostgresPropertyRegionRepository,
    PostgresRetailEnrichmentTargetRepository,
    RetailCoordinateEnrichment,
    RetailCoordinateEnrichmentService,
    RetailRegistryFact,
    derive_exact_lot_pnu,
    enrich_from_environment,
)


PUBLICATION_ID = UUID("10000000-0000-0000-0000-000000000001")


def _region() -> AdministrativeRegionPath:
    return AdministrativeRegionPath(
        legal_dong_code="41173101",
        sido_name="경기도",
        sigungu_name="안양시 동안구",
        eup_myeon_dong_name="비산동",
    )


def test_exact_lot_pnu_preserves_the_boundary_before_building_detail() -> None:
    assert derive_exact_lot_pnu(
        "경기도 안양시 동안구 비산동 281-1 1단지상가 122호",
        (_region(),),
    ) == "4117310100102810001"


def test_exact_lot_pnu_rejects_unsupported_or_ambiguous_address_shapes() -> None:
    assert derive_exact_lot_pnu("경기도 안양시 동안구 비산동 248-5.6호", (_region(),)) is None
    assert derive_exact_lot_pnu("경기도 안양시 동안구 비산동 호", (_region(),)) is None
    assert derive_exact_lot_pnu(
        "경기도 안양시 만안구 비산동 281-1", (_region(),)
    ) is None
    assert derive_exact_lot_pnu(
        "경기도 안양시 동안구 비산동 0번지", (_region(),)
    ) is None


def test_exact_lot_pnu_supports_mountain_and_rejects_ambiguous_region_paths() -> None:
    assert derive_exact_lot_pnu(
        "경기도 안양시 동안구 비산동 산 12번지 3호",
        (_region(),),
    ) == "4117310100200120003"
    duplicate = AdministrativeRegionPath(
        legal_dong_code="41173102",
        sido_name="경기도",
        sigungu_name="안양시 동안구",
        eup_myeon_dong_name="비산동",
    )
    assert derive_exact_lot_pnu(
        "경기도 안양시 동안구 비산동 281-1", (_region(), duplicate)
    ) is None
    assert derive_exact_lot_pnu("   ", (_region(),)) is None


@pytest.mark.parametrize(
    "factory",
    [
        lambda: AdministrativeRegionPath("bad", "경기도", "안양시", "비산동"),
        lambda: AdministrativeRegionPath("41173101", "경기도", "안양시", "비산면"),
        lambda: RetailRegistryFact(PUBLICATION_ID, "", "주소"),
        lambda: ParcelCoordinate("bad", 37.4, 126.9, "snapshot"),
        lambda: ParcelCoordinate("4117310100102810001", float("nan"), 126.9, "snapshot"),
        lambda: ParcelCoordinate("4117310100102810001", 37.4, 140.0, "snapshot"),
    ],
)
def test_enrichment_value_objects_reject_unsafe_values(factory) -> None:
    with pytest.raises(ValueError):
        factory()


class _TargetRepository:
    def __init__(self) -> None:
        self.published = ()

    def active_non_spatial_rows(self):
        return (
            PUBLICATION_ID,
            4_176,
            3_497,
            (
                RetailRegistryFact(
                    publication_id=PUBLICATION_ID,
                    fact_id="store-exact",
                    lot_address="경기도 안양시 동안구 비산동 281-1 1단지상가",
                ),
                RetailRegistryFact(
                    publication_id=PUBLICATION_ID,
                    fact_id="store-unresolved",
                    lot_address="경기도 안양시 동안구 비산동 호",
                ),
            ),
        )

    def publish(self, publication_id, enrichments):
        assert publication_id == PUBLICATION_ID
        self.published = enrichments
        return len(enrichments)


class _RegionRepository:
    def region_paths(self):
        return (_region(),)


class _CoordinateRepository:
    def coordinates(self, pnus):
        assert pnus == ("4117310100102810001",)
        return {
            "4117310100102810001": ParcelCoordinate(
                pnu="4117310100102810001",
                latitude=37.4,
                longitude=126.9,
                snapshot_version="vworld-2026-07",
            )
        }


def test_service_publishes_only_exact_coordinate_snapshot_matches() -> None:
    target = _TargetRepository()

    report = RetailCoordinateEnrichmentService(
        target_repository=target,
        region_repository=_RegionRepository(),
        coordinate_repository=_CoordinateRepository(),
        clock=lambda: datetime(2026, 7, 21, tzinfo=UTC),
    ).run()

    assert report.matched_count == 1
    assert report.unresolved_count == 1
    assert report.spatial_count == 3_498
    assert report.coordinate_coverage == 3_498 / 4_176
    assert target.published[0].fact_id == "store-exact"
    assert target.published[0].resolution_method == "EXACT_LOT_PNU"


@pytest.mark.parametrize(
    ("total_count", "spatial_count", "rows"),
    [
        (0, 0, ()),
        (1, 2, ()),
        (
            2,
            1,
            (
                RetailRegistryFact(PUBLICATION_ID, "duplicate", "주소"),
                RetailRegistryFact(PUBLICATION_ID, "duplicate", "주소2"),
            ),
        ),
        (
            1,
            0,
            (
                RetailRegistryFact(
                    UUID("20000000-0000-0000-0000-000000000001"),
                    "wrong-publication",
                    "주소",
                ),
            ),
        ),
    ],
)
def test_service_rejects_unbounded_or_inconsistent_inputs(
    total_count, spatial_count, rows
) -> None:
    target = _TargetRepository()
    target.active_non_spatial_rows = lambda: (
        PUBLICATION_ID,
        total_count,
        spatial_count,
        rows,
    )
    with pytest.raises(RuntimeError, match="bounded policy"):
        RetailCoordinateEnrichmentService(
            target_repository=target,
            region_repository=_RegionRepository(),
            coordinate_repository=_CoordinateRepository(),
        ).run()


def test_service_rejects_coordinate_source_pollution_and_incomplete_publish() -> None:
    target = _TargetRepository()

    class PollutedCoordinates:
        def coordinates(self, _pnus):
            return {
                "4117310100109990001": ParcelCoordinate(
                    "4117310100109990001", 37.4, 126.9, "snapshot"
                )
            }

    with pytest.raises(RuntimeError, match="unrequested PNU"):
        RetailCoordinateEnrichmentService(
            target_repository=target,
            region_repository=_RegionRepository(),
            coordinate_repository=PollutedCoordinates(),
        ).run()

    target.publish = lambda *_args: 0
    with pytest.raises(RuntimeError, match="publication is incomplete"):
        RetailCoordinateEnrichmentService(
            target_repository=target,
            region_repository=_RegionRepository(),
            coordinate_repository=_CoordinateRepository(),
        ).run()

    with pytest.raises(ValueError, match="timezone-aware"):
        RetailCoordinateEnrichmentService(
            target_repository=_TargetRepository(),
            region_repository=_RegionRepository(),
            coordinate_repository=_CoordinateRepository(),
            clock=lambda: datetime(2026, 7, 21),
        ).run()


class _Cursor:
    def __init__(self, *, one=None, rows=()):
        self._one = one
        self._rows = rows

    def fetchone(self):
        return self._one

    def fetchall(self):
        return self._rows


class _Connection:
    def __init__(self, database: str, username: str):
        self.info = SimpleNamespace(dbname=database, user=username)
        self.executed = []

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def execute(self, query, parameters=None):
        self.executed.append((query, parameters))
        if "FROM dataset_active_snapshot active" in query:
            return _Cursor(one={
                "publication_id": PUBLICATION_ID,
                "total_count": 4_176,
                "spatial_count": 3_708,
            })
        if "FROM reference_projection.registry_fact registry" in query:
            return _Cursor(rows=({
                "publication_id": PUBLICATION_ID,
                "fact_id": "store-1",
                "lot_address": "경기도 안양시 동안구 비산동 281-1",
            },))
        if "SELECT publication_id FROM dataset_active_snapshot" in query:
            return _Cursor(one={"publication_id": PUBLICATION_ID})
        if "FROM ai_read.region_fact child" in query:
            return _Cursor(rows=({
                "legal_dong_code": "41173101",
                "sido_name": "경기도",
                "sigungu_name": "안양시 동안구",
                "eup_myeon_dong_name": "비산동",
            },))
        if "FROM reference.parcel_coordinate_snapshot" in query:
            return _Cursor(rows=({
                "pnu": "4117310100102810001",
                "latitude": 37.4,
                "longitude": 126.9,
                "snapshot_version": "snapshot-v1",
            },))
        return _Cursor()


def test_postgres_repositories_keep_database_roles_and_exact_queries(
    monkeypatch,
) -> None:
    ai_connection = _Connection("home_search_ai", "home_search_ai_importer")
    monkeypatch.setattr(
        enrichment_module.psycopg, "connect", lambda *_args, **_kwargs: ai_connection
    )
    target = PostgresRetailEnrichmentTargetRepository("ai-dsn")
    publication_id, total, spatial, rows = target.active_non_spatial_rows()
    assert (publication_id, total, spatial, rows[0].fact_id) == (
        PUBLICATION_ID, 4_176, 3_708, "store-1"
    )
    enrichment = RetailCoordinateEnrichment(
        PUBLICATION_ID,
        "store-1",
        "4117310100102810001",
        37.4,
        126.9,
        "snapshot-v1",
        "EXACT_LOT_PNU",
        datetime(2026, 7, 21, tzinfo=UTC),
    )
    assert target.publish(PUBLICATION_ID, (enrichment,)) == 1
    with pytest.raises(ValueError, match="publication mismatch"):
        target.publish(
            UUID("20000000-0000-0000-0000-000000000001"), (enrichment,)
        )

    property_connection = _Connection("home_search", "home_search_ai_reader")
    monkeypatch.setattr(
        enrichment_module.psycopg,
        "connect",
        lambda *_args, **_kwargs: property_connection,
    )
    assert PostgresPropertyRegionRepository("property-dsn").region_paths() == (_region(),)

    coordinate_connection = _Connection(
        "home_search_coordinate_source", "home_search_coordinate_reader"
    )
    monkeypatch.setattr(
        enrichment_module.psycopg,
        "connect",
        lambda *_args, **_kwargs: coordinate_connection,
    )
    coordinates = PostgresCoordinateSnapshotRepository("coordinate-dsn").coordinates(
        ("4117310100102810001",)
    )
    assert coordinates["4117310100102810001"].snapshot_version == "snapshot-v1"
    assert PostgresCoordinateSnapshotRepository("coordinate-dsn").coordinates(()) == {}
    with pytest.raises(ValueError, match="bounded policy"):
        PostgresCoordinateSnapshotRepository("coordinate-dsn").coordinates(("bad",))


@pytest.mark.parametrize(
    ("repository", "database", "username", "message"),
    [
        (PostgresRetailEnrichmentTargetRepository, "wrong", "home_search_ai_importer", "target"),
        (PostgresPropertyRegionRepository, "wrong", "home_search_ai_reader", "property"),
        (
            PostgresCoordinateSnapshotRepository,
            "wrong",
            "home_search_coordinate_reader",
            "coordinate source",
        ),
    ],
)
def test_postgres_repositories_reject_wrong_database_boundary(
    monkeypatch, repository, database, username, message
) -> None:
    connection = _Connection(database, username)
    monkeypatch.setattr(
        enrichment_module.psycopg, "connect", lambda *_args, **_kwargs: connection
    )
    if repository is PostgresRetailEnrichmentTargetRepository:
        with pytest.raises(ValueError, match=message):
            repository("dsn")
    elif repository is PostgresPropertyRegionRepository:
        with pytest.raises(ValueError, match=message):
            repository("dsn").region_paths()
    else:
        with pytest.raises(ValueError, match=message):
            repository("dsn").coordinates(("4117310100102810001",))


def test_environment_entrypoint_requires_all_three_database_boundaries(
    monkeypatch, capsys
) -> None:
    with pytest.raises(RuntimeError, match="configuration is incomplete"):
        enrich_from_environment({})

    report = enrichment_module.RetailCoordinateEnrichmentReport(
        PUBLICATION_ID, 4_176, 3_708, 0, 468, 3_708 / 4_176
    )
    monkeypatch.setattr(enrichment_module, "enrich_from_environment", lambda _env: report)
    enrichment_module.main()
    output = capsys.readouterr().out
    assert "spatialCount: 3708" in output
    assert "coordinateCoverage: 0.887931" in output
