from __future__ import annotations

from datetime import date

from ai_service.models import ChatbotQueryRequest

from .agentic import AgentRunResult
from .models import ComplexRecord, FocusComplexAction


def build_agentic_response(
    *, request: ChatbotQueryRequest, request_id: str, result: AgentRunResult,
    requested_count: int, scope_label: str,
    scope_type: str = "ADMIN_REGION", criteria_order: tuple[str, ...] = (),
    basis_notes: tuple[str, ...] = (),
    selected_complexes: tuple[ComplexRecord, ...] = (),
) -> dict[str, object]:
    decision = result.decision
    if scope_type not in {"ADMIN_REGION", "STATION_RADIUS"}:
        raise ValueError("agentic recommendation scope is invalid")
    records_by_id = {record.complex_id: record for record in selected_complexes}
    if decision.rows and set(records_by_id) != {row.complex_id for row in decision.rows}:
        raise ValueError("selected complexes do not match agent decision")
    actions = []
    for row in decision.rows:
        record = records_by_id[row.complex_id]
        if (
            record.display_name != row.complex_name or not record.marker_safe
            or record.parcel_id is None or record.latitude is None
            or record.longitude is None
        ):
            raise ValueError("selected complex is not marker safe")
        actions.append(FocusComplexAction(
            label=f"{record.display_name} 지도에서 보기",
            parcel_id=record.parcel_id, complex_id=record.complex_id,
            latitude=record.latitude, longitude=record.longitude,
            auto_run=False, fact_ids=(f"complex:{record.complex_id}",),
        ).to_public_dict(request_id))
    response_fact_ids = tuple(dict.fromkeys((
        *((result.scope_fact_id,) if result.scope_fact_id else ()),
        *decision.fact_ids,
        *(fact_id for row in decision.rows for fact_id in row.fact_ids),
        *(fact_id for row in decision.rows for _, ids in (*row.strengths, *row.tradeoffs)
          for fact_id in ids),
    )))
    artifact = None
    if decision.rows:
        artifact = {
            "type": "recommendationTable", "version": 2,
            "artifactId": f"agentic-recommendation-{request_id}",
            "title": "AI 근거 비교 후보",
            "policyVersion": "agentic-recommendation-v1",
            "basis": {
                "selectionMode": "AGENTIC", "scopeType": scope_type,
                "scopeLabel": result.scope_label or scope_label,
                "requestedCount": requested_count,
                "criteriaOrder": list(criteria_order), "defaultPolicy": "BALANCED_V1",
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
        {"citationId": f"citation-{index}", **_citation_source(fact_id),
         "evidenceGrade": "A", "datasetVersion": "agentic-recommendation-v1",
         "dataAsOf": today, "observedAt": None, "factIds": [fact_id]}
        for index, fact_id in enumerate(response_fact_ids, 1)
    ] + [
        {"citationId": f"citation-web-{index}", "sourceId": "official.web",
         "sourceName": citation.title, "sourceUrl": citation.url,
         "evidenceGrade": "D", "datasetVersion": None, "dataAsOf": today,
         "observedAt": None, "factIds": [citation.fact_id]}
        for index, citation in enumerate(decision.web_citations, 1)
    ]
    complete = result.route != "minimal_fallback" and result.readiness == "supported"
    success = True
    official_only = not decision.rows and bool(decision.research_claims)
    verified_scope = result.scope_label or scope_label
    lead = (
        f"{verified_scope}에서 검증 후보 중 {len(decision.rows)}곳을 확인했습니다."
        if decision.rows else decision.research_claims[0]
        if official_only else decision.answer
    )
    answer_parts = [decision.answer if decision.answer.startswith(lead) else lead]
    if decision.answer != lead and not decision.answer.startswith(lead):
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
        {"key": f"AGENT_HIGHLIGHT_{index}", "label": _role_title_ko(row.role),
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
        "opening": {"text": answer[:2_000], "factIds": headline_fact_ids},
        "basis": [
            {"text": text, "factIds": list(
                (result.scope_fact_id,) if result.scope_fact_id else response_fact_ids[:1]
            )}
            for text in (f"선정 범위: {verified_scope}", *basis_notes)
        ], "primaryArtifactId": artifact["artifactId"] if artifact else None,
        "highlights": [
            {"complexId": row.complex_id, "title": f"{index}순위 · {row.complex_name}",
             "body": row.summary, "factIds": list(row.fact_ids)}
            for index, row in enumerate(decision.rows[:2], 1)
        ],
        "detailArtifactIds": [],
        "actionIds": [str(action["actionId"]) for action in actions],
    } if ui_summary is not None and decision.rows else None)
    capability = "redevelopment_official_evidence" if official_only else "recommendation"
    policy_version = (
        "official-web-evidence-v1" if official_only else "agentic-recommendation-v1"
    )
    return {
        "success": success, "status": "success" if complete else "partial_success",
        "question": request.question, "fragments": [], "result": {}, "message": "",
        "executionSummary": {"total": 1, "succeeded": int(success), "failed": int(not success)},
        "answer": answer, "resolvedQuestion": request.question,
        "conversationResolution": {
            "version": 1, "answerMode": "COMPLETE" if complete else "PARTIAL",
            "goals": [{"capability": capability, "status": "answered" if complete else "degraded"}],
        },
        "conversationMemoryPatch": ({
            "version": 2, "complexIds": selected_ids, "scopeKind": "RECOMMENDATION",
        } if 2 <= len(selected_ids) <= 5 else None),
        "uiActions": actions, "uiArtifacts": artifacts, "uiSummary": ui_summary,
        "uiReport": ui_report,
        "requestId": request_id, "citations": citations, "dataAsOf": today,
        "limitations": list(dict.fromkeys((
            *decision.limitations,
            *(("명시한 공식 source 일부를 확인하지 못했습니다.",)
              if result.readiness == "partial"
              and result.route != "minimal_fallback" else ()),
        ))),
        "evidenceSummary": {
            "status": result.readiness, "capabilities": [capability],
            "factCount": len(response_fact_ids) + len(decision.web_citations),
            "citationCount": len(citations),
        },
        "agentExecution": {
            "policyVersion": policy_version, "route": result.route,
            "toolRounds": result.tool_rounds, "toolCalls": result.tool_calls,
            "webUsed": result.web_used,
        },
    }


def _citation_source(fact_id: str) -> dict[str, object]:
    if fact_id.startswith(("station-distance:",)):
        return {
            "sourceId": "transport.rail-station", "sourceName": "철도역 공식 위치",
            "sourceUrl": None,
        }
    if fact_id.startswith("school-observation:"):
        return {
            "sourceId": "school.official", "sourceName": "학교 공식 위치",
            "sourceUrl": None,
        }
    if fact_id.startswith("academy-observation:"):
        return {
            "sourceId": "place.sbiz-academy",
            "sourceName": "소상공인시장진흥공단 교육업소 위치", "sourceUrl": None,
        }
    if fact_id.startswith("shopping-observation:"):
        return {
            "sourceId": "retail.large-store", "sourceName": "대규모점포 공식 위치",
            "sourceUrl": None,
        }
    return {
        "sourceId": "property.ai_read", "sourceName": "Home Search 검증 read model",
        "sourceUrl": None,
    }


def _role_title_ko(role: str) -> str:
    return {
        "BALANCED": "균형", "TRADE_ACTIVITY": "거래 활동", "SCALE": "규모",
        "NEWER": "연식", "TRANSIT": "교통", "EDUCATION": "교육",
        "LIFESTYLE": "생활 인프라",
    }.get(role, role)
