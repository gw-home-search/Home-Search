from __future__ import annotations

from datetime import UTC, datetime

import psycopg
import pytest

from ai_service.datasets.academy_registry import AcademyRegistryAdapter
from ai_service.datasets.postgres import PostgresDatasetRepository
from ai_service.datasets.service import DatasetLifecycleService
from tests.datasets.test_academy_registry_adapter import _bundle, _contract


pytestmark = pytest.mark.postgres


def test_academy_summary_has_exact_region_lookup_index(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    with psycopg.connect(postgres_dsn) as connection:
        index_definition = connection.execute(
            """
            SELECT indexdef
            FROM pg_indexes
            WHERE schemaname = 'reference_projection'
              AND indexname = 'registry_fact_academy_region_summary_idx'
            """
        ).fetchone()

    assert index_definition is not None
    assert "(attributes ->> 'educationOfficeName'::text)" in index_definition[0]
    assert "region_name" in index_definition[0]
    assert "publication_id" in index_definition[0]
    assert "WHERE (source_id = 'edu.academy-registry'::text)" in index_definition[0]


def test_academy_publication_exposes_only_safe_aggregate_view(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    observed_at = datetime(2026, 7, 19, 1, tzinfo=UTC)
    result = DatasetLifecycleService(
        dataset_repository,
        clock=lambda: datetime(2026, 7, 19, 2, tzinfo=UTC),
    ).ingest_validate_publish(
        _contract(),
        _bundle(),
        source_date=None,
        observed_at=observed_at,
        adapter=AcademyRegistryAdapter(),
        content_type="application/zip",
    )

    assert result.status == "Pass"
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute("SET ROLE home_search_ai_runtime")
        summary = connection.execute(
            """
            SELECT education_office_code, district_name, academy_type, status,
                   registry_count, observed_at
            FROM reference_read.academy_registry_summary
            WHERE education_office_code = 'B10'
            """
        ).fetchone()
        assert summary == ("B10", "송파구", "학원", "OPEN", 1, observed_at)
        assert connection.execute(
            "SELECT dataset_version FROM reference_read.source_status WHERE source_id = 'edu.academy-registry'"
        ).fetchone()[0] == result.dataset_version
        audit = connection.execute(
            "SELECT status, raw_row_count FROM reference_read.acquisition_audit WHERE source_id = 'edu.academy-registry'"
        ).fetchone()
        assert audit == ("PUBLISHED", 17)
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute(
                "SELECT normalized_name_key FROM reference_projection.registry_fact"
            )


def test_academy_projection_preserves_missing_name_without_exact_match_key(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    def mutate(code: str, row: dict[str, object]) -> None:
        if code == "B10":
            row["ACA_NM"] = ""
            row["REG_STTUS_NM"] = "개원"

    observed_at = datetime(2026, 7, 19, 1, tzinfo=UTC)
    result = DatasetLifecycleService(
        dataset_repository,
        clock=lambda: datetime(2026, 7, 19, 2, tzinfo=UTC),
    ).ingest_validate_publish(
        _contract(),
        _bundle(mutate=mutate),
        source_date=None,
        observed_at=observed_at,
        adapter=AcademyRegistryAdapter(),
        content_type="application/zip",
    )

    assert result.status == "Pass"
    with psycopg.connect(postgres_dsn) as connection:
        fact = connection.execute(
            """
            SELECT name, normalized_name_key, attributes -> 'nameMissing'
            FROM reference_projection.registry_fact
            WHERE publication_id = %s AND fact_id = 'B10|B10-001'
            """,
            (result.publication_id,),
        ).fetchone()
    assert fact == ("명칭 미제공", None, True)
