from __future__ import annotations

from copy import deepcopy
from typing import Literal, TypedDict


TerminalStatus = Literal["ANSWERED", "PARTIAL", "CLARIFICATION", "UNAVAILABLE"]
TerminalReason = Literal[
    "COMPLETED", "PARTIAL_EVIDENCE", "AMBIGUOUS_ENTITY",
    "INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE", "TEMPORARY_FAILURE",
]


class TerminalOutcome(TypedDict):
    version: Literal[1]
    status: TerminalStatus
    reason: TerminalReason
    retryable: bool


SAFE_FINAL_ANSWER = "일시적인 문제로 답변을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."


def terminal_outcome(
    status: TerminalStatus, reason: TerminalReason, *, retryable: bool = False,
) -> TerminalOutcome:
    return {"version": 1, "status": status, "reason": reason, "retryable": retryable}


def safe_final_response(request_id: str) -> dict[str, object]:
    response = unavailable_response(
        request_id,
        answer=SAFE_FINAL_ANSWER,
        reason="TEMPORARY_FAILURE",
        retryable=True,
    )
    response["limitations"] = []
    return response


def unavailable_response(
    request_id: str,
    *,
    answer: str,
    reason: Literal["INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE", "TEMPORARY_FAILURE"],
    retryable: bool = False,
) -> dict[str, object]:
    return {
        "success": False,
        "status": "failed",
        "fragments": [],
        "result": {},
        "message": "",
        "executionSummary": {"total": 0, "succeeded": 0, "failed": 0},
        "answer": answer,
        "resolvedQuestion": "",
        "conversationResolution": {
            "version": 1, "answerMode": "NO_RESULT", "goals": [],
            "assumptions": [], "omissions": [],
        },
        "conversationMemoryPatch": None,
        "uiActions": [],
        "uiArtifacts": [],
        "uiSummary": None,
        "uiReport": None,
        "requestId": request_id,
        "citations": [],
        "dataAsOf": None,
        "limitations": [answer] if reason != "TEMPORARY_FAILURE" else [],
        "evidenceSummary": {
            "status": "unavailable", "capabilities": [],
            "factCount": 0, "citationCount": 0,
        },
        "terminalOutcome": terminal_outcome(
            "UNAVAILABLE", reason, retryable=retryable
        ),
    }


def with_terminal_outcome(response: dict[str, object]) -> dict[str, object]:
    if isinstance(response.get("terminalOutcome"), dict):
        return response
    enriched = deepcopy(response)
    evidence = response.get("evidenceSummary")
    evidence_status = evidence.get("status") if isinstance(evidence, dict) else None
    if response.get("status") == "success" and evidence_status != "partial":
        outcome = terminal_outcome("ANSWERED", "COMPLETED")
    elif response.get("status") == "partial_success" or evidence_status == "partial":
        resolution = response.get("conversationResolution")
        assumptions = (
            resolution.get("assumptions") if isinstance(resolution, dict) else None
        )
        ambiguous_entity = isinstance(assumptions, list) and any(
            isinstance(item, dict)
            and item.get("code") == "AMBIGUOUS_COMPLEX_CANDIDATES"
            for item in assumptions
        )
        if ambiguous_entity:
            outcome = terminal_outcome("CLARIFICATION", "AMBIGUOUS_ENTITY")
            enriched["success"] = False
            enriched["status"] = "failed"
            enriched_resolution = enriched.get("conversationResolution")
            if isinstance(enriched_resolution, dict):
                enriched["conversationResolution"] = {
                    **enriched_resolution, "answerMode": "NO_RESULT"
                }
        else:
            outcome = terminal_outcome("PARTIAL", "PARTIAL_EVIDENCE")
    else:
        outcome = terminal_outcome("UNAVAILABLE", "INSUFFICIENT_EVIDENCE")
    enriched["terminalOutcome"] = outcome
    return enriched
