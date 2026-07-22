from __future__ import annotations

from dataclasses import dataclass
from datetime import date
import json
import math
import re

from .comparison import RecentThreeTradeBasis
from .models import ComplexRecord, LifestyleTheme

POLICY_VERSION = "recommendation-policy-v1"


@dataclass(frozen=True)
class RecommendationCandidate:
    complex_record: ComplexRecord
    trade_basis: RecentThreeTradeBasis
    rail_distance_meters: int | None
    retail_distance_meters: int | None
    student_score_ratio: float | None = None
    young_child_score_ratio: float | None = None

    def __post_init__(self) -> None:
        if (
            self.complex_record.complex_id != self.trade_basis.complex_id
            or not self.complex_record.marker_safe
            or self.complex_record.latitude is None
            or self.complex_record.longitude is None
            or any(
                value is not None and (isinstance(value, bool) or value < 0)
                for value in (self.rail_distance_meters, self.retail_distance_meters)
            )
            or any(
                value is not None and (
                    not math.isfinite(value) or not 0 <= value <= 1
                )
                for value in (
                    self.student_score_ratio, self.young_child_score_ratio
                )
            )
        ):
            raise ValueError("recommendation candidate is invalid")


@dataclass(frozen=True)
class ScoreBreakdown:
    price_points: float
    rail_points: float
    retail_points: float
    student_points: float = 0.0
    young_child_points: float = 0.0
    rail_weight: float = 25.0
    retail_weight: float = 15.0
    student_weight: float = 0.0
    young_child_weight: float = 0.0
    policy_version: str = POLICY_VERSION

    def __post_init__(self) -> None:
        if (
            self.policy_version != POLICY_VERSION
            or self.price_points != 60.0
            or not 0 <= self.rail_points <= self.rail_weight
            or not 0 <= self.retail_points <= self.retail_weight
            or not 0 <= self.student_points <= self.student_weight
            or not 0 <= self.young_child_points <= self.young_child_weight
            or not math.isclose(
                self.rail_weight + self.retail_weight
                + self.student_weight + self.young_child_weight,
                40,
            )
            or not all(math.isfinite(value) for value in (
                self.price_points, self.rail_points, self.retail_points,
                self.student_points, self.young_child_points,
            ))
        ):
            raise ValueError("recommendation score breakdown is invalid")

    @property
    def total_score(self) -> float:
        return round(
            self.price_points + self.rail_points + self.retail_points
            + self.student_points + self.young_child_points,
            1,
        )


@dataclass(frozen=True)
class RecommendationResult:
    candidate: RecommendationCandidate
    breakdown: ScoreBreakdown

    @property
    def total_score(self) -> float:
        return self.breakdown.total_score


