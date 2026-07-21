package com.home.application.ingest.buildingprofile;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record BuildingProfileCollectCommand(
        UUID collectionId,
        UUID requestId,
        LocalDate runDate,
        int sampleSize,
        String selectionSeed,
        int maxRequests,
        int parallelism) {
    public BuildingProfileCollectCommand {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(runDate, "runDate");
        if (sampleSize <= 0) throw new IllegalArgumentException("sampleSize must be positive");
        if (selectionSeed == null || selectionSeed.isBlank() || selectionSeed.length() > 200) {
            throw new IllegalArgumentException("selectionSeed must be 1..200 characters");
        }
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
        if (parallelism < 1 || parallelism > 4) throw new IllegalArgumentException("parallelism must be 1..4");
    }
}
