from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import date, datetime
from typing import Literal

QueryCapability = Literal[
    "complex_identity",
    "recent_trade_lookup",
    "price_trend",
    "school_location",
    "retail_location",
    "academy_registry_summary",
    "academy_lookup",
    "rail_station_lookup",
    "childcare_lookup",
    "kakao_place_search",
    "comparison",
    "recommendation",
]
PropertyCapability = Literal[
    "complex_identity", "recent_trade_lookup", "price_trend", "comparison",
    "recommendation",
]
ReferenceCapability = Literal[
    "school_location", "retail_location", "academy_registry_summary", "academy_lookup",
    "rail_station_lookup", "childcare_lookup", "kakao_place_search",
]
NearbyPlaceCategory = Literal["HOSPITAL", "DAYCARE_KINDERGARTEN"]
SchoolLevel = Literal["ELEMENTARY", "MIDDLE", "HIGH"]
LifestyleTheme = Literal["TRANSIT", "STUDENT", "YOUNG_CHILD", "SHOPPING"]
RecommendationMode = Literal["BUDGET", "CRITERIA"]
RecommendationCriterion = Literal["TRANSIT", "ACADEMY", "SCHOOL", "SHOPPING"]
RecommendationClarificationCode = Literal[
    "AMBIGUOUS_EDUCATION",
    "MISSING_PRIORITY",
    "NUMERIC_CONDITION_MISMATCH",
    "REGION_NOT_CONFIRMED",
    "STATION_RADIUS_REQUIRED",
    "STATION_RADIUS_OUT_OF_RANGE",
    "UNSUPPORTED_CHILDCARE",
]
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
    place_category: NearbyPlaceCategory | None = None
    complex_names: tuple[str, ...] = ()
    maximum_budget_ten_thousand_krw: int | None = None
    lifestyle_themes: tuple[LifestyleTheme, ...] = ()
    recommendation_mode: RecommendationMode | None = None
    minimum_unit_count: int | None = None
    recommendation_criteria: tuple[RecommendationCriterion, ...] = ()
    criteria_order: tuple[RecommendationCriterion, ...] = ()
    station_name: str | None = None
    clarification_code: RecommendationClarificationCode | None = None

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
        if self.capability in {
            "school_location",
            "retail_location",
            "academy_lookup",
            "rail_station_lookup",
            "childcare_lookup",
        } and self.limit > 5:
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
        if radius_meters is None and self.capability != "recommendation":
            radius_meters = (
                1500
                if self.capability == "rail_station_lookup"
                else 1000
                if self.capability == "retail_location"
                else 800
            )
            object.__setattr__(self, "radius_meters", radius_meters)
        if radius_meters is not None and not 0 <= radius_meters <= 10_000_000:
            raise ValueError("radius_meters cannot be represented safely")
        if self.capability == "price_trend" and (self.start_date is None or self.end_date is None):
            raise ValueError("price_trend requires start_date and end_date")
        if self.capability == "kakao_place_search":
            if self.place_category not in {"HOSPITAL", "DAYCARE_KINDERGARTEN"}:
                raise ValueError("kakao_place_search requires a supported place category")
        elif self.place_category is not None:
            raise ValueError("place_category is only supported for kakao_place_search")
        normalized_names = tuple(name.strip() for name in self.complex_names)
        if self.capability == "comparison":
            if (
                not 2 <= len(normalized_names) <= 4
                or len(normalized_names) != len(set(normalized_names))
                or any(not name or len(name) > 100 for name in normalized_names)
            ):
                raise ValueError("comparison requires 2..4 unique complexes")
            object.__setattr__(self, "complex_name", normalized_names[0])
        elif normalized_names:
            raise ValueError("complex_names are only supported for comparison")
        object.__setattr__(self, "complex_names", normalized_names)
        budget = self.maximum_budget_ten_thousand_krw
        if self.capability == "recommendation":
            if (
                self.limit > 5
                or budget is not None
                and (
                    isinstance(budget, bool)
                    or not 1 <= budget <= 100_000_000
                )
            ):
                raise ValueError("recommendation budget or limit is invalid")
            mode = self.recommendation_mode
            if mode is None:
                mode = (
                    "CRITERIA"
                    if self.minimum_unit_count is not None
                    or self.recommendation_criteria
                    or self.criteria_order
                    or self.station_name is not None
                    else "BUDGET"
                )
                object.__setattr__(self, "recommendation_mode", mode)
            if mode not in {"BUDGET", "CRITERIA"}:
                raise ValueError("recommendation mode is invalid")
            if self.minimum_unit_count is not None and (
                isinstance(self.minimum_unit_count, bool)
                or not 1 <= self.minimum_unit_count <= 100_000
            ):
                raise ValueError("minimum unit count is invalid")
            allowed_criteria = ("TRANSIT", "ACADEMY", "SCHOOL", "SHOPPING")
            if (
                len(self.recommendation_criteria)
                != len(set(self.recommendation_criteria))
                or any(key not in allowed_criteria for key in self.recommendation_criteria)
                or len(self.criteria_order) != len(set(self.criteria_order))
                or any(key not in allowed_criteria for key in self.criteria_order)
                or not set(self.criteria_order).issubset(self.recommendation_criteria)
            ):
                raise ValueError("recommendation criteria are invalid")
            if len(self.recommendation_criteria) == 1 and not self.criteria_order:
                object.__setattr__(
                    self, "criteria_order", self.recommendation_criteria
                )
            if self.station_name is not None:
                station_name = self.station_name.strip()
                if not 1 <= len(station_name) <= 100:
                    raise ValueError("station name is invalid")
                object.__setattr__(self, "station_name", station_name)
            if self.clarification_code is not None and self.clarification_code not in {
                "AMBIGUOUS_EDUCATION",
                "MISSING_PRIORITY",
                "NUMERIC_CONDITION_MISMATCH",
                "REGION_NOT_CONFIRMED",
                "STATION_RADIUS_REQUIRED",
                "STATION_RADIUS_OUT_OF_RANGE",
                "UNSUPPORTED_CHILDCARE",
            }:
                raise ValueError("recommendation clarification code is invalid")
        elif budget is not None:
            raise ValueError(
                "maximum_budget_ten_thousand_krw is only supported for recommendation"
            )
        elif (
            self.recommendation_mode is not None
            or self.minimum_unit_count is not None
            or self.recommendation_criteria
            or self.criteria_order
            or self.station_name is not None
            or self.clarification_code is not None
        ):
            raise ValueError("recommendation fields are only supported for recommendation")
        theme_order = ("TRANSIT", "STUDENT", "YOUNG_CHILD", "SHOPPING")
        if (
            len(self.lifestyle_themes) != len(set(self.lifestyle_themes))
            or any(theme not in theme_order for theme in self.lifestyle_themes)
            or self.lifestyle_themes and self.capability not in {"recommendation", "comparison"}
        ):
            raise ValueError("lifestyle themes are invalid")
        object.__setattr__(self, "lifestyle_themes", tuple(
            theme for theme in theme_order if theme in self.lifestyle_themes
        ))


