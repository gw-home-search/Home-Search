from __future__ import annotations

from contextlib import nullcontext
from dataclasses import replace
from datetime import UTC, date, datetime
from hashlib import sha256
from uuid import UUID

import pytest

from ai_service.datasets import large_store_ingest
from ai_service.datasets.contracts import load_reference_source_catalog
from ai_service.datasets.file_snapshot_client import CollectedFileSnapshot
from ai_service.datasets.file_snapshot_client import FileSnapshotError
from ai_service.datasets.models import AcquisitionRecord, LifecycleResult
from ai_service.datasets.raw_store import StoredRawObject


def _approved_catalog():
    source = load_reference_source_catalog(large_store_ingest._CONFIG_PATH).get(
        "retail.large-store"
    )
    source = replace(
        source,
        license=replace(
            source.license, status="APPROVED", terms_fingerprint="b" * 64,
            reviewed_on=date(2026, 7, 20), reviewed_by="test",
            attribution_text="출처: LOCALDATA", raw_private_storage_allowed=True,
            internal_derivative_allowed=True,
        ),
    )
    return type("Catalog", (), {"approved": lambda self, _source_id: source})()


def _environment():
    return {
        "HOME_AI_IMPORTER_DSN": "postgresql://home_search_ai_importer@db/home_search_ai",
        "HOME_AI_RAW_S3_BUCKET": "private-raw", "HOME_AI_RAW_S3_PREFIX": "raw",
        "HOME_AI_RAW_S3_REGION": "ap-northeast-2",
    }


def _result():
    return LifecycleResult(
        status="Pass", source_id="retail.large-store",
        acquisition_id=UUID(int=1), publication_id=UUID(int=2),
        dataset_version="2026-07-18-abc", checksum="0" * 64,
        source_date=date(2026, 7, 18),
        collected_at=datetime(2026, 7, 20, tzinfo=UTC), raw_row_count=4003,
        accepted_row_count=4003, rejected_row_count=0, issue_codes=(),
        idempotent=True,
    )


class Repository:
    def __init__(self):
        self.finished = None
        self.closed = False

    def start_refresh_run(self, **_kwargs):
        return UUID(int=20)

    def finish_refresh_run(self, **kwargs):
        self.finished = kwargs

    def source_lock(self, source_id):
        assert source_id == "retail.large-store"
        return nullcontext()

    def register_source_contract(self, _contract, _registered_at):
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
        content = b"fixture-csv"
        target.write_bytes(content)
        target.chmod(0o600)
        return CollectedFileSnapshot(
            target, date(2026, 7, 18), len(content), sha256(content).hexdigest(),
            "text/csv", "/file/large_scale_retail_stores/snapshot-20260718.csv",
        )


class RawStore:
    def put_verified_file(self, *, source_id, checksum, path, byte_length, content_type):
        assert source_id == "retail.large-store"
        assert path.stat().st_size == byte_length
        return StoredRawObject(
            "S3", f"raw/{checksum}.zip", "v1", content_type,
            byte_length, checksum,
        )

    def put_verified(self, **_kwargs):
        raise AssertionError("file refresh must not use bytes upload")


def test_large_store_refresh_downloads_file_builds_bundle_and_uses_prepared_lifecycle(
    monkeypatch,
) -> None:
    repository = Repository()
    monkeypatch.setattr(
        large_store_ingest, "load_reference_source_catalog",
        lambda _path: _approved_catalog(),
    )

    report = large_store_ingest.ingest_from_environment(
        _environment(), repository_factory=lambda _dsn: repository,
        client_factory=lambda _reference: Client(),
        raw_store_factory=lambda _environment: RawStore(),
        today=lambda: date(2026, 7, 20),
        clock=lambda: datetime(2026, 7, 20, tzinfo=UTC),
    )

    assert report.result.status == "Pass"
    assert report.raw_row_count == 4003
    assert repository.finished["status"] == "PASS"
    assert repository.closed is True


def test_pending_license_stops_before_repository_client_or_raw_store() -> None:
    class NeverCalled:
        def __init__(self, *_args, **_kwargs):
            raise AssertionError("external dependency must not be created")

    with pytest.raises(RuntimeError):
        large_store_ingest.ingest_from_environment(
            _environment(), repository_factory=NeverCalled,
            client_factory=lambda _reference: NeverCalled(),
            raw_store_factory=lambda _environment: NeverCalled(),
            today=lambda: date(2026, 7, 20),
        )


def test_download_failure_records_only_safe_reason_and_closes_repository(monkeypatch) -> None:
    repository = Repository()
    monkeypatch.setattr(
        large_store_ingest, "load_reference_source_catalog",
        lambda _path: _approved_catalog(),
    )

    class FailedClient:
        def collect(self, *, target):
            raise FileSnapshotError("FILE_TRANSPORT_FAILED")

    with pytest.raises(FileSnapshotError):
        large_store_ingest.ingest_from_environment(
            _environment(), repository_factory=lambda _dsn: repository,
            client_factory=lambda _reference: FailedClient(),
            raw_store_factory=lambda _environment: RawStore(),
            today=lambda: date(2026, 7, 20),
        )

    assert repository.finished["acquisition_id"] is None
    assert repository.finished["reason_codes"] == ("FILE_TRANSPORT_FAILED",)
    assert repository.closed is True
