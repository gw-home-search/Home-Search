from __future__ import annotations

import asyncio
from datetime import date
from typing import Protocol

from ai_service.models import ChatbotQueryRequest

from .engine import _verify_recommendation_plan, validate_draft
from .models import DraftAnswer, EvidenceFact, FactClaim, QueryPlan, QueryPlanBundle

CASE_ID = "criteria-recommendation-academy-transit"
QUESTION = (
    "영등포구 500세대 이상 중 학원 접근성을 먼저, "
    "교통을 그다음으로 후보를 알려줘"
)


class CriteriaActivationError(ValueError):
    pass


class ActivationLanguageModel(Protocol):
    async def plan_query(self, request: ChatbotQueryRequest) -> object: ...

    async def draft_answer(
        self, *, facts: list[EvidenceFact], limitations: list[str], question: str
    ) -> DraftAnswer: ...


async def run_activation_case(model: ActivationLanguageModel) -> dict[str, object]:
    request = ChatbotQueryRequest(question=QUESTION)
    try:
        proposed = await model.plan_query(request)
    except Exception:
        raise CriteriaActivationError("PLAN_STAGE_FAILED") from None
    if isinstance(proposed, QueryPlanBundle) and len(proposed.fragments) == 1:
        proposed = proposed.fragments[0]
    if not isinstance(proposed, QueryPlan):
        raise CriteriaActivationError("PLAN_POLICY_FAILED")
    plan = _verify_recommendation_plan(proposed, QUESTION)
    if (
        plan.capability != "recommendation"
        or plan.recommendation_mode != "CRITERIA"
        or plan.region_name not in {"영등포", "영등포구"}
        or plan.minimum_unit_count != 500
        or plan.recommendation_criteria != ("ACADEMY", "TRANSIT")
        or plan.criteria_order != ("ACADEMY", "TRANSIT")
        or plan.station_name is not None
        or plan.clarification_code is not None
    ):
        raise CriteriaActivationError("PLAN_POLICY_FAILED")

    facts = _activation_facts()
    try:
        draft = await model.draft_answer(
            facts=facts,
            limitations=[
                "학원 접근성은 단지 중심 직선거리 800m 내 위치 관찰값입니다.",
                "교통은 최근접 철도역 직선거리 관찰값입니다.",
            ],
            question=QUESTION,
        )
    except Exception:
        raise CriteriaActivationError("DRAFT_STAGE_FAILED") from None
    try:
        used = validate_draft(
            draft,
            facts,
            "supported",
            enforce_recommendation_policy=True,
        )
    except Exception:
        raise CriteriaActivationError("DRAFT_GROUNDING_FAILED") from None
    return {
        "caseId": CASE_ID,
        "capability": plan.capability,
        "mode": plan.recommendation_mode,
        "criteria": list(plan.criteria_order),
        "factCount": len(used),
    }


def _activation_facts() -> list[EvidenceFact]:
    observed_on = date(2026, 7, 20)
    return [
        EvidenceFact(
            fact_id="activation-scope-yeongdeungpo-500",
            claims=(FactClaim("영등포구", "TEXT"), FactClaim("500", "COUNT")),
            data_as_of=observed_on,
            payload={"regionName": "영등포구", "minimumUnitCount": 500},
        ),
        EvidenceFact(
            fact_id="activation-complex-501",
            claims=(FactClaim("검증 후보 단지", "TEXT"), FactClaim("1200", "COUNT")),
            data_as_of=observed_on,
            payload={"complexId": 501, "unitCount": 1200},
        ),
        EvidenceFact(
            fact_id="activation-academy-501",
            claims=(
                FactClaim("12", "COUNT"),
                FactClaim("120", "METERS"),
                FactClaim("800", "METERS"),
            ),
            data_as_of=observed_on,
            payload={"matchedCount": 12, "nearestDistanceMeters": 120},
            source_id="place.sbiz-academy",
            source_name="소상공인시장진흥공단 교육업소 위치",
            evidence_grade="B",
            dataset_version_value="activation-academy-v1",
        ),
        EvidenceFact(
            fact_id="activation-transit-501",
            claims=(FactClaim("검증역", "TEXT"), FactClaim("450", "METERS")),
            data_as_of=observed_on,
            payload={"stationName": "검증역", "nearestDistanceMeters": 450},
            source_id="transport.rail-station",
            source_name="철도역 위치",
            evidence_grade="A",
            dataset_version_value="activation-rail-v1",
        ),
    ]


def main() -> int:
    from ai_service.chat import get_grounded_language_model

    try:
        result = asyncio.run(run_activation_case(get_grounded_language_model()))
    except CriteriaActivationError as exception:
        print("상태: Fail")
        print(f"caseId: {CASE_ID}")
        print(f"reasonCode: {exception}")
        return 1
    except Exception:
        print("상태: Fail")
        print(f"caseId: {CASE_ID}")
        print("reasonCode: ACTIVATION_CASE_FAILED")
        return 1
    print("상태: Pass")
    print(f"caseId: {result['caseId']}")
    print(f"capability: {result['capability']}")
    print(f"mode: {result['mode']}")
    print("criteria: ACADEMY,TRANSIT")
    print(f"factCount: {result['factCount']}")
    print("providerRequestUpperBound: 6")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
