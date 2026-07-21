from __future__ import annotations

import asyncio
import os
import re
from collections.abc import Mapping
from datetime import date
from typing import Protocol

from ai_service.auth import AuthenticatedUser
from ai_service.models import ChatbotQueryRequest

from .engine import (
    GroundedChatbotEngine,
    GroundedLanguageModel,
    GroundingValidationError,
    PropertyFactRepository,
)
from .school_postgres import PostgresSchoolFactRepository
from .openai_responses import OpenAIResponsesError

SCHOOL_CASE_ID = "school-location-jamsil-ells"
SCHOOL_QUESTION = "잠실엘스 단지 중심 800m 안의 운영 중인 초등학교 위치를 알려줘"
COMPARISON_CASE_ID = "comparison-jamsil-ells-helio-84"
COMPARISON_QUESTION = "송파구 잠실엘스와 헬리오시티 전용 84㎡를 같은 기준으로 비교해줘"
BUDGET_RETAIL_CASE_ID = "budget-recommendation-songpa-84-retail"
BUDGET_RETAIL_QUESTION = "송파구에서 20억원 이하 전용 84㎡ 아파트 3곳을 추천해줘"


class ReferenceActivationError(ValueError):
    pass


class _SchoolRepository(Protocol):
    def active_snapshot(self) -> object: ...

    def nearby_schools(self, **kwargs: object) -> object: ...


class _TrackingLanguageModel:
    def __init__(self, model: GroundedLanguageModel) -> None:
        self._model = model
        self.stage = "PLAN_PENDING"
        self.plan: object | None = None

    async def plan_query(self, request: ChatbotQueryRequest) -> object:
        result = await self._model.plan_query(request)
        self.plan = result
        self.stage = "PLAN_DONE"
        return result

    async def draft_answer(self, **kwargs: object) -> object:
        self.stage = "DRAFT_PENDING"
        result = await self._model.draft_answer(**kwargs)  # type: ignore[arg-type]
        self.stage = "DRAFT_DONE"
        return result


async def run_school_activation_case(
    *,
    property_repository: PropertyFactRepository,
    school_repository: PostgresSchoolFactRepository,
    language_model: GroundedLanguageModel,
) -> dict[str, object]:
    try:
        await _preflight_school_observation(property_repository, school_repository)
    except Exception:
        raise ReferenceActivationError("SCHOOL_OBSERVATION_FAILED") from None
    tracking_model = _TrackingLanguageModel(language_model)
    try:
        response = await GroundedChatbotEngine(
            repository=property_repository,
            school_repository=school_repository,
            language_model=tracking_model,  # type: ignore[arg-type]
            enabled_capabilities=frozenset(),
            enabled_reference_capabilities=frozenset({"school_location"}),
        ).query(
            request=ChatbotQueryRequest(question=SCHOOL_QUESTION),
            user=AuthenticatedUser(user_id=1),
            request_id="activation-school-location-jamsil-ells",
        )
    except Exception as exception:
        grounding_reason = _grounding_reason(exception)
        if grounding_reason is not None:
            raise ReferenceActivationError(
                f"SCHOOL_DRAFT_{grounding_reason}"
            ) from None
        reason_by_stage = {
            "PLAN_PENDING": "SCHOOL_PLAN_STAGE_FAILED",
            "PLAN_DONE": "SCHOOL_OBSERVATION_REPLAY_FAILED",
            "DRAFT_PENDING": "SCHOOL_DRAFT_STAGE_FAILED",
            "DRAFT_DONE": "SCHOOL_GROUNDING_FAILED",
        }
        raise ReferenceActivationError(reason_by_stage[tracking_model.stage]) from None
    return validate_school_activation_response(response)


