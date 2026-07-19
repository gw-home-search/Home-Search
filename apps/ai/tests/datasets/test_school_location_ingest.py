from __future__ import annotations

from datetime import UTC, date, datetime
from contextlib import nullcontext
from uuid import UUID

import pytest

from ai_service.datasets.models import AcquisitionRecord, LifecycleResult
from ai_service.datasets.school_location_client import (
    CollectedSchoolBundle,
    SchoolLocationApiError,
)
from ai_service.datasets.school_location_ingest import (
    SchoolLocationConfigurationError,
    SchoolLocationIngestReport,
    ingest_from_environment,
    main,
)
from ai_service.datasets.raw_store import StoredRawObject


class NeverCalled:
    def __init__(self, *_args, **_kwargs) -> None:
        raise AssertionError("external dependencies must not be created")


@pytest.mark.parametrize(
    "environment",
    [
        {},
        {"HOME_AI_IMPORTER_DSN": "postgresql://importer@db/home_search_ai"},
        {
            "HOME_AI_IMPORTER_DSN": "postgresql://importer@db/home_search_ai",
            "HOME_AI_DATA_GO_KR_SERVICE_KEY": "key",
        },
    ],
)
def test_cli_fails_configuration_before_network_or_database(environment) -> None:
    with pytest.raises(RuntimeError):
        ingest_from_environment(
            environment,
            repository_factory=NeverCalled,
            client_factory=NeverCalled,
            today=lambda: date(2026, 7, 19),
        )


def _environment() -> dict[str, str]:
    return {
        "HOME_AI_IMPORTER_DSN": "postgresql://home_search_ai_importer@db/home_search_ai",
        "HOME_AI_DATA_GO_KR_SERVICE_KEY": "key",
        "HOME_AI_RAW_S3_BUCKET": "private-raw",
        "HOME_AI_RAW_S3_PREFIX": "raw",
        "HOME_AI_RAW_S3_REGION": "ap-northeast-2",
    }


def _result(status: str = "Pass") -> LifecycleResult:
    return LifecycleResult(
        status=status,  # type: ignore[arg-type]
        source_id="edu.school-location",
        acquisition_id=UUID(int=1),
        publication_id=UUID(int=2) if status == "Pass" else None,
        dataset_version="2026-03-20-abc" if status == "Pass" else None,
        checksum="0" * 64,
        source_date=date(2026, 3, 20),
        collected_at=datetime(2026, 7, 19, tzinfo=UTC),
        raw_row_count=17,
        accepted_row_count=17,
        rejected_row_count=0,
        issue_codes=(),
        idempotent=False,
    )


class IdempotentRepository:
    def __init__(self, dsn: str) -> None:
        assert dsn.endswith("/home_search_ai")
        self.closed = False

    def register_source_contract(self, _contract, _registered_at):
        return UUID(int=10)

    def acquire_stored_raw(self, *_args, **_kwargs):
        return AcquisitionRecord(acquisition_id=UUID(int=1), created=False)

    def result(self, _acquisition_id, *, idempotent: bool):
        assert idempotent is True
        return _result()

    def close(self) -> None:
        self.closed = True

    def source_lock(self, _source_id: str):
        return nullcontext()


class CompleteClient:
    def collect(self, service_key: str) -> CollectedSchoolBundle:
        assert service_key == "key"
        return CollectedSchoolBundle(
            content=b"preserved-bundle",
            source_date=date(2026, 3, 20),
            page_count=1,
            raw_row_count=17,
            complete=True,
            reason_codes=(),
        )


class VerifiedRawStore:
    def put_verified(self, *, source_id, checksum, content, content_type):
        assert source_id == "edu.school-location"
        assert content == b"preserved-bundle"
        return StoredRawObject(
            storage_backend="S3",
            object_key=f"raw/v1/{source_id}/{checksum[:2]}/{checksum}.zip",
            object_version_id="version-1",
            content_type=content_type,
            byte_length=len(content),
            checksum=checksum,
        )


def _raw_store_factory(_environment):
    return VerifiedRawStore()


