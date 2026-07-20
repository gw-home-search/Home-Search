package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRatioField;
import java.util.Map;

public record BuildingRatioRecordedEvaluation(Map<BuildingRatioField, Long> selectedCandidateIds) {
    public BuildingRatioRecordedEvaluation {
        selectedCandidateIds = selectedCandidateIds == null ? Map.of() : Map.copyOf(selectedCandidateIds);
    }
}
