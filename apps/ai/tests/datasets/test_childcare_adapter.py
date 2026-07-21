from __future__ import annotations

from datetime import UTC, date, datetime

import pytest

from ai_service.datasets.bundle import BundleArtifact, build_deterministic_bundle
from ai_service.datasets.childcare import (
    ACQUISITION_PATH,
    ChildcareAdapter,
    childcare_source_contract,
)
from ai_service.datasets.validation import RawPayloadError


OBSERVED_AT = datetime(2026, 7, 21, 9, 30, tzinfo=UTC)


def test_childcare_adapter_normalizes_only_allowed_fields_from_official_xml() -> None:
    parsed = ChildcareAdapter().parse(
        _bundle(
            _response(
                stcode="11620000341",
                name="꿈나무어린이집",
                center_type="국공립",
                status="정상",
                latitude="37.5131",
                longitude="127.0822",
            )
        ),
        _contract(),
        source_date=None,
    )

    rows = list(parsed.rows)
    assert rows == [
        {
            "center_id": "11620000341",
            "center_name": "꿈나무어린이집",
            "center_type": "국공립",
            "operating_status": "OPEN",
            "address": "서울특별시 송파구 올림픽로 1",
            "road_address": None,
            "lot_address": None,
            "capacity": 50,
            "latitude": 37.5131,
            "longitude": 127.0822,
            "reference_date": "2026-07-21",
            "observed_at": OBSERVED_AT.isoformat(),
            "region_code": "11710",
            "region_name": "서울특별시 송파구",
        }
    ]
    assert parsed.row_rejections == {}
    assert "000-0000-0000" not in repr(rows)
    assert "https://example.invalid" not in repr(rows)


def test_childcare_adapter_rejects_unknown_status_and_out_of_korea_coordinates() -> None:
    parsed = ChildcareAdapter().parse(
        _bundle(
            _response(
                stcode="11620000342",
                name="미확인어린이집",
                center_type="민간",
                status="알수없음",
                latitude="47.59452212",
                longitude="137.0643009",
            )
        ),
        _contract(),
        source_date=None,
    )

    assert parsed.row_rejections == {
        1: ("OPERATING_STATUS_UNKNOWN", "KOREA_COORDINATE_OUT_OF_RANGE")
    }


@pytest.mark.parametrize(
    ("provider_status", "normalized_status"),
    (("정상", "OPEN"), ("재개", "OPEN"), ("휴지", "SUSPENDED"), ("폐지", "CLOSED")),
)
def test_childcare_adapter_maps_only_documented_statuses(
    provider_status: str, normalized_status: str
) -> None:
    parsed = ChildcareAdapter().parse(
        _bundle(
            _response(
                stcode="11620000341",
                name="꿈나무어린이집",
                center_type="국공립",
                status=provider_status,
                latitude="37.5131",
                longitude="127.0822",
            )
        ),
        _contract(),
        source_date=None,
    )

    assert list(parsed.rows)[0]["operating_status"] == normalized_status


def test_childcare_adapter_rejects_xxe_and_provider_schema_drift() -> None:
    xxe = b"""<?xml version="1.0"?><!DOCTYPE response [
<!ENTITY leak SYSTEM "file:///etc/passwd">]><response><item><stcode>&leak;</stcode></item></response>"""
    with pytest.raises(RawPayloadError) as xxe_error:
        ChildcareAdapter().parse(
            _bundle(xxe), _contract(), source_date=None
        )
    assert xxe_error.value.reason_code == "SOURCE_XML_INVALID"

    changed = _response(
        stcode="11620000341",
        name="꿈나무어린이집",
        center_type="국공립",
        status="정상",
        latitude="37.5131",
        longitude="127.0822",
    ).replace(b"</item>", b"<unexpected>value</unexpected></item>")
    with pytest.raises(RawPayloadError) as schema_error:
        ChildcareAdapter().parse(
            _bundle(changed), _contract(), source_date=None
        )
    assert schema_error.value.reason_code == "SOURCE_SCHEMA_MISMATCH"


def test_childcare_contract_rejects_pending_license() -> None:
    with pytest.raises(ValueError, match="license terms"):
        childcare_source_contract(
            license_terms="pending",
            license_reviewed_on=date(2026, 7, 21),
        )


def test_childcare_adapter_rejects_invalid_capacity_date_and_temporal_contract() -> None:
    invalid = _response(
        stcode="11620000341",
        name="꿈나무어린이집",
        center_type="국공립",
        status="정상",
        latitude="nan",
        longitude="127.0822",
    ).replace(b"<crcapat>50</crcapat>", b"<crcapat>-1</crcapat>").replace(
        b"<datastdrdt>2026-07-21</datastdrdt>",
        b"<datastdrdt>invalid</datastdrdt>",
    )
    parsed = ChildcareAdapter().parse(
        _bundle(invalid), _contract(), source_date=None
    )
    assert parsed.row_rejections == {
        1: (
            "CAPACITY_INVALID",
            "KOREA_COORDINATE_OUT_OF_RANGE",
            "REFERENCE_DATE_INVALID",
        )
    }

    with pytest.raises(RawPayloadError) as temporal_error:
        ChildcareAdapter().parse(
            _bundle(invalid), _contract(), source_date=date(2026, 7, 21)
        )
    assert temporal_error.value.reason_code == "SOURCE_CONTRACT_MISMATCH"


def _contract():
    return childcare_source_contract(
        license_terms="official-contract-fixture",
        license_reviewed_on=date(2026, 7, 21),
        attribution_requirements="출처: 어린이집정보공개포털",
    )


def _bundle(response: bytes, *, observed_at: datetime = OBSERVED_AT) -> bytes:
    return build_deterministic_bundle(
        source_id="childcare.center",
        endpoint_path=ACQUISITION_PATH,
        artifacts=(
            BundleArtifact("region-11710", "xml", "application/xml", response),
        ),
        temporal_value=observed_at,
    )


def _response(
    *,
    stcode: str,
    name: str,
    center_type: str,
    status: str,
    latitude: str,
    longitude: str,
) -> bytes:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<response>
  <item>
    <sidoname>서울특별시</sidoname>
    <sigunguname>송파구</sigunguname>
    <stcode>{stcode}</stcode>
    <crname>{name}</crname>
    <crtypename>{center_type}</crtypename>
    <crstatusname>{status}</crstatusname>
    <craddr>서울특별시 송파구 올림픽로 1</craddr>
    <crtelno>000-0000-0000</crtelno>
    <crfaxno>000-0000-0000</crfaxno>
    <crhome>https://example.invalid</crhome>
    <crcapat>50</crcapat>
    <la>{latitude}</la>
    <lo>{longitude}</lo>
    <datastdrdt>2026-07-21</datastdrdt>
  </item>
</response>""".encode()
