package com.home.application.ingest.buildingregister;

import java.util.Objects;
import java.util.UUID;

public record BuildingRatioProjectCommand(
        UUID collectionId, UUID requestId, int maxTargets, Long fromComplexId, Long toComplexId) {
    public BuildingRatioProjectCommand {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(requestId, "requestId");
        if (maxTargets <= 0) throw new IllegalArgumentException("maxTargets must be positive");
        if (fromComplexId != null && fromComplexId <= 0)
            throw new IllegalArgumentException("fromComplexId must be positive");
        if (toComplexId != null && toComplexId <= 0) throw new IllegalArgumentException("toComplexId must be positive");
        if (fromComplexId != null && toComplexId != null && fromComplexId > toComplexId) {
            throw new IllegalArgumentException("fromComplexId must be <= toComplexId");
        }
    }
}
