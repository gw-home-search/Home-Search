from __future__ import annotations

from datetime import UTC, datetime

import psycopg
import pytest

from ai_service.datasets.academy_registry import AcademyRegistryAdapter
from ai_service.datasets.postgres import PostgresDatasetRepository
from ai_service.datasets.sbiz_academy import SbizAcademyAdapter
from ai_service.datasets.service import DatasetLifecycleService
from tests.datasets.test_academy_registry_adapter import _bundle as academy_bundle
from tests.datasets.test_academy_registry_adapter import _contract as academy_contract
from tests.datasets.test_sbiz_academy_adapter import _bundle as sbiz_bundle
from tests.datasets.test_sbiz_academy_adapter import _contract as sbiz_contract
from tests.datasets.test_sbiz_academy_adapter import _taxonomy


pytestmark = pytest.mark.postgres


def test_sbiz_projection_exposes_exact_match_without_internal_keys(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    def make_registry_key_unique(code: str, row: dict[str, object]) -> None:
        if code != "B10":
            row["ACA_NM"] = f"{code} 고유 학원"
            row["FA_RDNMA"] = f"{code} 고유 주소"

    clock = lambda: datetime(2026, 7, 20, 2, tzinfo=UTC)
    lifecycle = DatasetLifecycleService(dataset_repository, clock=clock)
    academy = lifecycle.ingest_validate_publish(
        academy_contract(), academy_bundle(mutate=make_registry_key_unique), source_date=None,
        observed_at=datetime(2026, 7, 19, 1, tzinfo=UTC),
        adapter=AcademyRegistryAdapter(), content_type="application/zip",
    )
    sbiz = lifecycle.ingest_validate_publish(
        sbiz_contract(), sbiz_bundle(), source_date=None,
        observed_at=datetime(2026, 7, 20, tzinfo=UTC),
        adapter=SbizAcademyAdapter(_taxonomy()), content_type="application/zip",
    )

    assert academy.status == "Pass"
    assert sbiz.status == "Pass", sbiz.issue_codes
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute("SET ROLE home_search_ai_runtime")
        row = connection.execute(
            """
            SELECT sbiz_fact_id, registry_fact_id, registry_match,
                   registry_academy_name, registry_status,
                   registry_dataset_version, registry_observed_at
            FROM reference_read.sbiz_academy_fact
            """
        ).fetchone()
        assert row == (
            "store-1",
            "B10|B10-001",
            "EXACT",
            "가나다 학원",
            "OPEN",
            academy.dataset_version,
            datetime(2026, 7, 19, 1, tzinfo=UTC),
        )
        with pytest.raises(psycopg.errors.UndefinedColumn):
            connection.execute(
                "SELECT normalized_name_key FROM reference_read.sbiz_academy_fact"
            )


def test_sbiz_projection_rejects_fuzzy_name_match(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    def make_registry_key_unique(code: str, row: dict[str, object]) -> None:
        if code != "B10":
            row["ACA_NM"] = f"{code} 고유 학원"
            row["FA_RDNMA"] = f"{code} 고유 주소"

    clock = lambda: datetime(2026, 7, 20, 2, tzinfo=UTC)
    lifecycle = DatasetLifecycleService(dataset_repository, clock=clock)
    academy = lifecycle.ingest_validate_publish(
        academy_contract(), academy_bundle(mutate=make_registry_key_unique), source_date=None,
        observed_at=datetime(2026, 7, 19, 1, tzinfo=UTC),
        adapter=AcademyRegistryAdapter(), content_type="application/zip",
    )
    sbiz = lifecycle.ingest_validate_publish(
        sbiz_contract(), sbiz_bundle(name="가나다학원"), source_date=None,
        observed_at=datetime(2026, 7, 20, tzinfo=UTC),
        adapter=SbizAcademyAdapter(_taxonomy()), content_type="application/zip",
    )

    assert academy.status == "Pass"
    assert sbiz.status == "Pass", sbiz.issue_codes
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute("SET ROLE home_search_ai_runtime")
        row = connection.execute(
            """
            SELECT registry_fact_id, registry_match
            FROM reference_read.sbiz_academy_fact
            """
        ).fetchone()

    assert row == (None, "UNMATCHED")