class RecommendationPolicy:
    def __init__(
        self,
        *,
        maximum_budget_ten_thousand_krw: int,
        lifestyle_themes: tuple[LifestyleTheme, ...] = (),
    ) -> None:
        if (
            isinstance(maximum_budget_ten_thousand_krw, bool)
            or not 1 <= maximum_budget_ten_thousand_krw <= 100_000_000
        ):
            raise ValueError("recommendation budget is outside the supported range")
        self._maximum_budget = maximum_budget_ten_thousand_krw
        if (
            len(lifestyle_themes) != len(set(lifestyle_themes))
            or len(lifestyle_themes) > 3
            or any(theme not in {
                "TRANSIT", "STUDENT", "YOUNG_CHILD", "SHOPPING"
            } for theme in lifestyle_themes)
        ):
            raise ValueError("recommendation lifestyle themes are invalid")
        self._themes = lifestyle_themes

    def is_budget_qualified(self, basis: RecentThreeTradeBasis) -> bool:
        return (
            basis.sample_count == 3
            and basis.median_amount_ten_thousand_krw is not None
            and basis.median_amount_ten_thousand_krw <= self._maximum_budget
        )

    def rank(
        self, candidates: tuple[RecommendationCandidate, ...]
    ) -> tuple[RecommendationResult, ...]:
        if (
            "STUDENT" in self._themes
            and any(candidate.student_score_ratio is None for candidate in candidates)
        ) or (
            "YOUNG_CHILD" in self._themes
            and any(candidate.young_child_score_ratio is None for candidate in candidates)
        ):
            return ()
        weights = self._weights()
        results = tuple(
            RecommendationResult(
                candidate=candidate,
                breakdown=ScoreBreakdown(
                    price_points=60.0,
                    rail_points=_distance_points(
                        candidate.rail_distance_meters, maximum_distance=1500,
                        weight=weights["TRANSIT"],
                    ),
                    retail_points=_distance_points(
                        candidate.retail_distance_meters, maximum_distance=1000,
                        weight=weights["SHOPPING"],
                    ),
                    student_points=weights["STUDENT"] * (candidate.student_score_ratio or 0),
                    young_child_points=(
                        weights["YOUNG_CHILD"] * (candidate.young_child_score_ratio or 0)
                    ),
                    rail_weight=weights["TRANSIT"],
                    retail_weight=weights["SHOPPING"],
                    student_weight=weights["STUDENT"],
                    young_child_weight=weights["YOUNG_CHILD"],
                ),
            )
            for candidate in candidates
            if self.is_budget_qualified(candidate.trade_basis)
        )
        return tuple(sorted(
            results,
            key=lambda result: (
                -result.total_score,
                result.candidate.complex_record.complex_id,
            ),
        ))

    def _weights(self) -> dict[LifestyleTheme, float]:
        if not self._themes:
            return {
                "TRANSIT": 25.0, "SHOPPING": 15.0,
                "STUDENT": 0.0, "YOUNG_CHILD": 0.0,
            }
        share = 25.0 / len(self._themes)
        return {
            "TRANSIT": 10.0 + (share if "TRANSIT" in self._themes else 0.0),
            "SHOPPING": 5.0 + (share if "SHOPPING" in self._themes else 0.0),
            "STUDENT": share if "STUDENT" in self._themes else 0.0,
            "YOUNG_CHILD": share if "YOUNG_CHILD" in self._themes else 0.0,
        }


def _distance_points(
    distance_meters: int | None, *, maximum_distance: int, weight: float
) -> float:
    if distance_meters is None or distance_meters >= maximum_distance:
        return 0.0
    return weight * (1 - distance_meters / maximum_distance)


@dataclass(frozen=True)
class RecommendationScoreItem:
    key: str
    label: str
    weight: float
    points: float
    distance_meters: int | None
    fact_ids: tuple[str, ...]
    details: tuple[str, ...] = ()

    def to_public_dict(self) -> dict[str, object]:
        if (
            self.key not in {"PRICE", "TRANSIT", "SHOPPING", "STUDENT", "YOUNG_CHILD"}
            or not 1 <= len(self.label.strip()) <= 100
            or not math.isfinite(self.weight)
            or not math.isfinite(self.points)
            or self.weight < 0
            or self.points < 0
            or self.points > self.weight
            or self.distance_meters is not None and (
                isinstance(self.distance_meters, bool) or self.distance_meters < 0
            )
            or not _valid_fact_ids(self.fact_ids)
            or len(self.details) > 5
            or any(not 1 <= len(value.strip()) <= 200 for value in self.details)
        ):
            raise ValueError("recommendation score item is invalid")
        return {
            "key": self.key,
            "label": self.label.strip(),
            "weight": self.weight,
            "points": self.points,
            "distanceMeters": self.distance_meters,
            "factIds": list(self.fact_ids),
            "details": [value.strip() for value in self.details],
        }


