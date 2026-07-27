package com.home.application.ingest.buildingprofile;

import java.util.UUID;

public record BuildingProfilePublicationCommand(
        UUID publicationId, UUID projectionRunId, String rulesVersion, boolean publish, boolean backfill) {
    public BuildingProfilePublicationCommand {
        if (publicationId == null) throw new IllegalArgumentException("publicationId is required");
        if (projectionRunId == null) throw new IllegalArgumentException("projectionRunId is required");
        if (rulesVersion == null || rulesVersion.isBlank()) {
            throw new IllegalArgumentException("rulesVersion is required");
        }
        if (rulesVersion.length() > 80)
            throw new IllegalArgumentException("rulesVersion must be at most 80 characters");
        if (backfill && !publish) {
            throw new IllegalArgumentException("backfill requires publish=true");
        }
    }
}
