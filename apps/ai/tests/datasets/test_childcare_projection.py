from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta

import psycopg
import pytest

from ai_service.datasets.childcare import ChildcareAdapter
from ai_service.datasets.postgres import PostgresDatasetRepository, _PROJECTION_WRITERS
from ai_service.datasets.service import DatasetLifecycleService
from ai_service.property_chat.childcare_centers import PostgresChildcareRepository
from tests.datasets.test_childcare_adapter import OBSERVED_AT, _bundle, _contract, _response


def test_childcare_projection_reuses_facility_point_and_exposes_typed_view(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    contract = replace(_contract(), expected_min_rows=1, expected_max_rows=10)
    result = DatasetLifecycleService(
        dataset_repository,
        clock=lambda: datetime(2026, 7, 21, 10, 0, tzinfo=UTC),
    ).ingest_validate_publish(
        contract,
        _bundle(
            _response(
                stcode="11620000341",
                name="꿈나무어린이집",
                center_type="국공립",
                status="정상",
                latitude="37.5131",
                longitude="127.0822",
            )
        ),
        source_date=None,
        observed_at=OBSERVED_AT,
        adapter=ChildcareAdapter(),
        content_type="application/zip",
    )

    assert result.status == "Pass"
    assert _PROJECTION_WRITERS["childcare.center"].__module__ == (
        "ai_service.datasets.childcare_projection"
    )
    with psycopg.connect(postgres_dsn) as connection:
        row = connection.execute(
            """
            SELECT center_id, center_name, center_type, operating_status,
                   capacity, latitude, longitude, reference_date
            FROM reference_read.childcare_center_fact
            """
        ).fetchone()
        columns = connection.execute(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'reference_read'
              AND table_name = 'childcare_center_fact'
            """
        ).fetchall()

    assert row == (
        "11620000341",
        "꿈나무어린이집",
        "국공립",
        "OPEN",
        50,
        37.5131,
        127.0822,
        OBSERVED_AT.date(),
    )
    assert "phone" not in {column[0] for column in columns}
    assert "homepage" not in {column[0] for column in columns}

    repository = PostgresChildcareRepository(
        postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        nearby = repository.nearby(
            latitude=37.5131,
            longitude=127.0822,
            radius_meters=800,
            limit=5,
            region_code="11710",
        )
    finally:
        repository.close()

    assert nearby is not None
    assert nearby.verified_zero is False
    assert nearby.coordinate_coverage == 1.0
    assert nearby.centers[0].center_name == "꿈나무어린이집"
    assert nearby.centers[0].capacity == 50

    with pytest.raises(ValueError, match="expected database"):
        PostgresChildcareRepository(
            postgres_dsn,
            expected_database="wrong",
            expected_username="test",
        )
    with pytest.raises(ValueError, match="runtime role"):
        PostgresChildcareRepository(
            postgres_dsn,
            expected_database="test",
            expected_username="wrong",
        )


def test_same_semantic_childcare_snapshot_is_no_change_without_second_publication(
    dataset_repository: PostgresDatasetRepository,
) -> None:
    contract = replace(_contract(), expected_min_rows=1, expected_max_rows=10)
    response = _response(
        stcode="11620000341",
        name="꿈나무어린이집",
        center_type="국공립",
        status="정상",
        latitude="37.5131",
        longitude="127.0822",
    )
    first = DatasetLifecycleService(
        dataset_repository,
        clock=lambda: OBSERVED_AT,
    ).ingest_validate_publish(
        contract,
        _bundle(response),
        source_date=None,
        observed_at=OBSERVED_AT,
        adapter=ChildcareAdapter(),
        content_type="application/zip",
    )
    next_observation = OBSERVED_AT + timedelta(hours=1)
    second = DatasetLifecycleService(
        dataset_repository,
        clock=lambda: next_observation,
    ).ingest_validate_publish(
        contract,
        _bundle(response, observed_at=next_observation),
        source_date=None,
        observed_at=next_observation,
        adapter=ChildcareAdapter(),
        content_type="application/zip",
    )

    assert first.status == "Pass"
    assert second.status == "NoChange"
    assert dataset_repository.publication_count("childcare.center") == 1
