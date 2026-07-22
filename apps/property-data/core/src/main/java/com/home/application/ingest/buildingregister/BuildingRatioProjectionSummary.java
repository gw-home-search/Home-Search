package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRatioProjectionOutcome;
import java.util.Map;

public record BuildingRatioProjectionSummary(
        int candidateCount, Map<BuildingRatioProjectionOutcome, Integer> outcomes) {
    public BuildingRatioProjectionSummary {
        outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
    }
}
