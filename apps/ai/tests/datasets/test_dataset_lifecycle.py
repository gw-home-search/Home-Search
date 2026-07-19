from __future__ import annotations

import json
from dataclasses import replace
from datetime import UTC, date, datetime
from pathlib import Path

import psycopg
import pytest

from ai_service.datasets import DatasetLifecycleService, DatasetSourceContract
from ai_service.datasets.postgres import PostgresDatasetRepository


pytestmark = pytest.mark.postgres

COLLECTED_AT = datetime(2026, 7, 16, 7, 30, tzinfo=UTC)
SOURCE_DATE = date(2026, 7, 15)
FIXTURE_DIR = Path(__file__).parents[1] / "fixtures" / "dataset_lifecycle"


def source_contract(
    *,
    expected_min_rows: int = 1,
    expected_max_rows: int = 10,
    freshness_days: int = 3650,
    maximum_rejected_ratio: float = 0.0,
) -> DatasetSourceContract:
    return DatasetSourceContract(
        source_id="fixture.rail-station",
        provider="Home Search fixture",
        landing_url="https://example.invalid/datasets/rail",
        acquisition_url="https://example.invalid/datasets/rail.json",
        license_terms="Fixture-only; repository test use",
        attribution_requirements="Home Search fixture",
        license_reviewed_on=date(2026, 7, 16),
        refresh_frequency="fixed-fixture",
        freshness_days=freshness_days,
        file_format="json",
        encoding="utf-8",
        schema_version="fixture-v1",
        coordinate_system="EPSG:4326",
        unique_key_fields=("station_id",),
        required_fields=("station_id", "name", "latitude", "longitude"),
        expected_min_rows=expected_min_rows,
        expected_max_rows=expected_max_rows,
        maximum_row_change_ratio=0.50,
        maximum_rejected_ratio=maximum_rejected_ratio,
        contains_personal_data=False,
        owner="ai-platform",
    )


def payload(rows: list[dict[str, object]]) -> bytes:
    return json.dumps({"rows": rows}, ensure_ascii=False, sort_keys=True).encode()


def valid_rows(version: int = 1) -> list[dict[str, object]]:
    return [
        {
            "station_id": f"station-{version}",
            "name": f"Fixture Station {version}",
            "latitude": 37.5665,
            "longitude": 126.9780,
        }
    ]


def service(repository: PostgresDatasetRepository) -> DatasetLifecycleService:
    return DatasetLifecycleService(repository, clock=lambda: COLLECTED_AT)