CAPABILITY_EXECUTION_ORDER: tuple[QueryCapability, ...] = (
    "complex_identity",
    "recent_trade_lookup",
    "price_trend",
    "comparison",
    "recommendation",
    "school_location",
    "academy_lookup",
    "academy_registry_summary",
    "rail_station_lookup",
    "retail_location",
    "childcare_lookup",
    "kakao_place_search",
)


@dataclass(frozen=True)
class QueryPlanBundle:
    fragments: tuple[QueryPlan, ...]

    def __post_init__(self) -> None:
        if not 1 <= len(self.fragments) <= 4:
            raise ValueError("query plan bundle must contain 1..4 fragments")
        merged: dict[QueryCapability, QueryPlan] = {}
        for plan in self.fragments:
            existing = merged.get(plan.capability)
            merged[plan.capability] = (
                plan if existing is None else _merge_duplicate_plan(existing, plan)
            )
        object.__setattr__(self, "fragments", tuple(merged.values()))


def _merge_duplicate_plan(left: QueryPlan, right: QueryPlan) -> QueryPlan:
    if left == right:
        return left
    scalar_fields = (
        "complex_name", "region_name", "start_date", "end_date",
        "exclusive_area_square_meters", "radius_meters", "place_category",
        "maximum_budget_ten_thousand_krw",
        "recommendation_mode", "minimum_unit_count", "station_name",
    )
    if any(
        getattr(left, name) is not None
        and getattr(right, name) is not None
        and getattr(left, name) != getattr(right, name)
        for name in scalar_fields
    ):
        raise ValueError("duplicate capability plans conflict")

    def merged_values(first: tuple[object, ...], second: tuple[object, ...]) -> tuple:
        return tuple(dict.fromkeys((*first, *second)))

    return replace(
        left,
        region_name=left.region_name or right.region_name,
        start_date=left.start_date or right.start_date,
        end_date=left.end_date or right.end_date,
        exclusive_area_square_meters=(
            left.exclusive_area_square_meters or right.exclusive_area_square_meters
        ),
        limit=max(left.limit, right.limit),
        school_levels=merged_values(left.school_levels, right.school_levels),
        facility_subtypes=merged_values(left.facility_subtypes, right.facility_subtypes),
        radius_meters=left.radius_meters or right.radius_meters,
        place_category=left.place_category or right.place_category,
        complex_names=merged_values(left.complex_names, right.complex_names),
        maximum_budget_ten_thousand_krw=(
            left.maximum_budget_ten_thousand_krw
            or right.maximum_budget_ten_thousand_krw
        ),
        lifestyle_themes=merged_values(
            left.lifestyle_themes, right.lifestyle_themes
        ),
        recommendation_mode=left.recommendation_mode or right.recommendation_mode,
        minimum_unit_count=left.minimum_unit_count or right.minimum_unit_count,
        recommendation_criteria=merged_values(
            left.recommendation_criteria, right.recommendation_criteria
        ),
        criteria_order=merged_values(left.criteria_order, right.criteria_order),
        station_name=left.station_name or right.station_name,
        clarification_code=left.clarification_code or right.clarification_code,
    )


