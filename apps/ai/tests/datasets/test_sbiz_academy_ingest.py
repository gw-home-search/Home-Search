from __future__ import annotations

from contextlib import nullcontext
from dataclasses import replace
from datetime import UTC, date, datetime
from uuid import UUID

import pytest

from ai_service.datasets import sbiz_academy_ingest
from ai_service.datasets.contracts import load_reference_source_catalog
from ai_service.datasets.models import AcquisitionRecord, LifecycleResult
from ai_service.datasets.raw_store import StoredRawObject
from ai_service.datasets.sbiz_academy_client import (
    CollectedSbizAcademyBundle, SbizAcademyApiError,
)
from tests.datasets.test_sbiz_academy_adapter import TAXONOMY, _bundle, _taxonomy


def _catalog():
    source = load_reference_source_catalog(sbiz_academy_ingest._CONFIG_PATH).get(
        "place.sbiz-academy"
    )
    source = replace(
        source,
        license=replace(
            source.license, status="APPROVED", terms_fingerprint="c" * 64,
            reviewed_on=date(2026, 7, 20), reviewed_by="test",
            attribution_text="출처: Sbiz", raw_private_storage_allowed=True,
            internal_derivative_allowed=True,
        ),
    )
    return type("Catalog", (), {"approved": lambda self, _source_id: source})()


def _environment():
    return {
        "HOME_AI_IMPORTER_DSN": "postgresql://home_search_ai_importer@db/home_search_ai",
        "HOME_AI_DATA_GO_KR_SERVICE_KEY": "key",
        "HOME_AI_RAW_S3_BUCKET": "private", "HOME_AI_RAW_S3_PREFIX": "raw",
        "HOME_AI_RAW_S3_REGION": "ap-northeast-2",
    }


def _result():
    return LifecycleResult(
        status="Pass", source_id="place.sbiz-academy",
        acquisition_id=UUID(int=1), publication_id=UUID(int=2),
        dataset_version="2026-07-20-abc", checksum="0" * 64,
        source_date=None, observed_at=datetime(2026, 7, 20, tzinfo=UTC),
        temporal_basis="OBSERVED_AT", collected_at=datetime(2026, 7, 20, tzinfo=UTC),
        raw_row_count=1, accepted_row_count=1, rejected_row_count=0,
        issue_codes=(), idempotent=True,
    )


class Repository:
    def __init__(self): self.finished = None; self.closed = False
    def start_refresh_run(self, **_kwargs): return UUID(int=20)
    def finish_refresh_run(self, **kwargs): self.finished = kwargs
    def source_lock(self, _source_id): return nullcontext()
    def register_source_contract(self, *_args): return UUID(int=10)
    def acquire_stored_raw(self, *_args): return AcquisitionRecord(UUID(int=1), False)
    def result(self, _id, *, idempotent): assert idempotent; return _result()
    def close(self): self.closed = True


class Client:
    def collect(self, key, *, observed_at):
        assert key == "key"
        return CollectedSbizAcademyBundle(_bundle(), observed_at, 1, 1, True, ())


class RawStore:
    def put_verified(self, *, source_id, checksum, content, content_type):
        return StoredRawObject("S3", f"raw/{checksum}.zip", "v1", content_type, len(content), checksum)


def test_sbiz_refresh_uses_tracked_taxonomy_and_static_orchestration(monkeypatch) -> None:
    repository = Repository()
    monkeypatch.setattr(sbiz_academy_ingest, "load_reference_source_catalog", lambda _path: _catalog())
    report = sbiz_academy_ingest.ingest_from_environment(
        _environment(), repository_factory=lambda _dsn: repository,
        client_factory=lambda _taxonomy, _artifacts: Client(),
        raw_store_factory=lambda _environment: RawStore(),
        taxonomy_loader=lambda: (_taxonomy(), TAXONOMY),
        today=lambda: date(2026, 7, 20),
        clock=lambda: datetime(2026, 7, 20, tzinfo=UTC),
    )
    assert report.result.status == "Pass"
    assert repository.finished["status"] == "PASS"
    assert repository.closed is True


def test_sbiz_first_page_failure_records_safe_reason(monkeypatch) -> None:
    repository = Repository()
    monkeypatch.setattr(sbiz_academy_ingest, "load_reference_source_catalog", lambda _path: _catalog())

    class Failed:
        def collect(self, _key, *, observed_at):
            raise SbizAcademyApiError("API_TRANSPORT_FAILED")

    with pytest.raises(SbizAcademyApiError):
        sbiz_academy_ingest.ingest_from_environment(
            _environment(), repository_factory=lambda _dsn: repository,
            client_factory=lambda *_args: Failed(),
            raw_store_factory=lambda _environment: RawStore(),
            taxonomy_loader=lambda: (_taxonomy(), TAXONOMY),
            today=lambda: date(2026, 7, 20),
        )
    assert repository.finished["reason_codes"] == ("API_TRANSPORT_FAILED",)
    assert repository.closed is True


def test_pending_license_stops_before_missing_taxonomy_file() -> None:
    with pytest.raises(RuntimeError):
        sbiz_academy_ingest.ingest_from_environment(_environment())
