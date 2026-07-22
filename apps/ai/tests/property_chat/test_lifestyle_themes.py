from __future__ import annotations

from ai_service.property_chat.lifestyle_themes import (
    detect_explicit_themes,
    detect_school_levels,
)


def test_theme_detector_keeps_only_proposed_themes_explicit_in_current_question() -> None:
    assert detect_explicit_themes(
        "학생이 있고 역도 가까운 곳을 추천해줘",
        ("SHOPPING", "STUDENT", "TRANSIT", "YOUNG_CHILD"),
    ) == ("TRANSIT", "STUDENT")


def test_theme_detector_does_not_add_childcare_to_a_general_recommendation() -> None:
    assert detect_explicit_themes(
        "송파구 20억 이하 전용 84㎡ 후보를 추천해줘",
        ("YOUNG_CHILD",),
    ) == ()


def test_school_level_detector_uses_only_explicit_levels_or_all_for_general_student() -> None:
    assert detect_school_levels("초등학교가 가까운 곳", ("STUDENT",)) == ("ELEMENTARY",)
    assert detect_school_levels("학생이 있는 집", ("STUDENT",)) == (
        "ELEMENTARY", "MIDDLE", "HIGH",
    )
