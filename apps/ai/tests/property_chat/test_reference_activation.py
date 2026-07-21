from __future__ import annotations

import asyncio
from datetime import date
from types import SimpleNamespace

import pytest

from ai_service.property_chat.engine import GroundingValidationError
from ai_service.property_chat.openai_responses import OpenAIResponsesError
from ai_service.property_chat.reference_activation import (
    ReferenceActivationError,
    _TrackingLanguageModel,
    _grounding_reason,
    _comparison_plan_reason,
    _provider_reason,
    _preflight_school_observation,
    main,
    run_comparison_activation_case,
    run_school_activation_case,
    validate_comparison_activation_response,
    validate_school_activation_response,
)


def _response() -> dict[str, object]:
    return {
        "success": True,
        "status": "success",
        "uiSummary": {"version": 1, "headline": {"text": "학교 위치"}},
        "uiArtifacts": [{"type": "factList", "version": 1}],
        "citations": [
            {"sourceId": "property.ai_read", "factIds": ["property-complex-501"]},
            {"sourceId": "edu.school-location", "factIds": ["school-scope-501"]},
        ],
        "evidenceSummary": {
            "status": "supported",
            "capabilities": ["school_location"],
            "factCount": 2,
            "citationCount": 2,
        },
        "dataAsOf": "2026-03-20",
    }


def _comparison_response() -> dict[str, object]:
    def row(key: str, availability: str) -> dict[str, object]:
        return {
            "key": key,
            "cells": [
                {"availability": availability},
                {"availability": availability},
            ],
        }

    return {
        "success": True,
        "status": "partial_success",
        "uiSummary": {"version": 1, "headline": {"text": "단지 비교"}},
        "uiArtifacts": [{
            "type": "comparisonTable",
            "version": 1,
            "columns": [{"key": "1"}, {"key": "2"}],
            "rows": [
                row("latestTrade", "available"),
                row("recentThreeMedian", "available"),
                row("unitCount", "available"),
                row("nearestRail", "available"),
                row("nearestRetail", "unavailable"),
            ],
        }],
        "citations": [
            {"sourceId": "property.ai_read", "factIds": ["property-1"]},
            {"sourceId": "transport.rail-station", "factIds": ["rail-1"]},
        ],
        "evidenceSummary": {
            "status": "partial",
            "capabilities": ["comparison"],
            "factCount": 6,
            "citationCount": 2,
        },
        "dataAsOf": "2026-07-04",
    }


def test_school_activation_accepts_grounded_structured_response() -> None:
    assert validate_school_activation_response(_response()) == {
        "caseId": "school-location-jamsil-ells",
        "capability": "school_location",
        "factCount": 2,
        "citationCount": 2,
        "dataAsOf": "2026-03-20",
    }


def test_school_activation_rejects_non_object_response() -> None:
    with pytest.raises(ReferenceActivationError, match="SCHOOL_RESPONSE_INVALID"):
        validate_school_activation_response(None)


def test_school_activation_requires_both_citation_sources() -> None:
    response = _response()
    response["citations"] = [
        {"sourceId": "property.ai_read", "factIds": ["property-complex-501"]},
        {"sourceId": "property.ai_read", "factIds": ["property-scope-501"]},
    ]

    with pytest.raises(ReferenceActivationError, match="SCHOOL_RESPONSE_INVALID"):
        validate_school_activation_response(response)


def test_comparison_activation_accepts_partial_retail_table() -> None:
    assert validate_comparison_activation_response(_comparison_response()) == {
        "caseId": "comparison-jamsil-ells-helio-84",
        "capability": "comparison",
        "factCount": 6,
        "citationCount": 2,
        "dataAsOf": "2026-07-04",
    }


def test_comparison_activation_rejects_non_object_response() -> None:
    with pytest.raises(ReferenceActivationError, match="COMPARISON_RESPONSE_SHAPE_INVALID"):
        validate_comparison_activation_response(None)


