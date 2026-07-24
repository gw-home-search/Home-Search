package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRatioField;
import java.util.Objects;

public record BuildingRatioProjectionTarget(long matchId, BuildingRatioField field, Long candidateId) {
    public BuildingRatioProjectionTarget {
        if (matchId <= 0) throw new IllegalArgumentException("matchId must be positive");
        Objects.requireNonNull(field, "field");
        if (candidateId != null && candidateId <= 0) {
            throw new IllegalArgumentException("candidateId must be positive");
        }
    }
}
