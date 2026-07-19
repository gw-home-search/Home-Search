from __future__ import annotations

from datetime import UTC, date, datetime

import pytest
import psycopg

from ai_service.datasets.postgres import PostgresDatasetRepository
from ai_service.datasets.postgres import SourceRefreshAlreadyRunning
from ai_service.datasets.raw_store import RawObjectIntegrityError, StoredRawObject
from ai_service.datasets.service import DatasetLifecycleService
from tests.datasets.test_dataset_lifecycle import source_contract


class NeverCalledRepository:
    def register_source_contract(self, *_args, **_kwargs):
        raise AssertionError("database must not be touched before S3 verification")


class FailingRawStore:
    def put_verified(self, **_kwargs):
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
