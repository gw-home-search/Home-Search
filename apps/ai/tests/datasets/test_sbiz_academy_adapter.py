from __future__ import annotations

import json
from datetime import UTC, date, datetime

import pytest
from dataclasses import replace

from ai_service.datasets.bundle import BundleArtifact, build_deterministic_bundle
from ai_service.datasets.models import DatasetSourceContract
from ai_service.datasets.sbiz_academy import (
    SbizAcademyAdapter,
    SbizTaxonomyContract,
    taxonomy_fingerprint,
)
from ai_service.datasets import sbiz_academy
from ai_service.datasets.validation import RawPayloadError
from ai_service.datasets.models import ParsedRow


TAXONOMY = {
    "taxonomy-large": [{"code": "P1", "name": "교육"}],
    "taxonomy-middle": [{"code": "P101", "name": "교육서비스"}],
    "taxonomy-small": [{"code": "P10101", "name": "fixture 학원"}],
}


def _taxonomy() -> SbizTaxonomyContract:
    return SbizTaxonomyContract(taxonomy_fingerprint(TAXONOMY), {"P10101": "fixture 학원"})


def _contract() -> DatasetSourceContract:
    return DatasetSourceContract(
        source_id="place.sbiz-academy", provider="소상공인시장진흥공단",
        landing_url="https://www.data.go.kr/data/15012005/openapi.do",
        acquisition_url="https://apis.data.go.kr/B553077/api/open/sdsc2/storeListInUpjong",
        license_terms="fixture", attribution_requirements="fixture",
        license_reviewed_on=date(2026, 7, 20), refresh_frequency="monthly",
        freshness_days=45, file_format="JSON", encoding="UTF-8", schema_version="sbiz-v1",
        coordinate_system="EPSG:4326", unique_key_fields=("store_id",),
        required_fields=("store_id", "name", "small_category_code", "latitude", "longitude", "observed_at"),
        expected_min_rows=1, expected_max_rows=10, maximum_row_change_ratio=0.1,
        maximum_rejected_ratio=0, contains_personal_data=False, owner="apps/ai",
        temporal_basis="OBSERVED_AT",
    )


def _bundle(
    *,
    duplicate: bool = False,
    taxonomy=TAXONOMY,
    name: str = "가나다 학원",
    small_category_name: str = "fixture 학원",
) -> bytes:
    items = [{
        "bizesId": "store-1", "bizesNm": name, "indsSclsCd": "P10101",
        "indsSclsNm": small_category_name,
        "rdnmAdr": "서울특별시 송파구 올림픽로 300", "lnoAdr": "서울 송파구 1", "newZipcd": "05551",
        "adongCd": "1171056600", "lat": "37.5", "lon": "127.1", "telNo": "02-secret",
    }]
    if duplicate:
        items.append(dict(items[0]))
    artifacts = [
        BundleArtifact(
            artifact_name,
            "json",
            "application/json",
            _taxonomy_page(artifact_name, value),
        )
        for artifact_name, value in taxonomy.items()
    ]
    artifacts.append(BundleArtifact(
        "p10101-page-000001", "json", "application/json",
        json.dumps({"body": {"totalCount": len(items), "numOfRows": 1000, "items": items}}).encode(),
    ))
    return build_deterministic_bundle(
        source_id="place.sbiz-academy", endpoint_path="/B553077/api/open/sdsc2/storeListInUpjong",
        artifacts=tuple(artifacts), temporal_value=datetime(2026, 7, 20, tzinfo=UTC),
    )


def _taxonomy_page(name: str, rows: list[dict[str, str]]) -> bytes:
    fields = {
        "taxonomy-large": ("indsLclsCd", "indsLclsNm"),
        "taxonomy-middle": ("indsMclsCd", "indsMclsNm"),
        "taxonomy-small": ("indsSclsCd", "indsSclsNm"),
    }
    code_field, name_field = fields[name]
    items = [
        {code_field: row["code"], name_field: row["name"]}
        for row in rows
    ]
    return json.dumps({"body": {"items": items}}, ensure_ascii=False).encode()


def _rows(parsed):
    return [
        candidate.row_data if isinstance(candidate, ParsedRow) else candidate
        for candidate in parsed.rows
    ]


def test_sbiz_adapter_requires_tracked_taxonomy_and_excludes_phone() -> None:
    parsed = SbizAcademyAdapter(_taxonomy()).parse(_bundle(), _contract(), source_date=None)
    rows = _rows(parsed)

    assert rows[0]["store_id"] == "store-1"
    assert rows[0]["small_category_name"] == "fixture 학원"
    assert rows[0]["postal_code"] == "05551"
    assert "telNo" not in rows[0]


def test_sbiz_file_adapter_matches_bytes_adapter(tmp_path) -> None:
    content = _bundle()
    path = tmp_path / "sbiz-academy.zip"
    path.write_bytes(content)

    rows = _rows(
        SbizAcademyAdapter(_taxonomy()).parse_file(
            path, _contract(), source_date=None
        )
    )

    assert rows[0]["store_id"] == "store-1"
    assert "telNo" not in rows[0]


def test_sbiz_adapter_blocks_taxonomy_change_and_duplicate_store_id() -> None:
    changed = {**TAXONOMY, "taxonomy-small": [{"code": "P10102", "name": "changed"}]}
    with pytest.raises(RawPayloadError) as error:
        _rows(SbizAcademyAdapter(_taxonomy()).parse(
            _bundle(taxonomy=changed), _contract(), source_date=None
        ))
    assert error.value.reason_code == "TAXONOMY_CHANGED"

    with pytest.raises(RawPayloadError) as error:
        _rows(SbizAcademyAdapter(_taxonomy()).parse(
            _bundle(duplicate=True), _contract(), source_date=None
        ))
    assert error.value.reason_code == "DUPLICATE_STORE_ID"

    with pytest.raises(RawPayloadError) as error:
        _rows(SbizAcademyAdapter(_taxonomy()).parse(
            _bundle(small_category_name="변경된 업종명"),
            _contract(),
            source_date=None,
        ))
    assert error.value.reason_code == "TAXONOMY_CHANGED"


def test_sbiz_contract_taxonomy_and_page_shapes_fail_closed() -> None:
    with pytest.raises(ValueError):
        SbizTaxonomyContract("bad", {})
    with pytest.raises(RawPayloadError) as error:
        SbizAcademyAdapter(_taxonomy()).parse(
            _bundle(), replace(_contract(), temporal_basis="SOURCE_DATE"),
            source_date=date(2026, 7, 20),
        )
    assert error.value.reason_code == "SOURCE_CONTRACT_MISMATCH"

    with pytest.raises(RawPayloadError) as error:
        sbiz_academy._page(
            json.dumps({"body": {"totalCount": 1, "numOfRows": 999, "items": []}}).encode()
        )
    assert error.value.reason_code == "SOURCE_SCHEMA_MISMATCH"
    assert sbiz_academy._number("nan") is None
    assert sbiz_academy._number("not-number") is None
