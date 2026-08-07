from __future__ import annotations

from dataclasses import replace
from datetime import UTC, date, datetime

import pytest

from ai_service.property_chat.candidate_selection import (
    CandidateMatch,
    CandidateObservationSummary,
    DeterministicCandidateSelector,
    select_compound_primary,
    validate_grounded_selection,
)
from ai_service.property_chat.models import ComplexRecord
from ai_service.property_chat.models import FocusComplexAction
from ai_service.property_chat.engine import (
    GroundingValidationError,
    _complex_fact,
    _focus_complex_actions,
    _validate_focus_action,
)
from ai_service.property_chat.answer_document import _bounded_actions


def candidate(complex_id: int, *, units: int) -> CandidateMatch:
    return CandidateMatch(ComplexRecord(
        complex_id=complex_id,
        display_name=f"후보 {complex_id}",
        region_code="11440",
        region_name="마포구",
        address=f"서울 마포구 후보 {complex_id}",
        latitude=37.55,
        longitude=126.95,
        marker_safe=True,
        data_updated_at=datetime(2026, 7, 31, tzinfo=UTC),
        unit_count=units,
        parcel_id=8015,
    ), search_ordinal=complex_id)


def test_grounded_selector_accepts_only_eligible_ids_and_real_reason_facts() -> None:
    candidates = (candidate(7753, units=5_000), candidate(7756, units=1_237))
    observations = (
        CandidateObservationSummary(7753, 0, None, ("recent_trade_lookup",)),
        CandidateObservationSummary(
            7756, 5, date(2026, 6, 20), ("recent_trade_lookup",)
        ),
    )
    deterministic = DeterministicCandidateSelector().select(candidates, observations)

    selected = validate_grounded_selection({
        "selectedComplexIds": [7756],
        "reasonFactIds": ["property-complex-7756", "candidate-observation-7756"],
        "reasonCode": "EXACT_DATA_AND_PUBLIC_SCALE",
    }, candidates, observations, deterministic)

    assert selected.primary.complex.complex_id == 7756
    assert selected.source == "GROUNDED_AI"

    with pytest.raises(ValueError, match="ignored exact data"):
        validate_grounded_selection({
            "selectedComplexIds": [7753],
            "reasonFactIds": ["property-complex-7753"],
            "reasonCode": "PUBLIC_SCALE",
        }, candidates, observations, deterministic)
    with pytest.raises(ValueError, match="reason"):
        validate_grounded_selection({
            "selectedComplexIds": [7756],
            "reasonFactIds": ["invented-fact"],
            "reasonCode": "EXACT_DATA",
        }, candidates, observations, deterministic)
    with pytest.raises(ValueError, match="incomplete"):
        validate_grounded_selection({
            "selectedComplexIds": [7756],
            "reasonFactIds": ["property-complex-7756"],
            "reasonCode": "EXACT_DATA",
        }, candidates, observations, deterministic)


def test_deterministic_selector_uses_exact_data_before_household_count() -> None:
    candidates = (candidate(7753, units=5_000), candidate(7756, units=1_237))
    selection = DeterministicCandidateSelector().select(candidates, (
        CandidateObservationSummary(7753, 0, None),
        CandidateObservationSummary(7756, 1, date(2026, 6, 20)),
    ))

    assert selection.primary.complex.complex_id == 7756
    assert selection.reason_code == "EXACT_OBSERVATION"


def test_candidate_selector_covers_empty_and_comparison_boundaries() -> None:
    with pytest.raises(ValueError, match="requires candidates"):
        DeterministicCandidateSelector().select(())
    with pytest.raises(ValueError, match="summary is invalid"):
        CandidateObservationSummary(0, -1, None)

    first = replace(candidate(7753, units=388), selected_context_match=True)
    second = candidate(7756, units=1_237)
    selection = DeterministicCandidateSelector().select(
        (second, first), comparison=True
    )

    assert selection.primary == first
    assert selection.comparison_secondary == second
    assert selection.alternatives == ()
    assert selection.source == "EXPLICIT_CONTEXT"
    assert selection.reason_code == "REPRESENTATIVE_COMPLEX"