async def run_comparison_activation_case(
    *,
    property_repository: PropertyFactRepository,
    rail_repository: object,
    language_model: GroundedLanguageModel,
) -> dict[str, object]:
    tracking_model = _TrackingLanguageModel(language_model)
    try:
        response = await GroundedChatbotEngine(
            repository=property_repository,
            rail_station_repository=rail_repository,  # type: ignore[arg-type]
            language_model=tracking_model,  # type: ignore[arg-type]
            enabled_capabilities=frozenset({"comparison"}),
        ).query(
            request=ChatbotQueryRequest(question=COMPARISON_QUESTION),
            user=AuthenticatedUser(user_id=1),
            request_id="activation-comparison-jamsil-ells-helio-84",
        )
    except Exception as exception:
        grounding_reason = _grounding_reason(exception)
        if grounding_reason is not None:
            raise ReferenceActivationError(
                f"COMPARISON_DRAFT_{grounding_reason}"
            ) from None
        provider_reason = _provider_reason(exception)
        if provider_reason is not None:
            raise ReferenceActivationError(
                f"COMPARISON_DRAFT_{provider_reason}"
            ) from None
        reason_by_stage = {
            "PLAN_PENDING": "COMPARISON_PLAN_STAGE_FAILED",
            "PLAN_DONE": "COMPARISON_OBSERVATION_FAILED",
            "DRAFT_PENDING": "COMPARISON_DRAFT_STAGE_FAILED",
            "DRAFT_DONE": "COMPARISON_GROUNDING_FAILED",
        }
        raise ReferenceActivationError(reason_by_stage[tracking_model.stage]) from None
    plan_reason = _comparison_plan_reason(tracking_model.plan)
    if plan_reason is not None:
        raise ReferenceActivationError(plan_reason)
    return validate_comparison_activation_response(response)


async def run_budget_retail_activation_case(
    *,
    property_repository: PropertyFactRepository,
    rail_repository: object,
    retail_repository: object,
    language_model: GroundedLanguageModel,
) -> dict[str, object]:
    tracking_model = _TrackingLanguageModel(language_model)
    try:
        response = await GroundedChatbotEngine(
            repository=property_repository,
            rail_station_repository=rail_repository,  # type: ignore[arg-type]
            point_facility_repository=retail_repository,  # type: ignore[arg-type]
            language_model=tracking_model,  # type: ignore[arg-type]
            enabled_capabilities=frozenset({"recommendation"}),
            enabled_recommendation_modes=frozenset({"BUDGET"}),
        ).query(
            request=ChatbotQueryRequest(question=BUDGET_RETAIL_QUESTION),
            user=AuthenticatedUser(user_id=1),
            request_id="activation-budget-recommendation-songpa-84-retail",
        )
    except Exception as exception:
        grounding_reason = _grounding_reason(exception)
        if grounding_reason is not None:
            raise ReferenceActivationError(
                f"BUDGET_RETAIL_DRAFT_{grounding_reason}"
            ) from None
        provider_reason = _provider_reason(exception)
        if provider_reason is not None:
            raise ReferenceActivationError(
                f"BUDGET_RETAIL_DRAFT_{provider_reason}"
            ) from None
        reason_by_stage = {
            "PLAN_PENDING": "BUDGET_RETAIL_PLAN_STAGE_FAILED",
            "PLAN_DONE": "BUDGET_RETAIL_OBSERVATION_FAILED",
            "DRAFT_PENDING": "BUDGET_RETAIL_DRAFT_STAGE_FAILED",
            "DRAFT_DONE": "BUDGET_RETAIL_GROUNDING_FAILED",
        }
        raise ReferenceActivationError(reason_by_stage[tracking_model.stage]) from None
    plan_reason = _budget_retail_plan_reason(tracking_model.plan)
    if plan_reason is not None:
        raise ReferenceActivationError(plan_reason)
    return validate_budget_retail_activation_response(response)


