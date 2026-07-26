package com.home.application.ingest.buildingprofile;

import java.util.Objects;
import java.util.UUID;

public record BuildingProfileProjectionCommand(UUID projectionRunId, UUID analysisRunId, String projectionVersion) {
    public BuildingProfileProjectionCommand {
        Objects.requireNonNull(projectionRunId, "projectionRunId");
        Objects.requireNonNull(analysisRunId, "analysisRunId");
        if (projectionVersion == null || projectionVersion.isBlank()) {
            throw new IllegalArgumentException("projectionVersion is required");
        }
    }
}
