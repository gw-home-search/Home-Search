from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path

from .bundle import FileBundleArtifact, build_deterministic_bundle_file
from .contracts import ReferenceSourceContract, load_reference_source_catalog
from .file_snapshot_client import FileSnapshotClient, FileSnapshotError
from .large_store import LargeStoreAdapter, large_store_source_contract
from .models import LifecycleResult
from .postgres import PostgresDatasetRepository
from .raw_store import S3RawObjectStore, s3_raw_store_from_environment
from .school_location_ingest import (
    SchoolLocationConfigurationError,
    _importer_repository,
    _required,
    _validate_importer_dsn,
)
from .secure_temp import SecureTempWorkspace
from .service import DatasetLifecycleService


_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config" / "reference_sources.toml"


@dataclass(frozen=True)
class LargeStoreIngestReport:
    result: LifecycleResult
    page_count: int
    raw_row_count: int


def ingest_from_environment(
    environment: Mapping[str, str],
    *,
    repository_factory: Callable[[str], PostgresDatasetRepository] = _importer_repository,
    client_factory: Callable[[ReferenceSourceContract], FileSnapshotClient] | None = None,
    raw_store_factory: Callable[[Mapping[str, str]], S3RawObjectStore] = s3_raw_store_from_environment,
    today: Callable[[], date] = date.today,
    clock: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> LargeStoreIngestReport:
    importer_dsn = _required(environment, "HOME_AI_IMPORTER_DSN")
    _validate_importer_dsn(importer_dsn)
    try:
        reference = load_reference_source_catalog(_CONFIG_PATH).approved("retail.large-store")
        if reference.license.reviewed_on is None or reference.license.reviewed_on > today():
            raise ValueError("large-store license review date is invalid")
        contract = large_store_source_contract(reference)
        raw_store = raw_store_factory(environment)
        client = (client_factory or _client)(reference)
    except (KeyError, RuntimeError, ValueError) as exception:
        raise SchoolLocationConfigurationError("large-store source contract is invalid") from exception

    started_at = clock()
    repository = repository_factory(importer_dsn)
    refresh_run_id = repository.start_refresh_run(
        source_id=contract.source_id, provider=contract.provider,
        profile=f"source:{contract.source_id}", trigger_type="MANUAL",
        started_at=started_at,
    )
    try:
        with repository.source_lock(contract.source_id), SecureTempWorkspace(
            required_free_bytes=reference.acquisition.maximum_bundle_bytes * 2
        ) as workspace:
            artifact = client.collect(target=workspace.path / "provider.csv")
            bundle = build_deterministic_bundle_file(
                source_id=contract.source_id,
                endpoint_path=artifact.endpoint_path,
                artifacts=(
                    FileBundleArtifact("large-store", "csv", "text/csv", artifact.path),
                ),
                temporal_value=artifact.source_date,
                target=workspace.create_file("bundle.zip"),
            )
            result = DatasetLifecycleService(
                repository, raw_store=raw_store
            ).ingest_validate_publish_prepared(
                contract, bundle, source_date=artifact.source_date,
                adapter=LargeStoreAdapter(), content_type="application/zip",
            )
    except FileSnapshotError as exception:
        _finish_failure(repository, refresh_run_id, contract.source_id, exception.reason_code, clock())
        raise
    except Exception:
        _finish_failure(repository, refresh_run_id, contract.source_id, "INGEST_FAILED", clock())
        raise
    else:
        repository.finish_refresh_run(
            refresh_run_id=refresh_run_id, source_id=contract.source_id,
            acquisition_id=result.acquisition_id,
            status="PASS" if result.status == "Pass" else "NO_CHANGE" if result.status == "NoChange" else "FAIL",
            reason_codes=result.issue_codes, finished_at=clock(),
        )
    finally:
        repository.close()
    return LargeStoreIngestReport(result=result, page_count=1, raw_row_count=result.raw_row_count)


def _client(reference: ReferenceSourceContract) -> FileSnapshotClient:
    acquisition = reference.acquisition
    return FileSnapshotClient(
        source_id=reference.id, url=acquisition.base_url,
        allowed_hosts=acquisition.allowed_hosts,
        allowed_path_prefixes=acquisition.allowed_path_prefixes,
        media_types=("text/csv",), extension="csv",
        maximum_bytes=acquisition.maximum_bundle_bytes,
        allow_one_redirect=acquisition.redirect_policy == "ALLOWLISTED_ONE_HOP",
    )


def _finish_failure(
    repository: PostgresDatasetRepository, refresh_run_id: object,
    source_id: str, reason_code: str, finished_at: datetime,
) -> None:
    repository.finish_refresh_run(
        refresh_run_id=refresh_run_id, source_id=source_id,
        acquisition_id=None, status="FAIL", reason_codes=(reason_code,),
        finished_at=finished_at,
    )
