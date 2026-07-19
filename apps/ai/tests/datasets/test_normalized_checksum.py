from __future__ import annotations

from datetime import UTC, date, datetime

import pytest

from ai_service.datasets.checksum import normalized_dataset_checksum


def test_normalized_checksum_ignores_row_and_json_field_order() -> None:
    rows_a = [
        {"school_id": "B", "name": "둘"},
        {"school_id": "A", "name": "하나", "attributes": {"b": 2, "a": 1}},
    ]
    rows_b = [
        {"attributes": {"a": 1, "b": 2}, "name": "하나", "school_id": "A"},
        {"name": "둘", "school_id": "B"},
    ]

    first = normalized_dataset_checksum(
        source_id="edu.school-location",
        normalization_schema_version="school-location-v2",
        temporal_value=date(2026, 3, 20),
        rows=rows_a,
    )
    second = normalized_dataset_checksum(
        source_id="edu.school-location",
        normalization_schema_version="school-location-v2",
        temporal_value=date(2026, 3, 20),
        rows=rows_b,
    )

    assert first == second


def test_normalized_checksum_distinguishes_temporal_basis_value() -> None:
    rows = [{"id": "same"}]

    by_source_date = normalized_dataset_checksum(
        source_id="fixture.source",
        normalization_schema_version="v1",
        temporal_value=date(2026, 7, 19),
        rows=rows,
    )
    by_observation = normalized_dataset_checksum(
        source_id="fixture.source",
        normalization_schema_version="v1",
        temporal_value=datetime(2026, 7, 19, tzinfo=UTC),
        rows=rows,
    )

    assert by_source_date != by_observation


def test_observed_at_checksum_uses_observation_date_not_exact_time() -> None:
    rows = [{"academy_id": "B10|1", "status": "OPEN"}]

    first = normalized_dataset_checksum(
        source_id="edu.academy-registry",
        normalization_schema_version="academy-registry-v2",
        temporal_value=datetime(2026, 7, 19, 1, 2, 3, tzinfo=UTC),
        rows=rows,
    )
    second = normalized_dataset_checksum(
        source_id="edu.academy-registry",
        normalization_schema_version="academy-registry-v2",
        temporal_value=datetime(2026, 7, 19, 23, 59, 59, tzinfo=UTC),
        rows=rows,
    )

    assert first == second


def test_normalized_checksum_requires_stable_identity() -> None:
    with pytest.raises(ValueError):
        normalized_dataset_checksum(
            source_id="",
            normalization_schema_version="v1",
            temporal_value=date(2026, 7, 19),
            rows=[],
        )