@pytest.mark.parametrize(
    "mutate",
    [
        lambda response: response.update(status="success"),
        lambda response: response.update(success=False),
        lambda response: response["evidenceSummary"].update(status="supported"),
        lambda response: response["evidenceSummary"].update(capabilities=[]),
        lambda response: response.update(uiArtifacts=[]),
        lambda response: response["uiArtifacts"][0]["rows"][-1]["cells"][0].update(
            availability="available"
        ),
        lambda response: response.update(citations=[]),
        lambda response: response["evidenceSummary"].update(factCount=3),
        lambda response: response["evidenceSummary"].update(factCount=13),
        lambda response: response["evidenceSummary"].update(factCount=None),
        lambda response: response.update(citations=None),
        lambda response: response.update(dataAsOf=None),
        lambda response: response.update(uiSummary=None),
        lambda response: response["uiArtifacts"][0].update(columns=[]),
        lambda response: response["uiArtifacts"][0].update(rows=[]),
        lambda response: response["uiArtifacts"][0]["rows"][0]["cells"][0].update(
            availability="unavailable"
        ),
    ],
)
def test_comparison_activation_rejects_incomplete_evidence(mutate) -> None:
    response = _comparison_response()
    mutate(response)

    with pytest.raises(ReferenceActivationError, match="COMPARISON_"):
        validate_comparison_activation_response(response)


def test_comparison_activation_runs_the_grounded_engine(monkeypatch) -> None:
    class Engine:
        def __init__(self, **kwargs):
            assert kwargs["enabled_capabilities"] == frozenset({"comparison"})
            kwargs["language_model"].plan = SimpleNamespace(
                capability="comparison",
                complex_names=("잠실엘스", "헬리오시티"),
                region_name="송파구",
                exclusive_area_square_meters=84.0,
            )

        async def query(self, **kwargs):
            assert "84㎡" in kwargs["request"].question
            return _comparison_response()

    monkeypatch.setattr(
        "ai_service.property_chat.reference_activation.GroundedChatbotEngine", Engine
    )

    result = asyncio.run(run_comparison_activation_case(
        property_repository=object(),
        rail_repository=object(),
        language_model=object(),
    ))

    assert result["capability"] == "comparison"


@pytest.mark.parametrize(
    ("plan", "reason"),
    [
        (None, "COMPARISON_PLAN_CAPABILITY_INVALID"),
        (
            SimpleNamespace(
                capability="comparison", complex_names=("잠실엘스",),
                region_name="송파구", exclusive_area_square_meters=84.0,
            ),
            "COMPARISON_PLAN_COMPLEX_NAMES_INVALID",
        ),
        (
            SimpleNamespace(
                capability="comparison", complex_names=("잠실엘스", "헬리오시티"),
                region_name=None, exclusive_area_square_meters=84.0,
            ),
            "COMPARISON_PLAN_REGION_INVALID",
        ),
        (
            SimpleNamespace(
                capability="comparison", complex_names=("잠실엘스", "헬리오시티"),
                region_name="송파구", exclusive_area_square_meters=59.0,
            ),
            "COMPARISON_PLAN_AREA_INVALID",
        ),
    ],
)
def test_comparison_plan_reason_identifies_typed_mismatch(plan, reason) -> None:
    assert _comparison_plan_reason(plan) == reason


def test_comparison_plan_reason_accepts_single_fragment_and_name_order() -> None:
    plan = SimpleNamespace(
        capability="comparison",
        complex_names=("헬리오시티", "잠실엘스"),
        region_name="송파구",
        exclusive_area_square_meters=84.0,
    )

    assert _comparison_plan_reason(SimpleNamespace(fragments=(plan,))) is None


def test_provider_reason_reads_only_the_safe_cause_code() -> None:
    exception = RuntimeError("must-not-leak")
    exception.__cause__ = OpenAIResponsesError("PROVIDER_RESPONSE_INCOMPLETE")

    assert _provider_reason(exception) == "PROVIDER_RESPONSE_INCOMPLETE"
    assert _provider_reason(RuntimeError("safe")) is None


def test_comparison_activation_maps_provider_failure(monkeypatch) -> None:
    class Engine:
        def __init__(self, **_kwargs):
            pass

        async def query(self, **_kwargs):
            raise RuntimeError("must-not-leak") from OpenAIResponsesError(
                "PROVIDER_TRANSPORT_FAILED"
            )

    monkeypatch.setattr(
        "ai_service.property_chat.reference_activation.GroundedChatbotEngine", Engine
    )

    with pytest.raises(
        ReferenceActivationError,
        match="COMPARISON_DRAFT_PROVIDER_TRANSPORT_FAILED",
    ):
        asyncio.run(run_comparison_activation_case(
            property_repository=object(), rail_repository=object(), language_model=object()
        ))