def _comparison_plan_reason(plan: object | None) -> str | None:
    fragments = getattr(plan, "fragments", None)
    if isinstance(fragments, tuple) and len(fragments) == 1:
        plan = fragments[0]
    if getattr(plan, "capability", None) != "comparison":
        return "COMPARISON_PLAN_CAPABILITY_INVALID"
    complex_names = tuple(getattr(plan, "complex_names", ()))
    if len(complex_names) != 2 or set(complex_names) != {"잠실엘스", "헬리오시티"}:
        return "COMPARISON_PLAN_COMPLEX_NAMES_INVALID"
    if getattr(plan, "region_name", None) != "송파구":
        return "COMPARISON_PLAN_REGION_INVALID"
    if getattr(plan, "exclusive_area_square_meters", None) != 84.0:
        return "COMPARISON_PLAN_AREA_INVALID"
    return None


def _budget_retail_plan_reason(plan: object | None) -> str | None:
    fragments = getattr(plan, "fragments", None)
    if isinstance(fragments, tuple) and len(fragments) == 1:
        plan = fragments[0]
    if getattr(plan, "capability", None) != "recommendation":
        return "BUDGET_RETAIL_PLAN_CAPABILITY_INVALID"
    if getattr(plan, "recommendation_mode", None) != "BUDGET":
        return "BUDGET_RETAIL_PLAN_MODE_INVALID"
    if getattr(plan, "region_name", None) != "송파구":
        return "BUDGET_RETAIL_PLAN_REGION_INVALID"
    if getattr(plan, "maximum_budget_ten_thousand_krw", None) != 200_000:
        return "BUDGET_RETAIL_PLAN_BUDGET_INVALID"
    if getattr(plan, "exclusive_area_square_meters", None) != 84.0:
        return "BUDGET_RETAIL_PLAN_AREA_INVALID"
    if getattr(plan, "limit", None) != 3:
        return "BUDGET_RETAIL_PLAN_LIMIT_INVALID"
    if tuple(getattr(plan, "recommendation_criteria", ())) or tuple(
        getattr(plan, "lifestyle_themes", ())
    ):
        return "BUDGET_RETAIL_PLAN_UNMENTIONED_CONDITION"
    return None


def _grounding_reason(exception: BaseException) -> str | None:
    current: BaseException | None = exception
    for _ in range(8):
        if isinstance(current, GroundingValidationError):
            return current.reason_code
        current = current.__cause__
        if current is None:
            return None
    return None


def _provider_reason(exception: BaseException) -> str | None:
    current: BaseException | None = exception
    for _ in range(8):
        if isinstance(current, OpenAIResponsesError):
            return current.reason_code
        current = current.__cause__
        if current is None:
            return None
    return None


async def _preflight_school_observation(
    property_repository: PropertyFactRepository,
    school_repository: _SchoolRepository,
) -> None:
    complexes = await asyncio.to_thread(
        property_repository.find_complexes, "잠실엘스", None, 6
    )
    if len(complexes) != 1:
        raise ValueError("school activation complex is not unique")
    complex_record = complexes[0]
    if (
        not complex_record.marker_safe
        or complex_record.latitude is None
        or complex_record.longitude is None
    ):
        raise ValueError("school activation complex has no marker-safe coordinate")
    snapshot = await asyncio.to_thread(school_repository.active_snapshot)
    if snapshot is None or not 0 <= (date.today() - snapshot.source_date).days <= 214:
        raise ValueError("school activation snapshot is unavailable")
    result = await asyncio.to_thread(
        school_repository.nearby_schools,
        latitude=complex_record.latitude,
        longitude=complex_record.longitude,
        school_levels=("ELEMENTARY",),
        radius_meters=800,
        limit=5,
    )
    if not result.schools or result.matched_count < len(result.schools):
        raise ValueError("school activation result count is invalid")


