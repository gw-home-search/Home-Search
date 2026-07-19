from __future__ import annotations

import csv
import io
from datetime import UTC, date, datetime

from pyproj import Transformer
import pytest

from ai_service.datasets.bundle import BundleArtifact, build_deterministic_bundle
from ai_service.datasets.large_store import LargeStoreAdapter
from ai_service.datasets.models import DatasetSourceContract
from ai_service.datasets.validation import validate_rows
from ai_service.datasets.postgres import PostgresDatasetRepository
from ai_service.datasets.service import DatasetLifecycleService
from ai_service.property_chat.reference_facilities import PostgresPointFacilityRepository
import psycopg


SOURCE_DATE = date(2026, 7, 18)


def _contract() -> DatasetSourceContract:
    return DatasetSourceContract(
        source_id="retail.large-store",
        provider="행정안전부",
        landing_url="https://www.data.go.kr/data/15114138/standard.do",
        acquisition_url="https://file.localdata.go.kr/file/large_scale_retail_stores/info",
        license_terms="offline fixture only",
        attribution_requirements="행정안전부",
        license_reviewed_on=date(2026, 7, 19),
        refresh_frequency="monthly",
        freshness_days=40,
        file_format="CSV",
        encoding="utf-8",
        schema_version="large-store-v1",
        coordinate_system="EPSG:5174",
        unique_key_fields=("facility_id",),
        required_fields=(
            "facility_id", "name", "category", "subcategory", "status",
            "original_crs", "reference_date", "fact_kind",
        ),
        expected_min_rows=1,
        expected_max_rows=10,
        maximum_row_change_ratio=0.1,
        maximum_rejected_ratio=0.0,
        contains_personal_data=False,
        owner="apps/ai",
    )


def _bundle(*rows: dict[str, str]) -> bytes:
    output = io.StringIO(newline="")
    fieldnames = [
        "관리번호", "개방자치단체코드", "영업상태명", "사업장명", "소재지전체주소",
        "도로명전체주소", "업태구분명", "좌표정보(X)", "좌표정보(Y)",
        "데이터갱신일자",
    ]
    writer = csv.DictWriter(output, fieldnames=fieldnames)
    writer.writeheader()
    writer.writerows(rows)
    return build_deterministic_bundle(
        source_id="retail.large-store",
        endpoint_path="/file/large_scale_retail_stores/info",
        artifacts=(
            BundleArtifact("large-store", "csv", "text/csv", output.getvalue().encode()),
        ),
        temporal_value=SOURCE_DATE,
    )


def _row(**overrides: str) -> dict[str, str]:
    x, y = Transformer.from_crs("EPSG:4326", "EPSG:5174", always_xy=True).transform(
        126.978, 37.5665
    )
    row = {
        "관리번호": "store-1",
        "개방자치단체코드": "3010000",
        "영업상태명": "영업/정상",
        "사업장명": "서울 대형마트",
        "소재지전체주소": "서울특별시 중구",
        "도로명전체주소": "서울특별시 중구 세종대로 1",
        "업태구분명": "대형마트",
        "좌표정보(X)": str(x),
        "좌표정보(Y)": str(y),
        "데이터갱신일자": SOURCE_DATE.isoformat(),
    }
    row.update(overrides)
    return row


def test_epsg_5174_coordinates_are_preserved_and_transformed_to_wgs84() -> None:
    parsed = LargeStoreAdapter().parse(_bundle(_row()), _contract(), source_date=SOURCE_DATE)

    point = parsed.rows[0]
    assert point["fact_kind"] == "POINT"
    assert point["original_crs"] == "EPSG:5174"
    assert abs(float(point["latitude"]) - 37.5665) < 0.00001
    assert abs(float(point["longitude"]) - 126.978) < 0.00001
    assert point["subcategory"] == "LARGE_MART"
    assert point["status"] == "OPEN"


def test_unknown_provider_status_blocks_publication_instead_of_defaulting_open() -> None:
    parsed = LargeStoreAdapter().parse(
        _bundle(_row(영업상태명="새로운상태")), _contract(), source_date=SOURCE_DATE
    )
    outcome = validate_rows(
        _contract(),
        parsed.rows,
        None,
        source_date=SOURCE_DATE,
        collected_at=datetime(2026, 7, 19, tzinfo=UTC),
        adapter_issues=parsed.issues,
        adapter_rejections=parsed.row_rejections,
    )

    assert outcome.has_blocking_issues is True
    assert "PROVIDER_STATUS_UNKNOWN" in outcome.staged_rows[0].rejection_codes
    assert parsed.rows[0]["status"] == "UNKNOWN"


