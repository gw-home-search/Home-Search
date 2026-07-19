from __future__ import annotations

import json
import re
import unicodedata
from collections import defaultdict
from datetime import date, datetime

from .bundle import read_deterministic_bundle
from .models import DatasetSourceContract, ParsedDataset, QualityIssue
from .validation import RawPayloadError


SOURCE_ID = "edu.academy-registry"
_OFFICE_CODES = frozenset(
    {
        "B10", "C10", "D10", "E10", "F10", "G10", "H10", "I10", "J10",
        "K10", "M10", "N10", "P10", "Q10", "R10", "S10", "T10",
    }
)
_STATUS = {
    "운영": "OPEN",
    "정상": "OPEN",
    "등록": "OPEN",
    "휴원": "SUSPENDED",
    "폐원": "CLOSED",
}
_ACADEMY_TYPES = frozenset({"학원", "교습소"})


class AcademyRegistryAdapter:
    def parse(
        self,
        raw_bytes: bytes,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset:
        if (
            contract.source_id != SOURCE_ID
            or contract.temporal_basis != "OBSERVED_AT"
            or source_date is not None
        ):
            raise RawPayloadError("academy source contract mismatch", "SOURCE_CONTRACT_MISMATCH")
        bundle = read_deterministic_bundle(
            raw_bytes,
            expected_source_id=SOURCE_ID,
            maximum_bytes=512 * 1024 * 1024,
        )
        if not isinstance(bundle.temporal_value, datetime):
            raise RawPayloadError("academy observed time is missing", "BUNDLE_MANIFEST_INVALID")
        observed_at = bundle.temporal_value
        office_pages: dict[str, dict[int, list[dict[str, object]]]] = defaultdict(dict)
        office_totals: dict[str, int] = {}
        for artifact in bundle.artifacts:
            match = re.fullmatch(r"([b-t][0-9]{2})-page-([0-9]{6})", artifact.logical_name)
            if artifact.media_type != "application/json" or match is None:
                raise RawPayloadError("academy artifact metadata is invalid", "BUNDLE_MANIFEST_INVALID")
            office_code = match.group(1).upper()
            page_number = int(match.group(2))
            if office_code not in _OFFICE_CODES or page_number in office_pages[office_code]:
                raise RawPayloadError("academy page identity is invalid", "PROVIDER_PAGE_INVALID")
            total_count, rows = _page(artifact.content, contract.encoding)
            previous_total = office_totals.setdefault(office_code, total_count)
            if previous_total != total_count:
                raise RawPayloadError(
                    "academy provider total changed between pages",
                    "PROVIDER_TOTAL_COUNT_MISMATCH",
                )
            if any(_canonical(row.get("ATPT_OFCDC_SC_CODE")) != office_code for row in rows):
                raise RawPayloadError("academy office coverage mismatch", "PROVIDER_PAGE_INVALID")
            office_pages[office_code][page_number] = rows
        if set(office_pages) != _OFFICE_CODES:
            raise RawPayloadError("academy office coverage is incomplete", "PROVIDER_COVERAGE_INCOMPLETE")

        provider_rows: list[dict[str, object]] = []
        for office_code in sorted(_OFFICE_CODES):
            pages = office_pages[office_code]
            if sorted(pages) != list(range(1, len(pages) + 1)):
                raise RawPayloadError("academy pages are not contiguous", "PROVIDER_PAGE_INVALID")
            office_rows = [row for page in sorted(pages) for row in pages[page]]
            if len(office_rows) != office_totals[office_code]:
                raise RawPayloadError(
                    "academy total count does not match rows",
                    "PROVIDER_TOTAL_COUNT_MISMATCH",
                )
            provider_rows.extend(office_rows)

        rows: list[dict[str, object]] = []
        issues: list[QualityIssue] = []
        rejections: dict[int, tuple[str, ...]] = {}
        for row_number, provider_row in enumerate(provider_rows, start=1):
            normalized, reasons = _normalize(provider_row, observed_at)
            rows.append(normalized)
            if reasons:
                rejections[row_number] = tuple(reasons)
                issues.extend(
                    QualityIssue(reason, "WARNING", row_number, {}) for reason in reasons
                )
        return ParsedDataset(rows=rows, issues=tuple(issues), row_rejections=rejections)


def _page(content: bytes, encoding: str) -> tuple[int, list[dict[str, object]]]:
    try:
        value = json.loads(content.decode(encoding))
        sections = value["acaInsTiInfo"]
        head = sections[0]["head"]
        total_count = head[0]["list_total_count"]
        result_code = head[1]["RESULT"]["CODE"]
        rows = sections[1]["row"]
        if (
            result_code != "INFO-000"
            or isinstance(total_count, bool)
            or not isinstance(total_count, int)
            or total_count < 0
            or not isinstance(rows, list)
            or not all(isinstance(row, dict) for row in rows)
        ):
            raise ValueError
        return total_count, rows
    except (KeyError, IndexError, TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
        raise RawPayloadError("academy provider page is invalid", "PROVIDER_PAGE_INVALID") from None


def _normalize(
    row: dict[str, object], observed_at: datetime
) -> tuple[dict[str, object], list[str]]:
    office_code = _canonical(row.get("ATPT_OFCDC_SC_CODE"))
    source_academy_id = _canonical(row.get("ACA_ASNUM"))
    academy_name = _canonical(row.get("ACA_NM"))
    academy_type = _canonical(row.get("ACA_INSTI_SC_NM"))
    provider_status = _canonical(row.get("REG_STTUS_NM"))
    status = _STATUS.get(provider_status)
    road_address = _optional(row.get("FA_RDNMA"))
    reasons: list[str] = []
    if not office_code or not source_academy_id or not academy_name:
        reasons.append("ACADEMY_IDENTITY_REQUIRED")
    if academy_type not in _ACADEMY_TYPES:
        reasons.append("ACADEMY_TYPE_UNKNOWN")
    if status is None:
        reasons.append("ACADEMY_STATUS_UNKNOWN")
    return (
        {
            "academy_id": f"{office_code}|{source_academy_id}",
            "education_office_code": office_code,
            "education_office_name": _canonical(row.get("ATPT_OFCDC_SC_NM")),
            "district_name": _optional(row.get("ADMST_ZONE_NM")),
            "academy_type": academy_type,
            "academy_name": academy_name,
            "status": status or "UNKNOWN",
            "registration_date": _compact_date(row.get("REG_YMD")),
            "suspension_start_date": _compact_date(row.get("CAA_BEGIN_YMD")),
            "suspension_end_date": _compact_date(row.get("CAA_END_YMD")),
            "capacity": _integer(row.get("TOFOR_SMTOT")),
            "realm": _optional(row.get("REALM_SC_NM")),
            "series": _optional(row.get("LE_ORD_NM")),
            "course": _optional(row.get("LE_CRSE_NM")),
            "road_address": road_address,
            "postal_code": _optional(row.get("FA_RDNZC")),
            "loaded_at": _optional(row.get("LOAD_DTM")),
            "normalized_name_key": academy_name,
            "normalized_address_key": road_address,
            "observed_at": observed_at.isoformat(),
            "fact_kind": "REGISTRY",
        },
        reasons,
    )


def _canonical(value: object) -> str:
    if value is None:
        return ""
    return " ".join(unicodedata.normalize("NFKC", str(value)).split())


def _optional(value: object) -> str | None:
    value = _canonical(value)
    return value or None


def _integer(value: object) -> int | None:
    try:
        number = int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    return number if number >= 0 else None


def _compact_date(value: object) -> str | None:
    text = _canonical(value)
    if not text:
        return None
    try:
        return datetime.strptime(text, "%Y%m%d").date().isoformat()
    except ValueError:
        return None
