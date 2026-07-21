from __future__ import annotations

import hashlib
import io
import json
import math
import zipfile
from datetime import date
from typing import Any

from .models import DatasetSourceContract, ParsedDataset, QualityIssue
from .contracts import ReferenceSourceContract
from .validation import RawPayloadError


SOURCE_ID = "edu.school-location"
SOURCE_NAME = "전국초중등학교위치표준데이터"
LANDING_URL = "https://www.data.go.kr/data/15021148/standard.do"
ACQUISITION_URL = "https://api.data.go.kr/openapi/tn_pubr_public_elesch_mskul_lc_api"
ACQUISITION_PATH = "/openapi/tn_pubr_public_elesch_mskul_lc_api"
PAGE_SIZE = 1000
EXPECTED_EDUCATION_OFFICE_CODES = frozenset(
    {
        "7010000",
        "7150000",
        "7240000",
        "7310000",
        "7380000",
        "7430000",
        "7480000",
        "7530000",
        "7801000",
        "8000000",
        "8140000",
        "8321000",
        "8490000",
        "8750000",
        "9010000",
        "9290000",
        "9300000",
    }
)
_SCHOOL_LEVEL_MAP = {
    "초등학교": "ELEMENTARY",
    "중학교": "MIDDLE",
    "고등학교": "HIGH",
}
_SCHOOL_LEVELS = frozenset(_SCHOOL_LEVEL_MAP.values())
_AUTHENTICATION_RESULT_CODES = frozenset({"20", "21", "30", "31", "32", "33"})
_FIXED_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
_MAX_BUNDLE_BYTES = 128 * 1024 * 1024
BUNDLE_FAILURE_REASON_CODES = frozenset(
    {
        "API_AUTHENTICATION_FAILED",
        "API_BAD_REQUEST",
        "API_BUNDLE_TOO_LARGE",
        "API_ENVELOPE_INVALID",
        "API_ENVELOPE_BODY_INVALID",
        "API_ENVELOPE_ITEMS_INVALID",
        "API_ENVELOPE_RESPONSE_INVALID",
        "API_ENVELOPE_ROOT_INVALID",
        "API_MEDIA_TYPE_INVALID",
        "API_PAGE_TOO_LARGE",
        "API_PAGINATION_INVALID",
        "API_PROVIDER_REJECTED",
        "API_QUOTA_EXCEEDED",
        "API_RATE_LIMITED",
        "API_REDIRECT_REJECTED",
        "API_SERVER_ERROR",
        "API_TRANSPORT_FAILED",
    }
)


def school_location_source_contract(
    *,
    license_terms: str | None = None,
    license_reviewed_on: date | None = None,
    attribution_requirements: str = SOURCE_NAME,
    reference_contract: ReferenceSourceContract | None = None,
) -> DatasetSourceContract:
    if reference_contract is not None:
        if reference_contract.id != SOURCE_ID or reference_contract.license.status != "APPROVED":
            raise ValueError("approved school reference contract is required")
        license_terms = (
            f"{reference_contract.license.terms_url}#"
            f"{reference_contract.license.terms_fingerprint}"
        )
        license_reviewed_on = reference_contract.license.reviewed_on
        attribution_requirements = reference_contract.license.attribution_text
    if not license_terms or not license_terms.strip() or "pending" in license_terms.casefold():
        raise ValueError("dataset-specific license terms must be approved")
    if license_reviewed_on is None:
        raise ValueError("dataset-specific license review date is required")
    return DatasetSourceContract(
        source_id=SOURCE_ID,
        provider="교육부·한국지방교육행정연구재단",
        landing_url=LANDING_URL,
        acquisition_url=ACQUISITION_URL,
        license_terms=license_terms,
        attribution_requirements=attribution_requirements,
        license_reviewed_on=license_reviewed_on,
        refresh_frequency="semiannual",
        freshness_days=214,
        file_format="json-api-bundle",
        encoding="utf-8",
        schema_version=(
            reference_contract.normalization_schema_version
            if reference_contract is not None
            else "data-go-kr-school-location-v1"
        ),
        coordinate_system="EPSG:4326",
        unique_key_fields=("school_id",),
        required_fields=(
            "school_id",
            "school_name",
            "school_level",
            "operating_status",
            "latitude",
            "longitude",
            "reference_date",
            "education_office_code",
            "education_office_name",
        ),
        expected_min_rows=10_000,
        expected_max_rows=50_000,
        maximum_row_change_ratio=0.10,
        maximum_rejected_ratio=0.0,
        contains_personal_data=False,
        owner="ai-platform",
    )


