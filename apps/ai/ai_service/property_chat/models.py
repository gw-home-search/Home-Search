from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Literal

QueryCapability = Literal[
    "complex_identity",
    "recent_trade_lookup",
    "price_trend",
    "school_location",
    "retail_location",
]
PropertyCapability = Literal["complex_identity", "recent_trade_lookup", "price_trend"]
ReferenceCapability = Literal["school_location", "retail_location"]
SchoolLevel = Literal["ELEMENTARY", "MIDDLE", "HIGH"]
FacilitySubtype = Literal[
    "LARGE_MART",
    "DEPARTMENT_STORE",
    "SHOPPING_CENTER",
    "COMPLEX_MALL",
    "OTHER_LARGE_STORE",
]


@dataclass(frozen=True)
class QueryPlan:
    capability: QueryCapability
    complex_name: str
    region_name: str | None = None
    start_date: date | None = None
    end_date: date | None = None
    exclusive_area_square_meters: float | None = None
    limit: int = 5
    school_levels: tuple[SchoolLevel, ...] = ("ELEMENTARY", "MIDDLE", "HIGH")
    facility_subtypes: tuple[FacilitySubtype, ...] = ()
    radius_meters: int | None = None

    def __post_init__(self) -> None:
        normalized_name = self.complex_name.strip()
        if not normalized_name or len(normalized_name) > 100:
            raise ValueError("complex_name must contain 1..100 characters")
        object.__setattr__(self, "complex_name", normalized_name)
        if self.region_name is not None:
            normalized_region = self.region_name.strip()
            if not normalized_region or len(normalized_region) > 100:
                raise ValueError("region_name must contain 1..100 characters")
            object.__setattr__(self, "region_name", normalized_region)
        if self.start_date and self.end_date and self.start_date > self.end_date:
            raise ValueError("start_date must not be after end_date")
        if self.exclusive_area_square_meters is not None and not (
            0 < self.exclusive_area_square_meters <= 1000
        ):
            raise ValueError("exclusive area is outside the supported range")
        if not 1 <= self.limit <= 10:
            raise ValueError("limit must be between 1 and 10")
        if self.capability in {"school_location", "retail_location"} and self.limit > 5:
            raise ValueError("reference facility limit must be between 1 and 5")
        if (
            not self.school_levels
            or len(self.school_levels) != len(set(self.school_levels))
            or any(level not in {"ELEMENTARY", "MIDDLE", "HIGH"} for level in self.school_levels)
        ):
            raise ValueError("school_levels are outside the supported set")
        canonical_levels = tuple(
            level
            for level in ("ELEMENTARY", "MIDDLE", "HIGH")
            if level in self.school_levels
        )
        object.__setattr__(self, "school_levels", canonical_levels)
        allowed_subtypes = (
            "LARGE_MART",
            "DEPARTMENT_STORE",
            "SHOPPING_CENTER",
            "COMPLEX_MALL",
            "OTHER_LARGE_STORE",
        )
        if (
            len(self.facility_subtypes) != len(set(self.facility_subtypes))
            or any(subtype not in allowed_subtypes for subtype in self.facility_subtypes)
        ):
            raise ValueError("facility_subtypes are outside the supported set")
        canonical_subtypes = tuple(
            subtype for subtype in allowed_subtypes if subtype in self.facility_subtypes
        )
        object.__setattr__(self, "facility_subtypes", canonical_subtypes)
        radius_meters = self.radius_meters
        if radius_meters is None:
            radius_meters = 1000 if self.capability == "retail_location" else 800
            object.__setattr__(self, "radius_meters", radius_meters)
        if not 0 <= radius_meters <= 10_000_000:
            raise ValueError("radius_meters cannot be represented safely")
        if self.capability == "price_trend" and (self.start_date is None or self.end_date is None):
            raise ValueError("price_trend requires start_date and end_date")


@dataclass(frozen=True)
class ComplexRecord:
    complex_id: int
    display_name: str
    region_code: str | None
    region_name: str | None
    address: str | None
    latitude: float | None
    longitude: float | None
    marker_safe: bool
    data_updated_at: datetime


@dataclass(frozen=True)
class TradeRecord:
    trade_id: int
    complex_id: int
    deal_date: date
    deal_amount_ten_thousand_krw: int
    exclusive_area_square_meters: float
    floor: int | None


@dataclass(frozen=True)
class MonthlyTrendRecord:
    complex_id: int
    month: date
    average_amount_ten_thousand_krw: int
    trade_count: int
    minimum_amount_ten_thousand_krw: int
    maximum_amount_ten_thousand_krw: int


@dataclass(frozen=True)
class SchoolSnapshot:
    dataset_version: str
    source_date: date
    published_at: datetime


@dataclass(frozen=True)
class SchoolRecord:
    school_id: str
    school_name: str
    school_level: SchoolLevel
    operating_status: str
    road_address: str | None
    lot_address: str | None
    latitude: float
    longitude: float
    distance_meters: int


@dataclass(frozen=True)
class SchoolSearchResult:
    schools: tuple[SchoolRecord, ...]
    matched_count: int

    @property
    def returned_count(self) -> int:
        return len(self.schools)

    @property
    def has_more(self) -> bool:
        return self.matched_count > self.returned_count


@dataclass(frozen=True)
class DraftClaim:
    fact_id: str
    value: str
    unit: str


@dataclass(frozen=True)
class DraftSentence:
    text: str
    fact_ids: list[str]
    claims: list[DraftClaim] = field(default_factory=list)


@dataclass(frozen=True)
class DraftAnswer:
    sentences: list[DraftSentence]


@dataclass(frozen=True)
class FactClaim:
    value: str
    unit: str


@dataclass(frozen=True)
class EvidenceFact:
    fact_id: str
    claims: tuple[FactClaim, ...]
    data_as_of: date
    payload: dict[str, object]
    source_id: str = "property.ai_read"
    source_name: str = "Home Search 실거래"
    source_url: str | None = None
    evidence_grade: str = "A"
    dataset_version_value: str | None = None

    @property
    def dataset_version(self) -> str:
        return self.dataset_version_value or f"property-{self.data_as_of.isoformat()}"
