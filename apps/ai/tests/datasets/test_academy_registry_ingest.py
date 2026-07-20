from __future__ import annotations

from contextlib import nullcontext
from dataclasses import replace
from datetime import UTC, date, datetime
from hashlib import sha256
from uuid import UUID

import pytest

from ai_service.datasets import academy_registry_ingest
from ai_service.datasets.academy_registry_client import (
    AcademyRegistryApiError,
    PreparedAcademyRegistryBundle,
)
from ai_service.datasets.bundle import PreparedBundle
from ai_service.datasets.contracts import load_reference_source_catalog
from ai_service.datasets.models import AcquisitionRecord, LifecycleResult
from ai_service.datasets.raw_store import StoredRawObject


CONFIG = academy_registry_ingest._CONFIG_PATH


def _environment():
    return {
        "HOME_AI_IMPORTER_DSN": "postgresql://home_search_ai_importer@db/home_search_ai",
        "HOME_AI_NEIS_SERVICE_KEY": "neis-key",
        "HOME_AI_RAW_S3_BUCKET": "private-raw",
        "HOME_AI_RAW_S3_PREFIX": "raw",
        "HOME_AI_RAW_S3_REGION": "ap-northeast-2",
    }


def _approved_catalog():
    source = load_reference_source_catalog(CONFIG).get("edu.academy-registry")
    source = replace(
        source,
        license=replace(
            source.license,
            status="APPROVED", terms_fingerprint="a" * 64,
            reviewed_on=date(2026, 7, 20), reviewed_by="test",
            attribution_text="출처: NEIS", raw_private_storage_allowed=True,
            internal_derivative_allowed=True,
        ),
    )
    return type("Catalog", (), {"approved": lambda self, _source_id: source})()


def _result():
    return LifecycleResult(
        status="Pass", source_id="edu.academy-registry",
        acquisition_id=UUID(int=1), publication_id=UUID(int=2),
        dataset_version="2026-07-20-abc", checksum="0" * 64,
        source_date=None, observed_at=datetime(2026, 7, 20, tzinfo=UTC),
        temporal_basis="OBSERVED_AT",
        collected_at=datetime(2026, 7, 20, tzinfo=UTC), raw_row_count=17,
        accepted_row_count=17, rejected_row_count=0, issue_codes=(),
        idempotent=True,
    )


class _Repository:
    def __init__(self, _dsn):
        self.closed = False
        self.finished = None

    def start_refresh_run(self, **kwargs):
        assert kwargs["source_id"] == "edu.academy-registry"
        return UUID(int=20)

    def finish_refresh_run(self, **kwargs):
        self.finished = kwargs

    def source_lock(self, source_id):
        assert source_id == "edu.academy-registry"
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


class _Client:
    def collect_prepared(self, key, *, observed_at, workspace):
        assert key == "neis-key"
        path = workspace.create_file("fixture-bundle.zip")
        path.write_bytes(b"bundle")
        return PreparedAcademyRegistryBundle(
            PreparedBundle(path, 6, sha256(b"bundle").hexdigest()),
            observed_at, 17, 17, True, ()
        )


class _RawStore:
    def put_verified(self, **_kwargs):
        raise AssertionError("API refresh must use file upload")

    def put_verified_file(self, *, source_id, checksum, path, byte_length, content_type):
        return StoredRawObject(
            "S3", f"raw/{checksum}.zip", "v1", content_type,
            byte_length, checksum,
        )


def test_academy_ingest_uses_single_observation_time_and_closes_repository(monkeypatch) -> None:
    repository = _Repository("dsn")
    monkeypatch.setattr(
        academy_registry_ingest, "load_reference_source_catalog",
        lambda _path: _approved_catalog(),
    )
    observed_at = datetime(2026, 7, 20, tzinfo=UTC)
    report = academy_registry_ingest.ingest_from_environment(
        _environment(), repository_factory=lambda _dsn: repository,
        client_factory=_Client, raw_store_factory=lambda _environment: _RawStore(),
        today=lambda: date(2026, 7, 20), clock=lambda: observed_at,
    )

    assert report.result.status == "Pass"
    assert report.page_count == 17
    assert repository.finished["status"] == "PASS"
    assert repository.closed is True


def test_academy_first_page_failure_records_only_safe_reason(monkeypatch) -> None:
    repository = _Repository("dsn")
    monkeypatch.setattr(
        academy_registry_ingest, "load_reference_source_catalog",
        lambda _path: _approved_catalog(),
    )

    class FailedClient:
        def collect_prepared(self, _key, *, observed_at, workspace):
            raise AcademyRegistryApiError("API_TRANSPORT_FAILED")

    with pytest.raises(AcademyRegistryApiError):
        academy_registry_ingest.ingest_from_environment(
            _environment(), repository_factory=lambda _dsn: repository,
            client_factory=FailedClient,
            raw_store_factory=lambda _environment: _RawStore(),
            today=lambda: date(2026, 7, 20),
        )

    assert repository.finished["acquisition_id"] is None
    assert repository.finished["reason_codes"] == ("API_TRANSPORT_FAILED",)
    assert repository.closed is True