def build_bundle(
    *,
    pages: list[bytes],
    page_size: int,
    total_count: int | None,
    complete: bool = True,
    failed_page: int | None = None,
    reason_code: str | None = None,
) -> bytes:
    if page_size != PAGE_SIZE:
        raise ValueError("school bundle page size must be 1000")
    if complete and (not pages or total_count is None or failed_page is not None or reason_code):
        raise ValueError("complete bundle metadata is inconsistent")
    if not complete and (failed_page is None or not reason_code):
        raise ValueError("incomplete bundle requires safe failure metadata")
    if not complete and reason_code not in BUNDLE_FAILURE_REASON_CODES:
        raise ValueError("incomplete bundle reason is not allowlisted")
    if not complete and failed_page != len(pages) + 1:
        raise ValueError("incomplete bundle failed page must follow preserved pages")
    bundle_source_date = _bundle_source_date(pages)
    manifest: dict[str, object] = {
        "bundleSchemaVersion": 1,
        "sourceId": SOURCE_ID,
        "endpointPath": ACQUISITION_PATH,
        "pageSize": page_size,
        "providerTotalCount": total_count,
        "complete": complete,
        "artifacts": [
            {
                "logicalName": f"page-{index:06d}",
                "mediaType": "application/json",
                "byteLength": len(page),
                "sha256": hashlib.sha256(page).hexdigest(),
            }
            for index, page in enumerate(pages, start=1)
        ],
    }
    if bundle_source_date is not None:
        manifest["sourceDate"] = bundle_source_date.isoformat()
    if not complete:
        manifest["failedPage"] = failed_page
        manifest["reasonCode"] = reason_code

    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        _write_zip_entry(
            archive,
            "manifest.json",
            json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode(),
        )
        for index, page in enumerate(pages, start=1):
            _write_zip_entry(archive, f"artifacts/page-{index:06d}.json", page)
    bundle = output.getvalue()
    if len(bundle) > _MAX_BUNDLE_BYTES:
        raise ValueError("school bundle exceeds maximum size")
    return bundle


