from __future__ import annotations

import math
import re
from datetime import date, datetime

from defusedxml import ElementTree
from defusedxml.common import DefusedXmlException

from .bundle import ReadArtifact, read_deterministic_bundle
from .contracts import ReferenceSourceContract
from .models import DatasetSourceContract, ParsedDataset
from .validation import RawPayloadError


SOURCE_ID = "childcare.center"
LANDING_URL = "https://www.data.go.kr/data/15013108/standard.do"
ACQUISITION_URL = (
    "https://api.childcare.go.kr/mediate/rest/cpmsapi030/cpmsapi030/request"
)
ACQUISITION_PATH = "/mediate/rest/cpmsapi030/cpmsapi030/request"
_MAX_BUNDLE_BYTES = 256 * 1024 * 1024
_STATUS = {
    "정상": "OPEN",
    "재개": "OPEN",
    "휴지": "SUSPENDED",
    "폐지": "CLOSED",
}
_ALLOWED_FIELDS = frozenset(
    {
        "sidoname",
        "sigunguname",
        "stcode",
        "crname",
        "crtypename",
        "crstatusname",
        "zipcode",
        "craddr",
        "crtelno",
        "crfaxno",
        "crhome",
        "nrtrroomcnt",
        "nrtrroomsize",
        "plgrdco",
        "cctvinstlcnt",
        "chcrtescnt",
        "crcapat",
        "crchcnt",
        "la",
        "lo",
        "crcargbname",
        "crcnfmdt",
        "crpausebegindt",
        "crpauseenddt",
        "crabldt",
        "datastdrdt",
        "crspec",
        "class_cnt_00",
        "class_cnt_01",
        "class_cnt_02",
        "class_cnt_03",
        "class_cnt_04",
        "class_cnt_05",
        "class_cnt_m2",
        "class_cnt_m3",
        "class_cnt_m5",
        "class_cnt_sp",
        "class_cnt_tot",
        "child_cnt_00",
        "child_cnt_01",
        "child_cnt_02",
        "child_cnt_03",
        "child_cnt_04",
        "child_cnt_05",
        "child_cnt_m2",
        "child_cnt_m3",
        "child_cnt_m5",
        "child_cnt_sp",
        "child_cnt_tot",
        "em_cnt_0y",
        "em_cnt_1y",
        "em_cnt_2y",
        "em_cnt_4y",
        "em_cnt_6y",
        "em_cnt_a1",
        "em_cnt_a2",
        "em_cnt_a3",
        "em_cnt_a4",
        "em_cnt_a5",
        "em_cnt_a6",
        "em_cnt_a10",
        "em_cnt_a7",
        "em_cnt_a8",
        "em_cnt_tot",
        "crrepname",
        "ew_cnt_00",
        "ew_cnt_01",
        "ew_cnt_02",
        "ew_cnt_03",
        "ew_cnt_04",
        "ew_cnt_05",
        "ew_cnt_m6",
        "ew_cnt_tot",
    }
)
_REQUIRED_FIELDS = frozenset(
    {
        "sidoname",
        "sigunguname",
        "stcode",
        "crname",
        "crtypename",
        "crstatusname",
        "craddr",
        "crcapat",
        "la",
        "lo",
        "datastdrdt",
    }
)


def childcare_source_contract(
    *,
    license_terms: str | None = None,
    license_reviewed_on: date | None = None,
    attribution_requirements: str = "출처: 어린이집정보공개포털",
    reference_contract: ReferenceSourceContract | None = None,
) -> DatasetSourceContract:
    if reference_contract is not None:
        if reference_contract.id != SOURCE_ID or reference_contract.license.status != "APPROVED":
            raise ValueError("approved childcare reference contract is required")
        license_terms = (
            f"{reference_contract.license.terms_url}#"
            f"{reference_contract.license.terms_fingerprint}"
        )
        license_reviewed_on = reference_contract.license.reviewed_on
        attribution_requirements = reference_contract.license.attribution_text
    if not license_terms or not license_terms.strip() or "pending" in license_terms.casefold():
        raise ValueError("childcare license terms must be approved")
    if license_reviewed_on is None:
        raise ValueError("childcare license review date is required")
    return DatasetSourceContract(
        source_id=SOURCE_ID,
        provider="교육부·한국사회보장정보원",
        landing_url=LANDING_URL,
        acquisition_url=ACQUISITION_URL,
        license_terms=license_terms,
        attribution_requirements=attribution_requirements,
        license_reviewed_on=license_reviewed_on,
        refresh_frequency="monthly",
        freshness_days=45,
        file_format="xml-api-bundle",
        encoding="utf-8",
        schema_version=(
            reference_contract.normalization_schema_version
            if reference_contract is not None
            else "childcare-center-v1"
        ),
        coordinate_system="EPSG:4326",
        unique_key_fields=("center_id",),
        required_fields=(
            "center_id",
            "center_name",
            "center_type",
            "operating_status",
            "address",
            "capacity",
            "latitude",
            "longitude",
            "reference_date",
            "observed_at",
            "region_code",
            "region_name",
        ),
        expected_min_rows=10_000,
        expected_max_rows=100_000,
        maximum_row_change_ratio=0.10,
        maximum_rejected_ratio=0.0,
        contains_personal_data=False,
        owner="ai-platform",
        temporal_basis="OBSERVED_AT",
    )


