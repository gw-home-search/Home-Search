from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from ai_service.auth import AuthenticatedUser, get_authenticator
from ai_service.chat import ConfiguredChatbotEngine, get_chatbot_engine
from ai_service.main import app
from ai_service.property_chat import answer_first_eval
from ai_service.property_chat.answer_first_eval import (
    AnswerFirstGoldenCase,
    grade_answer_first_response,
    grade_agentic_selection_stability,
    compare_rollout_responses,
    load_answer_first_catalog,
    AnswerFirstEvalError,
    assess_production_coverage,
    evaluate_rollout_catalog,
    rollout_fingerprint,
)


def rollout_response(capability: str = "recent_trade_lookup") -> dict[str, object]:
    return {
        "answer": "검증된 답변입니다.",
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
        "evidenceSummary": {
            "status": "supported", "capabilities": [capability],
            "factCount": 1, "citationCount": 1,
        },
    }


def rubric_complete_response(case: AnswerFirstGoldenCase) -> dict[str, object]:
    response = rollout_response()
    response["answer"] = " ".join(("검증된 답변입니다.", *case.required_answer_terms))
    response["conversationResolution"]["answerMode"] = case.allowed_modes[0]
    response["conversationResolution"]["goals"] = [
        {"capability": capability, "status": "answered"}
        for capability in (
            "complex_identity",
            "recent_trade_lookup",
            "price_trend",
            "comparison",
            "recommendation",
            "school_location",
            "academy_lookup",
            "rail_station_lookup",
            "retail_location",
            "childcare_lookup",
        )
    ]
    response["terminalOutcome"] = {
        "version": 1,
        "status": case.expected_terminal_status or "ANSWERED",
        "reason": case.expected_terminal_reason or "COMPLETED",
        "retryable": False,
    }
    return response


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
    assert all(len(case.allowed_modes) == 1 for case in cases)
    assert {case.cohort for case in cases} == {"PRODUCTION", "EXPERIMENTAL"}
    assert {case.scenario for case in cases}.issuperset({
        "NORMAL_FULL", "DEFAULTED_INPUT", "VERIFIED_ZERO",
        "OPTIONAL_SOURCE_UNAVAILABLE", "LLM_ALL_TIMEOUT",
        "AMBIGUOUS_ENTITY", "OFFICIAL_WEB_DISABLED",
    })


