from __future__ import annotations

import hashlib
import json
import re
from dataclasses import asdict, dataclass
from datetime import date, datetime
from typing import Literal
from urllib.parse import urlsplit
from uuid import UUID


ReadinessStatus = Literal["Pass", "NoChange", "Partial", "Fail"]


@dataclass(frozen=True)
class DatasetSourceContract:
    source_id: str
    provider: str
    landing_url: str
    acquisition_url: str
    license_terms: str
    attribution_requirements: str
    license_reviewed_on: date
    refresh_frequency: str
    freshness_days: int
    file_format: str
    encoding: str
    schema_version: str
    coordinate_system: str
    unique_key_fields: tuple[str, ...]
    required_fields: tuple[str, ...]
    expected_min_rows: int
    expected_max_rows: int
    maximum_row_change_ratio: float
    maximum_rejected_ratio: float
    contains_personal_data: bool
    owner: str
    temporal_basis: Literal["SOURCE_DATE", "OBSERVED_AT"] = "SOURCE_DATE"

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", self.source_id):
            raise ValueError("source_id must be a stable lowercase identifier")
        required_text = (
            self.provider,
            self.license_terms,
            self.attribution_requirements,
            self.refresh_frequency,
            self.file_format,
            self.encoding,
            self.schema_version,
            self.coordinate_system,
            self.owner,
        )
        if any(not value.strip() for value in required_text):
            raise ValueError("source contract text fields are required")
        landing_url = urlsplit(self.landing_url)
        acquisition_url = urlsplit(self.acquisition_url)
        if (
            landing_url.scheme != "https"
            or not landing_url.hostname
            or acquisition_url.scheme != "https"
            or not acquisition_url.hostname
        ):
            raise ValueError("source URLs must use HTTPS")
        if (
            acquisition_url.username
            or acquisition_url.password
            or acquisition_url.query
            or acquisition_url.fragment
        ):
            raise ValueError("acquisition_url must not contain credentials, query, or fragment")
        if not self.unique_key_fields or not self.required_fields:
            raise ValueError("unique key and required fields are required")
        if not set(self.unique_key_fields).issubset(self.required_fields):
            raise ValueError("unique key fields must also be required")
        if len(set(self.unique_key_fields)) != len(self.unique_key_fields):
            raise ValueError("unique key fields must not be duplicated")
        if self.expected_min_rows < 0 or self.expected_max_rows < self.expected_min_rows:
            raise ValueError("expected row range is invalid")
        if self.freshness_days <= 0:
            raise ValueError("freshness_days must be positive")
        if not 0 <= self.maximum_row_change_ratio <= 1:
            raise ValueError("maximum_row_change_ratio must be between zero and one")
        if not 0 <= self.maximum_rejected_ratio <= 1:
            raise ValueError("maximum_rejected_ratio must be between zero and one")
        if self.contains_personal_data:
            raise ValueError("AI reference datasets must not contain personal data")
        if self.temporal_basis not in {"SOURCE_DATE", "OBSERVED_AT"}:
            raise ValueError("temporal basis is invalid")

    def as_json(self) -> dict[str, object]:
        value = asdict(self)
        value["license_reviewed_on"] = self.license_reviewed_on.isoformat()
        value["unique_key_fields"] = list(self.unique_key_fields)
        value["required_fields"] = list(self.required_fields)
        return value

    def fingerprint(self) -> str:
        encoded = json.dumps(
            self.as_json(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode()
        return hashlib.sha256(encoded).hexdigest()


@dataclass(frozen=True)
class QualityIssue:
    reason_code: str
    severity: Literal["BLOCKING", "WARNING"]
    row_number: int | None
    details: dict[str, object]


@dataclass(frozen=True)
class StagedRow:
    row_number: int
    row_data: dict[str, object]
    accepted: bool
    rejection_codes: tuple[str, ...]
    source_key: str | None


@dataclass(frozen=True)
class ValidationOutcome:
    raw_row_count: int
    accepted_row_count: int
    rejected_row_count: int
    staged_rows: tuple[StagedRow, ...]
    issues: tuple[QualityIssue, ...]

    @property
    def has_blocking_issues(self) -> bool:
        return any(issue.severity == "BLOCKING" for issue in self.issues)


@dataclass(frozen=True)
class ParsedDataset:
    rows: list[dict[str, object]]
    issues: tuple[QualityIssue, ...] = ()
    row_rejections: dict[int, tuple[str, ...]] | None = None


@dataclass(frozen=True)
class AcquisitionRecord:
    acquisition_id: UUID
    created: bool


@dataclass(frozen=True)
class LifecycleResult:
    status: ReadinessStatus
    source_id: str
    acquisition_id: UUID
    publication_id: UUID | None
    dataset_version: str | None
    checksum: str
    source_date: date | None
    collected_at: datetime
    raw_row_count: int
    accepted_row_count: int
    rejected_row_count: int
    issue_codes: tuple[str, ...]
    idempotent: bool
    normalized_checksum: str | None = None
    temporal_basis: Literal["SOURCE_DATE", "OBSERVED_AT"] = "SOURCE_DATE"
    observed_at: datetime | None = None


@dataclass(frozen=True)
class RejectedRow:
    row_number: int
    reason_code: str
    row_data: dict[str, object] | None


@dataclass(frozen=True)
class ActiveSnapshot:
    source_id: str
    publication_id: UUID
    acquisition_id: UUID
    dataset_version: str
    source_date: date | None
    published_at: datetime
    rows: tuple[dict[str, object], ...]
    normalized_checksum: str | None = None
    observed_at: datetime | None = None
