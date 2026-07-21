from __future__ import annotations

import asyncio
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

SCHOOL_CASE_ID = "school-location-jamsil-ells"
SCHOOL_QUESTION = "잠실엘스 단지 중심 800m 안의 운영 중인 초등학교 위치를 알려줘"


class ReferenceActivationError(ValueError):
    pass


class _SchoolRepository(Protocol):
    def active_snapshot(self) -> object: ...

    def nearby_schools(self, **kwargs: object) -> object: ...


class _TrackingLanguageModel:
    def __init__(self, model: GroundedLanguageModel) -> None:
        self._model = model
        self.stage = "PLAN_PENDING"

    async def plan_query(self, request: ChatbotQueryRequest) -> object:
        result = await self._model.plan_query(request)
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


def _grounding_reason(exception: BaseException) -> str | None:
    current: BaseException | None = exception
    for _ in range(8):
        if isinstance(current, GroundingValidationError):
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


def main() -> int:
    from ai_service.chat import (
        get_grounded_language_model,
        get_property_fact_repository,
        get_school_fact_repository,
    )

    property_repository = None
    school_repository = None
    try:
        property_repository = get_property_fact_repository()
        school_repository = get_school_fact_repository()
        result = asyncio.run(run_school_activation_case(
            property_repository=property_repository,  # type: ignore[arg-type]
            school_repository=school_repository,  # type: ignore[arg-type]
            language_model=get_grounded_language_model(),  # type: ignore[arg-type]
        ))
    except ReferenceActivationError as exception:
        print("상태: Fail")
        print(f"caseId: {SCHOOL_CASE_ID}")
        print(f"reasonCode: {exception}")
        return 1
    except Exception:
        print("상태: Fail")
        print(f"caseId: {SCHOOL_CASE_ID}")
        print("reasonCode: SCHOOL_ACTIVATION_FAILED")
        return 1
    finally:
        for repository in (school_repository, property_repository):
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
