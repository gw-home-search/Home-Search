from __future__ import annotations

import csv
import io
import math
from datetime import date

from pyproj import Transformer

from .bundle import read_deterministic_bundle
from .models import DatasetSourceContract, ParsedDataset, QualityIssue
from .validation import RawPayloadError


SOURCE_ID = "retail.large-store"
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
        if contract.source_id != SOURCE_ID or source_date is None:
            raise RawPayloadError("large-store source contract mismatch", "SOURCE_CONTRACT_MISMATCH")
        bundle = read_deterministic_bundle(
            raw_bytes, expected_source_id=SOURCE_ID, maximum_bytes=256 * 1024 * 1024
        )
        if bundle.temporal_value != source_date or len(bundle.artifacts) != 1:
            raise RawPayloadError("large-store bundle metadata mismatch", "BUNDLE_MANIFEST_INVALID")
        artifact = bundle.artifacts[0]
        if artifact.media_type != "text/csv":
            raise RawPayloadError("large-store artifact type mismatch", "BUNDLE_MANIFEST_INVALID")
        try:
            text = artifact.content.decode(contract.encoding)
        except (LookupError, UnicodeDecodeError):
            raise RawPayloadError("large-store CSV encoding mismatch", "CSV_ENCODING_INVALID") from None
        reader = csv.DictReader(io.StringIO(text, newline=""))
        if reader.fieldnames is None or not _REQUIRED_COLUMNS.issubset(reader.fieldnames):
            raise RawPayloadError("large-store CSV schema mismatch", "SOURCE_SCHEMA_MISMATCH")
        rows: list[dict[str, object]] = []
        issues: list[QualityIssue] = []
        rejections: dict[int, tuple[str, ...]] = {}
        for row_number, provider_row in enumerate(reader, start=1):
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
                "reference_date": source_date.isoformat(),
                "fact_kind": fact_kind,
            }
            rows.append(normalized)
            if reasons:
                rejections[row_number] = tuple(reasons)
                issues.extend(
                    QualityIssue(reason, "WARNING", row_number, {}) for reason in reasons
                )
        return ParsedDataset(rows=rows, issues=tuple(issues), row_rejections=rejections)


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
