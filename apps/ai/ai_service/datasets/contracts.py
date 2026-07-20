from __future__ import annotations

import re
import tomllib
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Literal
from urllib.parse import parse_qsl, urlsplit


LicenseStatus = Literal["APPROVED", "PENDING", "REJECTED"]
TemporalBasis = Literal["SOURCE_DATE", "OBSERVED_AT"]


class LicenseNotApprovedError(RuntimeError):
    pass


@dataclass(frozen=True)
class AcquisitionContract:
    mode: Literal["api", "file", "release_bundle"]
    base_url: str
    allowed_hosts: tuple[str, ...]
    allowed_path_prefixes: tuple[str, ...]
    format: str
    encoding: str
    source_crs: str | None
    maximum_bundle_bytes: int
    redirect_policy: Literal["REJECT", "ALLOWLISTED_ONE_HOP"]
    fixed_query: str = ""
    source_date: date | None = None
    referer_url: str = ""

    def __post_init__(self) -> None:
        parsed = urlsplit(self.base_url)
        if (
            parsed.scheme != "https"
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or parsed.hostname not in self.allowed_hosts
            or not any(parsed.path.startswith(prefix) for prefix in self.allowed_path_prefixes)
        ):
            raise ValueError("acquisition base URL must match its HTTPS allowlist")
        if not 1 <= self.maximum_bundle_bytes <= 2_147_483_648:
            raise ValueError("maximum bundle bytes is invalid")
        if any(not prefix.startswith("/") or ".." in prefix for prefix in self.allowed_path_prefixes):
            raise ValueError("allowed path prefix is invalid")
        if self.fixed_query and (
            self.mode != "file" or not _is_safe_fixed_query(self.fixed_query)
        ):
            raise ValueError("acquisition fixed query is invalid")
        if self.source_date is not None and (
            type(self.source_date) is not date or self.mode != "file"
        ):
            raise ValueError("acquisition source date is invalid")
        if self.referer_url and (
            self.mode != "file"
            or not _is_safe_referer_url(self.referer_url, self.allowed_hosts)
        ):
            raise ValueError("acquisition referer URL is invalid")


@dataclass(frozen=True)
class TemporalContract:
    basis: TemporalBasis
    freshness_days: int
    refresh_profile: str

    def __post_init__(self) -> None:
        if self.freshness_days <= 0 or not self.refresh_profile.strip():
            raise ValueError("temporal contract is invalid")


@dataclass(frozen=True)
class QualityContract:
    minimum_rows: int
    maximum_rows: int
    maximum_row_change_ratio: float
    minimum_coordinate_ratio: float
    minimum_region_coordinate_ratio: float
    maximum_rejected_ratio: float

    def __post_init__(self) -> None:
        ratios = (
            self.maximum_row_change_ratio,
            self.minimum_coordinate_ratio,
            self.minimum_region_coordinate_ratio,
            self.maximum_rejected_ratio,
        )
        if (
            self.minimum_rows < 0
            or self.maximum_rows < self.minimum_rows
            or any(not 0 <= ratio <= 1 for ratio in ratios)
        ):
            raise ValueError("quality contract is invalid")


@dataclass(frozen=True)
class LicenseContract:
    status: LicenseStatus
    terms_url: str
    terms_fingerprint: str
    reviewed_on: date | None
    reviewed_by: str
    attribution_text: str
    raw_private_storage_allowed: bool
    internal_derivative_allowed: bool
    public_redistribution_allowed: bool
    third_party_rights: bool

    def __post_init__(self) -> None:
        parsed = urlsplit(self.terms_url)
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("license terms URL must use HTTPS")
        if self.status == "APPROVED":
            if (
                not re.fullmatch(r"[0-9a-f]{64}", self.terms_fingerprint)
                or self.reviewed_on is None
                or not self.reviewed_by.strip()
                or not self.attribution_text.strip()
                or not self.raw_private_storage_allowed
                or not self.internal_derivative_allowed
            ):
                raise ValueError("approved license evidence is incomplete")


