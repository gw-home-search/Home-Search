from __future__ import annotations

import hashlib
import json
import math
import unicodedata
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path

from .bundle import read_deterministic_bundle, read_deterministic_bundle_file
from .checksum import canonical_json_bytes
from .models import DatasetSourceContract, ParsedDataset, ParsedRow
from .validation import RawPayloadError
from .contracts import ReferenceSourceContract


SOURCE_ID = "place.sbiz-academy"
_TAXONOMY_FIELDS = {
    "taxonomy-large": ("indsLclsCd", "indsLclsNm"),
    "taxonomy-middle": ("indsMclsCd", "indsMclsNm"),
    "taxonomy-small": ("indsSclsCd", "indsSclsNm"),
}


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
        return ParsedDataset(rows=self._iter_rows(bundle.artifacts, bundle.temporal_value))

    def parse_file(
        self,
        raw_path: Path,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset:
        if (
            contract.source_id != SOURCE_ID
            or contract.temporal_basis != "OBSERVED_AT"
            or source_date is not None
        ):
            raise RawPayloadError(
                "Sbiz source contract mismatch", "SOURCE_CONTRACT_MISMATCH"
            )
        bundle = read_deterministic_bundle_file(
            raw_path,
            expected_source_id=SOURCE_ID,
            maximum_bytes=1024 * 1024 * 1024,
            maximum_artifact_bytes=8 * 1024 * 1024,
        )
        if not isinstance(bundle.temporal_value, datetime):
            raise RawPayloadError(
                "Sbiz observation time is missing", "BUNDLE_MANIFEST_INVALID"
            )
        return ParsedDataset(
            rows=self._iter_rows(bundle.artifacts, bundle.temporal_value)
        )

    def _iter_rows(self, artifacts, observed_at: datetime):
        taxonomy_values: dict[str, list[dict[str, str]]] = {
            "taxonomy-large": [],
            "taxonomy-middle": [],
            "taxonomy-small": [],
        }
        taxonomy_artifacts_seen: set[str] = set()
        taxonomy_stage = -1
        taxonomy_validated = False
        expected_page: dict[str, int] = {}
        expected_total: dict[str, int] = {}
        seen_count: dict[str, int] = {}
        seen_ids: set[str] = set()
        page_started = False
        previous_identity: tuple[str, int] | None = None
        for artifact in artifacts:
            if artifact.media_type != "application/json":
                raise RawPayloadError("Sbiz artifact type is invalid", "BUNDLE_MANIFEST_INVALID")
            taxonomy_identity = _taxonomy_artifact_identity(artifact.logical_name)
            if taxonomy_identity is not None:
                artifact_name, parent_code, stage = taxonomy_identity
                if (
                    page_started
                    or stage < taxonomy_stage
                    or artifact.logical_name in taxonomy_artifacts_seen
                ):
                    raise RawPayloadError("Sbiz taxonomy order is invalid", "TAXONOMY_CHANGED")
                rows = _taxonomy_page(artifact.content, artifact_name)
                if parent_code is not None and any(
                    not row["code"].startswith(parent_code) for row in rows
                ):
                    raise RawPayloadError(
                        "Sbiz taxonomy parent changed", "TAXONOMY_CHANGED"
                    )
                existing_codes = {row["code"] for row in taxonomy_values[artifact_name]}
                if any(row["code"] in existing_codes for row in rows):
                    raise RawPayloadError(
                        "Sbiz taxonomy code is duplicated", "TAXONOMY_CHANGED"
                    )
                taxonomy_values[artifact_name].extend(rows)
                taxonomy_values[artifact_name].sort(key=lambda item: item["code"])
                taxonomy_artifacts_seen.add(artifact.logical_name)
                taxonomy_stage = stage
                continue
            if not taxonomy_validated:
                _validate_taxonomy_values(taxonomy_values, self._taxonomy.fingerprint)
                taxonomy_validated = True
            page_started = True
            parts = artifact.logical_name.rsplit("-page-", 1)
            if len(parts) != 2 or not parts[1].isdigit():
                raise RawPayloadError("Sbiz page identity is invalid", "BUNDLE_MANIFEST_INVALID")
            code, page_text = parts[0].upper(), parts[1]
            if code not in self._taxonomy.allowed_small_categories:
                raise RawPayloadError("Sbiz partition is not allowlisted", "TAXONOMY_CHANGED")
            page_number = int(page_text)
            identity = (code, page_number)
            if previous_identity is not None and identity <= previous_identity:
                raise RawPayloadError("Sbiz page order is invalid", "PROVIDER_PAGE_INVALID")
            previous_identity = identity
            total, page_size, items = _page(artifact.content)
            if page_number != expected_page.setdefault(code, 1):
                raise RawPayloadError("Sbiz pages are not contiguous", "PROVIDER_PAGE_INVALID")
            expected_page[code] += 1
            if expected_total.setdefault(code, total) != total:
                raise RawPayloadError("Sbiz total changed", "PROVIDER_TOTAL_COUNT_MISMATCH")
            seen_count[code] = seen_count.get(code, 0) + len(items)
            for item in items:
                store_id = _text(item.get("bizesId"))
                item_code = _text(item.get("indsSclsCd"))
                item_name = _text(item.get("indsSclsNm"))
                if not store_id or item_code != code or store_id in seen_ids:
                    reason = "DUPLICATE_STORE_ID" if store_id in seen_ids else "SOURCE_SCHEMA_MISMATCH"
                    raise RawPayloadError("Sbiz store identity is invalid", reason)
                if item_name != self._taxonomy.allowed_small_categories[code]:
                    raise RawPayloadError(
                        "Sbiz store taxonomy changed", "TAXONOMY_CHANGED"
                    )
                seen_ids.add(store_id)
                latitude = _number(item.get("lat"))
                longitude = _number(item.get("lon"))
                yield ParsedRow(
                    {
                        "store_id": store_id,
                        "name": _text(item.get("bizesNm")),
                        "small_category_code": code,
                        "small_category_name": self._taxonomy.allowed_small_categories[code],
                        "road_address": _optional(item.get("rdnmAdr")),
                        "lot_address": _optional(item.get("lnoAdr")),
                        "postal_code": _optional(item.get("newZipcd")),
                        "region_code": _optional(item.get("adongCd")),
                        "latitude": latitude,
                        "longitude": longitude,
                        "observed_at": observed_at.isoformat(),
                        "status": "OPEN",
                    }
                )
        if not taxonomy_validated:
            _validate_taxonomy_values(taxonomy_values, self._taxonomy.fingerprint)
        if set(expected_page) != set(self._taxonomy.allowed_small_categories):
            raise RawPayloadError("Sbiz partitions are incomplete", "PROVIDER_COVERAGE_INCOMPLETE")
        for code, total in expected_total.items():
            if seen_count.get(code, 0) != total:
                raise RawPayloadError("Sbiz total does not match rows", "PROVIDER_TOTAL_COUNT_MISMATCH")


def _taxonomy_artifact_identity(
    logical_name: str,
) -> tuple[str, str | None, int] | None:
    if logical_name == "taxonomy-large":
        return "taxonomy-large", None, 0
    for artifact_name, stage in (("taxonomy-middle", 1), ("taxonomy-small", 2)):
        if logical_name == artifact_name:
            return artifact_name, None, stage
        prefix = f"{artifact_name}-"
        if logical_name.startswith(prefix):
            parent = logical_name[len(prefix):].upper()
            if parent and parent.isalnum():
                return artifact_name, parent, stage
    return None


def _validate_taxonomy_values(
    values: dict[str, list[dict[str, str]]], expected_fingerprint: str
) -> None:
    if any(not rows for rows in values.values()):
        raise RawPayloadError(
            "Sbiz taxonomy artifacts are incomplete", "TAXONOMY_CHANGED"
        )
    if taxonomy_fingerprint(values) != expected_fingerprint:
        raise RawPayloadError(
            "Sbiz taxonomy fingerprint changed", "TAXONOMY_CHANGED"
        )


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


def _taxonomy_page(content: bytes, artifact_name: str) -> list[dict[str, str]]:
    try:
        code_field, name_field = _TAXONOMY_FIELDS[artifact_name]
        value = _json(content)
        assert isinstance(value, dict)
        body = value["body"]
        items = body["items"]
        if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
            raise ValueError
        normalized = [
            {"code": _text(item.get(code_field)), "name": _text(item.get(name_field))}
            for item in items
        ]
        if (
            not normalized
            or any(not item["code"] or not item["name"] for item in normalized)
            or len({item["code"] for item in normalized}) != len(normalized)
        ):
            raise ValueError
        return sorted(normalized, key=lambda item: item["code"])
    except (AssertionError, KeyError, TypeError, ValueError):
        raise RawPayloadError(
            "Sbiz taxonomy page is invalid", "TAXONOMY_CHANGED"
        ) from None


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


def sbiz_academy_source_contract(
    reference_contract: ReferenceSourceContract,
) -> DatasetSourceContract:
    if reference_contract.id != SOURCE_ID or reference_contract.license.reviewed_on is None:
        raise ValueError("Sbiz reference contract mismatch")
    return DatasetSourceContract(
        source_id=reference_contract.id, provider=reference_contract.provider,
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
        unique_key_fields=("store_id",),
        required_fields=(
            "store_id", "name", "small_category_code", "latitude",
            "longitude", "observed_at",
        ),
        expected_min_rows=reference_contract.quality.minimum_rows,
        expected_max_rows=reference_contract.quality.maximum_rows,
        maximum_row_change_ratio=reference_contract.quality.maximum_row_change_ratio,
        maximum_rejected_ratio=reference_contract.quality.maximum_rejected_ratio,
        contains_personal_data=False, owner=reference_contract.owner,
        temporal_basis="OBSERVED_AT",
    )