def test_ingest_uses_approved_contract_and_closes_repository() -> None:
    report = ingest_from_environment(
        _environment(),
        repository_factory=IdempotentRepository,  # type: ignore[arg-type]
        client_factory=CompleteClient,
        raw_store_factory=_raw_store_factory,  # type: ignore[arg-type]
        today=lambda: date(2026, 7, 19),
    )

    assert report.result.status == "Pass"
    assert report.page_count == 1


@pytest.mark.parametrize("missing_name", ["HOME_AI_RAW_S3_BUCKET", "HOME_AI_RAW_S3_PREFIX"])
def test_ingest_rejects_invalid_raw_store_configuration(missing_name: str) -> None:
    environment = _environment()
    environment.pop(missing_name)

    with pytest.raises(SchoolLocationConfigurationError):
        ingest_from_environment(
            environment,
            repository_factory=NeverCalled,
            client_factory=NeverCalled,
            today=lambda: date(2026, 7, 19),
        )


@pytest.mark.parametrize(
    ("dsn", "service_key"),
    [
        ("postgresql://wrong_role@db/home_search_ai", "key"),
        ("postgresql://home_search_ai_importer@db/wrong_database", "key"),
        ("not-a-postgresql-dsn", "key"),
        (
            "postgresql://home_search_ai_importer@db/home_search_ai",
            "x" * 1025,
        ),
    ],
)
def test_ingest_rejects_invalid_importer_boundary_before_external_calls(
    dsn: str,
    service_key: str,
) -> None:
    environment = _environment()
    environment["HOME_AI_IMPORTER_DSN"] = dsn
    environment["HOME_AI_DATA_GO_KR_SERVICE_KEY"] = service_key

    with pytest.raises(SchoolLocationConfigurationError):
        ingest_from_environment(
            environment,
            repository_factory=NeverCalled,
            client_factory=NeverCalled,
            today=lambda: date(2026, 7, 19),
        )


def test_main_prints_bounded_success_report(monkeypatch, capsys) -> None:
    monkeypatch.setattr(
        "ai_service.datasets.school_location_ingest.ingest_from_environment",
        lambda _environment: SchoolLocationIngestReport(
            result=_result(), page_count=1, raw_row_count=17
        ),
    )

    with pytest.raises(SystemExit) as exit_info:
        main()

    output = capsys.readouterr().out
    assert exit_info.value.code == 0
    assert "상태: Pass" in output
    assert "datasetVersion: 2026-03-20-abc" in output
    assert "key" not in output


def test_main_maps_configuration_and_runtime_failures_to_safe_exit_codes(
    monkeypatch, capsys
) -> None:
    monkeypatch.setattr(
        "ai_service.datasets.school_location_ingest.ingest_from_environment",
        lambda _environment: (_ for _ in ()).throw(SchoolLocationConfigurationError()),
    )
    with pytest.raises(SystemExit) as config_exit:
        main()
    assert config_exit.value.code == 2
    assert "CONFIGURATION_INVALID" in capsys.readouterr().out

    monkeypatch.setattr(
        "ai_service.datasets.school_location_ingest.ingest_from_environment",
        lambda _environment: (_ for _ in ()).throw(RuntimeError("secret detail")),
    )
    with pytest.raises(SystemExit) as runtime_exit:
        main()
    output = capsys.readouterr().out
    assert runtime_exit.value.code == 1
    assert "INGEST_FAILED" in output
    assert "secret detail" not in output


def test_main_preserves_allowlisted_provider_failure_reason(monkeypatch, capsys) -> None:
    monkeypatch.setattr(
        "ai_service.datasets.school_location_ingest.ingest_from_environment",
        lambda _environment: (_ for _ in ()).throw(
            SchoolLocationApiError("API_AUTHENTICATION_FAILED")
        ),
    )

    with pytest.raises(SystemExit) as provider_exit:
        main()

    output = capsys.readouterr().out
    assert provider_exit.value.code == 1
    assert "reasonCodes: API_AUTHENTICATION_FAILED" in output
    assert "INGEST_FAILED" not in output
