from __future__ import annotations

from datetime import UTC, date, datetime

from ai_service.property_chat.academy_locations import AcademyLocationSearchResult
from ai_service.property_chat.childcare_centers import ChildcareCenter, ChildcareSearchResult
from ai_service.property_chat.lifestyle_metrics import (
    childcare_details,
    childcare_observation_fact,
    childcare_ratio,
    student_details,
    student_observation_fact,
    student_ratio,
)
from ai_service.property_chat.models import SchoolRecord, SchoolSearchResult, SchoolSnapshot


def _academies(count: int = 5) -> AcademyLocationSearchResult:
    return AcademyLocationSearchResult(
        (), count, 1.0, "academy-v1", datetime(2026, 7, 1, tzinfo=UTC), count == 0
    )


def test_student_metric_combines_level_distance_and_sbiz_count_without_quality_claims() -> None:
    schools = SchoolSearchResult((SchoolRecord(
        "school-1", "가까운초등학교", "ELEMENTARY", "운영", None, None,
        37.5, 127.1, 0,
    ),), 1)
    snapshot = SchoolSnapshot("school-v1", date(2026, 6, 30), datetime(2026, 7, 1, tzinfo=UTC))

    assert student_ratio(schools, _academies(), ("ELEMENTARY",)) == 1.0
    assert "Sbiz 교육업소 5곳" in student_details(
        schools, _academies(), ("ELEMENTARY",)
    )[-1]
    fact = student_observation_fact(
        1, schools, snapshot, _academies(), ("ELEMENTARY",), 25, 25
    )
    assert fact.evidence_grade == "B"
    assert fact.dataset_version == "school-v1:academy-v1"
    assert fact.payload["sbizEducationCountWithin800m"] == 5
    assert "학군" not in str(fact.payload)


def test_childcare_metric_uses_nearest_distance_and_count_not_capacity() -> None:
    result = ChildcareSearchResult(
        centers=(ChildcareCenter(
            "center-1", "해뜰어린이집", "국공립", 50, 0,
            date(2026, 7, 1), "child-v1",
        ),),
        matched_count=5, returned_count=1, has_more=True, verified_zero=False,
        coordinate_coverage=1.0, dataset_version="child-v1",
        observed_at=datetime(2026, 7, 1, tzinfo=UTC), freshness_days=45,
    )

    assert childcare_ratio(result) == 1.0
    assert childcare_details(result)[0].endswith("5곳")
    fact = childcare_observation_fact(1, result, 25, 25)
    assert "capacity" not in fact.payload
    assert fact.payload["nearestCenterName"] == "해뜰어린이집"


def test_missing_lifestyle_facilities_are_zero_only_for_verified_empty_results() -> None:
    schools = SchoolSearchResult((), 0)
    academies = _academies(0)
    assert student_ratio(schools, academies, ("ELEMENTARY",)) == 0.0
    assert student_details(schools, academies, ("ELEMENTARY",))[0].endswith(
        "확인되지 않음"
    )

    childcare = ChildcareSearchResult(
        centers=(), matched_count=0, returned_count=0, has_more=False,
        verified_zero=True, coordinate_coverage=1.0, dataset_version="child-v1",
        observed_at=datetime(2026, 7, 1, tzinfo=UTC), freshness_days=45,
    )
    assert childcare_ratio(childcare) == 0.0
    assert childcare_details(childcare)[1] == "최근접 어린이집 확인되지 않음"
    fact = childcare_observation_fact(1, childcare, 25, 0)
    assert fact.payload["nearestCenterName"] is None
    assert all(claim.unit not in {"TEXT", "METERS"} for claim in fact.claims)
