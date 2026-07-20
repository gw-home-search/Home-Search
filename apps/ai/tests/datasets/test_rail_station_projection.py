from __future__ import annotations

from datetime import UTC, date, datetime

import psycopg
import pytest

from ai_service.datasets.postgres import PostgresDatasetRepository
from ai_service.datasets.rail_station import RailStationAdapter
from ai_service.datasets.service import DatasetLifecycleService
from tests.datasets.test_rail_station_adapter import _bundle, _contract, _xlsx


pytestmark = pytest.mark.postgres


def test_rail_projection_preserves_line_occurrences_and_runtime_role_is_read_only(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    rows = [
        ["서울교통공사", "02", "2호선", "201", "시청", "서울 중구", 37.5657, 126.977, "1호선", "2026-01-01"],
        ["서울교통공사", "01", "1호선", "132", "시청", "서울 중구", 37.5653, 126.977, "2호선", "2026-01-01"],
    ]
    result = DatasetLifecycleService(
        dataset_repository,
        clock=lambda: datetime(2026, 1, 2, tzinfo=UTC),
    ).ingest_validate_publish(
        _contract(), _bundle(_xlsx(rows)), source_date=date(2026, 1, 1),
        adapter=RailStationAdapter(), content_type="application/zip",
    )

    assert result.status == "Pass"
    with psycopg.connect(postgres_dsn) as connection:
        connection.execute("SET ROLE home_search_ai_runtime")
        occurrences = connection.execute(
            """
            SELECT occurrence_id, line_name, transfer_lines
            FROM reference_read.rail_station_occurrence
            ORDER BY occurrence_id
            """
        ).fetchall()
        assert occurrences == [
            ("서울교통공사|01|132", "1호선", ["2호선"]),
            ("서울교통공사|02|201", "2호선", ["1호선"]),
        ]
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("SELECT * FROM reference_projection.rail_station_occurrence")
