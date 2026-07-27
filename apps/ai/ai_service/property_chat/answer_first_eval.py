from __future__ import annotations

import json
import re
from collections.abc import Awaitable, Callable
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
class RolloutFingerprint:
    goals: tuple[str, ...]
    answer_mode: str
    terminal_status: str
    terminal_reason: str
    fact_citation_closed: bool


@dataclass(frozen=True)
class RolloutEvaluationResult:
    case_id: str
    failures: tuple[str, ...]


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


def rollout_fingerprint(response: dict[str, object]) -> RolloutFingerprint:
    resolution = response.get("conversationResolution")
    goals: tuple[str, ...] = ()
    answer_mode = "UNKNOWN"
    if isinstance(resolution, dict):
        answer_mode = str(resolution.get("answerMode", "UNKNOWN"))
        raw_goals = resolution.get("goals")
        if isinstance(raw_goals, list):
            goals = tuple(
                str(goal["capability"])
                for goal in raw_goals
                if isinstance(goal, dict) and isinstance(goal.get("capability"), str)
            )
    terminal = response.get("terminalOutcome")
    terminal_status = str(terminal.get("status", "MISSING")) if isinstance(terminal, dict) else "MISSING"
    terminal_reason = str(terminal.get("reason", "MISSING")) if isinstance(terminal, dict) else "MISSING"
    citations = response.get("citations")
    citations_valid = isinstance(citations, list) and all(
        isinstance(citation, dict)
        and isinstance(citation.get("factIds"), list)
        and all(isinstance(fact_id, str) and fact_id for fact_id in citation["factIds"])
        for citation in citations
    )
    citation_fact_ids = {
        fact_id
        for citation in citations if citations_valid and isinstance(citation, dict)
        for fact_id in citation.get("factIds", [])
        if isinstance(fact_id, str)
    } if isinstance(citations, list) else set()
    fragments = response.get("fragments", [])
    evidence = response.get("evidenceSummary")
    fact_count = evidence.get("factCount") if isinstance(evidence, dict) else None
    citation_count = evidence.get("citationCount") if isinstance(evidence, dict) else None
    closed = (
        citations_valid
        and isinstance(evidence, dict)
        and isinstance(fact_count, int)
        and not isinstance(fact_count, bool)
        and fact_count >= 0
        and citation_count == len(citations)
        and fact_count == len(citation_fact_ids)
        and isinstance(fragments, list)
        and all(
        isinstance(fragment, dict)
        and isinstance(fragment.get("factIds", []), list)
        and set(fragment.get("factIds", [])).issubset(citation_fact_ids)
        for fragment in fragments
        )
    )
    return RolloutFingerprint(
        goals=goals,
        answer_mode=answer_mode,
        terminal_status=terminal_status,
        terminal_reason=terminal_reason,
        fact_citation_closed=closed,
    )


def compare_rollout_responses(
    legacy: dict[str, object], graph: dict[str, object],
) -> tuple[str, ...]:
    left = rollout_fingerprint(legacy)
    right = rollout_fingerprint(graph)
    failures: list[str] = []
    if left.goals != right.goals:
        failures.append("GOAL_SET_MISMATCH")
    if left.answer_mode != right.answer_mode:
        failures.append("ANSWER_MODE_MISMATCH")
    if (left.terminal_status, left.terminal_reason) != (
        right.terminal_status, right.terminal_reason
    ):
        failures.append("TERMINAL_OUTCOME_MISMATCH")
    if not left.fact_citation_closed or not right.fact_citation_closed:
        failures.append("FACT_CITATION_CLOSURE_FAILED")
    return tuple(failures)


async def evaluate_rollout_catalog(
    cases: tuple[AnswerFirstGoldenCase, ...],
    legacy_runner: Callable[[AnswerFirstGoldenCase], Awaitable[dict[str, object]]],
    graph_runner: Callable[[AnswerFirstGoldenCase], Awaitable[dict[str, object]]],
) -> tuple[RolloutEvaluationResult, ...]:
    expected_size = sum(EXPECTED_CATEGORY_COUNTS.values())
    if (
        len(cases) != expected_size
        or len({case.case_id for case in cases}) != expected_size
        or Counter(case.category for case in cases) != Counter(EXPECTED_CATEGORY_COUNTS)
    ):
        raise AnswerFirstEvalError("CATALOG_SIZE_INVALID")
    results: list[RolloutEvaluationResult] = []
    for case in cases:
        failures: list[str] = []
        legacy: dict[str, object] | None = None
        graph: dict[str, object] | None = None
        try:
            legacy = await legacy_runner(case)
        except Exception:
            failures.append("LEGACY_RUNNER_FAILED")
        try:
            graph = await graph_runner(case)
        except Exception:
            failures.append("GRAPH_RUNNER_FAILED")
        if legacy is not None:
            failures.extend(_rollout_response_failures("LEGACY", legacy))
        if graph is not None:
            failures.extend(_rollout_response_failures("GRAPH", graph))
        if legacy is not None and graph is not None:
            failures.extend(compare_rollout_responses(legacy, graph))
        results.append(RolloutEvaluationResult(case.case_id, tuple(dict.fromkeys(failures))))
    return tuple(results)


def _rollout_response_failures(
    prefix: str, response: dict[str, object],
) -> tuple[str, ...]:
    fingerprint = rollout_fingerprint(response)
    failures: list[str] = []
    answer = response.get("answer")
    if not isinstance(answer, str) or not answer.strip():
        failures.append(f"{prefix}_ANSWER_EMPTY")
    if fingerprint.answer_mode == "UNKNOWN":
        failures.append(f"{prefix}_ANSWER_MODE_MISSING")
    if fingerprint.terminal_status == "MISSING" or fingerprint.terminal_reason == "MISSING":
        failures.append(f"{prefix}_TERMINAL_OUTCOME_MISSING")
    if not fingerprint.fact_citation_closed:
        failures.append(f"{prefix}_FACT_CITATION_CLOSURE_FAILED")
    return tuple(failures)


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