def test_candidate_selector_marks_verified_zero_observations() -> None:
    selection = DeterministicCandidateSelector().select(
        (candidate(7753, units=388),),
        (CandidateObservationSummary(7753, 0, None),),
    )

    assert selection.reason_code == "NO_EXACT_OBSERVATION"
    assert selection.all_exact_results_empty is True
    assert selection.reason_fact_ids == (
        "property-complex-7753",
        "candidate-observation-7753",
    )


def test_compound_selector_uses_supported_capabilities_and_latest_date() -> None:
    candidates = (candidate(7753, units=5_000), candidate(7756, units=1_237))
    groups = (
        (
            CandidateObservationSummary(7753, 1, date(2026, 5, 1)),
            CandidateObservationSummary(7756, 2, date(2026, 6, 1)),
        ),
        (CandidateObservationSummary(7756, 1, date(2026, 7, 1)),),
    )

    assert select_compound_primary(candidates, groups).complex.complex_id == 7756
    with pytest.raises(ValueError, match="requires candidates"):
        select_compound_primary((), ())
    with pytest.raises(ValueError, match="candidate is invalid"):
        select_compound_primary(
            candidates,
            ((CandidateObservationSummary(9999, 1, date(2026, 7, 1)),),),
        )


def test_focus_complex_action_requires_marker_safe_fact_identity_and_korea_bounds() -> None:
    match = candidate(7756, units=1_237)
    fact = _complex_fact(match.complex)
    action = FocusComplexAction(
        label="후보 7756 지도에서 보기",
        parcel_id=8015,
        complex_id=7756,
        latitude=37.55,
        longitude=126.95,
        auto_run=True,
        fact_ids=(fact.fact_id,),
    )

    _validate_focus_action(action, fact)
    assert action.to_public_dict("request-1") == {
        "type": "focusComplex", "version": 1,
        "actionId": "action-request-1-focus-complex-7756",
        "label": "후보 7756 지도에서 보기", "parcelId": 8015,
        "complexId": 7756, "center": {"lat": 37.55, "lng": 126.95},
        "level": 4, "openDetail": True, "autoRun": True,
        "factIds": ["property-complex-7756"],
    }
    with pytest.raises(ValueError, match="invalid"):
        FocusComplexAction(
            label="잘못된 좌표", parcel_id=8015, complex_id=7756,
            latitude=32.9, longitude=126.95, auto_run=False,
            fact_ids=(fact.fact_id,),
        )
    with pytest.raises(GroundingValidationError):
        _validate_focus_action(
            FocusComplexAction(
                label="불일치", parcel_id=8015, complex_id=7753,
                latitude=37.55, longitude=126.95, auto_run=False,
                fact_ids=(fact.fact_id,),
            ),
            fact,
        )


def test_marker_unsafe_exact_complex_does_not_create_focus_action() -> None:
    primary = candidate(7756, units=1_237)
    primary = replace(
        primary,
        complex=replace(primary.complex, marker_safe=False),
    )

    actions = _focus_complex_actions(primary)

    assert actions == ()


def test_action_bounds_keep_six_focus_four_nearby_and_one_auto_run() -> None:
    focus = [{
        "type": "focusComplex", "version": 1,
        "actionId": f"focus-{index}", "label": "지도에서 보기",
        "parcelId": 8000 + index, "complexId": 7000 + index,
        "center": {"lat": 37.5, "lng": 127.0}, "level": 4,
        "openDetail": True, "autoRun": index in {0, 1},
        "factIds": [f"property-complex-{7000 + index}"],
    } for index in range(8)]
    nearby = [{
        "type": "showNearbyCategory", "version": 1,
        "actionId": f"nearby-{index}", "label": "병원 보기",
        "category": "HOSPITAL", "center": {"lat": 37.5, "lng": 127.0},
        "level": 4, "factIds": ["property-complex-7000"],
    } for index in range(6)]

    bounded = _bounded_actions((*focus, *nearby))

    assert len(bounded) == 10
    assert sum(item["type"] == "focusComplex" for item in bounded) == 6
    assert sum(item["type"] == "showNearbyCategory" for item in bounded) == 4
    assert sum(item.get("autoRun") is True for item in bounded) == 1