def test_comparison_activation_maps_grounding_failure(monkeypatch) -> None:
    class Engine:
        def __init__(self, **_kwargs):
            pass

        async def query(self, **_kwargs):
            raise RuntimeError("must-not-leak") from GroundingValidationError(
                "GROUNDING_FACTS_OMITTED"
            )

    monkeypatch.setattr(
        "ai_service.property_chat.reference_activation.GroundedChatbotEngine", Engine
    )

    with pytest.raises(
        ReferenceActivationError,
        match="COMPARISON_DRAFT_GROUNDING_FACTS_OMITTED",
    ):
        asyncio.run(run_comparison_activation_case(
            property_repository=object(), rail_repository=object(), language_model=object()
        ))


@pytest.mark.parametrize(
    "mutate",
    [
        lambda response: response.update(success=False),
        lambda response: response["evidenceSummary"].update(status="unavailable"),
        lambda response: response["evidenceSummary"].update(capabilities=[]),
        lambda response: response.update(citations=[]),
        lambda response: response.update(uiSummary=None),
        lambda response: response.update(uiArtifacts=[]),
    ],
)
def test_school_activation_rejects_incomplete_response(mutate) -> None:
    response = _response()
    mutate(response)

    with pytest.raises(ReferenceActivationError, match="SCHOOL_RESPONSE_INVALID"):
        validate_school_activation_response(response)


class _PropertyRepository:
    def __init__(self, complexes=None) -> None:
        self.complexes = (
            [
                SimpleNamespace(
                    marker_safe=True,
                    latitude=37.5,
                    longitude=127.0,
                )
            ]
            if complexes is None
            else complexes
        )
        self.closed = False

    def find_complexes(self, name, region, limit):
        assert (name, region, limit) == ("잠실엘스", None, 6)
        return self.complexes

    def close(self) -> None:
        self.closed = True


class _SchoolRepository:
    def __init__(self) -> None:
        self.snapshot = SimpleNamespace(source_date=date.today())
        self.result = SimpleNamespace(matched_count=1, schools=(object(),))
        self.closed = False

    def active_snapshot(self):
        return self.snapshot

    def nearby_schools(self, **kwargs):
        assert kwargs == {
            "latitude": 37.5,
            "longitude": 127.0,
            "school_levels": ("ELEMENTARY",),
            "radius_meters": 800,
            "limit": 5,
        }
        return self.result

    def close(self) -> None:
        self.closed = True


def test_school_activation_runs_observation_and_structured_validation(monkeypatch) -> None:
    class Engine:
        def __init__(self, **kwargs):
            assert kwargs["enabled_capabilities"] == frozenset()
            assert kwargs["enabled_reference_capabilities"] == frozenset(
                {"school_location"}
            )

        async def query(self, **kwargs):
            assert kwargs["request"].question.startswith("잠실엘스")
            return _response()

    monkeypatch.setattr(
        "ai_service.property_chat.reference_activation.GroundedChatbotEngine", Engine
    )

    result = asyncio.run(
        run_school_activation_case(
            property_repository=_PropertyRepository(),
            school_repository=_SchoolRepository(),
            language_model=object(),
        )
    )

    assert result["capability"] == "school_location"


@pytest.mark.parametrize(
    "property_complexes,school_mutation",
    [
        ([], None),
        ([SimpleNamespace(marker_safe=False, latitude=None, longitude=None)], None),
        (None, lambda repository: setattr(repository, "snapshot", None)),
        (
            None,
            lambda repository: setattr(
                repository,
                "result",
                SimpleNamespace(matched_count=0, schools=(object(),)),
            ),
        ),
        (
            None,
            lambda repository: setattr(
                repository,
                "result",
                SimpleNamespace(matched_count=0, schools=()),
            ),
        ),
    ],
)
def test_school_observation_preflight_rejects_incomplete_evidence(
    property_complexes, school_mutation
) -> None:
    property_repository = _PropertyRepository(property_complexes)
    school_repository = _SchoolRepository()
    if school_mutation is not None:
        school_mutation(school_repository)

    with pytest.raises(ValueError):
        asyncio.run(
            _preflight_school_observation(property_repository, school_repository)
        )


def test_school_activation_maps_grounding_reason_without_details(monkeypatch) -> None:
    class Engine:
        def __init__(self, **_kwargs):
            pass

        async def query(self, **_kwargs):
            raise RuntimeError("must-not-leak") from GroundingValidationError(
                "GROUNDING_FACTS_OMITTED"
            )

    monkeypatch.setattr(
        "ai_service.property_chat.reference_activation.GroundedChatbotEngine", Engine
    )

    with pytest.raises(
        ReferenceActivationError,
        match="SCHOOL_DRAFT_GROUNDING_FACTS_OMITTED",
    ):
        asyncio.run(
            run_school_activation_case(
                property_repository=_PropertyRepository(),
                school_repository=_SchoolRepository(),
                language_model=object(),
            )
        )


