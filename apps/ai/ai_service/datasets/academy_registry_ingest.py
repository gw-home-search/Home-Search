from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path

from .academy_registry import AcademyRegistryAdapter, academy_registry_source_contract
from .academy_registry_client import AcademyRegistryApiClient, AcademyRegistryApiError
from .contracts import load_reference_source_catalog
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
class AcademyRegistryIngestReport:
    result: LifecycleResult
    page_count: int
    raw_row_count: int


def ingest_from_environment(
    environment: Mapping[str, str],
    *,
    repository_factory: Callable[[str], PostgresDatasetRepository] = _importer_repository,
    client_factory: Callable[[], AcademyRegistryApiClient] = AcademyRegistryApiClient,
    raw_store_factory: Callable[[Mapping[str, str]], S3RawObjectStore] = s3_raw_store_from_environment,
    today: Callable[[], date] = date.today,
    clock: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> AcademyRegistryIngestReport:
    importer_dsn = _required(environment, "HOME_AI_IMPORTER_DSN")
    service_key = _required(environment, "HOME_AI_NEIS_SERVICE_KEY")
    _validate_importer_dsn(importer_dsn)
    try:
        reference = load_reference_source_catalog(_CONFIG_PATH).approved(
            "edu.academy-registry"
        )
        if reference.license.reviewed_on is None or reference.license.reviewed_on > today():
            raise ValueError("academy license review date is invalid")
        contract = academy_registry_source_contract(reference)
        raw_store = raw_store_factory(environment)
    except (KeyError, RuntimeError, ValueError) as exception:
        raise SchoolLocationConfigurationError("academy source contract is invalid") from exception

    observed_at = clock()
    repository = repository_factory(importer_dsn)
    refresh_run_id = repository.start_refresh_run(
        source_id=contract.source_id,
        provider=contract.provider,
        profile=f"source:{contract.source_id}",
        trigger_type="MANUAL",
        started_at=observed_at,
    )
    try:
        with repository.source_lock(contract.source_id), SecureTempWorkspace(
            required_free_bytes=reference.acquisition.maximum_bundle_bytes * 2
        ) as workspace:
            collected = client_factory().collect_prepared(
                service_key, observed_at=observed_at, workspace=workspace
            )
            lifecycle = DatasetLifecycleService(repository, raw_store=raw_store)
            if collected.complete:
                result = lifecycle.ingest_validate_publish_prepared(
                    contract, collected.prepared, source_date=None,
                    observed_at=observed_at, adapter=AcademyRegistryAdapter(),
                    content_type="application/zip",
                )
            else:
                result = lifecycle.preserve_incomplete_prepared(
                    contract, collected.prepared, source_date=None,
                    observed_at=observed_at, reason_codes=collected.reason_codes,
                    content_type="application/zip",
                )
    except AcademyRegistryApiError as exception:
        repository.finish_refresh_run(
            refresh_run_id=refresh_run_id, source_id=contract.source_id,
            acquisition_id=None, status="FAIL", reason_codes=(exception.reason_code,),
            finished_at=clock(),
        )
        raise
    except Exception:
        repository.finish_refresh_run(
            refresh_run_id=refresh_run_id, source_id=contract.source_id,
            acquisition_id=None, status="FAIL", reason_codes=("INGEST_FAILED",),
            finished_at=clock(),
        )
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
    return AcademyRegistryIngestReport(result, collected.page_count, collected.raw_row_count)
