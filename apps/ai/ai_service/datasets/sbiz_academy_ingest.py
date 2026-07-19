from __future__ import annotations

import json
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path

from .contracts import load_reference_source_catalog
from .models import LifecycleResult
from .postgres import PostgresDatasetRepository
from .raw_store import S3RawObjectStore, s3_raw_store_from_environment
from .sbiz_academy import (
    SbizAcademyAdapter, SbizTaxonomyContract, sbiz_academy_source_contract,
)
from .sbiz_academy_client import SbizAcademyApiClient, SbizAcademyApiError
from .school_location_ingest import (
    SchoolLocationConfigurationError, _importer_repository, _required,
    _validate_importer_dsn,
)
from .service import DatasetLifecycleService


_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config" / "reference_sources.toml"
_TAXONOMY_PATH = Path(__file__).resolve().parents[2] / "config" / "sbiz_academy_taxonomy.json"


@dataclass(frozen=True)
class SbizAcademyIngestReport:
    result: LifecycleResult
    page_count: int
    raw_row_count: int


def ingest_from_environment(
    environment: Mapping[str, str],
    *,
    repository_factory: Callable[[str], PostgresDatasetRepository] = _importer_repository,
    client_factory: Callable[[SbizTaxonomyContract, dict[str, object]], SbizAcademyApiClient] | None = None,
    raw_store_factory: Callable[[Mapping[str, str]], S3RawObjectStore] = s3_raw_store_from_environment,
    taxonomy_loader: Callable[[], tuple[SbizTaxonomyContract, dict[str, object]]] | None = None,
    today: Callable[[], date] = date.today,
    clock: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> SbizAcademyIngestReport:
    importer_dsn = _required(environment, "HOME_AI_IMPORTER_DSN")
    service_key = _required(environment, "HOME_AI_DATA_GO_KR_SERVICE_KEY")
    _validate_importer_dsn(importer_dsn)
    try:
        reference = load_reference_source_catalog(_CONFIG_PATH).approved("place.sbiz-academy")
        if reference.license.reviewed_on is None or reference.license.reviewed_on > today():
            raise ValueError("Sbiz license review date is invalid")
        contract = sbiz_academy_source_contract(reference)
        taxonomy, artifacts = (taxonomy_loader or _load_taxonomy)()
        client = (
            client_factory(taxonomy, artifacts)
            if client_factory else SbizAcademyApiClient(
                taxonomy=taxonomy, taxonomy_artifacts=artifacts
            )
        )
        raw_store = raw_store_factory(environment)
    except (KeyError, OSError, RuntimeError, ValueError) as exception:
        raise SchoolLocationConfigurationError("Sbiz source contract is invalid") from exception

    observed_at = clock()
    repository = repository_factory(importer_dsn)
    refresh_run_id = repository.start_refresh_run(
        source_id=contract.source_id, provider=contract.provider,
        profile=f"source:{contract.source_id}", trigger_type="MANUAL",
        started_at=observed_at,
    )
    try:
        with repository.source_lock(contract.source_id):
            collected = client.collect(service_key, observed_at=observed_at)
            lifecycle = DatasetLifecycleService(repository, raw_store=raw_store)
            result = (
                lifecycle.ingest_validate_publish(
                    contract, collected.content, source_date=None,
                    observed_at=observed_at, adapter=SbizAcademyAdapter(taxonomy),
                    content_type="application/zip",
                )
                if collected.complete else lifecycle.preserve_incomplete(
                    contract, collected.content, source_date=None,
                    observed_at=observed_at, reason_codes=collected.reason_codes,
                    content_type="application/zip",
                )
            )
    except SbizAcademyApiError as exception:
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
    return SbizAcademyIngestReport(result, collected.page_count, collected.raw_row_count)


def _load_taxonomy() -> tuple[SbizTaxonomyContract, dict[str, object]]:
    value = json.loads(_TAXONOMY_PATH.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Sbiz taxonomy config is invalid")
    fingerprint = value.get("fingerprint")
    allowed = value.get("allowedSmallCategories")
    artifacts = value.get("artifacts")
    if (
        not isinstance(fingerprint, str) or not isinstance(allowed, dict)
        or not all(isinstance(key, str) and isinstance(item, str) for key, item in allowed.items())
        or not isinstance(artifacts, dict)
    ):
        raise ValueError("Sbiz taxonomy config is invalid")
    return SbizTaxonomyContract(fingerprint, allowed), artifacts


def _finish_failure(repository, refresh_run_id, source_id, reason_code, finished_at):
    repository.finish_refresh_run(
        refresh_run_id=refresh_run_id, source_id=source_id,
        acquisition_id=None, status="FAIL", reason_codes=(reason_code,),
        finished_at=finished_at,
    )
