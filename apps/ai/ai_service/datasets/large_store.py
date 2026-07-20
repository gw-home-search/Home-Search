from __future__ import annotations

import csv
import io
import json
import math
import re
from datetime import date, datetime
from pathlib import Path
from typing import TextIO

from pyproj import Transformer

from .bundle import (
    extract_single_artifact_bundle_file, read_deterministic_bundle,
    read_deterministic_bundle_file,
)
from .contracts import ReferenceSourceContract
from .models import DatasetSourceContract, ParsedDataset, ParsedRow, QualityIssue
from .secure_temp import SecureTempWorkspace
from .validation import RawPayloadError


SOURCE_ID = "retail.large-store"
_MAX_ARTIFACT_BYTES = 256 * 1024 * 1024
_MAX_BUNDLE_BYTES = _MAX_ARTIFACT_BYTES + 5 * 1024 * 1024
_TRANSFORMER = Transformer.from_crs("EPSG:5174", "EPSG:4326", always_xy=True)
_STATUS = {
    "영업/정상": "OPEN",
    "정상영업": "OPEN",
    "영업중": "OPEN",
    "폐업": "CLOSED",
    "휴업": "SUSPENDED",
}
_SUBCATEGORY = {
    "대형마트": "LARGE_MART",
    "백화점": "DEPARTMENT_STORE",
    "쇼핑센터": "SHOPPING_CENTER",
    "복합쇼핑몰": "COMPLEX_MALL",
    "그 밖의 대규모점포": "OTHER_LARGE_STORE",
    "그밖의 대규모점포": "OTHER_LARGE_STORE",
}
_REQUIRED_COLUMNS = {
    "관리번호",
    "개방자치단체코드",
    "영업상태명",
    "사업장명",
    "소재지전체주소",
    "도로명전체주소",
    "업태구분명",
    "좌표정보(X)",
    "좌표정보(Y)",
    "데이터갱신일자",
}