def test_missing_coordinate_is_valid_non_spatial_registry_fact() -> None:
    parsed = LargeStoreAdapter().parse(
        _bundle(_row(**{"좌표정보(X)": "", "좌표정보(Y)": ""})),
        _contract(),
        source_date=SOURCE_DATE,
    )
    outcome = validate_rows(
        _contract(),
        parsed.rows,
        None,
        source_date=SOURCE_DATE,
        collected_at=datetime(2026, 7, 19, tzinfo=UTC),
        adapter_issues=parsed.issues,
        adapter_rejections=parsed.row_rejections,
    )

    assert outcome.has_blocking_issues is False
    assert outcome.rejected_row_count == 0
    assert parsed.rows[0]["fact_kind"] == "REGISTRY"


def test_publication_routes_spatial_and_non_spatial_rows_to_typed_projections(
    dataset_repository: PostgresDatasetRepository,
    postgres_dsn: str,
) -> None:
    lifecycle = DatasetLifecycleService(
        dataset_repository,
        clock=lambda: datetime(2026, 7, 19, tzinfo=UTC),
    )
    raw = _bundle(
        _row(),
        _row(
            **{
                "관리번호": "store-2",
                "사업장명": "좌표 없는 백화점",
                "업태구분명": "백화점",
                "좌표정보(X)": "",
                "좌표정보(Y)": "",
            }
        ),
    )

    result = lifecycle.ingest_validate_publish(
        _contract(), raw, source_date=SOURCE_DATE, adapter=LargeStoreAdapter()
    )

    assert result.status == "Pass"
    with psycopg.connect(postgres_dsn) as connection:
        point = connection.execute(
            "SELECT name, status, subcategory FROM reference_read.facility_point_fact"
        ).fetchone()
        registry = connection.execute(
            "SELECT name, status FROM reference_read.registry_fact"
        ).fetchone()
        coverage = connection.execute(
            """
            SELECT total_count, spatial_count, non_spatial_count
            FROM reference_read.source_coverage WHERE region_code = '3010000'
            """
        ).fetchone()
    assert point == ("서울 대형마트", "OPEN", "LARGE_MART")
    assert registry == ("좌표 없는 백화점", "OPEN")
    assert coverage == (2, 1, 1)

    with psycopg.connect(postgres_dsn) as connection:
        connection.execute(
            """
            INSERT INTO reference_projection.facility_point(
                publication_id, source_id, fact_id, category, subcategory,
                name, status, region_code, position, original_crs,
                row_reference_date
            ) VALUES (
                %s, 'retail.large-store', 'store-boundary', 'RETAIL', 'LARGE_MART',
                '정확히 1km 점포', 'OPEN', '3010000',
                ST_Project(
                    ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
                    1000, 0
                ),
                'EPSG:4326', %s
            ), (
                %s, 'retail.large-store', 'store-other-region', 'RETAIL', 'LARGE_MART',
                '다른 지역 점포', 'OPEN', '3020000',
                ST_SetSRID(ST_MakePoint(129.0, 35.0), 4326)::geography,
                'EPSG:4326', %s
            )
            """,
            (result.publication_id, SOURCE_DATE, result.publication_id, SOURCE_DATE),
        )
        connection.execute(
            """
            INSERT INTO reference_projection.source_coverage(
                publication_id, source_id, region_code, total_count, spatial_count,
                non_spatial_count, open_count, stale_row_count, unknown_region_count
            ) VALUES (%s, 'retail.large-store', '3020000', 1, 1, 0, 1, 0, 0)
            """,
            (result.publication_id,),
        )

    facility_repository = PostgresPointFacilityRepository(
        postgres_dsn,
        expected_database="test",
        expected_username="test",
    )
    try:
        boundary = facility_repository.nearby(
            source_id="retail.large-store",
            category="RETAIL",
            latitude=37.5665,
            longitude=126.978,
            radius_meters=1000,
            limit=5,
            region_code="3010000",
        )
        uncertain_zero = facility_repository.nearby(
            source_id="retail.large-store",
            category="RETAIL",
            latitude=37.0,
            longitude=127.0,
            radius_meters=100,
            limit=5,
            region_code="3010000",
        )
        verified_zero = facility_repository.nearby(
            source_id="retail.large-store",
            category="RETAIL",
            latitude=37.0,
            longitude=127.0,
            radius_meters=100,
            limit=5,
            region_code="3020000",
        )
    finally:
        facility_repository.close()

    assert any(fact.fact_id == "store-boundary" for fact in boundary.facilities), boundary
    assert uncertain_zero.verified_zero is False
    assert uncertain_zero.coordinate_coverage == 0.5
    assert verified_zero.verified_zero is True
    assert verified_zero.coordinate_coverage == 1.0

    with psycopg.connect(postgres_dsn) as connection:
        connection.execute("SET ROLE home_search_ai_runtime")
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("SELECT count(*) FROM reference_projection.facility_point")
