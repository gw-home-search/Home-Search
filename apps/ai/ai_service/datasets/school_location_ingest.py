from __future__ import annotations

import os
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import psycopg
from psycopg.conninfo import conninfo_to_dict

from .models import LifecycleResult
from .postgres import PostgresDatasetRepository
from .school_location import SchoolLocationAdapter, school_location_source_contract
from .school_location_client import SchoolLocationApiClient, SchoolLocationApiError
from .service import DatasetLifecycleService
from .contracts import load_reference_source_catalog
from .raw_store import S3RawObjectStore, s3_raw_store_from_environment


_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config" / "reference_sources.toml"


class SchoolLocationConfigurationError(RuntimeError):
    pass


def _importer_repository(dsn: str) -> PostgresDatasetRepository:
    return PostgresDatasetRepository(
        dsn,
        expected_database="home_search_ai",
        expected_username="home_search_ai_importer",
    )


@dataclass(frozen=True)
class SchoolLocationIngestReport:
    result: LifecycleResult
    page_count: int
    raw_row_count: int


def ingest_from_environment(
    environment: Mapping[str, str],
    *,
    repository_factory: Callable[[str], PostgresDatasetRepository] = _importer_repository,
    client_factory: Callable[[], SchoolLocationApiClient] = SchoolLocationApiClient,
    raw_store_factory: Callable[[Mapping[str, str]], S3RawObjectStore] = s3_raw_store_from_environment,
    today: Callable[[], date] = date.today,
) -> SchoolLocationIngestReport:
    importer_dsn = _required(environment, "HOME_AI_IMPORTER_DSN")
    service_key = _required(environment, "HOME_AI_DATA_GO_KR_SERVICE_KEY")
    _validate_importer_dsn(importer_dsn)
    if len(service_key) > 1024:
        raise SchoolLocationConfigurationError("school API service key is too long")
    try:
        reference_contract = load_reference_source_catalog(_CONFIG_PATH).approved(
            "edu.school-location"
        )
        if (
            reference_contract.license.reviewed_on is None
            or reference_contract.license.reviewed_on > today()
        ):
            raise ValueError("school license review date is invalid")
        contract = school_location_source_contract(
            reference_contract=reference_contract,
        )
        raw_store = raw_store_factory(environment)
    except (KeyError, RuntimeError, ValueError) as exception:
        raise SchoolLocationConfigurationError("school source contract is invalid") from exception

    repository = repository_factory(importer_dsn)
    try:
        with repository.source_lock("edu.school-location"):
            collected = client_factory().collect(service_key)
            lifecycle = DatasetLifecycleService(repository, raw_store=raw_store)
            if collected.complete:
                result = lifecycle.ingest_validate_publish(
                    contract,
                    collected.content,
                    source_date=collected.source_date,
                    adapter=SchoolLocationAdapter(),
                    content_type="application/zip",
                )
            else:
                result = lifecycle.preserve_incomplete(
                    contract,
                    collected.content,
                    source_date=collected.source_date,
                    reason_codes=collected.reason_codes,
                    content_type="application/zip",
                )
    finally:
        repository.close()
    return SchoolLocationIngestReport(
        result=result,
        page_count=collected.page_count,
        raw_row_count=collected.raw_row_count,
    )


def main() -> None:
    try:
        report = ingest_from_environment(os.environ)
    except SchoolLocationConfigurationError:
        _print_failure("CONFIGURATION_INVALID")
        raise SystemExit(2) from None
    except SchoolLocationApiError as exception:
        _print_failure(exception.reason_code)
        raise SystemExit(1) from None
    except Exception:
        _print_failure("INGEST_FAILED")
        raise SystemExit(1) from None

    result = report.result
    print(f"상태: {result.status}")
    print(f"sourceId: {result.source_id}")
    print(f"sourceDate: {result.source_date.isoformat() if result.source_date else ''}")
    print(f"pageCount: {report.page_count}")
    print(f"rawRowCount: {report.raw_row_count}")
    print(f"acceptedRowCount: {result.accepted_row_count}")
    print(f"rejectedRowCount: {result.rejected_row_count}")
    print(f"datasetVersion: {result.dataset_version or ''}")
    print(f"reasonCodes: {','.join(result.issue_codes)}")
    raise SystemExit(0 if result.status == "Pass" else 1)


def _required(environment: Mapping[str, str], name: str) -> str:
    value = environment.get(name, "").strip()
    if not value:
        raise SchoolLocationConfigurationError(f"{name} is required")
    return value


def _validate_importer_dsn(dsn: str) -> None:
    try:
        parameters = conninfo_to_dict(dsn)
    except psycopg.Error:
        raise SchoolLocationConfigurationError("importer DSN is invalid") from None
    if (
        parameters.get("dbname") != "home_search_ai"
        or parameters.get("user") != "home_search_ai_importer"
    ):
        raise SchoolLocationConfigurationError(
            "importer DSN must target the dedicated database and role"
        )


def _print_failure(reason_code: str) -> None:
    print("상태: Fail")
    print("sourceId: edu.school-location")
    print("sourceDate:")
    print("pageCount: 0")
    print("rawRowCount: 0")
    print("acceptedRowCount: 0")
    print("rejectedRowCount: 0")
    print("datasetVersion:")
    print(f"reasonCodes: {reason_code}")
