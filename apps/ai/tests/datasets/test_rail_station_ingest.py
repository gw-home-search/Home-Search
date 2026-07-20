from __future__ import annotations

from contextlib import nullcontext
from dataclasses import replace
from datetime import UTC, date, datetime
from hashlib import sha256
from uuid import UUID

import pytest

from ai_service.datasets import rail_station_ingest, reference_refresh
from ai_service.datasets.contracts import (
    AcquisitionContract,
    ReferenceSourceCatalog,
    load_reference_source_catalog,
)
from ai_service.datasets.file_snapshot_client import CollectedFileSnapshot, FileSnapshotError
from ai_service.datasets.models import AcquisitionRecord, LifecycleResult
from ai_service.datasets.raw_store import StoredRawObject


def _approved_source(*, fixture_release: bool = True):
    source = load_reference_source_catalog(rail_station_ingest._CONFIG_PATH).get(
        "transport.rail-station"
    )
    if fixture_release:
        source = replace(
            source,
            acquisition=AcquisitionContract(
                mode="file",
                base_url="https://files.example.go.kr/releases/rail-stations-20241231.xlsx",
                allowed_hosts=("files.example.go.kr",),
                allowed_path_prefixes=("/releases/",),
                format="XLSX",
                encoding="UTF-8",
                source_crs="EPSG:4326",
                maximum_bundle_bytes=268_435_456,
                redirect_policy="ALLOWLISTED_ONE_HOP",
            ),
        )
    return replace(
        source,
        license=replace(
            source.license,
            status="APPROVED",
            terms_fingerprint="c" * 64,
            reviewed_on=date(2026, 7, 20),
            reviewed_by="test",
            attribution_text="출처: 국가철도공단",
            raw_private_storage_allowed=True,
            internal_derivative_allowed=True,
        ),
    )


def _approved_catalog(*, fixture_release: bool = True):
    source = _approved_source(fixture_release=fixture_release)
    return type("Catalog", (), {"approved": lambda self, _source_id: source})()


def _environment():
    return {
        "HOME_AI_IMPORTER_DSN": "postgresql://home_search_ai_importer@db/home_search_ai",
        "HOME_AI_RAW_S3_BUCKET": "private-raw",
        "HOME_AI_RAW_S3_PREFIX": "raw",
        "HOME_AI_RAW_S3_REGION": "ap-northeast-2",
    }


def _result():
    return LifecycleResult(
        status="Pass",
        source_id="transport.rail-station",
        acquisition_id=UUID(int=1),
        publication_id=UUID(int=2),
        dataset_version="2024-12-31-abc",
        checksum="0" * 64,
        source_date=date(2024, 12, 31),
        collected_at=datetime(2026, 7, 20, tzinfo=UTC),
        raw_row_count=1073,
        accepted_row_count=1073,
        rejected_row_count=0,
        issue_codes=(),
        idempotent=True,
    )


class Repository:
    def __init__(self):
        self.finished = None
        self.closed = False
        self.contract = None

    def start_refresh_run(self, **_kwargs):
        return UUID(int=20)

    def finish_refresh_run(self, **kwargs):
        self.finished = kwargs

    def source_lock(self, source_id):
        assert source_id == "transport.rail-station"
        return nullcontext()

    def register_source_contract(self, _contract, _registered_at):
        self.contract = _contract
        return UUID(int=10)

    def acquire_stored_raw(self, *_args):
        return AcquisitionRecord(UUID(int=1), False)

    def result(self, _acquisition_id, *, idempotent):
        assert idempotent
        return _result()

    def close(self):
        self.closed = True


class Client:
    def collect(self, *, target):
        content = b"fixture-xlsx"
        target.write_bytes(content)
        target.chmod(0o600)
        return CollectedFileSnapshot(
            target,
            date(2024, 12, 31),
            len(content),
            sha256(content).hexdigest(),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "/releases/rail-stations-20241231.xlsx",
        )


class RawStore:
    def put_verified_file(self, *, source_id, checksum, path, byte_length, content_type):
        assert source_id == "transport.rail-station"
        assert path.stat().st_size == byte_length
        return StoredRawObject(
            "S3", f"raw/{checksum}.zip", "v1", content_type, byte_length, checksum
        )

    def put_verified(self, **_kwargs):
        raise AssertionError("file refresh must not use bytes upload")


