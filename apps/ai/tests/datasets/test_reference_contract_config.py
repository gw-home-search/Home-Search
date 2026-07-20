from __future__ import annotations

from pathlib import Path

import pytest

from ai_service.datasets.contracts import (
    AcquisitionContract,
    LicenseNotApprovedError,
    LicenseContract,
    QualityContract,
    ReferenceSourceCatalog,
    ReferenceSourceContract,
    TemporalContract,
    load_reference_source_catalog,
)
from datetime import date


CONFIG_PATH = Path(__file__).parents[2] / "config" / "reference_sources.toml"


def test_catalog_loads_the_fixed_source_order_and_only_school_is_approved() -> None:
    catalog = load_reference_source_catalog(CONFIG_PATH)

    assert catalog.source_ids == (
        "edu.school-location",
        "edu.school-zone",
        "edu.academy-registry",
        "place.sbiz-academy",
        "retail.large-store",
        "transport.rail-station",
        "medical.hira-hospital",
        "medical.hira-pharmacy",
        "childcare.center",
        "environment.city-park",
    )
    assert catalog.approved("edu.school-location").license.status == "APPROVED"
    retail = catalog.get("retail.large-store")
    assert retail.acquisition.base_url == (
        "https://file.localdata.go.kr/file/download/large_scale_retail_stores/info"
    )
    assert retail.acquisition.source_date == date(2025, 11, 27)
    assert retail.acquisition.referer_url == (
        "https://file.localdata.go.kr/file/large_scale_retail_stores/info"
    )
    rail = catalog.get("transport.rail-station")
    assert rail.acquisition.base_url == (
        "https://data.kric.go.kr/rips/dataset/download.file"
    )
    assert rail.acquisition.fixed_query == "type=filedata&id=32&operation=1"

    with pytest.raises(LicenseNotApprovedError):
        catalog.approved("retail.large-store")


def test_contract_rejects_credentials_queries_and_unallowlisted_acquisition_paths(
    tmp_path: Path,
) -> None:
    config = tmp_path / "reference_sources.toml"
    config.write_text(
        """
[[sources]]
id = "fixture.source"
provider = "fixture"
source_name = "fixture"
landing_url = "https://example.invalid/source"
evidence_grade = "A"
owner = "apps/ai"
normalization_schema_version = "fixture-v1"

[sources.acquisition]
mode = "api"
base_url = "https://example.invalid/not-allowed?serviceKey=secret"
allowed_hosts = ["api.example.invalid"]
allowed_path_prefixes = ["/allowed/"]
format = "JSON"
encoding = "UTF-8"
maximum_bundle_bytes = 1024
redirect_policy = "REJECT"

[sources.temporal]
basis = "SOURCE_DATE"
freshness_days = 10
refresh_profile = "MONTHLY"

[sources.quality]
minimum_rows = 1
maximum_rows = 2
maximum_row_change_ratio = 0.1
minimum_coordinate_ratio = 1.0
minimum_region_coordinate_ratio = 1.0
maximum_rejected_ratio = 0.0

[sources.license]
status = "APPROVED"
terms_url = "https://example.invalid/terms"
terms_fingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
reviewed_on = "2026-07-19"
reviewed_by = "product-owner"
attribution_text = "fixture"
raw_private_storage_allowed = true
internal_derivative_allowed = true
public_redistribution_allowed = false
third_party_rights = false
""",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="acquisition base URL"):
        load_reference_source_catalog(config)


def test_contract_value_objects_reject_unsafe_bounds_and_incomplete_approval() -> None:
    with pytest.raises(ValueError, match="maximum bundle"):
        AcquisitionContract(
            mode="api",
            base_url="https://api.example.invalid/allowed/source",
            allowed_hosts=("api.example.invalid",),
            allowed_path_prefixes=("/allowed/",),
            format="JSON",
            encoding="UTF-8",
            source_crs=None,
            maximum_bundle_bytes=0,
            redirect_policy="REJECT",
        )

    for fixed_query in (
        "serviceKey=secret",
        "id=32&id=33",
        "id=%0A",
        "?id=32",
    ):
        with pytest.raises(ValueError, match="fixed query"):
            AcquisitionContract(
                mode="file",
                base_url="https://api.example.invalid/allowed/source",
                allowed_hosts=("api.example.invalid",),
                allowed_path_prefixes=("/allowed/",),
                format="XLSX",
                encoding="UTF-8",
                source_crs=None,
                maximum_bundle_bytes=1024,
                redirect_policy="REJECT",
                fixed_query=fixed_query,
            )

    with pytest.raises(ValueError, match="source date"):
        AcquisitionContract(
            mode="api",
            base_url="https://api.example.invalid/allowed/source",
            allowed_hosts=("api.example.invalid",),
            allowed_path_prefixes=("/allowed/",),
            format="JSON",
            encoding="UTF-8",
            source_crs=None,
            maximum_bundle_bytes=1024,
            redirect_policy="REJECT",
            source_date=date(2025, 11, 27),
        )

    with pytest.raises(ValueError, match="source date"):
        AcquisitionContract(
            mode="file",
            base_url="https://api.example.invalid/allowed/source",
            allowed_hosts=("api.example.invalid",),
            allowed_path_prefixes=("/allowed/",),
            format="CSV",
            encoding="UTF-8",
            source_crs=None,
            maximum_bundle_bytes=1024,
            redirect_policy="REJECT",
            source_date="2025-11-27",  # type: ignore[arg-type]
        )

    with pytest.raises(ValueError, match="referer"):
        AcquisitionContract(
            mode="file",
            base_url="https://api.example.invalid/allowed/source",
            allowed_hosts=("api.example.invalid",),
            allowed_path_prefixes=("/allowed/",),
            format="XLSX",
            encoding="UTF-8",
            source_crs=None,
            maximum_bundle_bytes=1024,
            redirect_policy="REJECT",
            referer_url="https://evil.invalid/landing",
        )

    with pytest.raises(ValueError, match="referer"):
        AcquisitionContract(
            mode="file",
            base_url="https://api.example.invalid/allowed/source",
            allowed_hosts=("api.example.invalid",),
            allowed_path_prefixes=("/allowed/",),
            format="CSV",
            encoding="UTF-8",
            source_crs=None,
            maximum_bundle_bytes=1024,
            redirect_policy="REJECT",
            referer_url="https://api.example.invalid/landing\nInjected: value",
        )


