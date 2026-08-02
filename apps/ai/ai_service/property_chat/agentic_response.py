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
    official_only = not decision.rows and bool(decision.research_claims)
    verified_scope = result.scope_label or scope_label
    lead = (
        f"{verified_scope} 조건을 적용해 {len(decision.rows)}곳을 확인했으며 "
        f"먼저 볼 곳은 {decision.rows[0].complex_name}입니다."
        if decision.rows else decision.research_claims[0]
        if official_only else decision.answer
    )
    answer_parts = [lead]
    if decision.answer != lead:
        answer_parts.append(decision.answer)
    answer_parts.extend(
        claim for claim in decision.research_claims if claim != lead
    )
    answer = "\n\n".join(answer_parts)
    headline_fact_ids = list(dict.fromkeys((
        *decision.fact_ids,
        *((decision.rows[0].fact_ids) if decision.rows else ()),
        *((citation.fact_id for citation in decision.web_citations) if official_only else ()),
    )))
    interpretations = [
        {"key": f"AGENT_HIGHLIGHT_{index}", "label": row.role,
         "text": row.summary, "factIds": list(row.fact_ids)}
        for index, row in enumerate(decision.rows[:2], 1)
    ]
    ui_summary = ({
        "version": 1, "scopeNotice": None,
        "headline": {"text": lead, "factIds": headline_fact_ids},
        "criteria": [], "interpretations": interpretations,
        "followUp": (
            f"{decision.rows[0].complex_name}의 최근 실거래를 알려줘 · "
            "상위 후보의 학원과 역 접근성을 비교해줘"
            if decision.rows else None
        ),
        "fragmentSummaries": [],
    } if headline_fact_ids else None)
    ui_report = ({
        "version": 1, "kind": "RECOMMENDATION",
        "opening": {"text": lead, "factIds": headline_fact_ids},
        "basis": [], "primaryArtifactId": artifact["artifactId"] if artifact else None,
        "highlights": [
            {"complexId": row.complex_id, "title": f"{index}순위 · {row.complex_name}",
             "body": row.summary, "factIds": list(row.fact_ids)}
            for index, row in enumerate(decision.rows[:2], 1)
        ],
        "detailArtifactIds": [], "actionIds": [],
    } if ui_summary is not None and decision.rows else None)
    capability = "redevelopment_official_evidence" if official_only else "recommendation"
    policy_version = (
        "official-web-evidence-v1" if official_only else "agentic-recommendation-v1"
    )
    return {
        "success": success, "status": "success" if success else "partial_success",
        "question": request.question, "fragments": [], "result": {}, "message": "",
        "executionSummary": {"total": 1, "succeeded": int(success), "failed": int(not success)},
        "answer": answer, "resolvedQuestion": request.question,
        "conversationResolution": {
            "version": 1, "answerMode": "COMPLETE" if success else "PARTIAL",
            "goals": [{"capability": capability, "status": "answered" if success else "degraded"}],
        },
        "conversationMemoryPatch": ({
            "version": 2, "complexIds": selected_ids, "scopeKind": "RECOMMENDATION",
        } if 2 <= len(selected_ids) <= 5 else None),
        "uiActions": [], "uiArtifacts": artifacts, "uiSummary": ui_summary,
        "uiReport": ui_report,
        "requestId": request_id, "citations": citations, "dataAsOf": today,
        "limitations": list(decision.limitations),
        "evidenceSummary": {
            "status": result.readiness, "capabilities": [capability],
            "factCount": len(set(decision.fact_ids)) + len(decision.web_citations),
            "citationCount": len(citations),
        },
        "agentExecution": {
            "policyVersion": policy_version, "route": result.route,
            "toolRounds": result.tool_rounds, "toolCalls": result.tool_calls,
            "webUsed": result.web_used,
        },
    }
