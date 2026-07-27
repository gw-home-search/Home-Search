from __future__ import annotations

from datetime import date

from ai_service.models import ChatbotQueryRequest

from .agentic import AgentRunResult


def build_agentic_response(
    *, request: ChatbotQueryRequest, request_id: str, result: AgentRunResult,
    requested_count: int, scope_label: str,
) -> dict[str, object]:
    decision = result.decision
    artifact = None
    if decision.rows:
        artifact = {
            "type": "recommendationTable", "version": 2,
            "artifactId": f"agentic-recommendation-{request_id}",
            "title": "AI 근거 비교 후보",
            "policyVersion": "agentic-recommendation-v1",
            "basis": {
                "selectionMode": "AGENTIC", "scopeType": "ADMIN_REGION",
                "scopeLabel": result.scope_label or scope_label,
                "requestedCount": requested_count,
                "criteriaOrder": [], "defaultPolicy": "BALANCED_V1",
            },
            "rows": [
                {
                    "order": order, "complexId": row.complex_id,
                    "complexName": row.complex_name, "role": row.role,
                    "summary": row.summary,
                    "strengths": [
                        {"text": text, "factIds": list(fact_ids)}
                        for text, fact_ids in row.strengths
                    ],
                    "tradeoffs": [
                        {"text": text, "factIds": list(fact_ids)}
                        for text, fact_ids in row.tradeoffs
                    ],
                    "metrics": dict(row.metrics), "factIds": list(row.fact_ids),
                }
                for order, row in enumerate(decision.rows, 1)
            ],
        }
    artifacts = [artifact] if artifact is not None else []
    selected_ids = [row.complex_id for row in decision.rows]
    today = date.today().isoformat()
    citations = [
        {"citationId": f"citation-{index}", "sourceId": "property.ai_read",
         "sourceName": "Home Search 검증 read model", "sourceUrl": None,
         "evidenceGrade": "A", "datasetVersion": "agentic-recommendation-v1",
         "dataAsOf": today, "observedAt": None, "factIds": [fact_id]}
        for index, fact_id in enumerate(dict.fromkeys(decision.fact_ids), 1)
    ] + [
        {"citationId": f"citation-web-{index}", "sourceId": "official.web",
         "sourceName": citation.title, "sourceUrl": citation.url,
         "evidenceGrade": "D", "datasetVersion": None, "dataAsOf": today,
         "observedAt": None, "factIds": [citation.fact_id]}
        for index, citation in enumerate(decision.web_citations, 1)
    ]
    success = result.route != "minimal_fallback"
    return {
        "success": success, "status": "success" if success else "partial_success",
        "question": request.question, "fragments": [], "result": {}, "message": "",
        "executionSummary": {"total": 1, "succeeded": int(success), "failed": int(not success)},
        "answer": decision.answer, "resolvedQuestion": request.question,
        "conversationResolution": {
            "version": 1, "answerMode": "COMPLETE" if success else "PARTIAL",
            "goals": [{"capability": "recommendation", "status": "answered" if success else "degraded"}],
        },
        "conversationMemoryPatch": ({
            "version": 2, "complexIds": selected_ids, "scopeKind": "RECOMMENDATION",
        } if 2 <= len(selected_ids) <= 5 else None),
        "uiActions": [], "uiArtifacts": artifacts, "uiSummary": None, "uiReport": None,
        "requestId": request_id, "citations": citations, "dataAsOf": today,
        "limitations": list(decision.limitations),
        "evidenceSummary": {
            "status": result.readiness, "capabilities": ["recommendation"],
            "factCount": len(set(decision.fact_ids)) + len(decision.web_citations),
            "citationCount": len(citations),
        },
        "agentExecution": {
            "policyVersion": "agentic-recommendation-v1", "route": result.route,
            "toolRounds": result.tool_rounds, "toolCalls": result.tool_calls,
            "webUsed": result.web_used,
        },
    }
