from __future__ import annotations


class RecommendationExecutionError(RuntimeError):
    """Stable non-disclosing phase failure for recommendation execution."""

    _REASONS = frozenset({
        "RECOMMENDATION_PLAN_VALIDATION_FAILED",
        "RECOMMENDATION_PROPERTY_CANDIDATE_FAILED",
        "RECOMMENDATION_RAIL_BATCH_FAILED",
        "RECOMMENDATION_RETAIL_BATCH_FAILED",
        "RECOMMENDATION_OBSERVATION_ASSEMBLY_FAILED",
        "RECOMMENDATION_TEXT_PRESENTATION_FAILED",
        "RECOMMENDATION_STRUCTURED_PRESENTATION_FAILED",
        "RECOMMENDATION_DOCUMENT_FAILED",
        "RECOMMENDATION_CITATION_SERIALIZATION_FAILED",
        "RECOMMENDATION_UI_SUMMARY_SERIALIZATION_FAILED",
        "RECOMMENDATION_RESPONSE_SERIALIZATION_FAILED",
    })

    def __init__(self, reason_code: str) -> None:
        if reason_code not in self._REASONS:
            raise ValueError("invalid recommendation execution failure reason")
        super().__init__()
        self.reason_code = reason_code
