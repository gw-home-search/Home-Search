from __future__ import annotations

import csv
import hashlib
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
    taxonomy_fingerprint,
)
from .sbiz_academy_client import SbizAcademyApiClient, SbizAcademyApiError
from .school_location_ingest import (
    SchoolLocationConfigurationError, _importer_repository, _required,
    _validate_importer_dsn,
)
from .secure_temp import SecureTempWorkspace
from .service import DatasetLifecycleService


_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config" / "reference_sources.toml"
_TAXONOMY_PATH = Path(__file__).resolve().parents[2] / "config" / "sbiz_academy_taxonomy.json"
_TAXONOMY_COLUMNS = (
    "대분류코드", "대분류명", "중분류코드", "중분류명", "소분류코드", "소분류명",
)


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
                    observed_at=observed_at, adapter=SbizAcademyAdapter(taxonomy),
                    content_type="application/zip",
                )
                if collected.complete else lifecycle.preserve_incomplete_prepared(
                    contract, collected.prepared, source_date=None,
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
    if not isinstance(value, dict) or value.get("schemaVersion") != 1:
        raise ValueError("Sbiz taxonomy config is invalid")
    source = value.get("source")
    expected_counts = value.get("expectedCounts")
    education_category = value.get("educationMajorCategory")
    fingerprint = value.get("fingerprint")
    allowed = value.get("allowedSmallCategories")
    if (
        not isinstance(source, dict)
        or not isinstance(expected_counts, dict)
        or not isinstance(education_category, dict)
        or not isinstance(fingerprint, str)
        or not isinstance(allowed, dict)
        or not all(
            isinstance(key, str) and isinstance(item, str)
            for key, item in allowed.items()
        )
    ):
        raise ValueError("Sbiz taxonomy config is invalid")
    tracked_file = source.get("trackedFile")
    tracked_checksum = source.get("trackedSha256")
    if (
        not isinstance(tracked_file, str)
        or Path(tracked_file).name != tracked_file
        or not isinstance(tracked_checksum, str)
        or len(tracked_checksum) != 64
    ):
        raise ValueError("Sbiz taxonomy source metadata is invalid")
    source_path = _TAXONOMY_PATH.parent / tracked_file
    if source_path.is_symlink() or not source_path.is_file():
        raise ValueError("Sbiz taxonomy source file is invalid")
    content = source_path.read_bytes()
    if hashlib.sha256(content).hexdigest() != tracked_checksum:
        raise ValueError("Sbiz taxonomy source checksum changed")
    try:
        reader = csv.DictReader(content.decode("utf-8").splitlines())
        if tuple(reader.fieldnames or ()) != _TAXONOMY_COLUMNS:
            raise ValueError("Sbiz taxonomy source schema changed")
        rows = list(reader)
    except (csv.Error, UnicodeDecodeError) as exception:
        raise ValueError("Sbiz taxonomy source is invalid") from exception
    if not rows or any(
        set(row) != set(_TAXONOMY_COLUMNS)
        or any(
            not isinstance(row[column], str) or not row[column].strip()
            for column in _TAXONOMY_COLUMNS
        )
        for row in rows
    ):
        raise ValueError("Sbiz taxonomy source row is invalid")

    large = _taxonomy_level(rows, "대분류코드", "대분류명")
    middle = _taxonomy_level(rows, "중분류코드", "중분류명")
    small = _taxonomy_level(rows, "소분류코드", "소분류명")
    artifacts: dict[str, object] = {
        "taxonomy-large": _taxonomy_artifact(large),
        "taxonomy-middle": _taxonomy_artifact(middle),
        "taxonomy-small": _taxonomy_artifact(small),
    }
    actual_counts = {name: len(items) for name, items in artifacts.items()}
    if expected_counts != actual_counts:
        raise ValueError("Sbiz taxonomy source count changed")

    education_code = education_category.get("code")
    education_name = education_category.get("name")
    if not isinstance(education_code, str) or not isinstance(education_name, str):
        raise ValueError("Sbiz education category is invalid")
    if large.get(education_code) != education_name:
        raise ValueError("Sbiz education category changed")
    derived_allowlist = {
        row["소분류코드"]: row["소분류명"]
        for row in rows
        if row["대분류코드"] == education_code
    }
    if allowed != dict(sorted(derived_allowlist.items())):
        raise ValueError("Sbiz education allowlist changed")
    if taxonomy_fingerprint(artifacts) != fingerprint:
        raise ValueError("Sbiz taxonomy fingerprint changed")
    return SbizTaxonomyContract(fingerprint, allowed), artifacts


def _taxonomy_level(
    rows: list[dict[str, str]], code_field: str, name_field: str
) -> dict[str, str]:
    values: dict[str, str] = {}
    for row in rows:
        code = row[code_field]
        name = row[name_field]
        previous = values.setdefault(code, name)
        if previous != name:
            raise ValueError("Sbiz taxonomy code is ambiguous")
    return values


def _taxonomy_artifact(values: dict[str, str]) -> list[dict[str, str]]:
    return [
        {"code": code, "name": name}
        for code, name in sorted(values.items())
    ]


def _finish_failure(repository, refresh_run_id, source_id, reason_code, finished_at):
    repository.finish_refresh_run(
        refresh_run_id=refresh_run_id, source_id=source_id,
        acquisition_id=None, status="FAIL", reason_codes=(reason_code,),
        finished_at=finished_at,
    )
