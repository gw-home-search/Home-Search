from pathlib import Path

import pytest

from ai_service.property_chat import answer_first_eval
from ai_service.property_chat.answer_first_eval import (
    AnswerFirstGoldenCase,
    grade_answer_first_response,
    grade_agentic_selection_stability,
    compare_rollout_responses,
    load_answer_first_catalog,
    AnswerFirstEvalError,
)


def rollout_response(capability: str = "recent_trade_lookup") -> dict[str, object]:
    return {
        "conversationResolution": {
            "version": 1,
            "answerMode": "COMPLETE",
            "goals": [{"capability": capability, "status": "answered"}],
        },
        "terminalOutcome": {
            "version": 1, "status": "ANSWERED", "reason": "COMPLETED", "retryable": False,
        },
        "citations": [{"factIds": ["fact-1"]}],
        "fragments": [{"factIds": ["fact-1"]}],
    }


def _catalog_path() -> Path:
    return Path(answer_first_eval.__file__).with_name(
        "answer_first_golden_catalog.json"
    )


def test_answer_first_catalog_has_the_approved_agentic_120_case_distribution() -> None:
    cases = load_answer_first_catalog(_catalog_path())

    assert len(cases) == 120
    assert {
        category: sum(case.category == category for case in cases)
        for category in answer_first_eval.EXPECTED_CATEGORY_COUNTS
    } == answer_first_eval.EXPECTED_CATEGORY_COUNTS


def test_rollout_comparator_uses_only_contract_metadata_and_closure() -> None:
    legacy = rollout_response()
    assert compare_rollout_responses(legacy, rollout_response()) == ()

    graph = rollout_response("comparison")
    graph["fragments"] = [{"factIds": ["hallucinated-fact"]}]
    assert compare_rollout_responses(legacy, graph) == (
        "GOAL_SET_MISMATCH", "FACT_CITATION_CLOSURE_FAILED",
    )


def test_answer_first_grader_accepts_grounded_best_effort_result() -> None:
    case = AnswerFirstGoldenCase(
        "missing-period-example",
        "missing_conditions",
        "잠실엘스 최근 거래 알려줘",
        ("BEST_EFFORT",),
        True,
    )
    response = {
        "answer": "최근 1년을 기준으로 확인한 거래를 정리했습니다.",
        "conversationResolution": {
            "version": 1,
            "answerMode": "BEST_EFFORT",
            "goals": [{"capability": "recent_trade_lookup", "status": "degraded"}],
        },
        "evidenceSummary": {"factCount": 3},
    }

    assert grade_answer_first_response(case, response) == ()


def test_agentic_selection_stability_requires_two_shared_candidates_across_five_runs() -> None:
    assert grade_agentic_selection_stability((
        (10, 20, 30), (20, 10, 40), (10, 50, 20), (20, 60, 10), (10, 20, 70),
    )) == ()
    assert grade_agentic_selection_stability((
        (10, 20, 30), (10, 40, 50), (10, 60, 70), (10, 80, 90), (10, 11, 12),
    )) == ("TOP_THREE_OVERLAP_BELOW_TWO",)
    assert grade_agentic_selection_stability(((1, 1, 2),)) == (
        "SELECTION_RUNS_INVALID",
    )


@pytest.mark.parametrize("values", [
    ("BAD_ID", "exact_simple", "질문", ("COMPLETE",)),
    ("valid-id", "unknown", "질문", ("COMPLETE",)),
    ("valid-id", "exact_simple", " ", ("COMPLETE",)),
    ("valid-id", "exact_simple", "질문", ()),
])
def test_answer_first_case_metadata_fails_closed(values) -> None:
    with pytest.raises(AnswerFirstEvalError):
        AnswerFirstGoldenCase(*values, True)


def test_catalog_rejects_missing_corrupt_and_wrong_distribution(tmp_path) -> None:
    with pytest.raises(AnswerFirstEvalError):
        load_answer_first_catalog(tmp_path / "missing.json")
    corrupt = tmp_path / "corrupt.json"
    corrupt.write_text("not-json", encoding="utf-8")
    with pytest.raises(AnswerFirstEvalError):
        load_answer_first_catalog(corrupt)

    import json
    root = json.loads(_catalog_path().read_text(encoding="utf-8"))
    root["cases"][0]["category"] = "missing_conditions"
    distribution = tmp_path / "distribution.json"
    distribution.write_text(json.dumps(root, ensure_ascii=False), encoding="utf-8")
    with pytest.raises(AnswerFirstEvalError, match="CATEGORY_COVERAGE_INVALID"):
        load_answer_first_catalog(distribution)


def test_grader_reports_missing_goals_and_invalid_fact_count() -> None:
    case = AnswerFirstGoldenCase(
        "missing-goals", "exact_simple", "질문", ("COMPLETE",), True,
    )
    assert grade_answer_first_response(case, {
        "answer": "확인했습니다.",
        "conversationResolution": {"version": 1, "answerMode": "COMPLETE", "goals": []},
        "evidenceSummary": {"factCount": "one"},
    }) == ("GOALS_MISSING", "VERIFIED_FACT_MISSING")


def test_answer_first_grader_rejects_retry_only_copy_without_facts() -> None:
    case = AnswerFirstGoldenCase(
        "provider-failure-example",
        "provider_failure",
        "잠실엘스 주변 학교 알려줘",
        ("BEST_EFFORT", "PARTIAL"),
        True,
    )
    response = {
        "answer": "잠시 후 다시 시도해 주세요.",
        "conversationResolution": {
            "version": 1,
            "answerMode": "PARTIAL",
            "goals": [{"capability": "school_location", "status": "unavailable"}],
        },
        "evidenceSummary": {"factCount": 0},
    }

    assert grade_answer_first_response(case, response) == (
        "ANSWER_DEFLECTION_ONLY",
        "VERIFIED_FACT_MISSING",
    )


@pytest.mark.parametrize(
    "mutator",
    [
        lambda root: {**root, "version": 2},
        lambda root: {**root, "cases": root["cases"][:-1]},
        lambda root: {
            **root,
            "cases": [root["cases"][0], *root["cases"][1:79], root["cases"][0]],
        },
    ],
)
def test_answer_first_catalog_rejects_invalid_contract(tmp_path, mutator) -> None:
    source = _catalog_path().read_text(encoding="utf-8")
    import json

    path = tmp_path / "catalog.json"
    path.write_text(
        json.dumps(mutator(json.loads(source)), ensure_ascii=False),
        encoding="utf-8",
    )

    with pytest.raises(AnswerFirstEvalError):
        load_answer_first_catalog(path)


def test_answer_first_grader_reports_resolution_and_goal_contract_failures() -> None:
    case = AnswerFirstGoldenCase(
        "exact-invalid-response",
        "exact_simple",
        "잠실엘스 주소",
        ("COMPLETE",),
        False,
    )

    assert grade_answer_first_response(case, {"answer": "", "evidenceSummary": {}}) == (
        "ANSWER_EMPTY",
        "RESOLUTION_MISSING",
    )
    assert grade_answer_first_response(case, {
        "answer": "확인했습니다.",
        "conversationResolution": {
            "version": 1,
            "answerMode": "PARTIAL",
            "goals": [{"status": "invalid"}],
        },
    }) == ("ANSWER_MODE_UNEXPECTED", "GOAL_STATUS_INVALID")