@dataclass(frozen=True)
class ShowNearbyCategoryAction:
    label: str
    category: NearbyPlaceCategory
    latitude: float
    longitude: float
    fact_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        if (
            not 1 <= len(self.label.strip()) <= 100
            or self.category not in {"HOSPITAL", "DAYCARE_KINDERGARTEN"}
            or not 32 <= self.latitude <= 39.5
            or not 123 <= self.longitude <= 132
            or not self.fact_ids
            or len(self.fact_ids) > 10
            or len(self.fact_ids) != len(set(self.fact_ids))
        ):
            raise ValueError("nearby category action is invalid")

    def to_public_dict(self, request_id: str) -> dict[str, object]:
        action_id = f"action-{request_id}-{self.category.lower()}"
        if len(action_id) > 200:
            raise ValueError("nearby category action id is too long")
        return {
            "type": "showNearbyCategory",
            "version": 1,
            "actionId": action_id,
            "label": self.label.strip(),
            "category": self.category,
            "center": {"lat": self.latitude, "lng": self.longitude},
            "level": 4,
            "factIds": list(self.fact_ids),
        }


@dataclass(frozen=True)
class FocusComplexAction:
    label: str
    parcel_id: int
    complex_id: int
    latitude: float
    longitude: float
    auto_run: bool
    fact_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        if (
            not 1 <= len(self.label.strip()) <= 100
            or isinstance(self.parcel_id, bool)
            or not isinstance(self.parcel_id, int)
            or self.parcel_id <= 0
            or isinstance(self.complex_id, bool)
            or not isinstance(self.complex_id, int)
            or self.complex_id <= 0
            or not 33 <= self.latitude <= 39
            or not 124 <= self.longitude <= 132
            or not isinstance(self.auto_run, bool)
            or not 1 <= len(self.fact_ids) <= 10
            or len(self.fact_ids) != len(set(self.fact_ids))
        ):
            raise ValueError("focus complex action is invalid")

    def to_public_dict(self, request_id: str) -> dict[str, object]:
        action_id = f"action-{request_id}-focus-complex-{self.complex_id}"
        if len(action_id) > 200:
            raise ValueError("focus complex action id is too long")
        return {
            "type": "focusComplex",
            "version": 1,
            "actionId": action_id,
            "label": self.label.strip(),
            "parcelId": self.parcel_id,
            "complexId": self.complex_id,
            "center": {"lat": self.latitude, "lng": self.longitude},
            "level": 4,
            "openDetail": True,
            "autoRun": self.auto_run,
            "factIds": list(self.fact_ids),
        }


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
    unit_count: int | None = None
    use_date: date | None = None
    parcel_id: int | None = None
    match_tier: int = 3


@dataclass(frozen=True)
class AdministrativeRegionContext:
    province_name: str
    district_name: str
    education_office_name: str


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