def _valid_acquisition() -> AcquisitionContract:
    return AcquisitionContract(
        mode="api",
        base_url="https://api.example.invalid/allowed/source",
        allowed_hosts=("api.example.invalid",),
        allowed_path_prefixes=("/allowed/",),
        format="JSON",
        encoding="UTF-8",
        source_crs=None,
        maximum_bundle_bytes=1024,
        redirect_policy="REJECT",
    )


def _pending_license() -> LicenseContract:
    return LicenseContract(
        status="PENDING",
        terms_url="https://example.invalid/terms",
        terms_fingerprint="",
        reviewed_on=None,
        reviewed_by="",
        attribution_text="",
        raw_private_storage_allowed=False,
        internal_derivative_allowed=False,
        public_redistribution_allowed=False,
        third_party_rights=False,
    )


def _valid_reference_source(**overrides: object) -> ReferenceSourceContract:
    values: dict[str, object] = {
        "id": "fixture.source",
        "provider": "provider",
        "source_name": "source",
        "landing_url": "https://example.invalid/source",
        "evidence_grade": "A",
        "owner": "apps/ai",
        "normalization_schema_version": "v1",
        "acquisition": _valid_acquisition(),
        "temporal": TemporalContract("SOURCE_DATE", 10, "MONTHLY"),
        "quality": QualityContract(1, 2, 0.1, 1, 1, 0),
        "license": _pending_license(),
    }
    values.update(overrides)
    return ReferenceSourceContract(**values)  # type: ignore[arg-type]


@pytest.mark.parametrize(
    "factory",
    [
        lambda: AcquisitionContract(
            mode="api",
            base_url="https://api.example.invalid/allowed/source",
            allowed_hosts=("api.example.invalid",),
            allowed_path_prefixes=("relative",),
            format="JSON",
            encoding="UTF-8",
            source_crs=None,
            maximum_bundle_bytes=1024,
            redirect_policy="REJECT",
        ),
        lambda: TemporalContract("SOURCE_DATE", 1, " "),
        lambda: QualityContract(1, 2, 1.1, 1, 1, 0),
        lambda: LicenseContract(
            status="PENDING",
            terms_url="http://example.invalid/terms",
            terms_fingerprint="",
            reviewed_on=None,
            reviewed_by="",
            attribution_text="",
            raw_private_storage_allowed=False,
            internal_derivative_allowed=False,
            public_redistribution_allowed=False,
            third_party_rights=False,
        ),
        lambda: _valid_reference_source(id="INVALID"),
        lambda: _valid_reference_source(landing_url="http://example.invalid/source"),
        lambda: _valid_reference_source(provider=" "),
    ],
)
def test_reference_contract_components_fail_closed(factory) -> None:
    with pytest.raises(ValueError):
        factory()


def test_reference_catalog_missing_source_raises_key_error() -> None:
    catalog = ReferenceSourceCatalog((_valid_reference_source(),))

    with pytest.raises(KeyError):
        catalog.get("missing.source")


def test_temporal_quality_and_approved_license_bounds_fail_closed() -> None:
    with pytest.raises(ValueError, match="temporal"):
        TemporalContract(basis="SOURCE_DATE", freshness_days=0, refresh_profile="MONTHLY")
    with pytest.raises(ValueError, match="quality"):
        QualityContract(
            minimum_rows=10,
            maximum_rows=1,
            maximum_row_change_ratio=0.1,
            minimum_coordinate_ratio=1.0,
            minimum_region_coordinate_ratio=1.0,
            maximum_rejected_ratio=0.0,
        )
    with pytest.raises(ValueError, match="approved license"):
        LicenseContract(
            status="APPROVED",
            terms_url="https://example.invalid/terms",
            terms_fingerprint="invalid",
            reviewed_on=date(2026, 7, 19),
            reviewed_by="product-owner",
            attribution_text="fixture",
            raw_private_storage_allowed=True,
            internal_derivative_allowed=True,
            public_redistribution_allowed=False,
            third_party_rights=False,
        )
