from __future__ import annotations

import hashlib
import io
import json
import zipfile
from dataclasses import replace
from datetime import UTC, date, datetime

import pytest

from ai_service.datasets.school_location import (
    SchoolLocationAdapter,
    build_bundle,
    school_location_source_contract,
)
from ai_service.datasets.validation import RawPayloadError, validate_rows


REFERENCE_DATE = date(2026, 3, 20)
OFFICE_CODES = (
    "7010000", "7150000", "7240000", "7310000", "7380000", "7430000",
    "7480000", "7530000", "7801000", "8000000", "8140000", "8321000",
    "8490000", "8750000", "9010000", "9290000", "9300000",
)


def _row(index: int, office_code: str = "7010000") -> dict[str, object]:
    return {
        "schoolId": f"B{index:09d}",
        "schoolNm": f"학교 {index}",
        "schoolSe": ("초등학교", "중학교", "고등학교")[index % 3],
        "operSttus": "운영",
        "lnmadr": f"서울특별시 주소 {index}",
        "rdnmadr": "",
        "cddcCode": office_code,
        "cddcNm": f"교육청 {office_code}",
        "latitude": "37.5001",
        "longitude": "127.0001",
        "referenceDate": REFERENCE_DATE.isoformat(),
    }


def _page(page_no: int, rows: list[dict[str, object]], total_count: int) -> bytes:
    return json.dumps(
        {
            "response": {
                "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                "body": {
                    "items": rows,
                    "pageNo": page_no,
                    "numOfRows": 1000,
                    "totalCount": total_count,
                },
            }
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()


def _contract():
    return replace(
        school_location_source_contract(
            license_terms="Dataset-specific use approved for storage and display",
            license_reviewed_on=date(2026, 7, 19),
        ),
        expected_min_rows=1,
        expected_max_rows=100,
    )


def test_bundle_bytes_are_deterministic_and_preserve_provider_pages() -> None:
    pages = [_page(1, [_row(1)], 1)]

    first = build_bundle(pages=pages, page_size=1000, total_count=1)
    second = build_bundle(pages=pages, page_size=1000, total_count=1)

    assert first == second
    with zipfile.ZipFile(io.BytesIO(first)) as archive:
        assert archive.namelist() == ["manifest.json", "artifacts/page-000001.json"]
        assert archive.read("artifacts/page-000001.json") == pages[0]
        manifest = json.loads(archive.read("manifest.json"))
        assert manifest["artifacts"][0]["sha256"] == hashlib.sha256(pages[0]).hexdigest()
        assert manifest["sourceId"] == "edu.school-location"
        assert manifest["complete"] is True


def test_adapter_normalizes_official_rows_and_checks_all_17_offices() -> None:
    rows = [_row(index, code) for index, code in enumerate(OFFICE_CODES, start=1)]
    raw = build_bundle(pages=[_page(1, rows, len(rows))], page_size=1000, total_count=len(rows))

    parsed = SchoolLocationAdapter().parse(raw, _contract(), source_date=REFERENCE_DATE)
    outcome = validate_rows(
        _contract(),
        parsed.rows,
        None,
        source_date=REFERENCE_DATE,
        collected_at=datetime(2026, 7, 19, tzinfo=UTC),
        adapter_issues=parsed.issues,
        adapter_rejections=parsed.row_rejections,
    )

    assert outcome.has_blocking_issues is False
    assert outcome.accepted_row_count == 17
    assert parsed.rows[0]["school_id"] == "B000000001"
    assert parsed.rows[0]["latitude"] == 37.5001


@pytest.mark.parametrize(
    ("mutate", "reason_code"),
    [
        (lambda manifest, pages: manifest["artifacts"][0].update(sha256="0" * 64), "BUNDLE_PAGE_CHECKSUM_MISMATCH"),
        (lambda manifest, pages: pages[0]["response"]["body"].update(totalCount=2), "API_TOTAL_COUNT_MISMATCH"),
        (lambda manifest, pages: pages[0]["response"]["body"]["items"][0].update(referenceDate="2026-03-19"), "REFERENCE_DATE_MISMATCH"),
    ],
)
def test_adapter_rejects_pagination_integrity_failures(mutate, reason_code: str) -> None:
    page_document = json.loads(_page(1, [_row(1)], 1))
    page_bytes = json.dumps(page_document, ensure_ascii=False, separators=(",", ":")).encode()
    bundle = build_bundle(pages=[page_bytes], page_size=1000, total_count=1)
    with zipfile.ZipFile(io.BytesIO(bundle)) as archive:
        manifest = json.loads(archive.read("manifest.json"))
    pages = [page_document]
    mutate(manifest, pages)
    page_bytes = json.dumps(pages[0], ensure_ascii=False, separators=(",", ":")).encode()
    if reason_code == "BUNDLE_PAGE_CHECKSUM_MISMATCH":
        broken = _manual_bundle(manifest, [page_bytes])
    else:
        broken = build_bundle(pages=[page_bytes], page_size=1000, total_count=1)

    with pytest.raises(RawPayloadError) as error:
        SchoolLocationAdapter().parse(broken, _contract(), source_date=REFERENCE_DATE)

    assert error.value.reason_code == reason_code


def test_adapter_maps_malformed_manifest_metadata_to_stable_parse_failure() -> None:
    page = _page(1, [_row(1)], 1)
    bundle = build_bundle(pages=[page], page_size=1000, total_count=1)
    with zipfile.ZipFile(io.BytesIO(bundle)) as archive:
        manifest = json.loads(archive.read("manifest.json"))
    manifest["artifacts"][0]["logicalName"] = "page-invalid"
    broken = _manual_bundle(manifest, [page])

    with pytest.raises(RawPayloadError) as error:
        SchoolLocationAdapter().parse(broken, _contract(), source_date=REFERENCE_DATE)

    assert error.value.reason_code == "BUNDLE_MANIFEST_INVALID"


def _manual_bundle(manifest: dict[str, object], pages: list[bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        info = zipfile.ZipInfo("manifest.json", date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode())
        for index, page in enumerate(pages, start=1):
            info = zipfile.ZipInfo(f"artifacts/page-{index:06d}.json", date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, page)
    return output.getvalue()


@pytest.mark.parametrize(
    "kwargs",
    [
        {"pages": [], "page_size": 1000, "total_count": 0},
        {"pages": [b"{}"], "page_size": 10, "total_count": 1},
        {
            "pages": [b"{}"],
            "page_size": 1000,
            "total_count": 1,
            "complete": False,
        },
    ],
)
def test_bundle_builder_rejects_inconsistent_metadata(kwargs) -> None:
    with pytest.raises(ValueError):
        build_bundle(**kwargs)


def test_bundle_builder_rejects_unbounded_failure_reason() -> None:
    with pytest.raises(ValueError):
        build_bundle(
            pages=[b"{}"],
            page_size=1000,
            total_count=2,
            complete=False,
            failed_page=2,
            reason_code="secret-provider-detail",
        )


def test_adapter_quarantines_school_specific_row_errors_and_blocks_coverage() -> None:
    row = _row(1)
    row.update(
        schoolSe="대학교",
        lnmadr="",
        rdnmadr="",
        latitude="10",
        longitude="10",
    )
    raw = build_bundle(pages=[_page(1, [row], 1)], page_size=1000, total_count=1)

    parsed = SchoolLocationAdapter().parse(raw, _contract(), source_date=REFERENCE_DATE)
    outcome = validate_rows(
        _contract(),
        parsed.rows,
        None,
        source_date=REFERENCE_DATE,
        collected_at=datetime(2026, 7, 19, tzinfo=UTC),
        adapter_issues=parsed.issues,
        adapter_rejections=parsed.row_rejections,
    )

    assert set(outcome.staged_rows[0].rejection_codes) == {
        "ADDRESS_MISSING",
        "SCHOOL_LEVEL_INVALID",
        "KOREA_COORDINATE_OUT_OF_RANGE",
    }
    assert "EDUCATION_OFFICE_COVERAGE_MISMATCH" in {
        issue.reason_code for issue in outcome.issues
    }


def test_adapter_rejects_source_and_incomplete_bundle_contracts() -> None:
    other_contract = replace(_contract(), source_id="edu.other")
    complete = build_bundle(
        pages=[_page(1, [_row(1)], 1)], page_size=1000, total_count=1
    )
    incomplete = build_bundle(
        pages=[_page(1, [_row(1)], 2)],
        page_size=1000,
        total_count=2,
        complete=False,
        failed_page=2,
        reason_code="API_SERVER_ERROR",
    )

    with pytest.raises(RawPayloadError) as source_error:
        SchoolLocationAdapter().parse(complete, other_contract, source_date=REFERENCE_DATE)
    with pytest.raises(RawPayloadError) as incomplete_error:
        SchoolLocationAdapter().parse(incomplete, _contract(), source_date=REFERENCE_DATE)

    assert source_error.value.reason_code == "SOURCE_CONTRACT_MISMATCH"
    assert incomplete_error.value.reason_code == "API_SERVER_ERROR"


@pytest.mark.parametrize(
    ("document", "reason_code"),
    [
        ({"unexpected": {}}, "API_ENVELOPE_ROOT_INVALID"),
        (
            {
                "response": {
                    "header": {"resultCode": "99", "resultMsg": "failure"},
                    "body": {},
                }
            },
            "API_PROVIDER_REJECTED",
        ),
        (
            {
                "response": {
                    "header": {"resultCode": "00", "resultMsg": "ok"},
                    "body": {"items": [], "pageNo": 2, "numOfRows": 1000, "totalCount": 0},
                }
            },
            "API_PAGE_NUMBER_MISMATCH",
        ),
        (
            {
                "response": {
                    "header": {"resultCode": "00", "resultMsg": "ok"},
                    "body": {"items": [], "pageNo": 1, "numOfRows": 10, "totalCount": 0},
                }
            },
            "API_PAGE_SIZE_MISMATCH",
        ),
    ],
)
def test_adapter_rejects_invalid_official_envelopes(document, reason_code: str) -> None:
    page = json.dumps(document, separators=(",", ":")).encode()
    raw = build_bundle(pages=[page], page_size=1000, total_count=0)

    with pytest.raises(RawPayloadError) as error:
        SchoolLocationAdapter().parse(raw, _contract(), source_date=REFERENCE_DATE)

    assert error.value.reason_code == reason_code
