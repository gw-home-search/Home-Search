from __future__ import annotations

from datetime import UTC, datetime

import psycopg

from ai_service.datasets.postgres import PostgresDatasetRepository, _PROJECTION_WRITERS
from ai_service.datasets.school_location import SchoolLocationAdapter, build_bundle
from ai_service.datasets.service import DatasetLifecycleService
from tests.datasets.test_school_location_adapter import (
    OFFICE_CODES,
    REFERENCE_DATE,
    _contract,
    _page,
    _row,
)


def test_projection_writers_are_feature_local_and_statically_composed() -> None:
    assert tuple(_PROJECTION_WRITERS) == (
        "edu.school-location",
        "edu.academy-registry",
        "place.sbiz-academy",
        "retail.large-store",
        "transport.rail-station",
        "childcare.center",
    )
    assert tuple(writer.__module__ for writer in _PROJECTION_WRITERS.values()) == (
        "ai_service.datasets.school_location_projection",
        "ai_service.datasets.academy_registry_projection",
        "ai_service.datasets.sbiz_academy_projection",
        "ai_service.datasets.large_store_projection",
        "ai_service.datasets.rail_station_projection",
        "ai_service.datasets.childcare_projection",
    )


def test_school_projection_preserves_typed_points_and_coverage(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    rows = [_row(index, code) for index, code in enumerate(OFFICE_CODES, start=1)]
    result = DatasetLifecycleService(
        dataset_repository,
        clock=lambda: datetime(2026, 7, 20, tzinfo=UTC),
    ).ingest_validate_publish(
        _contract(),
        build_bundle(
            pages=[_page(1, rows, len(rows))],
            page_size=1000,
            total_count=len(rows),
        ),
        source_date=REFERENCE_DATE,
        adapter=SchoolLocationAdapter(),
    )

    assert result.status == "Pass"
    with psycopg.connect(postgres_dsn) as connection:
        point = connection.execute(
            """
            SELECT category, status, row_reference_date,
                   attributes ->> 'educationOfficeCode'
            FROM reference_read.facility_point_fact
            WHERE source_id = 'edu.school-location'
            ORDER BY fact_id
            LIMIT 1
            """
        ).fetchone()
        coverage = connection.execute(
            """
            SELECT total_count, spatial_count, non_spatial_count, open_count
            FROM reference_read.source_coverage
            WHERE source_id = 'edu.school-location'
            """
        ).fetchone()

    assert point == ("SCHOOL", "OPEN", REFERENCE_DATE, "7010000")
    assert coverage == (17, 17, 0, 17)
