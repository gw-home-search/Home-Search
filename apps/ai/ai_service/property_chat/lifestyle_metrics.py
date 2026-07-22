from __future__ import annotations

from .academy_locations import AcademyLocationSearchResult
from .childcare_centers import ChildcareSearchResult
from .models import EvidenceFact, FactClaim, SchoolSearchResult, SchoolSnapshot


def student_ratio(
    schools: SchoolSearchResult,
    academies: AcademyLocationSearchResult,
    levels: tuple[str, ...],
) -> float:
    distance_by_level = {
        school.school_level: school.distance_meters for school in schools.schools
    }
    school_ratio = sum(
        linear_ratio(distance_by_level.get(level), 1500) for level in levels
    ) / len(levels)
    return school_ratio * 0.5 + min(academies.matched_count, 5) / 5 * 0.5


def childcare_ratio(result: ChildcareSearchResult) -> float:
    nearest = result.centers[0].distance_meters if result.centers else None
    return linear_ratio(nearest, 800) * 0.5 + min(result.matched_count, 5) / 5 * 0.5


def linear_ratio(distance_meters: int | None, maximum: int) -> float:
    if distance_meters is None or distance_meters >= maximum:
        return 0.0
    return 1 - distance_meters / maximum


def student_details(
    schools: SchoolSearchResult,
    academies: AcademyLocationSearchResult,
    levels: tuple[str, ...],
) -> tuple[str, ...]:
    by_level = {school.school_level: school for school in schools.schools}
    labels = {"ELEMENTARY": "초등학교", "MIDDLE": "중학교", "HIGH": "고등학교"}
    values = tuple(
        f"{labels[level]}: {by_level[level].school_name} {by_level[level].distance_meters}m"
        if level in by_level else f"{labels[level]}: 1,500m 내 확인되지 않음"
        for level in levels
    )
    return (*values, f"800m 내 Sbiz 교육업소 {academies.matched_count}곳")


def childcare_details(result: ChildcareSearchResult) -> tuple[str, ...]:
    nearest = result.centers[0] if result.centers else None
    return (
        f"800m 내 공식 운영 어린이집 {result.matched_count}곳",
        "최근접 어린이집 확인되지 않음" if nearest is None
        else f"최근접 {nearest.center_name} {nearest.distance_meters}m",
    )


def student_observation_fact(
    complex_id: int,
    schools: SchoolSearchResult,
    snapshot: SchoolSnapshot,
    academies: AcademyLocationSearchResult,
    levels: tuple[str, ...],
    weight: float,
    points: float,
) -> EvidenceFact:
    nearest = {
        school.school_level: {
            "name": school.school_name,
            "distanceMeters": school.distance_meters,
        }
        for school in schools.schools
    }
    claims = [
        FactClaim(str(min(academies.matched_count, 5)), "SBIZ_EDUCATION_COUNT_CAPPED_5"),
        FactClaim(format(weight, ".15g"), "WEIGHT_POINTS"),
        FactClaim(format(points, ".15g"), "POINTS"),
    ]
    claims.extend(
        FactClaim(str(value["distanceMeters"]), f"{level}_METERS")
        for level, value in nearest.items()
    )
    return EvidenceFact(
        fact_id=f"recommendation-student-{complex_id}",
        claims=tuple(claims),
        data_as_of=min(snapshot.source_date, academies.observed_at.date()),
        payload={
            "complexId": complex_id, "schoolLevels": list(levels),
            "nearestSchools": nearest,
            "sbizEducationCountWithin800m": academies.matched_count,
            "sbizEducationCountScoreCap": 5, "weight": weight, "points": points,
        },
        source_id="lifestyle.student-observation",
        source_name="학교 위치 + Sbiz 교육업소",
        evidence_grade="B",
        dataset_version_value=(
            f"{snapshot.dataset_version}:{academies.dataset_version}"
        ),
    )


def childcare_observation_fact(
    complex_id: int,
    result: ChildcareSearchResult,
    weight: float,
    points: float,
) -> EvidenceFact:
    nearest = result.centers[0] if result.centers else None
    claims = [
        FactClaim(str(min(result.matched_count, 5)), "CHILDCARE_COUNT_CAPPED_5"),
        FactClaim(format(weight, ".15g"), "WEIGHT_POINTS"),
        FactClaim(format(points, ".15g"), "POINTS"),
    ]
    if nearest is not None:
        claims.extend((
            FactClaim(nearest.center_name, "TEXT"),
            FactClaim(str(nearest.distance_meters), "METERS"),
        ))
    return EvidenceFact(
        fact_id=f"recommendation-childcare-{complex_id}",
        claims=tuple(claims), data_as_of=result.observed_at.date(),
        payload={
            "complexId": complex_id, "countWithin800m": result.matched_count,
            "countScoreCap": 5,
            "nearestCenterName": None if nearest is None else nearest.center_name,
            "nearestDistanceMeters": None if nearest is None else nearest.distance_meters,
            "weight": weight, "points": points,
        },
        source_id="childcare.center",
        source_name="어린이집별 기본정보 조회",
        evidence_grade="A",
        dataset_version_value=result.dataset_version,
    )
