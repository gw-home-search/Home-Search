from __future__ import annotations

import argparse
import os
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from .contracts import LicenseNotApprovedError, load_reference_source_catalog
from .school_location_ingest import (
    SchoolLocationConfigurationError,
    SchoolLocationIngestReport,
    ingest_from_environment,
)


_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config" / "reference_sources.toml"
_PRIORITY_ORDER = (
    "edu.school-location",
    "edu.academy-registry",
    "retail.large-store",
    "transport.rail-station",
)


@dataclass(frozen=True)
class RefreshFailure:
    source_id: str
    reason_code: str
    configuration: bool


RefreshOutcome = SchoolLocationIngestReport | RefreshFailure


def run(
    arguments: Sequence[str],
    environment: Mapping[str, str],
    *,
    school_refresh: Callable[[Mapping[str, str]], SchoolLocationIngestReport] = ingest_from_environment,
) -> int:
    parser = argparse.ArgumentParser(prog="home-ai-reference-refresh")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--source")
    group.add_argument("--family", choices=("priority",))
    group.add_argument("--profile", choices=("monthly",))
    parsed = parser.parse_args(arguments)
    catalog = load_reference_source_catalog(_CONFIG_PATH)
    source_ids = _selected_sources(parsed.source, parsed.family, parsed.profile, catalog.source_ids)
    outcomes: list[RefreshOutcome] = []
    for source_id in source_ids:
        try:
            catalog.approved(source_id)
            if source_id != "edu.school-location":
                raise SchoolLocationConfigurationError("source adapter is not implemented")
            outcomes.append(school_refresh(environment))
        except (KeyError, LicenseNotApprovedError, SchoolLocationConfigurationError):
            outcomes.append(
                RefreshFailure(
                    source_id=source_id,
                    reason_code="CONFIGURATION_INVALID",
                    configuration=True,
                )
            )
        except Exception:
            outcomes.append(
                RefreshFailure(
                    source_id=source_id,
                    reason_code="REFRESH_FAILED",
                    configuration=False,
                )
            )
    for outcome in outcomes:
        _print_outcome(outcome)
    if any(isinstance(outcome, RefreshFailure) and outcome.configuration for outcome in outcomes):
        return 2
    if any(
        isinstance(outcome, RefreshFailure) or outcome.result.status not in {"Pass", "NoChange"}
        for outcome in outcomes
    ):
        return 1
    return 0


def main() -> None:
    raise SystemExit(run(os.sys.argv[1:], os.environ))


def _selected_sources(
    source: str | None,
    family: str | None,
    profile: str | None,
    catalog_ids: tuple[str, ...],
) -> tuple[str, ...]:
    if source is not None:
        if source not in catalog_ids:
            return (source,)
        return (source,)
    if family == "priority":
        return _PRIORITY_ORDER
    if profile == "monthly":
        return catalog_ids
    raise AssertionError("refresh selector is required")


def _print_outcome(outcome: RefreshOutcome) -> None:
    if isinstance(outcome, RefreshFailure):
        print("상태: Fail")
        print(f"sourceId: {outcome.source_id}")
        print("temporalBasis:")
        print("dataAsOf:")
        print("pageCount: 0")
        print("rawRowCount: 0")
        print("acceptedRowCount: 0")
        print("nonSpatialRowCount: 0")
        print("rejectedRowCount: 0")
        print("coordinateCoverage:")
        print("datasetVersion:")
        print(f"reasonCodes: {outcome.reason_code}")
        return
    result = outcome.result
    print(f"상태: {result.status}")
    print(f"sourceId: {result.source_id}")
    print(f"temporalBasis: {result.temporal_basis}")
    data_as_of = result.source_date or result.observed_at
    print(f"dataAsOf: {data_as_of.isoformat() if data_as_of else ''}")
    print(f"pageCount: {outcome.page_count}")
    print(f"rawRowCount: {outcome.raw_row_count}")
    print(f"acceptedRowCount: {result.accepted_row_count}")
    print("nonSpatialRowCount: 0")
    print(f"rejectedRowCount: {result.rejected_row_count}")
    print("coordinateCoverage: 1.0")
    print(f"datasetVersion: {result.dataset_version or ''}")
    print(f"reasonCodes: {','.join(result.issue_codes)}")