@dataclass(frozen=True)
class RecommendationCard:
    rank: int
    complex_id: int
    complex_name: str
    total_score: float
    latest_trade_date: date
    latest_trade_amount_ten_thousand_krw: int
    median_amount_ten_thousand_krw: int
    latest_trade_fact_ids: tuple[str, ...]
    median_fact_ids: tuple[str, ...]
    score_breakdown: tuple[RecommendationScoreItem, ...]
    limitations: tuple[str, ...]
    fact_ids: tuple[str, ...]
    active_themes: tuple[LifestyleTheme, ...] = ()

    def to_public_dict(self) -> dict[str, object]:
        if (
            not 1 <= self.rank <= 5
            or self.complex_id <= 0
            or not 1 <= len(self.complex_name.strip()) <= 100
            or not math.isfinite(self.total_score)
            or not 0 <= self.total_score <= 100
            or self.latest_trade_amount_ten_thousand_krw <= 0
            or self.median_amount_ten_thousand_krw <= 0
            or not 3 <= len(self.score_breakdown) <= 5
            or tuple(item.key for item in self.score_breakdown[:3])
            != ("PRICE", "TRANSIT", "SHOPPING")
            or tuple(item.key for item in self.score_breakdown[3:])
            != tuple(
                theme for theme in ("STUDENT", "YOUNG_CHILD")
                if theme in self.active_themes
            )
            or len(self.limitations) > 5
            or any(not 1 <= len(value.strip()) <= 2_000 for value in self.limitations)
            or not self.latest_trade_fact_ids
            or not self.median_fact_ids
            or not self.fact_ids
            or len(self.fact_ids) != len(set(self.fact_ids))
            or not _valid_fact_ids(self.latest_trade_fact_ids)
            or not _valid_fact_ids(self.median_fact_ids)
            or not _valid_fact_ids(self.fact_ids)
            or not set(self.latest_trade_fact_ids).issubset(self.fact_ids)
            or not set(self.median_fact_ids).issubset(self.fact_ids)
            or any(
                not set(item.fact_ids).issubset(self.fact_ids)
                for item in self.score_breakdown
            )
            or not math.isclose(
                sum(item.weight for item in self.score_breakdown), 100
            )
            or round(sum(item.points for item in self.score_breakdown), 1)
            != self.total_score
        ):
            raise ValueError("recommendation card is invalid")
        return {
            "rank": self.rank,
            "complexId": self.complex_id,
            "complexName": self.complex_name.strip(),
            "totalScore": self.total_score,
            "latestTrade": {
                "date": self.latest_trade_date.isoformat(),
                "amountTenThousandKrw": self.latest_trade_amount_ten_thousand_krw,
                "factIds": list(self.latest_trade_fact_ids),
            },
            "recentThreeMedian": {
                "amountTenThousandKrw": self.median_amount_ten_thousand_krw,
                "factIds": list(self.median_fact_ids),
            },
            "scoreBreakdown": [item.to_public_dict() for item in self.score_breakdown],
            "activeThemes": list(self.active_themes),
            "limitations": [value.strip() for value in self.limitations],
            "factIds": list(self.fact_ids),
        }


@dataclass(frozen=True)
class RecommendationCardsArtifact:
    artifact_id: str
    cards: tuple[RecommendationCard, ...]

    def to_public_dict(self) -> dict[str, object]:
        if (
            re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,199}", self.artifact_id) is None
            or not 1 <= len(self.cards) <= 5
            or tuple(card.rank for card in self.cards) != tuple(range(1, len(self.cards) + 1))
            or len({card.complex_id for card in self.cards}) != len(self.cards)
        ):
            raise ValueError("recommendation cards bounds are invalid")
        artifact: dict[str, object] = {
            "type": "recommendationCards",
            "version": 1,
            "artifactId": self.artifact_id,
            "title": "조건을 충족한 단지",
            "policyVersion": POLICY_VERSION,
            "cards": [card.to_public_dict() for card in self.cards],
        }
        if len(json.dumps(artifact, ensure_ascii=False).encode("utf-8")) > 65_536:
            raise ValueError("recommendation cards exceed the public size limit")
        return artifact


def _valid_fact_ids(values: tuple[str, ...]) -> bool:
    return (
        1 <= len(values) <= 100
        and len(values) == len(set(values))
        and all(
            re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,199}", value) is not None
            for value in values
        )
    )