@dataclass(frozen=True)
class ReferenceSourceContract:
    id: str
    provider: str
    source_name: str
    landing_url: str
    evidence_grade: Literal["A", "B", "C"]
    owner: str
    normalization_schema_version: str
    acquisition: AcquisitionContract
    temporal: TemporalContract
    quality: QualityContract
    license: LicenseContract

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", self.id):
            raise ValueError("source ID is invalid")
        parsed = urlsplit(self.landing_url)
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("landing URL must use HTTPS")
        if any(
            not value.strip()
            for value in (self.provider, self.source_name, self.owner, self.normalization_schema_version)
        ):
            raise ValueError("source contract text is required")


@dataclass(frozen=True)
class ReferenceSourceCatalog:
    sources: tuple[ReferenceSourceContract, ...]

    @property
    def source_ids(self) -> tuple[str, ...]:
        return tuple(source.id for source in self.sources)

    def get(self, source_id: str) -> ReferenceSourceContract:
        matches = [source for source in self.sources if source.id == source_id]
        if len(matches) != 1:
            raise KeyError(source_id)
        return matches[0]

    def approved(self, source_id: str) -> ReferenceSourceContract:
        source = self.get(source_id)
        if source.license.status != "APPROVED":
            raise LicenseNotApprovedError(f"license is not approved for {source_id}")
        return source


def load_reference_source_catalog(path: Path) -> ReferenceSourceCatalog:
    document = tomllib.loads(path.read_text(encoding="utf-8"))
    raw_sources = document.get("sources")
    if not isinstance(raw_sources, list) or not raw_sources:
        raise ValueError("reference source catalog must contain sources")
    sources = tuple(_source(item) for item in raw_sources)
    ids = tuple(source.id for source in sources)
    if len(ids) != len(set(ids)):
        raise ValueError("reference source IDs must be unique")
    return ReferenceSourceCatalog(sources=sources)


def _source(value: object) -> ReferenceSourceContract:
    if not isinstance(value, dict):
        raise ValueError("source entry must be a table")
    acquisition = _mapping(value, "acquisition")
    temporal = _mapping(value, "temporal")
    quality = _mapping(value, "quality")
    license_value = _mapping(value, "license")
    return ReferenceSourceContract(
        id=_text(value, "id"),
        provider=_text(value, "provider"),
        source_name=_text(value, "source_name"),
        landing_url=_text(value, "landing_url"),
        evidence_grade=_text(value, "evidence_grade"),  # type: ignore[arg-type]
        owner=_text(value, "owner"),
        normalization_schema_version=_text(value, "normalization_schema_version"),
        acquisition=AcquisitionContract(
            mode=_text(acquisition, "mode"),  # type: ignore[arg-type]
            base_url=_text(acquisition, "base_url"),
            allowed_hosts=_text_tuple(acquisition, "allowed_hosts"),
            allowed_path_prefixes=_text_tuple(acquisition, "allowed_path_prefixes"),
            format=_text(acquisition, "format"),
            encoding=_text(acquisition, "encoding"),
            source_crs=_optional_text(acquisition.get("source_crs")),
            maximum_bundle_bytes=_integer(acquisition, "maximum_bundle_bytes"),
            redirect_policy=_text(acquisition, "redirect_policy"),  # type: ignore[arg-type]
            fixed_query=_optional_text(acquisition.get("fixed_query")) or "",
            source_date=_optional_date(acquisition.get("source_date")),
            referer_url=_optional_text(acquisition.get("referer_url")) or "",
        ),
        temporal=TemporalContract(
            basis=_text(temporal, "basis"),  # type: ignore[arg-type]
            freshness_days=_integer(temporal, "freshness_days"),
            refresh_profile=_text(temporal, "refresh_profile"),
        ),
        quality=QualityContract(
            minimum_rows=_integer(quality, "minimum_rows"),
            maximum_rows=_integer(quality, "maximum_rows"),
            maximum_row_change_ratio=_float(quality, "maximum_row_change_ratio"),
            minimum_coordinate_ratio=_float(quality, "minimum_coordinate_ratio"),
            minimum_region_coordinate_ratio=_float(quality, "minimum_region_coordinate_ratio"),
            maximum_rejected_ratio=_float(quality, "maximum_rejected_ratio"),
        ),
        license=LicenseContract(
            status=_text(license_value, "status"),  # type: ignore[arg-type]
            terms_url=_text(license_value, "terms_url"),
            terms_fingerprint=_optional_text(license_value.get("terms_fingerprint")) or "",
            reviewed_on=_optional_date(license_value.get("reviewed_on")),
            reviewed_by=_optional_text(license_value.get("reviewed_by")) or "",
            attribution_text=_optional_text(license_value.get("attribution_text")) or "",
            raw_private_storage_allowed=_boolean(license_value, "raw_private_storage_allowed"),
            internal_derivative_allowed=_boolean(license_value, "internal_derivative_allowed"),
            public_redistribution_allowed=_boolean(
                license_value, "public_redistribution_allowed"
            ),
            third_party_rights=_boolean(license_value, "third_party_rights"),
        ),
    )


