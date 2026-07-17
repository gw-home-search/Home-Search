from __future__ import annotations

from datetime import date

import pytest

from ai_service.datasets import DatasetSourceContract


def test_acquisition_url_must_not_persist_query_credentials() -> None:
    with pytest.raises(ValueError, match="acquisition_url must not contain credentials"):
        DatasetSourceContract(
            source_id="fixture.secure-source",
            provider="Fixture",
            landing_url="https://example.invalid/source?view=landing",
            acquisition_url="https://example.invalid/source.json?serviceKey=redacted",
            license_terms="Fixture only",
            attribution_requirements="Fixture",
            license_reviewed_on=date(2026, 7, 16),
            refresh_frequency="fixed",
            freshness_days=1,
            file_format="json",
            encoding="utf-8",
            schema_version="v1",
            coordinate_system="EPSG:4326",
            unique_key_fields=("id",),
            required_fields=("id",),
            expected_min_rows=1,
            expected_max_rows=1,
            maximum_row_change_ratio=0.0,
            maximum_rejected_ratio=0.0,
            contains_personal_data=False,
            owner="ai-platform",
        )