class SchoolLocationAdapter:
    def parse(
        self,
        raw_bytes: bytes,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset:
        try:
            return self._parse_bundle(
                raw_bytes,
                contract,
                source_date=source_date,
            )
        except RawPayloadError:
            raise
        except (TypeError, ValueError):
            raise RawPayloadError(
                "school bundle metadata is invalid",
                "BUNDLE_MANIFEST_INVALID",
            ) from None

    def _parse_bundle(
        self,
        raw_bytes: bytes,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset:
        if contract.source_id != SOURCE_ID:
            raise RawPayloadError("school adapter source mismatch", "SOURCE_CONTRACT_MISMATCH")
        manifest, page_bytes = _read_bundle(raw_bytes)
        if manifest.get("complete") is not True:
            reason_code = manifest.get("reasonCode")
            failed_page = manifest.get("failedPage")
            if (
                manifest.get("complete") is not False
                or reason_code not in BUNDLE_FAILURE_REASON_CODES
                or _integer(failed_page, minimum=1) != len(page_bytes) + 1
            ):
                raise RawPayloadError(
                    "school incomplete bundle metadata is invalid",
                    "BUNDLE_MANIFEST_INVALID",
                )
            raise RawPayloadError(
                "school API pagination is incomplete",
                str(reason_code),
            )
        if manifest.get("endpointPath") != ACQUISITION_PATH or manifest.get("pageSize") != PAGE_SIZE:
            raise RawPayloadError("school bundle contract mismatch", "BUNDLE_MANIFEST_INVALID")
        if manifest.get("sourceId") != SOURCE_ID or manifest.get("bundleSchemaVersion") != 1:
            raise RawPayloadError("school bundle source mismatch", "BUNDLE_MANIFEST_INVALID")
        total_count = _integer(manifest.get("providerTotalCount"), minimum=0)
        manifest_pages = manifest.get("artifacts")
        if not isinstance(manifest_pages, list) or len(manifest_pages) != len(page_bytes):
            raise RawPayloadError("school bundle page list mismatch", "BUNDLE_MANIFEST_INVALID")

        normalized_rows: list[dict[str, object]] = []
        for expected_page_no, (raw_page, page_meta) in enumerate(
            zip(page_bytes, manifest_pages, strict=True), start=1
        ):
            meta = _object(page_meta)
            if set(meta) != {"logicalName", "mediaType", "byteLength", "sha256"}:
                raise RawPayloadError(
                    "school bundle page metadata fields invalid",
                    "BUNDLE_MANIFEST_INVALID",
                )
            if (
                meta.get("logicalName") != f"page-{expected_page_no:06d}"
                or meta.get("mediaType") != "application/json"
                or _integer(meta.get("byteLength"), minimum=0) != len(raw_page)
            ):
                raise RawPayloadError("school bundle page metadata mismatch", "BUNDLE_MANIFEST_INVALID")
            if meta.get("sha256") != hashlib.sha256(raw_page).hexdigest():
                raise RawPayloadError(
                    "school bundle page checksum mismatch", "BUNDLE_PAGE_CHECKSUM_MISMATCH"
                )
            rows, envelope_total = _parse_page(raw_page, expected_page_no)
            if envelope_total != total_count:
                raise RawPayloadError("school API total count mismatch", "API_TOTAL_COUNT_MISMATCH")
            normalized_rows.extend(_normalize_row(row) for row in rows)
        if len(normalized_rows) != total_count:
            raise RawPayloadError("school API row count mismatch", "API_TOTAL_COUNT_MISMATCH")

        reference_dates = {row.get("reference_date") for row in normalized_rows}
        expected_reference_date = source_date.isoformat() if source_date else None
        if (
            len(reference_dates) != 1
            or expected_reference_date not in reference_dates
            or manifest.get("sourceDate") != expected_reference_date
        ):
            raise RawPayloadError("school reference date mismatch", "REFERENCE_DATE_MISMATCH")

        row_rejections: dict[int, tuple[str, ...]] = {}
        for row_number, row in enumerate(normalized_rows, start=1):
            reasons: list[str] = []
            if not row.get("road_address") and not row.get("lot_address"):
                reasons.append("ADDRESS_MISSING")
            if row.get("school_level") not in _SCHOOL_LEVELS:
                reasons.append("SCHOOL_LEVEL_INVALID")
            if not _korea_coordinate(row.get("latitude"), row.get("longitude")):
                reasons.append("KOREA_COORDINATE_OUT_OF_RANGE")
            if reasons:
                row_rejections[row_number] = tuple(reasons)

        office_codes = {str(row.get("education_office_code")) for row in normalized_rows}
        issues: list[QualityIssue] = []
        if office_codes != EXPECTED_EDUCATION_OFFICE_CODES:
            issues.append(
                QualityIssue(
                    reason_code="EDUCATION_OFFICE_COVERAGE_MISMATCH",
                    severity="BLOCKING",
                    row_number=None,
                    details={
                        "actualCount": len(office_codes),
                        "expectedCount": len(EXPECTED_EDUCATION_OFFICE_CODES),
                    },
                )
            )
        return ParsedDataset(
            rows=normalized_rows,
            issues=tuple(issues),
            row_rejections=row_rejections,
        )


def extract_source_date(raw_bytes: bytes) -> date | None:
    try:
        manifest, pages = _read_bundle(raw_bytes)
        manifest_date = manifest.get("sourceDate")
        if isinstance(manifest_date, str):
            return date.fromisoformat(manifest_date)
        if not pages:
            return None
        rows, _total = _parse_page(pages[0], 1)
        values = {row.get("referenceDate") for row in rows if isinstance(row, dict)}
        if len(values) != 1:
            return None
        value = values.pop()
        return date.fromisoformat(value) if isinstance(value, str) else None
    except (ValueError, RawPayloadError):
        return None


def _read_bundle(raw_bytes: bytes) -> tuple[dict[str, Any], list[bytes]]:
    if len(raw_bytes) > _MAX_BUNDLE_BYTES:
        raise RawPayloadError("school bundle too large", "BUNDLE_TOO_LARGE")
    try:
        with zipfile.ZipFile(io.BytesIO(raw_bytes)) as archive:
            names = archive.namelist()
            if not names or names[0] != "manifest.json" or len(names) != len(set(names)):
                raise RawPayloadError("school bundle entries invalid", "BUNDLE_MANIFEST_INVALID")
            expected_names = ["manifest.json"] + [
                f"artifacts/page-{index:06d}.json" for index in range(1, len(names))
            ]
            if names != expected_names:
                raise RawPayloadError("school bundle entry order invalid", "BUNDLE_MANIFEST_INVALID")
            if len(names) - 1 > 128 or sum(info.file_size for info in archive.infolist()) > _MAX_BUNDLE_BYTES:
                raise RawPayloadError("school bundle expands beyond limit", "BUNDLE_TOO_LARGE")
            if any(info.file_size > 4 * 1024 * 1024 for info in archive.infolist()[1:]):
                raise RawPayloadError("school API page too large", "API_PAGE_TOO_LARGE")
            manifest = _object(_json(archive.read("manifest.json")))
            complete_keys = {
                "bundleSchemaVersion", "sourceId", "endpointPath", "pageSize",
                "providerTotalCount", "complete", "artifacts", "sourceDate"
            }
            incomplete_keys = complete_keys | {"failedPage", "reasonCode"}
            complete_without_date = complete_keys - {"sourceDate"}
            incomplete_without_date = incomplete_keys - {"sourceDate"}
            if set(manifest) not in {
                frozenset(complete_keys),
                frozenset(complete_without_date),
                frozenset(incomplete_keys),
                frozenset(incomplete_without_date),
            }:
                raise RawPayloadError("school bundle manifest fields invalid", "BUNDLE_MANIFEST_INVALID")
            pages = [archive.read(name) for name in names[1:]]
    except RawPayloadError:
        raise
    except (OSError, ValueError, zipfile.BadZipFile, KeyError, json.JSONDecodeError):
        raise RawPayloadError("school bundle is invalid", "BUNDLE_MANIFEST_INVALID") from None
    return manifest, pages


def _bundle_source_date(pages: list[bytes]) -> date | None:
    if not pages:
        return None
    try:
        rows, _total = _parse_page(pages[0], 1)
        values = {row.get("referenceDate") for row in rows}
        if len(values) != 1:
            return None
        value = values.pop()
        return date.fromisoformat(value) if isinstance(value, str) else None
    except (ValueError, RawPayloadError):
        return None


def _parse_page(raw_page: bytes, expected_page_no: int) -> tuple[list[dict[str, object]], int]:
    try:
        root = _object(_json(raw_page))
        if "response" not in root:
            raise RawPayloadError(
                "school API root envelope invalid", "API_ENVELOPE_ROOT_INVALID"
            )
        try:
            response = _object(root["response"])
        except ValueError:
            raise RawPayloadError(
                "school API response envelope invalid",
                "API_ENVELOPE_RESPONSE_INVALID",
            ) from None
        if not {"header", "body"}.issubset(response):
            raise RawPayloadError(
                "school API response envelope invalid",
                "API_ENVELOPE_RESPONSE_INVALID",
            )
        header = _object(response["header"])
        result_code = header.get("resultCode")
        if result_code not in {"00", "0"}:
            if result_code in _AUTHENTICATION_RESULT_CODES:
                reason_code = "API_AUTHENTICATION_FAILED"
            elif result_code == "22":
                reason_code = "API_QUOTA_EXCEEDED"
            elif result_code in {"10", "11"}:
                reason_code = "API_BAD_REQUEST"
            else:
                reason_code = "API_PROVIDER_REJECTED"
            raise RawPayloadError("school API provider failure", reason_code)
        try:
            body = _object(response["body"])
        except ValueError:
            raise RawPayloadError(
                "school API body envelope invalid", "API_ENVELOPE_BODY_INVALID"
            ) from None
        if not {"items", "pageNo", "numOfRows", "totalCount"}.issubset(body):
            raise RawPayloadError(
                "school API body envelope invalid", "API_ENVELOPE_BODY_INVALID"
            )
        if _integer(body["pageNo"], minimum=1) != expected_page_no:
            raise RawPayloadError("school API page number mismatch", "API_PAGE_NUMBER_MISMATCH")
        if _integer(body["numOfRows"], minimum=1) != PAGE_SIZE:
            raise RawPayloadError("school API page size mismatch", "API_PAGE_SIZE_MISMATCH")
        rows = body["items"]
        if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
            raise RawPayloadError(
                "school API items envelope invalid", "API_ENVELOPE_ITEMS_INVALID"
            )
        return rows, _integer(body["totalCount"], minimum=0)
    except RawPayloadError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError, ValueError):
        raise RawPayloadError("school API envelope invalid", "API_ENVELOPE_INVALID") from None


def _normalize_row(row: dict[str, object]) -> dict[str, object]:
    return {
        "school_id": _text(row.get("schoolId")),
        "school_name": _text(row.get("schoolNm")),
        "school_level": _SCHOOL_LEVEL_MAP.get(
            _text(row.get("schoolSe")), _text(row.get("schoolSe"))
        ),
        "operating_status": _text(row.get("operSttus")),
        "lot_address": _optional_text(row.get("lnmadr")),
        "road_address": _optional_text(row.get("rdnmadr")),
        "education_office_code": _text(row.get("cddcCode")),
        "education_office_name": _text(row.get("cddcNm")),
        "latitude": _float(row.get("latitude")),
        "longitude": _float(row.get("longitude")),
        "reference_date": _text(row.get("referenceDate")),
    }


def _write_zip_entry(archive: zipfile.ZipFile, name: str, content: bytes) -> None:
    info = zipfile.ZipInfo(name, date_time=_FIXED_ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o600 << 16
    archive.writestr(info, content, compress_type=zipfile.ZIP_STORED)


def _json(value: bytes) -> object:
    return json.loads(value.decode("utf-8"), parse_constant=lambda _value: (_ for _ in ()).throw(ValueError()))


def _object(value: object) -> dict[str, Any]:
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
        raise ValueError("object required")
    return value


def _integer(value: object, *, minimum: int) -> int:
    if (
        isinstance(value, str)
        and value.isascii()
        and value.isdigit()
        and len(value) <= 10
    ):
        value = int(value)
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError("integer required")
    return value


def _text(value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        return ""
    return " ".join(value.split())


def _optional_text(value: object) -> str | None:
    normalized = _text(value)
    return normalized or None


def _float(value: object) -> float | object:
    try:
        if isinstance(value, bool):
            return value
        converted = float(value)  # type: ignore[arg-type]
        return converted if math.isfinite(converted) else value
    except (TypeError, ValueError):
        return value


def _korea_coordinate(latitude: object, longitude: object) -> bool:
    return (
        isinstance(latitude, (int, float))
        and not isinstance(latitude, bool)
        and isinstance(longitude, (int, float))
        and not isinstance(longitude, bool)
        and math.isfinite(float(latitude))
        and math.isfinite(float(longitude))
        and 32.0 <= float(latitude) <= 39.5
        and 124.0 <= float(longitude) <= 132.0
    )