def validate_school_activation_response(
    response: object,
) -> dict[str, object]:
    if not isinstance(response, dict):
        raise ReferenceActivationError("SCHOOL_RESPONSE_INVALID")
    summary = response.get("evidenceSummary")
    citations = response.get("citations")
    ui_summary = response.get("uiSummary")
    artifacts = response.get("uiArtifacts")
    if (
        response.get("success") is not True
        or response.get("status") != "success"
        or not isinstance(summary, Mapping)
        or summary.get("status") != "supported"
        or summary.get("capabilities") != ["school_location"]
        or not isinstance(summary.get("factCount"), int)
        or not 2 <= summary["factCount"] <= 7
        or not isinstance(summary.get("citationCount"), int)
        or not isinstance(citations, list)
        or len(citations) != summary["citationCount"]
        or not isinstance(ui_summary, Mapping)
        or ui_summary.get("version") != 1
        or not isinstance(ui_summary.get("headline"), Mapping)
        or not isinstance(artifacts, list)
        or not any(
            isinstance(artifact, Mapping)
            and artifact.get("type") == "factList"
            and artifact.get("version") == 1
            for artifact in artifacts
        )
        or not isinstance(response.get("dataAsOf"), str)
        or re.fullmatch(r"\d{4}-\d{2}-\d{2}", response["dataAsOf"]) is None
    ):
        raise ReferenceActivationError("SCHOOL_RESPONSE_INVALID")
    source_ids = {
        citation.get("sourceId")
        for citation in citations
        if isinstance(citation, Mapping)
        and isinstance(citation.get("factIds"), list)
        and citation["factIds"]
    }
    if source_ids != {"property.ai_read", "edu.school-location"}:
        raise ReferenceActivationError("SCHOOL_RESPONSE_INVALID")
    return {
        "caseId": SCHOOL_CASE_ID,
        "capability": "school_location",
        "factCount": summary["factCount"],
        "citationCount": summary["citationCount"],
        "dataAsOf": response["dataAsOf"],
    }


def validate_comparison_activation_response(response: object) -> dict[str, object]:
    if not isinstance(response, dict):
        raise ReferenceActivationError("COMPARISON_RESPONSE_SHAPE_INVALID")
    summary = response.get("evidenceSummary")
    citations = response.get("citations")
    ui_summary = response.get("uiSummary")
    artifacts = response.get("uiArtifacts")
    if response.get("success") is not True:
        raise ReferenceActivationError("COMPARISON_RESPONSE_NOT_SUCCESSFUL")
    if response.get("status") != "partial_success":
        raise ReferenceActivationError("COMPARISON_RESPONSE_STATUS_INVALID")
    if not isinstance(summary, Mapping) or summary.get("status") != "partial":
        raise ReferenceActivationError("COMPARISON_RESPONSE_READINESS_INVALID")
    if summary.get("capabilities") != ["comparison"]:
        raise ReferenceActivationError("COMPARISON_RESPONSE_CAPABILITY_INVALID")
    if not isinstance(summary.get("factCount"), int):
        raise ReferenceActivationError("COMPARISON_RESPONSE_FACT_COUNT_INVALID")
    if summary["factCount"] < 4:
        raise ReferenceActivationError("COMPARISON_RESPONSE_FACT_COUNT_LOW")
    if summary["factCount"] > 12:
        raise ReferenceActivationError("COMPARISON_RESPONSE_FACT_COUNT_HIGH")
    if not isinstance(citations, list):
        raise ReferenceActivationError("COMPARISON_RESPONSE_CITATIONS_INVALID")
    if (
        not isinstance(ui_summary, Mapping)
        or ui_summary.get("version") != 1
        or not isinstance(ui_summary.get("headline"), Mapping)
        or not isinstance(artifacts, list)
    ):
        raise ReferenceActivationError("COMPARISON_RESPONSE_UI_INVALID")
    if not isinstance(response.get("dataAsOf"), str):
        raise ReferenceActivationError("COMPARISON_RESPONSE_DATE_INVALID")
    tables = [
        artifact
        for artifact in artifacts
        if isinstance(artifact, Mapping)
        and artifact.get("type") == "comparisonTable"
        and artifact.get("version") == 1
    ]
    if len(tables) != 1:
        raise ReferenceActivationError("COMPARISON_TABLE_INVALID")
    table = tables[0]
    rows = table.get("rows")
    columns = table.get("columns")
    if not isinstance(rows, list) or not isinstance(columns, list) or len(columns) != 2:
        raise ReferenceActivationError("COMPARISON_TABLE_INVALID")
    by_key = {
        row.get("key"): row
        for row in rows
        if isinstance(row, Mapping) and isinstance(row.get("key"), str)
    }
    required = {"latestTrade", "recentThreeMedian", "unitCount", "nearestRail", "nearestRetail"}
    if not required.issubset(by_key):
        raise ReferenceActivationError("COMPARISON_TABLE_INVALID")
    for key in ("latestTrade", "recentThreeMedian", "nearestRail"):
        cells = by_key[key].get("cells")
        if not isinstance(cells, list) or len(cells) != 2 or any(
            not isinstance(cell, Mapping) or cell.get("availability") != "available"
            for cell in cells
        ):
            raise ReferenceActivationError("COMPARISON_AVAILABLE_CELL_INVALID")
    retail_cells = by_key["nearestRetail"].get("cells")
    if not isinstance(retail_cells, list) or len(retail_cells) != 2 or any(
        not isinstance(cell, Mapping) or cell.get("availability") != "unavailable"
        for cell in retail_cells
    ):
        raise ReferenceActivationError("COMPARISON_RETAIL_CELL_INVALID")
    source_ids = {
        citation.get("sourceId")
        for citation in citations
        if isinstance(citation, Mapping) and citation.get("factIds")
    }
    if source_ids != {"property.ai_read", "transport.rail-station"}:
        raise ReferenceActivationError("COMPARISON_CITATION_INVALID")
    return {
        "caseId": COMPARISON_CASE_ID,
        "capability": "comparison",
        "factCount": summary["factCount"],
        "citationCount": len(citations),
        "dataAsOf": response["dataAsOf"],
    }


