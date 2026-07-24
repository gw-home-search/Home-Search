package com.home.domain.complex.buildingregister;

import java.util.Set;

public record BuildingRegisterFollowUpDecision(
        boolean fetchTitles, boolean fetchBasicOverview, Set<BuildingRatioField> fallbackFields) {
    public BuildingRegisterFollowUpDecision {
        fallbackFields = fallbackFields == null ? Set.of() : Set.copyOf(fallbackFields);
    }
}
