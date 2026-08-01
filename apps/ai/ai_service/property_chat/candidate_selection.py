from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Literal

from .models import ComplexRecord, QueryCapability


SelectionSource = Literal["EXPLICIT_CONTEXT", "GROUNDED_AI", "DETERMINISTIC"]


@dataclass(frozen=True)
class CandidateMatch:
    complex: ComplexRecord
    search_ordinal: int
    match_tier: int = 3
    explicit_region_match: bool = False
    selected_context_match: bool = False


@dataclass(frozen=True)
class CandidateObservationSummary:
    complex_id: int
    exact_observation_count: int
    latest_observation_date: date | None
    supported_capabilities: tuple[QueryCapability, ...] = ()

    def __post_init__(self) -> None:
        if self.complex_id <= 0 or self.exact_observation_count < 0:
            raise ValueError("candidate observation summary is invalid")


@dataclass(frozen=True)
class CandidateSelection:
    primary: CandidateMatch
    comparison_secondary: CandidateMatch | None
    alternatives: tuple[CandidateMatch, ...]
    source: SelectionSource
    reason_fact_ids: tuple[str, ...]
    reason_code: str
    all_exact_results_empty: bool


class DeterministicCandidateSelector:
    def select(
        self,
        candidates: tuple[CandidateMatch, ...],
        observations: tuple[CandidateObservationSummary, ...] = (),
        *,
        comparison: bool = False,
    ) -> CandidateSelection:
        if not candidates:
            raise ValueError("candidate selection requires candidates")
        summary_by_id = {summary.complex_id: summary for summary in observations}
        has_exact = any(summary.exact_observation_count > 0 for summary in observations)
        eligible = tuple(
            candidate for candidate in candidates
            if not has_exact
            or summary_by_id.get(
                candidate.complex.complex_id,
                CandidateObservationSummary(candidate.complex.complex_id, 0, None),
            ).exact_observation_count > 0
        )
        def key(candidate: CandidateMatch) -> tuple[object, ...]:
            record = candidate.complex
            summary = summary_by_id.get(
                record.complex_id,
                CandidateObservationSummary(record.complex_id, 0, None),
            )
            return (
                -int(candidate.selected_context_match),
                -int(candidate.explicit_region_match),
                -int(summary.exact_observation_count > 0),
                candidate.match_tier,
                -int(record.marker_safe),
                record.unit_count is None,
                -(record.unit_count or 0),
                summary.latest_observation_date is None,
                -(summary.latest_observation_date.toordinal()
                  if summary.latest_observation_date else 0),
                -summary.exact_observation_count,
                record.complex_id,
            )

        ordered = tuple(sorted(eligible, key=key))
        primary = ordered[0]
        secondary = ordered[1] if comparison and len(ordered) > 1 else None
        selected_ids = {primary.complex.complex_id}
        if secondary is not None:
            selected_ids.add(secondary.complex.complex_id)
        alternatives = tuple(
            candidate for candidate in sorted(candidates, key=key)
            if candidate.complex.complex_id not in selected_ids
        )
        reason_ids = [f"property-complex-{primary.complex.complex_id}"]
        if observations:
            reason_ids.append(f"candidate-observation-{primary.complex.complex_id}")
        return CandidateSelection(
            primary=primary,
            comparison_secondary=secondary,
            alternatives=alternatives,
            source=(
                "EXPLICIT_CONTEXT"
                if primary.selected_context_match
                else "DETERMINISTIC"
            ),
            reason_fact_ids=tuple(reason_ids),
            reason_code=(
                "EXACT_OBSERVATION"
                if has_exact
                else "NO_EXACT_OBSERVATION"
                if observations
                else "REPRESENTATIVE_COMPLEX"
            ),
            all_exact_results_empty=bool(observations) and not has_exact,
        )


