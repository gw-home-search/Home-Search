from pathlib import Path

import pytest

from ai_service.property_chat import answer_first_eval
from ai_service.property_chat.answer_first_eval import (
    AnswerFirstGoldenCase,
    grade_answer_first_response,
    load_answer_first_catalog,
    AnswerFirstEvalError,
)


def _catalog_path() -> Path:
    return Path(answer_first_eval.__file__).with_name(
        "answer_first_golden_catalog.json"
    )


def test_answer_first_catalog_has_the_approved_eighty_case_distribution() -> None:
    cases = load_answer_first_catalog(_catalog_path())

    assert len(cases) == 80
    assert {
        category: sum(case.category == category for case in cases)
        for category in answer_first_eval.EXPECTED_CATEGORY_COUNTS
    } == answer_first_eval.EXPECTED_CATEGORY_COUNTS


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