def test_http_catalog_grader_accepts_missing_trend_area_condition_request() -> None:
    case = next(
        case
        for case in load_answer_first_catalog(_catalog_path())
        if case.case_id == "missing-conditions-02"
    )

    class AcceptingAuthenticator:
        def authenticate(self, _authorization: str | None) -> AuthenticatedUser:
            return AuthenticatedUser(user_id=42)

    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = ConfiguredChatbotEngine
    try:
        response = TestClient(app).post(
            "/api/v1/chatbot/query",
            headers={"Authorization": "Bearer test-token"},
            json={"question": case.question},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert grade_answer_first_response(case, response.json()) == ()


def test_rollout_comparator_uses_only_contract_metadata_and_closure() -> None:
    legacy = rollout_response()
    assert compare_rollout_responses(legacy, rollout_response()) == ()

    graph = rollout_response("comparison")
    graph["fragments"] = [{"factIds": ["hallucinated-fact"]}]
    assert compare_rollout_responses(legacy, graph) == (
        "GOAL_SET_MISMATCH", "FACT_CITATION_CLOSURE_FAILED",
    )


def test_rollout_closure_rejects_fact_count_without_citations_for_single_response() -> None:
    response = rollout_response()
    response["citations"] = []
    response["fragments"] = []
    response["evidenceSummary"] = {
        "status": "supported", "capabilities": ["recent_trade_lookup"],
        "factCount": 1, "citationCount": 0,
    }

    assert rollout_fingerprint(response).fact_citation_closed is False


def test_rollout_evaluator_executes_both_engines_for_all_120_cases() -> None:
    cases = load_answer_first_catalog(_catalog_path())
    legacy_calls: list[str] = []
    graph_calls: list[str] = []

    async def legacy(case: AnswerFirstGoldenCase) -> dict[str, object]:
        legacy_calls.append(case.case_id)
        return rubric_complete_response(case)

    async def graph(case: AnswerFirstGoldenCase) -> dict[str, object]:
        graph_calls.append(case.case_id)
        return rubric_complete_response(case)

    import asyncio
    results = asyncio.run(evaluate_rollout_catalog(cases, legacy, graph))

    assert len(results) == 120
    assert legacy_calls == [case.case_id for case in cases]
    assert graph_calls == [case.case_id for case in cases]
    assert all(
        not any(
            failure.endswith("RUNNER_FAILED") or failure.endswith("MISMATCH")
            for failure in result.failures
        )
        for result in results
    ), [result for result in results if result.failures]
    assert not hasattr(results[0], "legacy_response")
    assert not hasattr(results[0], "graph_response")


def test_rollout_evaluator_applies_each_catalog_question_quality_rubric() -> None:
    cases = load_answer_first_catalog(_catalog_path())

    async def generic_trade(_case: AnswerFirstGoldenCase) -> dict[str, object]:
        return rollout_response("recent_trade_lookup")

    import asyncio
    results = asyncio.run(evaluate_rollout_catalog(cases, generic_trade, generic_trade))
    identity_case = next(
        result for result in results if result.case_id == "exact-simple-03"
    )

    assert "LEGACY_REQUESTED_CAPABILITY_MISSING" in identity_case.failures
    assert "GRAPH_REQUESTED_CAPABILITY_MISSING" in identity_case.failures


def test_rollout_evaluator_continues_after_runner_failure_without_storing_error() -> None:
    cases = load_answer_first_catalog(_catalog_path())
    legacy_calls: list[str] = []
    graph_calls: list[str] = []

    async def legacy(case: AnswerFirstGoldenCase) -> dict[str, object]:
        legacy_calls.append(case.case_id)
        if case == cases[0]:
            raise RuntimeError("must-not-leak")
        return rollout_response()

    async def graph(case: AnswerFirstGoldenCase) -> dict[str, object]:
        graph_calls.append(case.case_id)
        return rubric_complete_response(case)

    import asyncio
    results = asyncio.run(evaluate_rollout_catalog(cases, legacy, graph))

    assert len(legacy_calls) == 120
    assert len(graph_calls) == 120
    assert results[0].failures == ("LEGACY_RUNNER_FAILED",)
    assert "must-not-leak" not in repr(results)


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
        "citations": [{"factIds": ["fact-1", "fact-2", "fact-3"]}],
        "fragments": [],
        "evidenceSummary": {"factCount": 3, "citationCount": 1},
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
        "FACT_CITATION_CLOSURE_FAILED",
    )


def test_answer_first_grader_rejects_missing_requested_goal_and_open_citations() -> None:
    case = AnswerFirstGoldenCase(
        "trade-goal-mismatch",
        "exact_simple",
        "잠실엘스 최근 실거래 3건을 알려줘",
        ("COMPLETE",),
        True,
    )
    response = rollout_response("complex_identity")
    response["citations"] = []
    response["fragments"] = []

    assert grade_answer_first_response(case, response) == (
        "REQUESTED_CAPABILITY_MISSING",
        "FACT_CITATION_CLOSURE_FAILED",
    )


def test_production_coverage_separates_inactive_and_missing_condition_cases() -> None:
    cases = load_answer_first_catalog(_catalog_path())
    by_id = {case.case_id: case for case in cases}
    enabled_property = frozenset({
        "complex_identity", "recent_trade_lookup", "price_trend",
        "recommendation", "comparison",
    })
    enabled_reference = frozenset({
        "academy_lookup", "rail_station_lookup", "school_location", "retail_location",
    })

    trade = assess_production_coverage(
        by_id["exact-simple-01"], enabled_property, enabled_reference
    )
    childcare = assess_production_coverage(
        by_id["exact-simple-08"], enabled_property, enabled_reference
    )
    missing_area = assess_production_coverage(
        by_id["missing-conditions-02"], enabled_property, enabled_reference
    )

    assert trade.production_answerable is True
    assert trade.blockers == ()
    assert childcare.production_answerable is False
    assert childcare.blockers == ("CAPABILITY_INACTIVE:childcare_lookup",)
    assert missing_area.production_answerable is False
    assert missing_area.blockers == ("PRICE_TREND_AREA_REQUIRED",)


@pytest.mark.parametrize(
    "mutator",
    [
        lambda root: {**root, "version": 1},
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
