from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Literal

PropertyCapability = Literal["complex_identity", "recent_trade_lookup", "price_trend"]


@dataclass(frozen=True)
class PropertyQueryPlan:
    capability: PropertyCapability
    complex_name: str
    region_name: str | None = None
    start_date: date | None = None
    end_date: date | None = None
    exclusive_area_square_meters: float | None = None
    limit: int = 5

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

    @property
    def dataset_version(self) -> str:
        return f"property-{self.data_as_of.isoformat()}"
