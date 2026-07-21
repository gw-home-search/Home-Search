from __future__ import annotations

import re

from .models import LifestyleTheme, SchoolLevel

_THEME_ORDER: tuple[LifestyleTheme, ...] = (
    "TRANSIT", "STUDENT", "YOUNG_CHILD", "SHOPPING",
)
_TRIGGERS = {
    "TRANSIT": r"역세권|지하철|철도|(?:^|\s)역(?:도|이|을|과|이랑|은|가|\s|$)",
    "STUDENT": r"학생|초등학교|중학교|고등학교|학교|학원|교육",
    "YOUNG_CHILD": r"영유아|어린아이|어린이집|유치원",
    "SHOPPING": r"마트|백화점|쇼핑|대규모점포",
}


def detect_explicit_themes(
    question: str, proposed: tuple[LifestyleTheme, ...]
) -> tuple[LifestyleTheme, ...]:
    proposed_set = set(proposed)
    return tuple(
        theme
        for theme in _THEME_ORDER
        if theme in proposed_set and re.search(_TRIGGERS[theme], question)
    )


def detect_school_levels(
    question: str, themes: tuple[LifestyleTheme, ...]
) -> tuple[SchoolLevel, ...]:
    if "STUDENT" not in themes:
        return ("ELEMENTARY", "MIDDLE", "HIGH")
    levels: list[SchoolLevel] = []
    for trigger, level in (
        ("초등학교", "ELEMENTARY"),
        ("중학교", "MIDDLE"),
        ("고등학교", "HIGH"),
    ):
        if trigger in question:
            levels.append(level)  # type: ignore[arg-type]
    return tuple(levels) or ("ELEMENTARY", "MIDDLE", "HIGH")
