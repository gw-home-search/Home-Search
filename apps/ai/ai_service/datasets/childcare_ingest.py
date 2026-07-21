from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path

from .childcare import ChildcareAdapter, childcare_source_contract
from .childcare_client import ChildcareApiClient, ChildcareApiError
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
from .service import DatasetLifecycleService


_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config" / "reference_sources.toml"


@dataclass(frozen=True)
class ChildcareIngestReport:
    result: LifecycleResult
    page_count: int
    raw_row_count: int


def ingest_from_environment(
    environment: Mapping[str, str],
    *,
    repository_factory: Callable[[str], PostgresDatasetRepository] = _importer_repository,
    client_factory: Callable[[], ChildcareApiClient] = ChildcareApiClient,
    raw_store_factory: Callable[[Mapping[str, str]], S3RawObjectStore] = (
        s3_raw_store_from_environment
    ),
    today: Callable[[], date] = date.today,
    clock: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> ChildcareIngestReport:
    importer_dsn = _required(environment, "HOME_AI_IMPORTER_DSN")
    service_key = _required(environment, "HOME_AI_CHILDCARE_SERVICE_KEY")
    region_codes = _region_codes(
        _required(environment, "HOME_AI_CHILDCARE_REGION_CODES")
    )
    _validate_importer_dsn(importer_dsn)
    if len(service_key) > 1024:
        raise SchoolLocationConfigurationError("childcare API key is too long")
    try:
        reference = load_reference_source_catalog(_CONFIG_PATH).approved(
            "childcare.center"
        )
        if reference.license.reviewed_on is None or reference.license.reviewed_on > today():
            raise ValueError("childcare license review date is invalid")
        contract = childcare_source_contract(reference_contract=reference)
        raw_store = raw_store_factory(environment)
        client = client_factory()
    except (KeyError, RuntimeError, ValueError) as exception:
        raise SchoolLocationConfigurationError(
            "childcare source contract is invalid"
        ) from exception

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
        with repository.source_lock(contract.source_id):
            collected = client.collect(
                service_key,
                region_codes=region_codes,
                observed_at=observed_at,
            )
            lifecycle = DatasetLifecycleService(repository, raw_store=raw_store)
            result = (
                lifecycle.ingest_validate_publish(
                    contract,
                    collected.content,
                    source_date=None,
                    observed_at=observed_at,
                    adapter=ChildcareAdapter(),
                    content_type="application/zip",
                )
                if collected.complete
                else lifecycle.preserve_incomplete(
                    contract,
                    collected.content,
                    source_date=None,
                    observed_at=observed_at,
                    reason_codes=collected.reason_codes,
                    content_type="application/zip",
                )
            )
    except ChildcareApiError as exception:
        _finish_failure(
            repository,
            refresh_run_id,
            contract.source_id,
            exception.reason_code,
            clock(),
        )
        raise
    except Exception:
        _finish_failure(
            repository,
            refresh_run_id,
            contract.source_id,
            "INGEST_FAILED",
            clock(),
        )
        raise
    else:
        repository.finish_refresh_run(
            refresh_run_id=refresh_run_id,
            source_id=contract.source_id,
            acquisition_id=result.acquisition_id,
            status=(
                "PASS"
                if result.status == "Pass"
                else "NO_CHANGE"
                if result.status == "NoChange"
                else "FAIL"
            ),
            reason_codes=result.issue_codes,
            finished_at=clock(),
        )
    finally:
        repository.close()
    return ChildcareIngestReport(
        result,
        collected.region_count,
        collected.raw_row_count,
    )


def _region_codes(value: str) -> tuple[str, ...]:
    codes = tuple(part.strip() for part in value.split(","))
    if (
        not codes
        or len(codes) > 300
        or len(codes) != len(set(codes))
        or any(len(code) != 5 or not code.isascii() or not code.isdigit() for code in codes)
    ):
        raise SchoolLocationConfigurationError(
            "HOME_AI_CHILDCARE_REGION_CODES is invalid"
        )
    return codes


def _finish_failure(
    repository: PostgresDatasetRepository,
    refresh_run_id: object,
    source_id: str,
    reason_code: str,
    finished_at: datetime,
) -> None:
    repository.finish_refresh_run(
        refresh_run_id=refresh_run_id,
        source_id=source_id,
        acquisition_id=None,
        status="FAIL",
        reason_codes=(reason_code,),
        finished_at=finished_at,
    )