class LargeStoreAdapter:
    def parse(
        self,
        raw_bytes: bytes,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset:
        if contract.source_id != SOURCE_ID:
            raise RawPayloadError("large-store source contract mismatch", "SOURCE_CONTRACT_MISMATCH")
        bundle = read_deterministic_bundle(
            raw_bytes,
            expected_source_id=SOURCE_ID,
            maximum_bytes=_MAX_BUNDLE_BYTES,
        )
        if contract.temporal_basis == "OBSERVED_AT" and source_date is None:
            if not isinstance(bundle.temporal_value, datetime):
                raise RawPayloadError(
                    "large-store observed time is missing", "BUNDLE_MANIFEST_INVALID"
                )
            return ParsedDataset(rows=_iter_api_rows(bundle.artifacts, bundle.temporal_value))
        if (
            contract.temporal_basis != "SOURCE_DATE"
            or source_date is None
            or bundle.temporal_value != source_date
            or len(bundle.artifacts) != 1
        ):
            raise RawPayloadError("large-store bundle metadata mismatch", "BUNDLE_MANIFEST_INVALID")
        artifact = bundle.artifacts[0]
        if artifact.media_type != "text/csv":
            raise RawPayloadError("large-store artifact type mismatch", "BUNDLE_MANIFEST_INVALID")
        try:
            text = artifact.content.decode(contract.encoding)
        except (LookupError, UnicodeDecodeError):
            raise RawPayloadError("large-store CSV encoding mismatch", "CSV_ENCODING_INVALID") from None
        return _parse_csv(io.StringIO(text, newline=""), source_date)

    def parse_file(
        self,
        raw_path: Path,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset:
        if contract.source_id != SOURCE_ID:
            raise RawPayloadError(
                "large-store source contract mismatch", "SOURCE_CONTRACT_MISMATCH"
            )
        if contract.temporal_basis == "OBSERVED_AT" and source_date is None:
            bundle = read_deterministic_bundle_file(
                raw_path, expected_source_id=SOURCE_ID,
                maximum_bytes=_MAX_BUNDLE_BYTES,
                maximum_artifact_bytes=4 * 1024 * 1024,
            )
            if not isinstance(bundle.temporal_value, datetime):
                raise RawPayloadError(
                    "large-store observed time is missing", "BUNDLE_MANIFEST_INVALID"
                )
            return ParsedDataset(rows=_iter_api_rows(bundle.artifacts, bundle.temporal_value))
        if contract.temporal_basis != "SOURCE_DATE" or source_date is None:
            raise RawPayloadError(
                "large-store source contract mismatch", "SOURCE_CONTRACT_MISMATCH"
            )
        with SecureTempWorkspace(required_free_bytes=_MAX_ARTIFACT_BYTES) as workspace:
            bundle = extract_single_artifact_bundle_file(
                raw_path,
                workspace.create_file("large-store.csv"),
                expected_source_id=SOURCE_ID,
                maximum_bytes=_MAX_BUNDLE_BYTES,
                maximum_artifact_bytes=_MAX_ARTIFACT_BYTES,
            )
            if bundle.temporal_value != source_date or bundle.media_type != "text/csv":
                raise RawPayloadError(
                    "large-store bundle metadata mismatch", "BUNDLE_MANIFEST_INVALID"
                )
            try:
                with bundle.artifact_path.open(
                    "r", encoding=contract.encoding, newline=""
                ) as stream:
                    return _parse_csv(stream, source_date)
            except (LookupError, UnicodeDecodeError):
                raise RawPayloadError(
                    "large-store CSV encoding mismatch", "CSV_ENCODING_INVALID"
                ) from None


def _parse_csv(stream: TextIO, source_date: date) -> ParsedDataset:
    reader = csv.DictReader(stream)
    if reader.fieldnames is None or not _REQUIRED_COLUMNS.issubset(reader.fieldnames):
        raise RawPayloadError("large-store CSV schema mismatch", "SOURCE_SCHEMA_MISMATCH")
    rows: list[dict[str, object]] = []
    issues: list[QualityIssue] = []
    rejections: dict[int, tuple[str, ...]] = {}
    for row_number, provider_row in enumerate(reader, start=1):
        normalized, reasons = _normalize(provider_row, source_date)
        rows.append(normalized)
        if reasons:
            rejections[row_number] = tuple(reasons)
            issues.extend(
                QualityIssue(reason, "WARNING", row_number, {}) for reason in reasons
            )
    return ParsedDataset(rows=rows, issues=tuple(issues), row_rejections=rejections)


def _iter_api_rows(artifacts, observed_at: datetime):
    expected_page = 1
    expected_total: int | None = None
    seen_count = 0
    for artifact in artifacts:
        match = re.fullmatch(r"page-([0-9]{6})", artifact.logical_name)
        if artifact.media_type != "application/json" or match is None:
            raise RawPayloadError(
                "large-store artifact metadata is invalid", "BUNDLE_MANIFEST_INVALID"
            )
        page_number = int(match.group(1))
        if page_number != expected_page:
            raise RawPayloadError(
                "large-store pages are not contiguous", "PROVIDER_PAGE_INVALID"
            )
        page_total, provider_rows = _api_page(artifact.content, page_number)
        if expected_total is None:
            expected_total = page_total
        elif page_total != expected_total:
            raise RawPayloadError(
                "large-store total changed between pages",
                "PROVIDER_TOTAL_COUNT_MISMATCH",
            )
        for provider_row in provider_rows:
            seen_count += 1
            normalized, reasons = _normalize(_api_to_legacy(provider_row), observed_at.date())
            yield ParsedRow(normalized, tuple(reasons))
        expected_page += 1
    if expected_total is None or seen_count != expected_total:
        raise RawPayloadError(
            "large-store total count does not match rows",
            "PROVIDER_TOTAL_COUNT_MISMATCH",
        )


def _api_page(content: bytes, expected_page: int) -> tuple[int, list[dict[str, object]]]:
    try:
        value = json.loads(content)
        response = value["response"]
        header = response["header"]
        body = response["body"]
        rows = body["items"]["item"]
        total = body["totalCount"]
        if (
            header["resultCode"] != "00"
            or body["dataType"] != "JSON"
            or body["numOfRows"] != 100
            or body["pageNo"] != expected_page
            or isinstance(total, bool)
            or not isinstance(total, int)
            or total < 0
            or not isinstance(rows, list)
            or len(rows) > 100
            or not all(isinstance(row, dict) for row in rows)
        ):
            raise ValueError
        return total, rows
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        raise RawPayloadError(
            "large-store provider page is invalid", "PROVIDER_PAGE_INVALID"
        ) from None


def _api_to_legacy(row: dict[str, object]) -> dict[str, object]:
    return {
        "관리번호": row.get("MNG_NO"),
        "개방자치단체코드": row.get("OPN_ATMY_GRP_CD"),
        "영업상태명": row.get("SALS_STTS_NM"),
        "사업장명": row.get("BPLC_NM"),
        "소재지전체주소": row.get("LOTNO_ADDR"),
        "도로명전체주소": row.get("ROAD_NM_ADDR"),
        "업태구분명": row.get("BZSTAT_SE_NM"),
        "좌표정보(X)": row.get("CRD_INFO_X"),
        "좌표정보(Y)": row.get("CRD_INFO_Y"),
        "데이터갱신일자": row.get("DAT_UPDT_PNT"),
    }


def _normalize(provider_row: dict[str, object], reference_date: date):
    status_value = _clean(provider_row.get("영업상태명"))
    subtype_value = _clean(provider_row.get("업태구분명"))
    status = _STATUS.get(status_value)
    subtype = _SUBCATEGORY.get(subtype_value)
    reasons: list[str] = []
    if status is None:
        reasons.append("PROVIDER_STATUS_UNKNOWN")
    if subtype is None:
        reasons.append("RETAIL_SUBTYPE_NOT_ALLOWED")
    original_x = _number(provider_row.get("좌표정보(X)"))
    original_y = _number(provider_row.get("좌표정보(Y)"))
    longitude: float | None = None
    latitude: float | None = None
    fact_kind = "REGISTRY"
    if original_x is not None or original_y is not None:
        if original_x is None or original_y is None:
            reasons.append("COORDINATE_PAIR_INCOMPLETE")
        else:
            longitude, latitude = _TRANSFORMER.transform(original_x, original_y)
            if not _korea_coordinate(latitude, longitude):
                reasons.append("KOREA_COORDINATE_OUT_OF_RANGE")
            else:
                fact_kind = "POINT"
    normalized = {
        "facility_id": _clean(provider_row.get("관리번호")),
        "name": _clean(provider_row.get("사업장명")),
        "category": "RETAIL",
        "subcategory": subtype or subtype_value,
        "status": status or "UNKNOWN",
        "road_address": _optional(provider_row.get("도로명전체주소")),
        "lot_address": _optional(provider_row.get("소재지전체주소")),
        "region_code": _optional(provider_row.get("개방자치단체코드")),
        "region_name": _region_name(
            provider_row.get("도로명전체주소") or provider_row.get("소재지전체주소")
        ),
        "latitude": latitude,
        "longitude": longitude,
        "original_crs": "EPSG:5174",
        "original_x": original_x,
        "original_y": original_y,
        "reference_date": reference_date.isoformat(),
        "fact_kind": fact_kind,
    }
    return normalized, reasons


def large_store_source_contract(
    reference_contract: ReferenceSourceContract,
) -> DatasetSourceContract:
    if reference_contract.id != SOURCE_ID or reference_contract.license.reviewed_on is None:
        raise ValueError("large-store reference contract mismatch")
    return DatasetSourceContract(
        source_id=reference_contract.id,
        provider=reference_contract.provider,
        landing_url=reference_contract.landing_url,
        acquisition_url=reference_contract.acquisition.base_url,
        license_terms=reference_contract.license.terms_url,
        attribution_requirements=reference_contract.license.attribution_text,
        license_reviewed_on=reference_contract.license.reviewed_on,
        refresh_frequency=reference_contract.temporal.refresh_profile,
        freshness_days=reference_contract.temporal.freshness_days,
        file_format=reference_contract.acquisition.format,
        encoding=reference_contract.acquisition.encoding,
        schema_version=reference_contract.normalization_schema_version,
        coordinate_system=reference_contract.acquisition.source_crs or "NONE",
        unique_key_fields=("facility_id",),
        required_fields=(
            "facility_id", "name", "category", "subcategory", "status",
            "original_crs", "reference_date", "fact_kind",
        ),
        expected_min_rows=reference_contract.quality.minimum_rows,
        expected_max_rows=reference_contract.quality.maximum_rows,
        maximum_row_change_ratio=reference_contract.quality.maximum_row_change_ratio,
        maximum_rejected_ratio=reference_contract.quality.maximum_rejected_ratio,
        contains_personal_data=False,
        owner=reference_contract.owner,
        temporal_basis=reference_contract.temporal.basis,
    )


def _clean(value: object) -> str:
    return " ".join(value.split()) if isinstance(value, str) else ""


def _optional(value: object) -> str | None:
    normalized = _clean(value)
    return normalized or None


def _number(value: object) -> float | None:
    try:
        number = float(value)  # type: ignore[arg-type]
        return number if math.isfinite(number) else None
    except (TypeError, ValueError):
        return None


def _korea_coordinate(latitude: float, longitude: float) -> bool:
    return 32.0 <= latitude <= 39.5 and 124.0 <= longitude <= 132.0


def _region_name(value: object) -> str | None:
    address = _clean(value)
    parts = address.split()
    return " ".join(parts[:2]) if len(parts) >= 2 else None