def test_rail_refresh_uses_fixed_release_and_prepared_lifecycle(monkeypatch) -> None:
    repository = Repository()
    monkeypatch.setattr(
        rail_station_ingest,
        "load_reference_source_catalog",
        lambda _path: _approved_catalog(),
    )

    report = rail_station_ingest.ingest_from_environment(
        _environment(),
        repository_factory=lambda _dsn: repository,
        client_factory=lambda _reference: Client(),
        raw_store_factory=lambda _environment: RawStore(),
        today=lambda: date(2026, 7, 20),
        clock=lambda: datetime(2026, 7, 20, tzinfo=UTC),
    )

    assert report.result.status == "Pass"
    assert report.raw_row_count == 1073
    assert repository.contract.unique_key_fields == ("station_occurrence_id",)
    assert repository.contract.coordinate_system == "EPSG:4326"
    assert repository.contract.temporal_basis == "SOURCE_DATE"
    assert repository.finished["status"] == "PASS"
    assert repository.closed is True


def test_landing_page_is_rejected_as_acquisition_before_network() -> None:
    source = _approved_source(fixture_release=False)
    with pytest.raises(ValueError, match="fixed release XLSX URL"):
        rail_station_ingest._client(
            replace(
                source,
                acquisition=replace(
                    source.acquisition,
                    base_url="https://www.data.go.kr/data/15013205/standard.do",
                    allowed_hosts=("www.data.go.kr",),
                    allowed_path_prefixes=("/data/15013205/",),
                    fixed_query="",
                ),
            )
        )


def test_tracked_kric_release_builds_client_before_network() -> None:
    rail_station_ingest._client(_approved_source(fixture_release=False))


def test_landing_page_preflight_stops_before_raw_store_creation(monkeypatch) -> None:
    source = _approved_source(fixture_release=False)
    invalid_source = replace(
        source,
        acquisition=replace(
            source.acquisition,
            base_url="https://www.data.go.kr/data/15013205/standard.do",
            allowed_hosts=("www.data.go.kr",),
            allowed_path_prefixes=("/data/15013205/",),
            fixed_query="",
        ),
    )
    monkeypatch.setattr(
        rail_station_ingest,
        "load_reference_source_catalog",
        lambda _path: type(
            "Catalog", (), {"approved": lambda self, _source_id: invalid_source}
        )(),
    )

    def raw_store_factory(_environment):
        raise AssertionError("raw store must not be created before release URL preflight")

    with pytest.raises(RuntimeError):
        rail_station_ingest.ingest_from_environment(
            _environment(),
            raw_store_factory=raw_store_factory,
            today=lambda: date(2026, 7, 20),
        )


def test_pending_license_stops_before_repository_client_or_raw_store(monkeypatch) -> None:
    source = _approved_source()
    pending_source = replace(source, license=replace(source.license, status="PENDING"))
    monkeypatch.setattr(
        rail_station_ingest,
        "load_reference_source_catalog",
        lambda _path: ReferenceSourceCatalog((pending_source,)),
    )

    class NeverCalled:
        def __init__(self, *_args, **_kwargs):
            raise AssertionError("external dependency must not be created")

    with pytest.raises(RuntimeError):
        rail_station_ingest.ingest_from_environment(
            _environment(),
            repository_factory=NeverCalled,
            client_factory=lambda _reference: NeverCalled(),
            raw_store_factory=lambda _environment: NeverCalled(),
            today=lambda: date(2026, 7, 20),
        )


def test_download_failure_records_only_safe_reason_and_closes_repository(monkeypatch) -> None:
    repository = Repository()
    monkeypatch.setattr(
        rail_station_ingest,
        "load_reference_source_catalog",
        lambda _path: _approved_catalog(),
    )

    class FailedClient:
        def collect(self, *, target):
            raise FileSnapshotError("FILE_TRANSPORT_FAILED")

    with pytest.raises(FileSnapshotError):
        rail_station_ingest.ingest_from_environment(
            _environment(),
            repository_factory=lambda _dsn: repository,
            client_factory=lambda _reference: FailedClient(),
            raw_store_factory=lambda _environment: RawStore(),
            today=lambda: date(2026, 7, 20),
        )

    assert repository.finished["acquisition_id"] is None
    assert repository.finished["reason_codes"] == ("FILE_TRANSPORT_FAILED",)
    assert repository.closed is True


def test_static_catalog_composes_the_rail_refresher() -> None:
    definition = next(
        item
        for item in reference_refresh._SOURCE_DEFINITIONS
        if item.source_id == "transport.rail-station"
    )
    assert definition.refresh is rail_station_ingest.ingest_from_environment
