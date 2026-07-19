from __future__ import annotations

import hashlib
import json
import math
import unicodedata
from dataclasses import dataclass
from datetime import date, datetime

from .bundle import read_deterministic_bundle
from .checksum import canonical_json_bytes
from .models import DatasetSourceContract, ParsedDataset
from .validation import RawPayloadError


SOURCE_ID = "place.sbiz-academy"


@dataclass(frozen=True)
class SbizTaxonomyContract:
    fingerprint: str
    allowed_small_categories: dict[str, str]

    def __post_init__(self) -> None:
        if (
            len(self.fingerprint) != 64
            or not self.allowed_small_categories
            or any(not code.strip() or not name.strip() for code, name in self.allowed_small_categories.items())
        ):
            raise ValueError("Sbiz taxonomy contract is incomplete")


def taxonomy_fingerprint(artifacts: dict[str, object]) -> str:
    return hashlib.sha256(canonical_json_bytes(artifacts)).hexdigest()


class SbizAcademyAdapter:
    def __init__(self, taxonomy: SbizTaxonomyContract) -> None:
        self._taxonomy = taxonomy

    def parse(
        self,
        raw_bytes: bytes,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset:
        if contract.source_id != SOURCE_ID or contract.temporal_basis != "OBSERVED_AT" or source_date is not None:
            raise RawPayloadError("Sbiz source contract mismatch", "SOURCE_CONTRACT_MISMATCH")
        bundle = read_deterministic_bundle(
            raw_bytes, expected_source_id=SOURCE_ID, maximum_bytes=1024 * 1024 * 1024
        )
        if not isinstance(bundle.temporal_value, datetime):
            raise RawPayloadError("Sbiz observation time is missing", "BUNDLE_MANIFEST_INVALID")
        taxonomy_values: dict[str, object] = {}
        pages: dict[str, dict[int, tuple[int, int, list[dict[str, object]]]]] = {}
        for artifact in bundle.artifacts:
            if artifact.media_type != "application/json":
                raise RawPayloadError("Sbiz artifact type is invalid", "BUNDLE_MANIFEST_INVALID")
            if artifact.logical_name in {"taxonomy-large", "taxonomy-middle", "taxonomy-small"}:
                taxonomy_values[artifact.logical_name] = _json(artifact.content)
                continue
            parts = artifact.logical_name.rsplit("-page-", 1)
            if len(parts) != 2 or not parts[1].isdigit():
                raise RawPayloadError("Sbiz page identity is invalid", "BUNDLE_MANIFEST_INVALID")
            code, page_text = parts[0].upper(), parts[1]
            if code not in self._taxonomy.allowed_small_categories:
                raise RawPayloadError("Sbiz partition is not allowlisted", "TAXONOMY_CHANGED")
            page_number = int(page_text)
            total, page_size, items = _page(artifact.content)
            if page_number in pages.setdefault(code, {}):
                raise RawPayloadError("Sbiz page is duplicated", "PROVIDER_PAGE_INVALID")
            pages[code][page_number] = (total, page_size, items)
        if set(taxonomy_values) != {"taxonomy-large", "taxonomy-middle", "taxonomy-small"}:
            raise RawPayloadError("Sbiz taxonomy artifacts are incomplete", "TAXONOMY_CHANGED")
        if taxonomy_fingerprint(taxonomy_values) != self._taxonomy.fingerprint:
            raise RawPayloadError("Sbiz taxonomy fingerprint changed", "TAXONOMY_CHANGED")
        if set(pages) != set(self._taxonomy.allowed_small_categories):
            raise RawPayloadError("Sbiz partitions are incomplete", "PROVIDER_COVERAGE_INCOMPLETE")

        rows: list[dict[str, object]] = []
        seen_ids: set[str] = set()
        for code in sorted(pages):
            partition = pages[code]
            if sorted(partition) != list(range(1, len(partition) + 1)):
                raise RawPayloadError("Sbiz pages are not contiguous", "PROVIDER_PAGE_INVALID")
            totals = {value[0] for value in partition.values()}
            if len(totals) != 1:
                raise RawPayloadError("Sbiz total changed", "PROVIDER_TOTAL_COUNT_MISMATCH")
            items = [item for page in sorted(partition) for item in partition[page][2]]
            if len(items) != next(iter(totals)):
                raise RawPayloadError("Sbiz total does not match rows", "PROVIDER_TOTAL_COUNT_MISMATCH")
            for item in items:
                store_id = _text(item.get("bizesId"))
                item_code = _text(item.get("indsSclsCd"))
                if not store_id or item_code != code or store_id in seen_ids:
                    reason = "DUPLICATE_STORE_ID" if store_id in seen_ids else "SOURCE_SCHEMA_MISMATCH"
                    raise RawPayloadError("Sbiz store identity is invalid", reason)
                seen_ids.add(store_id)
                latitude = _number(item.get("lat"))
                longitude = _number(item.get("lon"))
                rows.append(
                    {
                        "store_id": store_id,
                        "name": _text(item.get("bizesNm")),
                        "small_category_code": code,
                        "small_category_name": self._taxonomy.allowed_small_categories[code],
                        "road_address": _optional(item.get("rdnmAdr")),
                        "lot_address": _optional(item.get("lnoAdr")),
                        "postal_code": _optional(item.get("zipcd")),
                        "region_code": _optional(item.get("adongCd")),
                        "latitude": latitude,
                        "longitude": longitude,
                        "observed_at": bundle.temporal_value.isoformat(),
                        "status": "OPEN",
                    }
                )
        return ParsedDataset(rows=rows)


def _json(content: bytes) -> object:
    try:
        return json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise RawPayloadError("Sbiz JSON is invalid", "SOURCE_SCHEMA_MISMATCH") from None


def _page(content: bytes) -> tuple[int, int, list[dict[str, object]]]:
    try:
        value = _json(content)
        assert isinstance(value, dict)
        body = value["body"]
        total = body["totalCount"]
        page_size = body["numOfRows"]
        items = body["items"]
        if (
            isinstance(total, bool) or not isinstance(total, int) or total < 0
            or isinstance(page_size, bool) or not isinstance(page_size, int) or page_size != 1000
            or not isinstance(items, list) or not all(isinstance(item, dict) for item in items)
        ):
            raise ValueError
        return total, page_size, items
    except (AssertionError, KeyError, TypeError, ValueError):
        raise RawPayloadError("Sbiz page is invalid", "SOURCE_SCHEMA_MISMATCH") from None


def _text(value: object) -> str:
    return (
        " ".join(unicodedata.normalize("NFKC", str(value)).split())
        if value is not None
        else ""
    )


def _optional(value: object) -> str | None:
    text = _text(value)
    return text or None


def _number(value: object) -> float | None:
    try:
        number = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None