def validate_budget_retail_activation_response(response: object) -> dict[str, object]:
    if not isinstance(response, dict):
        raise ReferenceActivationError("BUDGET_RETAIL_RESPONSE_SHAPE_INVALID")
    summary = response.get("evidenceSummary")
    citations = response.get("citations")
    ui_summary = response.get("uiSummary")
    artifacts = response.get("uiArtifacts")
    if (
        response.get("success") is not True
        or response.get("status") != "success"
        or not isinstance(summary, Mapping)
        or summary.get("status") != "supported"
        or summary.get("capabilities") != ["recommendation"]
        or not isinstance(summary.get("factCount"), int)
        or not 4 <= summary["factCount"] <= 15
        or not isinstance(summary.get("citationCount"), int)
        or not isinstance(citations, list)
        or len(citations) != summary["citationCount"]
        or not isinstance(ui_summary, Mapping)
        or ui_summary.get("version") != 1
        or not isinstance(ui_summary.get("headline"), Mapping)
        or not isinstance(artifacts, list)
        or not isinstance(response.get("dataAsOf"), str)
    ):
        raise ReferenceActivationError("BUDGET_RETAIL_RESPONSE_INVALID")
    cards_artifacts = [
        artifact
        for artifact in artifacts
        if isinstance(artifact, Mapping)
        and artifact.get("type") == "recommendationCards"
        and artifact.get("version") == 1
        and artifact.get("policyVersion") == "recommendation-policy-v1"
    ]
    if len(cards_artifacts) != 1:
        raise ReferenceActivationError("BUDGET_RETAIL_ARTIFACT_INVALID")
    cards = cards_artifacts[0].get("cards")
    if not isinstance(cards, list) or not 1 <= len(cards) <= 3:
        raise ReferenceActivationError("BUDGET_RETAIL_CARDS_INVALID")
    for expected_rank, card in enumerate(cards, start=1):
        if not isinstance(card, Mapping) or card.get("rank") != expected_rank:
            raise ReferenceActivationError("BUDGET_RETAIL_CARD_RANK_INVALID")
        breakdown = card.get("scoreBreakdown")
        if not isinstance(breakdown, list) or {
            item.get("key")
            for item in breakdown
            if isinstance(item, Mapping)
        } != {"PRICE", "TRANSIT", "SHOPPING"}:
            raise ReferenceActivationError("BUDGET_RETAIL_BREAKDOWN_INVALID")
        if not isinstance(card.get("factIds"), list) or not card["factIds"]:
            raise ReferenceActivationError("BUDGET_RETAIL_CARD_FACTS_INVALID")
    source_ids = {
        citation.get("sourceId")
        for citation in citations
        if isinstance(citation, Mapping) and citation.get("factIds")
    }
    if source_ids != {
        "property.ai_read",
        "transport.rail-station",
        "retail.large-store",
    }:
        raise ReferenceActivationError("BUDGET_RETAIL_CITATION_INVALID")
    criteria = ui_summary.get("criteria")
    if not isinstance(criteria, list):
        raise ReferenceActivationError("BUDGET_RETAIL_CRITERIA_INVALID")
    criterion_keys = {
        criterion.get("key")
        for criterion in criteria
        if isinstance(criterion, Mapping)
    }
    if criterion_keys != {"REGION", "MAX_BUDGET", "EXCLUSIVE_AREA"}:
        raise ReferenceActivationError("BUDGET_RETAIL_CRITERIA_INVALID")
    return {
        "caseId": BUDGET_RETAIL_CASE_ID,
        "capability": "recommendation",
        "factCount": summary["factCount"],
        "citationCount": len(citations),
        "dataAsOf": response["dataAsOf"],
    }


