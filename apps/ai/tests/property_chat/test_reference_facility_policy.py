from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import pytest

from ai_service.property_chat.reference_facilities import (
    PostgresPointFacilityRepository,
    _coverage_ratio,
    _facility,
    _validate_query,
    _verified_zero,
)


def _coverage(**overrides: object) -> dict[str, object]:
    value: dict[str, object] = {
        "total_count": 10,
        "spatial_count": 10,
        "non_spatial_count": 0,
        "stale_row_count": 0,
        "unknown_region_count": 0,
        "source_date": date.today(),
        "observed_at": None,
        "freshness_days": 40,
    }
    value.update(overrides)
    return value


def test_coordinate_coverage_handles_missing_empty_and_partial_metadata() -> None:
    assert _coverage_ratio(None) is None
    assert _coverage_ratio(_coverage(total_count=0)) is None
    assert _coverage_ratio(_coverage(spatial_count=9)) == 0.9


@pytest.mark.parametrize(
    "overrides",
    [
        {"total_count": None},
        {"source_date": None, "observed_at": None},
        {"freshness_days": None},
        {"source_date": date.min},
        {"source_date": date.max},
        {"non_spatial_count": 1},
        {"stale_row_count": 1},
        {"unknown_region_count": 1},
    ],
)
def test_verified_zero_fails_closed_when_coverage_is_incomplete(
    overrides: dict[str, object],
) -> None:
    assert _verified_zero(_coverage(**overrides)) is False


def test_verified_zero_accepts_fresh_complete_observed_snapshot() -> None:
    assert _verified_zero(
        _coverage(
            source_date=None,
            observed_at=datetime.now(UTC) - timedelta(days=1),
        )
    ) is True


def test_facility_fact_prefers_source_date_and_rounds_distance() -> None:
    fact = _facility(
        {
            "fact_id": "store-1",
            "name": "대형점포",
            "category": "RETAIL",
            "subcategory": None,
            "status": "OPEN",
            "address": None,
            "distance_meters": 999.6,
            "dataset_version": "v1",
            "source_date": date(2026, 1, 1),
            "dataset_observed_at": None,
        }
    )

    assert fact.distance_meters == 1000
    assert fact.subcategory is None
    assert fact.address is None


@pytest.mark.parametrize(
    "kwargs",
    [
        {"latitude": float("nan")},
        {"longitude": 181.0},
        {"radius_meters": 99},
        {"limit": 6},
        {"region_code": " "},
        {"subcategories": ("LARGE_MART", "LARGE_MART")},
    ],
)
def test_facility_query_policy_rejects_unsafe_inputs(kwargs: dict[str, object]) -> None:
    valid: dict[str, object] = {
        "latitude": 37.5,
        "longitude": 127.0,
        "radius_meters": 1000,
        "limit": 5,
        "region_code": "11710",
        "subcategories": (),
    }
    valid.update(kwargs)
    with pytest.raises(ValueError):
        _validate_query(**valid)  # type: ignore[arg-type]


def test_repository_rejects_empty_dsn_before_opening_pool() -> None:
    with pytest.raises(ValueError):
        PostgresPointFacilityRepository("")
