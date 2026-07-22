from __future__ import annotations

from contextlib import nullcontext
from dataclasses import replace
from datetime import UTC, date, datetime
from uuid import UUID

import pytest

from ai_service.datasets import childcare_ingest
from ai_service.datasets.childcare_client import CollectedChildcareBundle
from ai_service.datasets.contracts import load_reference_source_catalog
from ai_service.datasets.models import AcquisitionRecord, LifecycleResult
from ai_service.datasets.raw_store import StoredRawObject
from tests.datasets.test_childcare_adapter import OBSERVED_AT, _bundle, _response


def test_childcare_refresh_uses_dedicated_key_and_bounded_region_scope(monkeypatch) -> None:
    source = load_reference_source_catalog(childcare_ingest._CONFIG_PATH).get(
        "childcare.center"
    )
    approved = replace(
        source,
        license=replace(
            source.license,
            status="APPROVED",
            terms_fingerprint="d" * 64,
            reviewed_on=date(2026, 7, 21),
            reviewed_by="test",
            attribution_text="출처: 어린이집정보공개포털",
            raw_private_storage_allowed=True,
            internal_derivative_allowed=True,
        ),
    )
    monkeypatch.setattr(
        childcare_ingest,
        "load_reference_source_catalog",
        lambda _path: type(
            "Catalog", (), {"approved": lambda self, _source_id: approved}
        )(),
    )
    repository = Repository()
    client = Client()

    report = childcare_ingest.ingest_from_environment(
        {
            "HOME_AI_IMPORTER_DSN": (
                "postgresql://home_search_ai_importer@db/home_search_ai"
            ),
            "HOME_AI_CHILDCARE_SERVICE_KEY": "childcare-key",
            "HOME_AI_CHILDCARE_REGION_CODES": "11710,11680",
        },
        repository_factory=lambda _dsn: repository,
        client_factory=lambda: client,
        raw_store_factory=lambda _environment: RawStore(),
        today=lambda: date(2026, 7, 21),
        clock=lambda: OBSERVED_AT,
    )

    assert report.result.status == "Pass"
    assert client.request == (
        "childcare-key",
        ("11710", "11680"),
        OBSERVED_AT,
    )
    assert repository.finished["status"] == "PASS"
    assert repository.closed is True


@pytest.mark.parametrize("value", ("", "11710,11710", "1171", "11710,unsafe"))
def test_childcare_refresh_rejects_invalid_region_scope(value: str) -> None:
    with pytest.raises(
        childcare_ingest.SchoolLocationConfigurationError,
        match="REGION_CODES",
    ):
        childcare_ingest._region_codes(value)


class Client:
    request = None

    def collect(self, key, *, region_codes, observed_at):
        self.request = (key, region_codes, observed_at)
        content = _bundle(
            _response(
                stcode="11620000341",
                name="꿈나무어린이집",
                center_type="국공립",
                status="정상",
                latitude="37.5131",
                longitude="127.0822",
            )
        )
        return CollectedChildcareBundle(
            content, observed_at, 1, 1, True, ()
        )


class Repository:
    def __init__(self):
        self.finished = None
        self.closed = False

    def start_refresh_run(self, **_kwargs):
        return UUID(int=20)

    def finish_refresh_run(self, **kwargs):
        self.finished = kwargs

    def source_lock(self, _source_id):
        return nullcontext()

    def register_source_contract(self, *_args):
        return UUID(int=10)

    def acquire_stored_raw(self, *_args):
        return AcquisitionRecord(UUID(int=1), False)

    def result(self, _id, *, idempotent):
        assert idempotent is True
        return _result()

    def close(self):
        self.closed = True


class RawStore:
    def put_verified(self, *, source_id, checksum, content, content_type):
        return StoredRawObject(
            "S3", f"raw/{checksum}.zip", "v1", content_type, len(content), checksum
        )


def _result() -> LifecycleResult:
    return LifecycleResult(
        status="Pass",
        source_id="childcare.center",
        acquisition_id=UUID(int=1),
        publication_id=UUID(int=2),
        dataset_version="2026-07-21-abc",
        checksum="0" * 64,
        source_date=None,
        observed_at=OBSERVED_AT,
        temporal_basis="OBSERVED_AT",
        collected_at=datetime(2026, 7, 21, tzinfo=UTC),
        raw_row_count=1,
        accepted_row_count=1,
        rejected_row_count=0,
        issue_codes=(),
        idempotent=True,
    )
