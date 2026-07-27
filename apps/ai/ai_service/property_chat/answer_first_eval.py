from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

AnswerMode = Literal["COMPLETE", "BEST_EFFORT", "PARTIAL", "NO_RESULT"]

EXPECTED_CATEGORY_COUNTS = {
    "exact_simple": 12,
    "missing_conditions": 12,
    "zero_alternative": 12,
    "partial_source": 12,
    "compound": 12,
    "provider_failure": 8,
    "ambiguous_typo": 6,
    "broad_overview": 6,
    "entity_natural_language": 5,
    "multi_complex_ambiguity": 5,
    "balanced_recommendation": 5,
    "explicit_recommendation": 5,
    "complex_overview": 5,
    "web_policy": 5,
    "provider_route": 5,
    "stability_ui": 5,
}


class AnswerFirstEvalError(ValueError):
    pass


@dataclass(frozen=True)
class AnswerFirstGoldenCase:
    case_id: str
    category: str
    question: str
    allowed_modes: tuple[AnswerMode, ...]
    answerable: bool

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", self.case_id):
            raise AnswerFirstEvalError("CASE_ID_INVALID")
        if self.category not in EXPECTED_CATEGORY_COUNTS:
            raise AnswerFirstEvalError("CATEGORY_INVALID")
        if not 1 <= len(self.question.strip()) <= 2_000:
            raise AnswerFirstEvalError("QUESTION_INVALID")
        if not self.allowed_modes or any(
            mode not in {"COMPLETE", "BEST_EFFORT", "PARTIAL", "NO_RESULT"}
            for mode in self.allowed_modes
        ):
            raise AnswerFirstEvalError("ANSWER_MODE_INVALID")


def load_answer_first_catalog(path: Path) -> tuple[AnswerFirstGoldenCase, ...]:
    try:
        if not path.is_file() or path.stat().st_size > 128 * 1024:
            raise AnswerFirstEvalError("CATALOG_INVALID")
        root = json.loads(path.read_text(encoding="utf-8"))
        raw_cases = root["cases"]
        if root.get("version") != 1 or not isinstance(raw_cases, list):
            raise AnswerFirstEvalError("CATALOG_INVALID")
        cases = tuple(_parse_case(value) for value in raw_cases)
        expected_size = sum(EXPECTED_CATEGORY_COUNTS.values())
        if len(cases) != expected_size or len({case.case_id for case in cases}) != expected_size:
            raise AnswerFirstEvalError("CATALOG_SIZE_INVALID")
        if Counter(case.category for case in cases) != Counter(
            EXPECTED_CATEGORY_COUNTS
        ):
            raise AnswerFirstEvalError("CATEGORY_COVERAGE_INVALID")
        return cases
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as exception:
        raise AnswerFirstEvalError("CATALOG_INVALID") from exception


def grade_answer_first_response(
    case: AnswerFirstGoldenCase,
    response: dict[str, object],
) -> tuple[str, ...]:
    failures: list[str] = []
    answer = response.get("answer")
    if not isinstance(answer, str) or not answer.strip():
        failures.append("ANSWER_EMPTY")
    elif case.answerable and _is_deflection_only(answer):
        failures.append("ANSWER_DEFLECTION_ONLY")

    resolution = response.get("conversationResolution")
    if not isinstance(resolution, dict) or resolution.get("version") != 1:
        failures.append("RESOLUTION_MISSING")
    else:
        answer_mode = resolution.get("answerMode")
        if answer_mode not in case.allowed_modes:
            failures.append("ANSWER_MODE_UNEXPECTED")
        goals = resolution.get("goals")
        if not isinstance(goals, list) or not goals:
            failures.append("GOALS_MISSING")
        elif any(
            not isinstance(goal, dict)
            or goal.get("status") not in {"answered", "degraded", "unavailable"}
            for goal in goals
        ):
            failures.append("GOAL_STATUS_INVALID")

    evidence = response.get("evidenceSummary")
    if case.answerable and (
        not isinstance(evidence, dict)
        or not isinstance(evidence.get("factCount"), int)
        or evidence["factCount"] < 1
    ):
        failures.append("VERIFIED_FACT_MISSING")
    return tuple(failures)


def grade_agentic_selection_stability(
    top_three_runs: tuple[tuple[int, ...], ...],
) -> tuple[str, ...]:
    if len(top_three_runs) != 5 or any(
        len(run) != 3 or len(set(run)) != 3 or any(value <= 0 for value in run)
        for run in top_three_runs
    ):
        return ("SELECTION_RUNS_INVALID",)
    shared = set(top_three_runs[0]).intersection(*top_three_runs[1:])
    return () if len(shared) >= 2 else ("TOP_THREE_OVERLAP_BELOW_TWO",)


def _parse_case(value: object) -> AnswerFirstGoldenCase:
    if not isinstance(value, dict) or set(value) != {
        "caseId", "category", "question", "allowedModes", "answerable"
    }:
        raise AnswerFirstEvalError("CASE_SHAPE_INVALID")
    modes = value["allowedModes"]
    if (
        not isinstance(value["caseId"], str)
        or not isinstance(value["category"], str)
        or not isinstance(value["question"], str)
        or not isinstance(value["answerable"], bool)
        or not isinstance(modes, list)
        or any(not isinstance(mode, str) for mode in modes)
    ):
        raise AnswerFirstEvalError("ANSWER_MODE_INVALID")
    return AnswerFirstGoldenCase(
        case_id=value["caseId"],
        category=value["category"],
        question=value["question"],
        allowed_modes=tuple(modes),  # type: ignore[arg-type]
        answerable=value["answerable"],
    )


def _is_deflection_only(answer: str) -> bool:
    asks_again = re.search(
        r"(?:다시\s*(?:입력|질문|시도)|더\s*(?:알려|입력)|범위를\s*좁혀|"
        r"지역을\s*(?:선택|알려)).{0,30}(?:주세요|보세요|필요합니다)",
        answer,
    )
    has_result = re.search(
        r"(?:확인했습니다|정리했습니다|없습니다|입니다|후보|거래|주소|직선거리)",
        answer,
    )
    return asks_again is not None and has_result is None