def test_grounding_reason_stops_at_a_non_grounding_cause() -> None:
    assert _grounding_reason(RuntimeError("safe")) is None


def test_tracking_language_model_records_completed_stages() -> None:
    class LanguageModel:
        async def plan_query(self, request):
            return request

        async def draft_answer(self, **kwargs):
            return kwargs

    tracking = _TrackingLanguageModel(LanguageModel())
    request = object()

    assert asyncio.run(tracking.plan_query(request)) is request
    assert tracking.stage == "PLAN_DONE"
    assert asyncio.run(tracking.draft_answer(facts=(), limitations=(), question="q")) == {
        "facts": (),
        "limitations": (),
        "question": "q",
    }
    assert tracking.stage == "DRAFT_DONE"


def test_school_activation_maps_preflight_failure(monkeypatch) -> None:
    class Engine:
        def __init__(self, **_kwargs):
            raise AssertionError("engine must not be constructed")

    monkeypatch.setattr(
        "ai_service.property_chat.reference_activation.GroundedChatbotEngine", Engine
    )

    with pytest.raises(ReferenceActivationError, match="SCHOOL_OBSERVATION_FAILED"):
        asyncio.run(
            run_school_activation_case(
                property_repository=_PropertyRepository([]),
                school_repository=_SchoolRepository(),
                language_model=object(),
            )
        )


def test_school_activation_main_closes_repositories(monkeypatch, capsys) -> None:
    property_repository = _PropertyRepository()
    school_repository = _SchoolRepository()
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: property_repository
    )
    monkeypatch.setattr(
        "ai_service.chat.get_school_fact_repository", lambda: school_repository
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", object)

    async def successful_case(**_kwargs):
        return {
            "caseId": "school-location-jamsil-ells",
            "capability": "school_location",
            "factCount": 4,
            "citationCount": 2,
            "dataAsOf": "2026-03-20",
        }

    monkeypatch.setattr(
        "ai_service.property_chat.reference_activation.run_school_activation_case",
        successful_case,
    )

    assert main() == 0
    assert property_repository.closed is True
    assert school_repository.closed is True
    assert "상태: Pass" in capsys.readouterr().out


def test_school_activation_main_returns_stable_failure(monkeypatch, capsys) -> None:
    property_repository = _PropertyRepository()
    school_repository = _SchoolRepository()
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: property_repository
    )
    monkeypatch.setattr(
        "ai_service.chat.get_school_fact_repository", lambda: school_repository
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", object)

    async def failed_case(**_kwargs):
        raise ReferenceActivationError("SCHOOL_DRAFT_STAGE_FAILED")

    monkeypatch.setattr(
        "ai_service.property_chat.reference_activation.run_school_activation_case",
        failed_case,
    )

    assert main() == 1
    output = capsys.readouterr().out
    assert "SCHOOL_DRAFT_STAGE_FAILED" in output
    assert property_repository.closed is True
    assert school_repository.closed is True


def test_comparison_activation_main_uses_rail_and_closes_repositories(
    monkeypatch, capsys
) -> None:
    property_repository = _PropertyRepository()
    rail_repository = _PropertyRepository()
    monkeypatch.setenv(
        "HOME_AI_REFERENCE_ACTIVATION_CASE_ID",
        "comparison-jamsil-ells-helio-84",
    )
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: property_repository
    )
    monkeypatch.setattr(
        "ai_service.chat.get_rail_station_repository", lambda: rail_repository
    )
    monkeypatch.setattr("ai_service.chat.get_grounded_language_model", object)

    async def successful_case(**_kwargs):
        return {
            "caseId": "comparison-jamsil-ells-helio-84",
            "capability": "comparison",
            "factCount": 6,
            "citationCount": 4,
            "dataAsOf": "2026-06-12",
        }

    monkeypatch.setattr(
        "ai_service.property_chat.reference_activation.run_comparison_activation_case",
        successful_case,
    )

    assert main() == 0
    assert property_repository.closed is True
    assert rail_repository.closed is True
    assert "capability: comparison" in capsys.readouterr().out
