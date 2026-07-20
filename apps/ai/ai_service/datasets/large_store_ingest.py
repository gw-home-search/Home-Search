from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path

from .contracts import ReferenceSourceContract, load_reference_source_catalog
from .large_store_client import LargeStoreApiClient, LargeStoreApiError
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
    client_factory: Callable[[ReferenceSourceContract], LargeStoreApiClient] | None = None,
    raw_store_factory: Callable[[Mapping[str, str]], S3RawObjectStore] = s3_raw_store_from_environment,
    today: Callable[[], date] = date.today,
    clock: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> LargeStoreIngestReport:
    importer_dsn = _required(environment, "HOME_AI_IMPORTER_DSN")
    service_key = _required(environment, "HOME_AI_DATA_GO_KR_SERVICE_KEY")
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

    observed_at = clock()
    repository = repository_factory(importer_dsn)
    refresh_run_id = repository.start_refresh_run(
        source_id=contract.source_id, provider=contract.provider,
        profile=f"source:{contract.source_id}", trigger_type="MANUAL",
        started_at=observed_at,
    )
    try:
        with repository.source_lock(contract.source_id), SecureTempWorkspace(
            required_free_bytes=reference.acquisition.maximum_bundle_bytes * 2
        ) as workspace:
            collected = client.collect_prepared(
                service_key, observed_at=observed_at, workspace=workspace
            )
            lifecycle = DatasetLifecycleService(repository, raw_store=raw_store)
            result = (
                lifecycle.ingest_validate_publish_prepared(
                    contract, collected.prepared, source_date=None,
                    observed_at=observed_at, adapter=LargeStoreAdapter(),
                    content_type="application/zip",
                )
                if collected.complete else lifecycle.preserve_incomplete_prepared(
                    contract, collected.prepared, source_date=None,
                    observed_at=observed_at, reason_codes=collected.reason_codes,
                    content_type="application/zip",
                )
            )
    except LargeStoreApiError as exception:
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
    return LargeStoreIngestReport(
        result=result, page_count=collected.page_count,
        raw_row_count=collected.raw_row_count,
    )


def _client(reference: ReferenceSourceContract) -> LargeStoreApiClient:
    if (
        reference.acquisition.mode != "api"
        or reference.acquisition.base_url
        != "https://apis.data.go.kr/1741000/large_scale_retail_stores/info"
    ):
        raise ValueError("large-store API acquisition contract mismatch")
    return LargeStoreApiClient()


def _finish_failure(
    repository: PostgresDatasetRepository, refresh_run_id: object,
    source_id: str, reason_code: str, finished_at: datetime,
) -> None:
    repository.finish_refresh_run(
        refresh_run_id=refresh_run_id, source_id=source_id,
        acquisition_id=None, status="FAIL", reason_codes=(reason_code,),
        finished_at=finished_at,
    )