def _mapping(parent: dict[str, object], key: str) -> dict[str, object]:
    value = parent.get(key)
    if not isinstance(value, dict):
        raise ValueError(f"{key} must be a table")
    return value


def _text(parent: dict[str, object], key: str) -> str:
    value = parent.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{key} must be text")
    return value


def _optional_text(value: object) -> str | None:
    return value if isinstance(value, str) else None


def _text_tuple(parent: dict[str, object], key: str) -> tuple[str, ...]:
    value = parent.get(key)
    if not isinstance(value, list) or not value or not all(isinstance(item, str) for item in value):
        raise ValueError(f"{key} must be a non-empty text array")
    return tuple(value)


def _integer(parent: dict[str, object], key: str) -> int:
    value = parent.get(key)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError(f"{key} must be an integer")
    return value


def _is_safe_fixed_query(value: str) -> bool:
    if not value or len(value) > 512 or value.startswith("?"):
        return False
    try:
        pairs = parse_qsl(value, keep_blank_values=True, strict_parsing=True)
    except ValueError:
        return False
    names = [name for name, _value in pairs]
    return (
        bool(pairs)
        and len(names) == len(set(names))
        and all(
            re.fullmatch(r"[A-Za-z0-9._~-]+", name) is not None
            and re.fullmatch(r"[A-Za-z0-9._~-]+", item) is not None
            for name, item in pairs
        )
        and not any(
            re.search(r"key|token|secret|password|credential|auth", name, re.IGNORECASE)
            for name in names
        )
    )


def _is_safe_referer_url(value: str, allowed_hosts: tuple[str, ...]) -> bool:
    parsed = urlsplit(value)
    return (
        value.isascii()
        and all(33 <= ord(character) <= 126 for character in value)
        and parsed.scheme == "https"
        and not parsed.username
        and not parsed.password
        and not parsed.query
        and not parsed.fragment
        and parsed.hostname in allowed_hosts
        and bool(parsed.path)
    )


def _float(parent: dict[str, object], key: str) -> float:
    value = parent.get(key)
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"{key} must be numeric")
    return float(value)


def _boolean(parent: dict[str, object], key: str) -> bool:
    value = parent.get(key)
    if not isinstance(value, bool):
        raise ValueError(f"{key} must be boolean")
    return value


def _optional_date(value: object) -> date | None:
    if value in {None, ""}:
        return None
    if isinstance(value, date):
        return value
    if isinstance(value, str):
        return date.fromisoformat(value)
    raise ValueError("reviewed_on must be a date")
