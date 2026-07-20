package com.home.domain.complex.buildingregister;

import java.util.List;

public record BuildingRatioFieldEvaluation(
        BuildingRatioField field,
        BuildingRatioResolutionStatus status,
        BuildingRatioCandidate selectedCandidate,
        List<BuildingRatioCandidate> candidates) {
    public BuildingRatioFieldEvaluation {
        if (field == null || status == null)
            throw new IllegalArgumentException("field evaluation identity is required");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public boolean projectable() {
        return status == BuildingRatioResolutionStatus.SELECTED;
    }
}
