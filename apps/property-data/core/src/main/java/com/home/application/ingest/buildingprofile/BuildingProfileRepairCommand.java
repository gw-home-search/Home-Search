package com.home.application.ingest.buildingprofile;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record BuildingProfileRepairCommand(
        UUID sourceCollectionId,
        UUID collectionId,
        UUID requestId,
        LocalDate runDate,
        String repairPolicyVersion,
        int maxRequests,
        int parallelism) {
    public BuildingProfileRepairCommand {
        Objects.requireNonNull(sourceCollectionId, "sourceCollectionId");
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(runDate, "runDate");
        if (sourceCollectionId.equals(collectionId)) {
            throw new IllegalArgumentException("repair collectionId must differ from sourceCollectionId");
        }
        if (!"PROFILE_REPAIR_V1".equals(repairPolicyVersion)) {
            throw new IllegalArgumentException("repairPolicyVersion must be PROFILE_REPAIR_V1");
        }
        if (maxRequests < 1 || maxRequests > 20_000) {
            throw new IllegalArgumentException("maxRequests must be 1..20000");
        }
        if (parallelism < 1 || parallelism > 4) {
            throw new IllegalArgumentException("parallelism must be 1..4");
        }
    }
}