def test_checksum_reingest_is_idempotent_and_does_not_duplicate_publication(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    lifecycle = service(dataset_repository)
    raw = (FIXTURE_DIR / "valid.json").read_bytes()

    first = lifecycle.ingest_validate_publish(source_contract(), raw, source_date=SOURCE_DATE)
    second = lifecycle.ingest_validate_publish(source_contract(), raw, source_date=SOURCE_DATE)

    assert first.status == "Pass"
    assert second.status == "Pass"
    assert second.idempotent is True
    assert second.acquisition_id == first.acquisition_id
    assert second.publication_id == first.publication_id
    assert dataset_repository.table_counts() == {
        "raw_objects": 1,
        "acquisitions": 1,
        "publications": 1,
    }


def test_semantically_equal_raw_creates_no_second_publication(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    lifecycle = service(dataset_repository)
    first_raw = b'{"rows":[{"station_id":"station-1","name":"Fixture Station","latitude":37.5665,"longitude":126.978}]}'
    reordered_raw = b'{ "rows": [ { "longitude": 126.978, "latitude": 37.5665, "name": "Fixture Station", "station_id": "station-1" } ] }'

    first = lifecycle.ingest_validate_publish(
        source_contract(), first_raw, source_date=SOURCE_DATE
    )
    unchanged = lifecycle.ingest_validate_publish(
        source_contract(), reordered_raw, source_date=SOURCE_DATE
    )

    assert first.status == "Pass"
    assert unchanged.status == "NoChange"
    assert unchanged.normalized_checksum == first.normalized_checksum
    assert unchanged.publication_id is None
    assert dataset_repository.table_counts() == {
        "raw_objects": 2,
        "acquisitions": 2,
        "publications": 1,
    }


@pytest.mark.parametrize(
    ("rows", "reason_code"),
    [
        (
            [{"station_id": "missing-name", "latitude": 37.5, "longitude": 127.0}],
            "REQUIRED_FIELD_MISSING",
        ),
        (
            [
                {
                    "station_id": "bad-coordinate",
                    "name": "Bad Coordinate",
                    "latitude": 137.5,
                    "longitude": 127.0,
                }
            ],
            "INVALID_COORDINATE",
        ),
        (
            [
                {
                    "station_id": "duplicate",
                    "name": "First",
                    "latitude": 37.5,
                    "longitude": 127.0,
                },
                {
                    "station_id": "duplicate",
                    "name": "Second",
                    "latitude": 37.6,
                    "longitude": 127.1,
                },
            ],
            "DUPLICATE_UNIQUE_KEY",
        ),
    ],
)
def test_blocking_row_quality_errors_are_queryable_and_never_become_active(
    dataset_repository: PostgresDatasetRepository,
    rows: list[dict[str, object]],
    reason_code: str,
) -> None:
    result = service(dataset_repository).ingest_validate_publish(
        source_contract(), payload(rows), source_date=SOURCE_DATE
    )

    assert result.status == "Fail"
    assert result.publication_id is None
    assert dataset_repository.active_snapshot("fixture.rail-station") is None
    rejected = dataset_repository.rejected_rows(result.acquisition_id)
    assert reason_code in {row.reason_code for row in rejected}


def test_rejected_rows_below_threshold_are_quarantined_and_excluded_from_snapshot(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    rows = [
        *valid_rows(1),
        *valid_rows(2),
        {
            "station_id": "invalid-coordinate",
            "name": "Invalid Coordinate",
            "latitude": 137.5,
            "longitude": 127.0,
        },
    ]

    result = service(dataset_repository).ingest_validate_publish(
        source_contract(expected_min_rows=3, maximum_rejected_ratio=0.34),
        payload(rows),
        source_date=SOURCE_DATE,
    )

    assert result.status == "Pass"
    assert result.rejected_row_count == 1
    assert dataset_repository.rejected_rows(result.acquisition_id)[0].reason_code == (
        "INVALID_COORDINATE"
    )
    active = dataset_repository.active_snapshot("fixture.rail-station")
    assert active is not None
    assert {row["station_id"] for row in active.rows} == {"station-1", "station-2"}


def test_abnormal_row_count_blocks_publication_with_acquisition_issue(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    result = service(dataset_repository).ingest_validate_publish(
        source_contract(expected_min_rows=2), payload(valid_rows()), source_date=SOURCE_DATE
    )

    assert result.status == "Fail"
    assert "ROW_COUNT_OUT_OF_RANGE" in result.issue_codes
    assert dataset_repository.active_snapshot("fixture.rail-station") is None


def test_same_raw_reuses_acquisition_when_only_the_source_contract_changes(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    lifecycle = service(dataset_repository)
    raw = (FIXTURE_DIR / "valid.json").read_bytes()
    first = lifecycle.ingest_validate_publish(source_contract(), raw, source_date=SOURCE_DATE)

    revalidated = lifecycle.ingest_validate_publish(
        source_contract(expected_min_rows=2), raw, source_date=SOURCE_DATE
    )

    assert first.status == "Pass"
    assert revalidated.status == "Pass"
    assert revalidated.idempotent is True
    assert revalidated.acquisition_id == first.acquisition_id
    assert dataset_repository.table_counts() == {
        "raw_objects": 1,
        "acquisitions": 1,
        "publications": 1,
    }


def test_stale_source_date_blocks_publication(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    result = service(dataset_repository).ingest_validate_publish(
        source_contract(freshness_days=1),
        payload(valid_rows()),
        source_date=date(2026, 7, 14),
    )

    assert result.status == "Fail"
    assert "DATASET_STALE" in result.issue_codes
    assert dataset_repository.active_snapshot("fixture.rail-station") is None


def test_observed_at_snapshot_preserves_temporal_basis_without_claiming_source_date(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    observed_at = datetime(2026, 7, 16, 7, 30, tzinfo=UTC)
    observed_contract = replace(
        source_contract(),
        source_id="fixture.observed-source",
        temporal_basis="OBSERVED_AT",
    )

    result = service(dataset_repository).ingest_validate_publish(
        observed_contract,
        payload(valid_rows()),
        source_date=None,
        observed_at=observed_at,
    )

    assert result.status == "Pass"
    assert result.temporal_basis == "OBSERVED_AT"
    assert result.source_date is None
    assert result.observed_at == observed_at
    assert result.dataset_version is not None
    assert result.dataset_version.startswith("20260716-")


def test_invalid_payload_is_preserved_before_parse_failure(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    result = service(dataset_repository).ingest_validate_publish(
        source_contract(), b"not-json", source_date=SOURCE_DATE
    )

    assert result.status == "Fail"
    assert "RAW_PARSE_FAILED" in result.issue_codes
    assert dataset_repository.raw_bytes(result.checksum) == b"not-json"


def test_runtime_role_can_read_only_the_typed_reference_view(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    del dataset_repository
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute("SET ROLE home_search_ai_runtime")
        assert connection.execute(
            "SELECT count(*) FROM reference_read.school_location_fact"
        ).fetchone()[0] == 0

    with psycopg.connect(postgres_dsn) as connection:
        connection.execute("SET ROLE home_search_ai_runtime")
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("SELECT count(*) FROM dataset_raw_object")


def test_importer_role_cannot_mutate_immutable_raw_objects(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    del dataset_repository
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute("SET ROLE home_search_ai_importer")
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("UPDATE dataset_raw_object SET byte_length = byte_length")


def test_publication_failure_keeps_previous_active_snapshot(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    lifecycle = service(dataset_repository)
    first = lifecycle.ingest_validate_publish(
        source_contract(), payload(valid_rows(1)), source_date=SOURCE_DATE
    )
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute(
            """
            CREATE OR REPLACE FUNCTION fixture_block_active_pointer() RETURNS trigger AS $$
            BEGIN
              RAISE EXCEPTION 'fixture publication failure';
            END;
            $$ LANGUAGE plpgsql
            """
        )
        connection.execute(
            """
            CREATE TRIGGER fixture_block_active_pointer
            BEFORE UPDATE ON dataset_active_snapshot
            FOR EACH ROW EXECUTE FUNCTION fixture_block_active_pointer()
            """
        )

    failed = lifecycle.ingest_validate_publish(
        source_contract(), payload(valid_rows(2)), source_date=SOURCE_DATE
    )

    active = dataset_repository.active_snapshot("fixture.rail-station")
    assert first.status == "Pass"
    assert failed.status == "Fail"
    assert "PUBLICATION_FAILED" in failed.issue_codes
    assert active is not None
    assert active.publication_id == first.publication_id
    assert dataset_repository.publication_count("fixture.rail-station") == 1


def test_active_pointer_can_roll_back_to_previous_publication(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    lifecycle = service(dataset_repository)
    first = lifecycle.ingest_validate_publish(
        source_contract(), payload(valid_rows(1)), source_date=SOURCE_DATE
    )
    second = lifecycle.ingest_validate_publish(
        source_contract(), payload(valid_rows(2)), source_date=SOURCE_DATE
    )

    lifecycle.rollback("fixture.rail-station", first.publication_id)

    active = dataset_repository.active_snapshot("fixture.rail-station")
    assert second.status == "Pass"
    assert active is not None
    assert active.publication_id == first.publication_id
    assert active.rows[0]["station_id"] == "station-1"
    assert dataset_repository.activation_actions("fixture.rail-station") == (
        "PUBLISH",
        "PUBLISH",
        "ROLLBACK",
    )
