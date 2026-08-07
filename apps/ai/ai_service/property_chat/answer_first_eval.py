from __future__ import annotations

import json
import re
from collections.abc import Awaitable, Callable
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

AnswerMode = Literal["COMPLETE", "BEST_EFFORT", "PARTIAL", "NO_RESULT"]
CatalogCohort = Literal["PRODUCTION", "EXPERIMENTAL"]

_SCENARIOS = frozenset({
    "NORMAL_FULL", "DEFAULTED_INPUT", "VERIFIED_ZERO",
    "OPTIONAL_SOURCE_UNAVAILABLE", "OPTIONAL_SOURCE_TIMEOUT",
    "LLM_PRIMARY_TIMEOUT", "LLM_ALL_TIMEOUT", "LLM_MALFORMED",
    "AMBIGUOUS_ENTITY", "NO_MATCH", "CORE_DB_UNAVAILABLE",
    "OFFICIAL_WEB_DISABLED",
})
_LATENCY_CLASSES = frozenset({
    "DIRECT_PROPERTY", "COMPLEX_OVERVIEW", "REFERENCE_COMPOUND", "TREND",
    "COMPARISON", "RECOMMENDATION", "DETERMINISTIC_FALLBACK",
    "AMBIGUOUS_CLARIFICATION",
})
_ENTITY_OUTCOMES = frozenset({"RESOLVED", "AMBIGUOUS", "NO_MATCH", "NOT_APPLICABLE"})

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
class ProductionCoverageAssessment:
    case_id: str
    production_answerable: bool
    blockers: tuple[str, ...]


@dataclass(frozen=True)
class AnswerFirstGoldenCase:
    case_id: str
    category: str
    question: str
    allowed_modes: tuple[AnswerMode, ...]
    answerable: bool
    cohort: CatalogCohort = "PRODUCTION"
    scenario: str = "NORMAL_FULL"
    selected_complex_id: int | None = None
    selected_parcel_id: int | None = None
    memory: tuple[dict[str, object], ...] = ()
    expected_terminal_status: str | None = None
    expected_terminal_reason: str | None = None
    expected_goal_status: tuple[tuple[str, str], ...] = ()
    entity_outcome: str = "NOT_APPLICABLE"
    required_capabilities: tuple[str, ...] = ()
    forbidden_capabilities: tuple[str, ...] = ()
    required_answer_terms: tuple[str, ...] = ()
    required_artifact_types: tuple[str, ...] = ()
    required_action_types: tuple[str, ...] = ()
    forbidden_claims: tuple[str, ...] = ()
    latency_class: str = "DIRECT_PROPERTY"

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
        if self.cohort not in {"PRODUCTION", "EXPERIMENTAL"}:
            raise AnswerFirstEvalError("COHORT_INVALID")
        if self.scenario not in _SCENARIOS:
            raise AnswerFirstEvalError("SCENARIO_INVALID")
        if self.latency_class not in _LATENCY_CLASSES:
            raise AnswerFirstEvalError("LATENCY_CLASS_INVALID")
        if self.entity_outcome not in _ENTITY_OUTCOMES:
            raise AnswerFirstEvalError("ENTITY_OUTCOME_INVALID")
        if (self.selected_complex_id is None) != (self.selected_parcel_id is None):
            raise AnswerFirstEvalError("SELECTED_COMPLEX_INVALID")
        if self.selected_complex_id is not None and (
            self.selected_complex_id <= 0 or self.selected_parcel_id <= 0  # type: ignore[operator]
        ):
            raise AnswerFirstEvalError("SELECTED_COMPLEX_INVALID")
        if len(self.allowed_modes) != len(set(self.allowed_modes)):
            raise AnswerFirstEvalError("ANSWER_MODE_INVALID")


