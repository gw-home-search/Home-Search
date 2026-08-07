from __future__ import annotations

import json
from pathlib import Path

from ai_service.terminal_response import safe_final_response, with_terminal_outcome


def test_safe_final_matches_cross_service_fixture() -> None:
    fixture_path = Path(__file__).parents[3] / "docs/fixtures/chatbot-safe-final-v1.json"
    expected = json.loads(fixture_path.read_text())
    expected["requestId"] = "request-1"

    assert safe_final_response("request-1") == expected


def test_single_ambiguous_result_maps_to_clarification_contract() -> None:
    response = with_terminal_outcome({
        "success": True,
        "status": "partial_success",
        "executionSummary": {"total": 1, "succeeded": 1, "failed": 0},
        "limitations": ["지역이나 주소를 더 알려주세요."],
        "evidenceSummary": {"status": "partial"},
        "conversationResolution": {
            "version": 1,
            "answerMode": "PARTIAL",
            "assumptions": [{
                "code": "AMBIGUOUS_COMPLEX_CANDIDATES",
                "text": "확인 가능한 대체 근거를 함께 사용했습니다.",
            }],
        },
    })

    assert response["success"] is False
    assert response["status"] == "failed"
    assert response["conversationResolution"]["answerMode"] == "NO_RESULT"
    assert response["terminalOutcome"] == {
        "version": 1,
        "status": "CLARIFICATION",
        "reason": "AMBIGUOUS_ENTITY",
        "retryable": False,
    }