class ChildcareAdapter:
    def parse(
        self,
        raw_bytes: bytes,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset:
        if contract.source_id != SOURCE_ID or source_date is not None:
            raise RawPayloadError(
                "childcare source contract mismatch", "SOURCE_CONTRACT_MISMATCH"
            )
        bundle = read_deterministic_bundle(
            raw_bytes,
            expected_source_id=SOURCE_ID,
            maximum_bytes=_MAX_BUNDLE_BYTES,
        )
        if (
            bundle.endpoint_path != ACQUISITION_PATH
            or not isinstance(bundle.temporal_value, datetime)
            or bundle.temporal_value.tzinfo is None
        ):
            raise RawPayloadError(
                "childcare bundle metadata mismatch", "BUNDLE_MANIFEST_INVALID"
            )
        rows: list[dict[str, object]] = []
        rejections: dict[int, tuple[str, ...]] = {}
        seen_regions: set[str] = set()
        for artifact in bundle.artifacts:
            region_code = _artifact_region_code(artifact)
            if region_code in seen_regions:
                raise RawPayloadError(
                    "childcare region artifact is duplicated", "BUNDLE_MANIFEST_INVALID"
                )
            seen_regions.add(region_code)
            for provider_row in _parse_items(artifact.content):
                normalized, reasons = _normalize(
                    provider_row,
                    region_code=region_code,
                    observed_at=bundle.temporal_value,
                )
                rows.append(normalized)
                if reasons:
                    rejections[len(rows)] = tuple(reasons)
        if not seen_regions:
            raise RawPayloadError(
                "childcare bundle has no regions", "BUNDLE_MANIFEST_INVALID"
            )
        return ParsedDataset(rows=rows, row_rejections=rejections)


def _artifact_region_code(artifact: ReadArtifact) -> str:
    match = re.fullmatch(r"region-([0-9]{5})", artifact.logical_name)
    if artifact.media_type not in {"application/xml", "text/xml"} or match is None:
        raise RawPayloadError(
            "childcare artifact metadata mismatch", "BUNDLE_MANIFEST_INVALID"
        )
    return match.group(1)


def _parse_items(content: bytes) -> list[dict[str, str]]:
    try:
        root = ElementTree.fromstring(content)
    except (DefusedXmlException, ElementTree.ParseError, ValueError):
        raise RawPayloadError("childcare XML is invalid", "SOURCE_XML_INVALID") from None
    if root.tag.casefold() != "response" or (root.text or "").strip():
        raise RawPayloadError(
            "childcare XML envelope mismatch", "SOURCE_SCHEMA_MISMATCH"
        )
    rows: list[dict[str, str]] = []
    for item in root:
        if item.tag.casefold() != "item" or list(item) == []:
            raise RawPayloadError(
                "childcare XML item mismatch", "SOURCE_SCHEMA_MISMATCH"
            )
        row: dict[str, str] = {}
        for field in item:
            name = field.tag.casefold()
            if name not in _ALLOWED_FIELDS or name in row or list(field):
                raise RawPayloadError(
                    "childcare XML field mismatch", "SOURCE_SCHEMA_MISMATCH"
                )
            row[name] = _text(field.text)
        if not _REQUIRED_FIELDS.issubset(row):
            raise RawPayloadError(
                "childcare XML required fields missing", "SOURCE_SCHEMA_MISMATCH"
            )
        rows.append(row)
    return rows


def _normalize(
    row: dict[str, str],
    *,
    region_code: str,
    observed_at: datetime,
) -> tuple[dict[str, object], list[str]]:
    status = _STATUS.get(row["crstatusname"], "UNKNOWN")
    capacity = _integer(row["crcapat"])
    latitude = _number(row["la"])
    longitude = _number(row["lo"])
    reference_date = _date(row["datastdrdt"])
    reasons: list[str] = []
    if status == "UNKNOWN":
        reasons.append("OPERATING_STATUS_UNKNOWN")
    if not isinstance(capacity, int) or capacity < 0:
        reasons.append("CAPACITY_INVALID")
    if not _korea_coordinate(latitude, longitude):
        reasons.append("KOREA_COORDINATE_OUT_OF_RANGE")
    if reference_date is None:
        reasons.append("REFERENCE_DATE_INVALID")
    region_name = " ".join(
        value for value in (row["sidoname"], row["sigunguname"]) if value
    )
    return (
        {
            "center_id": row["stcode"],
            "center_name": row["crname"],
            "center_type": row["crtypename"],
            "operating_status": status,
            "address": row["craddr"],
            "road_address": None,
            "lot_address": None,
            "capacity": capacity,
            "latitude": latitude,
            "longitude": longitude,
            "reference_date": reference_date,
            "observed_at": observed_at.isoformat(),
            "region_code": region_code,
            "region_name": region_name,
        },
        reasons,
    )


def _text(value: str | None) -> str:
    return " ".join((value or "").split())


def _integer(value: str) -> int | str:
    return int(value) if value.isascii() and value.isdigit() and len(value) <= 9 else value


def _number(value: str) -> float | str:
    try:
        parsed = float(value)
        return parsed if math.isfinite(parsed) else value
    except ValueError:
        return value


def _date(value: str) -> str | None:
    try:
        normalized = value.replace("-", "")
        if not re.fullmatch(r"[0-9]{8}", normalized):
            return None
        return date(int(normalized[:4]), int(normalized[4:6]), int(normalized[6:])).isoformat()
    except ValueError:
        return None


def _korea_coordinate(latitude: object, longitude: object) -> bool:
    return (
        isinstance(latitude, (int, float))
        and not isinstance(latitude, bool)
        and isinstance(longitude, (int, float))
        and not isinstance(longitude, bool)
        and 32.0 <= float(latitude) <= 39.5
        and 123.0 <= float(longitude) <= 132.0
    )
