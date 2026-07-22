from __future__ import annotations

from datetime import date

import pytest

from ai_service.property_chat.childcare_centers import (
    PostgresChildcareRepository,
    _center,
    _complete_region_coverage,
    _coverage_ratio,
    _validate_query,
)


def _coverage(**overrides: object) -> dict[str, object]:
    row: dict[str, object] = {
        "total_count": 10,
        "spatial_count": 10,
        "non_spatial_count": 0,
        "stale_row_count": 0,
        "unknown_region_count": 0,
    }
    row.update(overrides)
    return row


def test_childcare_center_mapping_keeps_capacity_distance_and_reference_date() -> None:
    center = _center(
        {
            "center_id": "center-1",
            "center_name": "해뜰어린이집",
            "center_type": "국공립",
            "capacity": 45,
            "distance_meters": 319.6,
            "reference_date": date(2026, 7, 19),
            "dataset_version": "v1",
        }
    )

    assert center.capacity == 45
    assert center.distance_meters == 320
    assert center.reference_date == date(2026, 7, 19)


def test_childcare_region_coverage_requires_90_percent_and_clean_counts() -> None:
    assert _coverage_ratio(_coverage()) == 1.0
    assert _complete_region_coverage(_coverage(spatial_count=9)) is True
    assert _complete_region_coverage(_coverage(spatial_count=8)) is False
    assert _complete_region_coverage(_coverage(non_spatial_count=1)) is False
    assert _complete_region_coverage(_coverage(stale_row_count=1)) is False
    assert _complete_region_coverage(_coverage(unknown_region_count=1)) is False
    assert _coverage_ratio(_coverage(total_count=0, spatial_count=0)) is None
    assert _coverage_ratio(_coverage(total_count=None, spatial_count=None)) is None


@pytest.mark.parametrize(
    "overrides",
    [
        {"latitude": float("nan")},
        {"longitude": 181.0},
        {"radius_meters": 99},
        {"radius_meters": 2001},
        {"limit": 6},
        {"region_code": "1171"},
        {"region_code": "abcde"},
    ],
)
def test_childcare_query_rejects_unsafe_bounds(overrides: dict[str, object]) -> None:
    query: dict[str, object] = {
        "latitude": 37.5,
        "longitude": 127.0,
        "radius_meters": 800,
        "limit": 5,
        "region_code": "11710",
    }
    query.update(overrides)

    with pytest.raises(ValueError, match="childcare query"):
        _validate_query(**query)  # type: ignore[arg-type]


def test_childcare_repository_rejects_unsafe_configuration_before_connecting() -> None:
    with pytest.raises(ValueError, match="DSN"):
        PostgresChildcareRepository("")
    with pytest.raises(ValueError, match="database boundary"):
        PostgresChildcareRepository("postgresql://unused", expected_database=" ")
    with pytest.raises(ValueError, match="pool size"):
        PostgresChildcareRepository(
            "postgresql://unused",
            min_pool_size=0,
            max_pool_size=5,
        )