def select_compound_primary(
    candidates: tuple[CandidateMatch, ...],
    observation_groups: tuple[tuple[CandidateObservationSummary, ...], ...],
) -> CandidateMatch:
    if not candidates:
        raise ValueError("compound candidate selection requires candidates")
    supported_counts = {item.complex.complex_id: 0 for item in candidates}
    observation_counts = {item.complex.complex_id: 0 for item in candidates}
    latest_dates: dict[int, date | None] = {
        item.complex.complex_id: None for item in candidates
    }
    for group in observation_groups:
        for summary in group:
            if summary.complex_id not in supported_counts:
                raise ValueError("compound observation candidate is invalid")
            if summary.exact_observation_count > 0:
                supported_counts[summary.complex_id] += 1
            observation_counts[summary.complex_id] += summary.exact_observation_count
            previous = latest_dates[summary.complex_id]
            if (
                summary.latest_observation_date is not None
                and (
                    previous is None
                    or summary.latest_observation_date > previous
                )
            ):
                latest_dates[summary.complex_id] = summary.latest_observation_date

    def key(candidate: CandidateMatch) -> tuple[object, ...]:
        record = candidate.complex
        latest = latest_dates[record.complex_id]
        return (
            -supported_counts[record.complex_id],
            -int(observation_counts[record.complex_id] > 0),
            -int(candidate.explicit_region_match),
            candidate.match_tier,
            -int(record.marker_safe),
            record.unit_count is None,
            -(record.unit_count or 0),
            latest is None,
            -(latest.toordinal() if latest else 0),
            -observation_counts[record.complex_id],
            record.complex_id,
        )

    return min(candidates, key=key)


def validate_grounded_selection(
    output: object,
    candidates: tuple[CandidateMatch, ...],
    observations: tuple[CandidateObservationSummary, ...],
    deterministic: CandidateSelection,
) -> CandidateSelection:
    if not isinstance(output, dict):
        raise ValueError("grounded candidate selection is invalid")
    selected = output.get("selectedComplexIds")
    reason_ids = output.get("reasonFactIds")
    reason_code = output.get("reasonCode")
    if (
        not isinstance(selected, list)
        or len(selected) != 1
        or isinstance(selected[0], bool)
        or not isinstance(selected[0], int)
        or not isinstance(reason_ids, list)
        or not 1 <= len(reason_ids) <= 10
        or len(reason_ids) != len(set(reason_ids))
        or any(not isinstance(value, str) for value in reason_ids)
        or not isinstance(reason_code, str)
        or not 1 <= len(reason_code) <= 100
    ):
        raise ValueError("grounded candidate selection is invalid")
    candidate_by_id = {item.complex.complex_id: item for item in candidates}
    chosen = candidate_by_id.get(selected[0])
    if chosen is None:
        raise ValueError("grounded candidate selection id is invalid")
    observation_by_id = {item.complex_id: item for item in observations}
    if any(item.exact_observation_count > 0 for item in observations):
        chosen_observation = observation_by_id.get(chosen.complex.complex_id)
        if chosen_observation is None or chosen_observation.exact_observation_count == 0:
            raise ValueError("grounded candidate selection ignored exact data")
    if any(item.explicit_region_match for item in candidates) and not chosen.explicit_region_match:
        raise ValueError("grounded candidate selection ignored explicit region")
    allowed_reason_ids = {
        *(f"property-complex-{item.complex.complex_id}" for item in candidates),
        *(f"candidate-observation-{item.complex_id}" for item in observations),
    }
    if not set(reason_ids).issubset(allowed_reason_ids):
        raise ValueError("grounded candidate selection reason is invalid")
    required_reason_ids = {f"property-complex-{chosen.complex.complex_id}"}
    if observations:
        required_reason_ids.add(f"candidate-observation-{chosen.complex.complex_id}")
    if not required_reason_ids.issubset(reason_ids):
        raise ValueError("grounded candidate selection reason is incomplete")
    alternatives = tuple(
        item for item in (deterministic.primary, *deterministic.alternatives)
        if item.complex.complex_id != chosen.complex.complex_id
    )
    return CandidateSelection(
        primary=chosen,
        comparison_secondary=None,
        alternatives=alternatives,
        source="GROUNDED_AI",
        reason_fact_ids=tuple(reason_ids),
        reason_code=reason_code,
        all_exact_results_empty=deterministic.all_exact_results_empty,
    )