def main() -> int:
    from ai_service.chat import (
        get_grounded_language_model,
        get_point_facility_repository,
        get_property_fact_repository,
        get_rail_station_repository,
        get_school_fact_repository,
    )

    case_id = os.getenv("HOME_AI_REFERENCE_ACTIVATION_CASE_ID", SCHOOL_CASE_ID)
    property_repository = None
    school_repository = None
    rail_repository = None
    retail_repository = None
    try:
        property_repository = get_property_fact_repository()
        if case_id == SCHOOL_CASE_ID:
            school_repository = get_school_fact_repository()
            result = asyncio.run(run_school_activation_case(
                property_repository=property_repository,  # type: ignore[arg-type]
                school_repository=school_repository,  # type: ignore[arg-type]
                language_model=get_grounded_language_model(),  # type: ignore[arg-type]
            ))
        elif case_id == COMPARISON_CASE_ID:
            rail_repository = get_rail_station_repository()
            result = asyncio.run(run_comparison_activation_case(
                property_repository=property_repository,  # type: ignore[arg-type]
                rail_repository=rail_repository,
                language_model=get_grounded_language_model(),  # type: ignore[arg-type]
            ))
        elif case_id == BUDGET_RETAIL_CASE_ID:
            rail_repository = get_rail_station_repository()
            retail_repository = get_point_facility_repository()
            result = asyncio.run(run_budget_retail_activation_case(
                property_repository=property_repository,  # type: ignore[arg-type]
                rail_repository=rail_repository,
                retail_repository=retail_repository,
                language_model=get_grounded_language_model(),  # type: ignore[arg-type]
            ))
        else:
            raise ReferenceActivationError("ACTIVATION_CASE_INVALID")
    except ReferenceActivationError as exception:
        print("상태: Fail")
        print(f"caseId: {case_id}")
        print(f"reasonCode: {exception}")
        return 1
    except Exception:
        print("상태: Fail")
        print(f"caseId: {case_id}")
        print("reasonCode: REFERENCE_ACTIVATION_FAILED")
        return 1
    finally:
        for repository in (
            school_repository,
            rail_repository,
            retail_repository,
            property_repository,
        ):
            close = getattr(repository, "close", None)
            if callable(close):
                close()
    print("상태: Pass")
    print(f"caseId: {result['caseId']}")
    print(f"capability: {result['capability']}")
    print(f"factCount: {result['factCount']}")
    print(f"citationCount: {result['citationCount']}")
    print(f"dataAsOf: {result['dataAsOf']}")
    print("providerRequestUpperBound: 6")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