def load_answer_first_catalog(path: Path) -> tuple[AnswerFirstGoldenCase, ...]:
    try:
        if not path.is_file() or path.stat().st_size > 128 * 1024:
            raise AnswerFirstEvalError("CATALOG_INVALID")
        root = json.loads(path.read_text(encoding="utf-8"))
        raw_cases = root["cases"]
        if root.get("version") != 2 or not isinstance(raw_cases, list):
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
        else:
            actual_capabilities = {
                goal["capability"]
                for goal in goals
                if isinstance(goal.get("capability"), str)
            }
            required_capabilities = (
                set(case.required_capabilities)
                if case.required_capabilities
                else _required_capabilities(case.question)
            )
            if not required_capabilities.issubset(
                actual_capabilities
            ):
                failures.append("REQUESTED_CAPABILITY_MISSING")
            if set(case.forbidden_capabilities).intersection(actual_capabilities):
                failures.append("FORBIDDEN_CAPABILITY_PRESENT")
            actual_status = {
                str(goal.get("capability")): str(goal.get("status"))
                for goal in goals if isinstance(goal, dict)
            }
            if any(actual_status.get(capability) != status for capability, status in case.expected_goal_status):
                failures.append("GOAL_STATUS_UNEXPECTED")

    evidence = response.get("evidenceSummary")
    terminal = response.get("terminalOutcome")
    condition_required = (
        isinstance(terminal, dict)
        and terminal.get("status") == "UNAVAILABLE"
        and terminal.get("reason") == "INSUFFICIENT_EVIDENCE"
    )
    if case.answerable and not condition_required and (
        not isinstance(evidence, dict)
        or not isinstance(evidence.get("factCount"), int)
        or evidence["factCount"] < 1
    ):
        failures.append("VERIFIED_FACT_MISSING")
    fact_count = evidence.get("factCount") if isinstance(evidence, dict) else None
    if (
        isinstance(fact_count, int)
        and not isinstance(fact_count, bool)
        and not rollout_fingerprint(response).fact_citation_closed
    ):
        failures.append("FACT_CITATION_CLOSURE_FAILED")
    if isinstance(terminal, dict):
        if (
            case.expected_terminal_status is not None
            and terminal.get("status") != case.expected_terminal_status
        ):
            failures.append("TERMINAL_STATUS_UNEXPECTED")
        if (
            case.expected_terminal_reason is not None
            and terminal.get("reason") != case.expected_terminal_reason
        ):
            failures.append("TERMINAL_REASON_UNEXPECTED")
    failures.extend(_semantic_response_failures(case, response))
    return tuple(failures)


