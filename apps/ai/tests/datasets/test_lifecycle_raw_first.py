from __future__ import annotations

from datetime import UTC, date, datetime
from hashlib import sha256
from pathlib import Path

import pytest
import psycopg

from ai_service.datasets.postgres import PostgresDatasetRepository
from ai_service.datasets.postgres import SourceRefreshAlreadyRunning
from ai_service.datasets.raw_store import RawObjectIntegrityError, StoredRawObject
from ai_service.datasets.service import DatasetLifecycleService
from ai_service.datasets.bundle import PreparedBundle
from tests.datasets.test_dataset_lifecycle import source_contract


class NeverCalledRepository:
    def register_source_contract(self, *_args, **_kwargs):
        raise AssertionError("database must not be touched before S3 verification")


class FailingRawStore:
    def put_verified(self, **_kwargs):
        raise RawObjectIntegrityError("HEAD mismatch")

    def put_verified_file(self, **_kwargs):
        raise RawObjectIntegrityError("HEAD mismatch")


def test_s3_verification_failure_happens_before_database_registration() -> None:
    lifecycle = DatasetLifecycleService(
        NeverCalledRepository(),  # type: ignore[arg-type]
        raw_store=FailingRawStore(),  # type: ignore[arg-type]
        clock=lambda: datetime(2026, 7, 19, tzinfo=UTC),
    )

    with pytest.raises(RawObjectIntegrityError):
        lifecycle.ingest_validate_publish(
            source_contract(),
            b'{"rows":[]}',
            source_date=date(2026, 7, 19),
            content_type="application/zip",
        )


def test_prepared_file_s3_failure_happens_before_database_and_uses_file_api(
    tmp_path: Path,
) -> None:
    path = tmp_path / "bundle.zip"
    path.write_bytes(b'{"rows":[]}')
    path.chmod(0o600)
    prepared = PreparedBundle(path, path.stat().st_size, sha256(path.read_bytes()).hexdigest())
    lifecycle = DatasetLifecycleService(
        NeverCalledRepository(),  # type: ignore[arg-type]
        raw_store=FailingRawStore(),  # type: ignore[arg-type]
        clock=lambda: datetime(2026, 7, 19, tzinfo=UTC),
    )

    with pytest.raises(RawObjectIntegrityError):
        lifecycle.ingest_validate_publish_prepared(
            source_contract(), prepared, source_date=date(2026, 7, 19),
            content_type="application/zip",
        )


class VerifiedRawStore:
    def put_verified(self, *, source_id, checksum, content, content_type):
        return StoredRawObject(
            storage_backend="S3",
            object_key=f"raw/v1/{source_id}/{checksum[:2]}/{checksum}.zip",
            object_version_id="version-1",
            content_type=content_type,
            byte_length=len(content),
            checksum=checksum,
        )


class FileOnlyRawStore:
    def put_verified(self, **_kwargs):
        raise AssertionError("prepared lifecycle must not upload through bytes API")

    def put_verified_file(
        self, *, source_id, checksum, path, byte_length, content_type
    ):
        assert path.is_file()
        assert path.stat().st_size == byte_length
        return StoredRawObject(
            storage_backend="S3",
            object_key=f"raw/v1/{source_id}/{checksum[:2]}/{checksum}.zip",
            object_version_id="file-version",
            content_type=content_type,
            byte_length=byte_length,
            checksum=checksum,
        )


def test_prepared_lifecycle_registers_verified_file_without_bytes_upload(
    dataset_repository: PostgresDatasetRepository,
    tmp_path: Path,
) -> None:
    content = b'{"rows":[{"station_id":"station-file","name":"File Station","latitude":37.5,"longitude":127.0}]}'
    path = tmp_path / "bundle.zip"
    path.write_bytes(content)
    path.chmod(0o600)
    lifecycle = DatasetLifecycleService(
        dataset_repository,
        raw_store=FileOnlyRawStore(),  # type: ignore[arg-type]
        clock=lambda: datetime(2026, 7, 19, tzinfo=UTC),
    )

    result = lifecycle.ingest_validate_publish_prepared(
        source_contract(),
        PreparedBundle(path, len(content), sha256(content).hexdigest()),
        source_date=date(2026, 7, 15),
    )

    assert result.status == "Pass"
    assert result.raw_row_count == 1


def test_verified_s3_metadata_is_registered_before_parse_and_inline_content_is_absent(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    raw = b'{"rows":[{"station_id":"station-1","name":"Fixture Station","latitude":37.5665,"longitude":126.978}]}'
    lifecycle = DatasetLifecycleService(
        dataset_repository,
        raw_store=VerifiedRawStore(),  # type: ignore[arg-type]
        clock=lambda: datetime(2026, 7, 19, tzinfo=UTC),
    )

    result = lifecycle.ingest_validate_publish(
        source_contract(),
        raw,
        source_date=date(2026, 7, 15),
        content_type="application/zip",
    )

    assert result.status == "Pass"
    with psycopg.connect(postgres_dsn) as connection:
        row = connection.execute(
            """
            SELECT storage_backend, content, object_key, object_version_id, byte_length
            FROM dataset_raw_object WHERE checksum = %s
            """,
            (result.checksum,),
        ).fetchone()
    assert row == (
        "S3",
        None,
        f"raw/v1/fixture.rail-station/{result.checksum[:2]}/{result.checksum}.zip",
        "version-1",
        len(raw),
    )


def test_source_advisory_lock_rejects_concurrent_same_source(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    other = PostgresDatasetRepository(postgres_dsn)
    try:
        with dataset_repository.source_lock("edu.school-location"):
            with pytest.raises(SourceRefreshAlreadyRunning):
                with other.source_lock("edu.school-location"):
                    raise AssertionError("unreachable")
    finally:
        other.close()


def test_incomplete_bundle_is_preserved_but_never_parsed_or_published(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    lifecycle = DatasetLifecycleService(
        dataset_repository,
        raw_store=VerifiedRawStore(),  # type: ignore[arg-type]
        clock=lambda: datetime(2026, 7, 19, tzinfo=UTC),
    )

    result = lifecycle.preserve_incomplete(
        source_contract(),
        b"incomplete-provider-pages",
        source_date=date(2026, 7, 15),
        reason_codes=("API_SERVER_ERROR",),
    )

    assert result.status == "Fail"
    assert result.publication_id is None
    assert result.issue_codes == ("API_SERVER_ERROR",)
    assert dataset_repository.publication_count("fixture.rail-station") == 0
