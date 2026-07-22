from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal
from statistics import median
import json

from .models import TradeRecord


@dataclass(frozen=True)
class CandidatePoint:
    complex_id: int
    latitude: float
    longitude: float
    region_code: str | None

    def __post_init__(self) -> None:
        if (
            self.complex_id <= 0
            or not 33 <= self.latitude <= 39
            or not 124 <= self.longitude <= 132
        ):
            raise ValueError("comparison candidate point is invalid")


@dataclass(frozen=True)
class ComparisonCell:
    availability: str
    value: str | None
    unit: str
    reason: str | None
    fact_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        if self.availability == "available":
            if not self.value or self.reason is not None or not self.fact_ids:
                raise ValueError("available comparison cell is invalid")
        elif self.availability == "unavailable":
            if self.value is not None or not self.reason:
                raise ValueError("unavailable comparison cell is invalid")
        else:
            raise ValueError("comparison availability is invalid")
        if not self.unit or len(self.unit) > 100:
            raise ValueError("comparison unit is invalid")
        if self.value is not None and not 1 <= len(self.value.strip()) <= 2_000:
            raise ValueError("comparison value is invalid")
        if self.reason is not None and not 1 <= len(self.reason.strip()) <= 2_000:
            raise ValueError("comparison reason is invalid")
        if len(self.fact_ids) != len(set(self.fact_ids)):
            raise ValueError("comparison cell fact ids are invalid")

    def to_public_dict(self) -> dict[str, object]:
        return {
            "availability": self.availability,
            "value": self.value,
            "unit": self.unit,
            "reason": self.reason,
            "factIds": list(self.fact_ids),
        }


@dataclass(frozen=True)
class ComparisonColumn:
    key: str
    label: str
    fact_ids: tuple[str, ...]

    def to_public_dict(self) -> dict[str, object]:
        if not self.key or not 1 <= len(self.label.strip()) <= 100 or not self.fact_ids:
            raise ValueError("comparison column is invalid")
        return {"key": self.key, "label": self.label.strip(), "factIds": list(self.fact_ids)}


@dataclass(frozen=True)
class ComparisonRow:
    key: str
    label: str
    cells: tuple[ComparisonCell, ...]

    def to_public_dict(self) -> dict[str, object]:
        if not self.key or not 1 <= len(self.label.strip()) <= 100:
            raise ValueError("comparison row is invalid")
        return {
            "key": self.key,
            "label": self.label.strip(),
            "cells": [cell.to_public_dict() for cell in self.cells],
        }


@dataclass(frozen=True)
class ComparisonTableArtifact:
    artifact_id: str
    columns: tuple[ComparisonColumn, ...]
    rows: tuple[ComparisonRow, ...]
    cutoff: date | None
    start_date: date | None
    exclusive_area_square_meters: float | None

    def to_public_dict(self) -> dict[str, object]:
        if (
            not 2 <= len(self.columns) <= 4
            or not 1 <= len(self.rows) <= 12
            or any(len(row.cells) != len(self.columns) for row in self.rows)
            or len({column.key for column in self.columns}) != len(self.columns)
            or len({row.key for row in self.rows}) != len(self.rows)
        ):
            raise ValueError("comparison table bounds are invalid")
        is_partial_basis = self.exclusive_area_square_meters is None
        if is_partial_basis != (self.cutoff is None or self.start_date is None):
            raise ValueError("comparison basis is inconsistent")
        artifact: dict[str, object] = {
            "type": "comparisonTable",
            "version": 2 if is_partial_basis else 1,
            "artifactId": self.artifact_id,
            "title": "확인 가능한 기준으로 단지 비교" if is_partial_basis else "동일 기준 단지 비교",
            "columns": [column.to_public_dict() for column in self.columns],
            "rows": [
                {
                    **row.to_public_dict(),
                    **({"group": _comparison_group(row.key)} if is_partial_basis else {}),
                }
                for row in self.rows
            ],
            "basis": {
                "cutoffDate": self.cutoff.isoformat() if self.cutoff is not None else None,
                "startDate": self.start_date.isoformat() if self.start_date is not None else None,
                "exclusiveAreaSquareMeters": self.exclusive_area_square_meters,
            },
        }
        if len(json.dumps(artifact, ensure_ascii=False).encode("utf-8")) > 65_536:
            raise ValueError("comparison table exceeds the public size limit")
        return artifact


def _comparison_group(row_key: str) -> str:
    if row_key in {"latestTrade", "recentThreeMedian", "tradeSampleCount"}:
        return "PRICE"
    if row_key in {"nearestRail"}:
        return "TRANSPORT"
    if row_key in {"studentAccess"}:
        return "EDUCATION"
    if row_key in {"nearestRetail", "youngChildAccess"}:
        return "LIFESTYLE"
    return "SCALE"


@dataclass(frozen=True)
class RecentThreeTradeBasis:
    complex_id: int
    cutoff: date
    start_date: date
    exclusive_area_square_meters: float
    trades: tuple[TradeRecord, ...]
    sample_count: int
    latest_trade: TradeRecord | None
    median_amount_ten_thousand_krw: int | None

    @classmethod
    def from_trades(
        cls,
        *,
        complex_id: int,
        cutoff: date,
        exclusive_area_square_meters: float,
        trades: tuple[TradeRecord, ...],
    ) -> RecentThreeTradeBasis:
        start_date = cutoff - timedelta(days=364)
        if (
            complex_id <= 0
            or not 0 < exclusive_area_square_meters <= 1000
            or len(trades) > 3
            or len({trade.trade_id for trade in trades}) != len(trades)
            or any(
                trade.complex_id != complex_id
                or not start_date <= trade.deal_date <= cutoff
                or abs(
                    Decimal(str(trade.exclusive_area_square_meters))
                    - Decimal(str(exclusive_area_square_meters))
                ) > Decimal("1.0")
                for trade in trades
            )
        ):
            raise ValueError("recent-three trade observations are inconsistent")
        ordered = tuple(
            sorted(trades, key=lambda trade: (trade.deal_date, trade.trade_id), reverse=True)
        )
        complete = len(ordered) == 3
        return cls(
            complex_id=complex_id,
            cutoff=cutoff,
            start_date=start_date,
            exclusive_area_square_meters=exclusive_area_square_meters,
            trades=ordered,
            sample_count=len(ordered),
            latest_trade=ordered[0] if complete else None,
            median_amount_ten_thousand_krw=(
                int(median(trade.deal_amount_ten_thousand_krw for trade in ordered))
                if complete
                else None
            ),
        )