def _semantic_response_failures(
    case: AnswerFirstGoldenCase, response: dict[str, object],
) -> tuple[str, ...]:
    failures: list[str] = []
    answer = response.get("answer")
    answer_text = answer if isinstance(answer, str) else ""
    semantic_surface = json.dumps(
        {
            "answer": answer_text,
            "artifacts": response.get("uiArtifacts"),
            "actions": response.get("uiActions"),
            "summary": response.get("uiSummary"),
            "report": response.get("uiReport"),
            "limitations": response.get("limitations"),
        },
        ensure_ascii=False,
        sort_keys=True,
    )
    normalized_answer = re.sub(r"\s+", " ", answer_text).strip().casefold()
    if any(term.casefold() not in semantic_surface.casefold() for term in case.required_answer_terms):
        failures.append("REQUIRED_MEANING_MISSING")
    if any(claim.casefold() in normalized_answer for claim in case.forbidden_claims):
        failures.append("FORBIDDEN_CLAIM_PRESENT")
    sentences = [
        re.sub(r"\s+", " ", sentence).strip().casefold()
        for sentence in re.split(r"(?:[.!?。！？]+|\n+)", answer_text)
        if len(re.sub(r"\s+", " ", sentence).strip()) >= 8
    ]
    if len(sentences) != len(set(sentences)):
        failures.append("ANSWER_SENTENCE_DUPLICATED")
    midpoint = len(normalized_answer) // 2
    if (
        len(normalized_answer) >= 16
        and len(normalized_answer) % 2 == 0
        and normalized_answer[:midpoint] == normalized_answer[midpoint:]
    ):
        failures.append("ANSWER_HALF_DUPLICATED")
    if len(json.dumps(response, ensure_ascii=False).encode("utf-8")) > 128 * 1024:
        failures.append("RESPONSE_BYTES_EXCEEDED")
    artifacts = response.get("uiArtifacts")
    artifact_items = artifacts if isinstance(artifacts, list) else []
    artifact_types = {
        str(item.get("type")) for item in artifact_items if isinstance(item, dict)
    }
    if not set(case.required_artifact_types).issubset(artifact_types):
        failures.append("REQUIRED_ARTIFACT_MISSING")
    if isinstance(artifacts, list) and len(
        json.dumps(artifacts, ensure_ascii=False).encode("utf-8")
    ) > 64 * 1024:
        failures.append("ARTIFACT_BYTES_EXCEEDED")
    actions = response.get("uiActions")
    action_items = actions if isinstance(actions, list) else []
    action_types = {
        str(item.get("type")) for item in action_items if isinstance(item, dict)
    }
    if not set(case.required_action_types).issubset(action_types):
        failures.append("REQUIRED_ACTION_MISSING")
    if isinstance(actions, list) and sum(
        item.get("autoRun") is True for item in actions if isinstance(item, dict)
    ) > 1:
        failures.append("AUTO_RUN_ACTION_LIMIT_EXCEEDED")
    terminal = response.get("terminalOutcome")
    terminal_status = terminal.get("status") if isinstance(terminal, dict) else None
    terminal_reason = terminal.get("reason") if isinstance(terminal, dict) else None
    if (
        case.answerable
        and (terminal_status, terminal_reason)
        == ("UNAVAILABLE", "INSUFFICIENT_EVIDENCE")
        and case.scenario not in {
            "DEFAULTED_INPUT", "VERIFIED_ZERO", "NO_MATCH",
            "OFFICIAL_WEB_DISABLED",
        }
    ):
        failures.append("ANSWERABLE_CASE_UNAVAILABLE")
    if case.entity_outcome == "AMBIGUOUS" and (
        terminal_status, terminal_reason
    ) != ("CLARIFICATION", "AMBIGUOUS_ENTITY"):
        failures.append("ENTITY_AMBIGUITY_NOT_PRESERVED")
    elif case.entity_outcome == "NO_MATCH" and terminal_status != "UNAVAILABLE":
        failures.append("ENTITY_NO_MATCH_UNEXPECTED")
    elif case.entity_outcome == "RESOLVED" and terminal_reason == "AMBIGUOUS_ENTITY":
        failures.append("ENTITY_RESOLUTION_UNEXPECTED")
    return tuple(failures)


def _required_capabilities(question: str) -> set[str]:
    required: set[str] = set()
    patterns = (
        ("recent_trade_lookup", r"(?:실거래|최근\s*거래|거래\s*(?:내역|결과))"),
        ("price_trend", r"(?:가격\s*(?:흐름|추이)|시세\s*추이|월별|거래량)"),
        ("school_location", r"(?:초등학교|중학교|고등학교|주변\s*학교)"),
        ("academy_lookup", r"(?:학원|교습소)"),
        ("rail_station_lookup", r"(?:철도|지하철|가까운\s*역|역[·\s-]*노선|역세권)"),
        ("retail_location", r"(?:대규모점포|대형마트|백화점|쇼핑시설|쇼핑센터|복합몰)"),
        ("childcare_lookup", r"(?:어린이집|유치원)"),
        ("complex_identity", r"(?:주소|기본정보|단지\s*정보|지도에서\s*보여)"),
    )
    for capability, pattern in patterns:
        if re.search(pattern, question):
            required.add(capability)
    if re.search(r"(?:추천|골라\s*줘)", question):
        return {"recommendation"}
    if re.search(r"(?:비교|차이|대조)", question):
        return {"comparison"}
    return required


def assess_production_coverage(
    case: AnswerFirstGoldenCase,
    enabled_property_capabilities: frozenset[str],
    enabled_reference_capabilities: frozenset[str],
) -> ProductionCoverageAssessment:
    required = _required_capabilities(case.question)
    enabled = enabled_property_capabilities | enabled_reference_capabilities
    blockers: list[str] = []
    if "price_trend" in required and re.search(
        r"(?:전용\s*)?[0-9]+(?:\.[0-9]+)?\s*(?:㎡|m2|제곱미터)",
        case.question,
        re.IGNORECASE,
    ) is None:
        blockers.append("PRICE_TREND_AREA_REQUIRED")
    blockers.extend(
        f"CAPABILITY_INACTIVE:{capability}"
        for capability in sorted(required - enabled)
    )
    if not case.answerable:
        blockers.append("CATALOG_NOT_ANSWERABLE")
    return ProductionCoverageAssessment(
        case_id=case.case_id,
        production_answerable=not blockers,
        blockers=tuple(blockers),
    )


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
            failures.extend(
                f"LEGACY_{failure}"
                for failure in grade_answer_first_response(case, legacy)
            )
        if graph is not None:
            failures.extend(_rollout_response_failures("GRAPH", graph))
            failures.extend(
                f"GRAPH_{failure}"
                for failure in grade_answer_first_response(case, graph)
            )
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
    expected_keys = {
        "caseId", "category", "cohort", "scenario", "question", "request",
        "expectedMode", "expectedTerminal", "expectedGoalStatus", "entityOutcome",
        "requiredCapabilities", "forbiddenCapabilities", "requiredAnswerTerms",
        "requiredArtifactTypes", "requiredActionTypes", "forbiddenClaims",
        "latencyClass", "answerable",
    }
    if not isinstance(value, dict) or set(value) != expected_keys:
        raise AnswerFirstEvalError("CASE_SHAPE_INVALID")
    request = value["request"]
    terminal = value["expectedTerminal"]
    goal_status = value["expectedGoalStatus"]
    list_fields = (
        "requiredCapabilities", "forbiddenCapabilities", "requiredAnswerTerms",
        "requiredArtifactTypes", "requiredActionTypes", "forbiddenClaims",
    )
    if (
        not isinstance(value["caseId"], str)
        or not isinstance(value["category"], str)
        or not isinstance(value["question"], str)
        or not isinstance(value["answerable"], bool)
        or not isinstance(value["cohort"], str)
        or not isinstance(value["scenario"], str)
        or not isinstance(value["expectedMode"], str)
        or not isinstance(value["entityOutcome"], str)
        or not isinstance(value["latencyClass"], str)
        or not isinstance(request, dict)
        or set(request) != {"selectedComplex", "memory"}
        or not isinstance(request["memory"], list)
        or any(not isinstance(item, dict) for item in request["memory"])
        or not isinstance(terminal, dict)
        or set(terminal) != {"status", "reason"}
        or not all(isinstance(terminal[key], str) for key in terminal)
        or not isinstance(goal_status, dict)
        or any(not isinstance(key, str) or not isinstance(status, str) for key, status in goal_status.items())
        or any(not isinstance(value[field], list) or any(not isinstance(item, str) for item in value[field]) for field in list_fields)
    ):
        raise AnswerFirstEvalError("ANSWER_MODE_INVALID")
    selected = request["selectedComplex"]
    if selected is not None and (
        not isinstance(selected, dict)
        or set(selected) != {"complexId", "parcelId"}
        or any(isinstance(selected.get(key), bool) or not isinstance(selected.get(key), int) for key in ("complexId", "parcelId"))
    ):
        raise AnswerFirstEvalError("SELECTED_COMPLEX_INVALID")
    return AnswerFirstGoldenCase(
        case_id=value["caseId"],
        category=value["category"],
        question=value["question"],
        allowed_modes=(value["expectedMode"],),  # type: ignore[arg-type]
        answerable=value["answerable"],
        cohort=value["cohort"],  # type: ignore[arg-type]
        scenario=value["scenario"],
        selected_complex_id=selected["complexId"] if selected else None,
        selected_parcel_id=selected["parcelId"] if selected else None,
        memory=tuple(request["memory"]),
        expected_terminal_status=terminal["status"],
        expected_terminal_reason=terminal["reason"],
        expected_goal_status=tuple(sorted(goal_status.items())),
        entity_outcome=value["entityOutcome"],
        required_capabilities=tuple(value["requiredCapabilities"]),
        forbidden_capabilities=tuple(value["forbiddenCapabilities"]),
        required_answer_terms=tuple(value["requiredAnswerTerms"]),
        required_artifact_types=tuple(value["requiredArtifactTypes"]),
        required_action_types=tuple(value["requiredActionTypes"]),
        forbidden_claims=tuple(value["forbiddenClaims"]),
        latency_class=value["latencyClass"],
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
