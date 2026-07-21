package com.home.application.ingest.buildingprofile;

import java.util.Objects;
import java.util.UUID;

public record BuildingProfileReplayCommand(
        UUID sourceCollectionId, UUID parseRunId, String parserVersion, int maxPages) {
    public BuildingProfileReplayCommand {
        Objects.requireNonNull(sourceCollectionId, "sourceCollectionId");
        Objects.requireNonNull(parseRunId, "parseRunId");
        if (parserVersion == null || parserVersion.isBlank() || parserVersion.length() > 80) {
            throw new IllegalArgumentException("parserVersion must be 1..80 characters");
        }
        if (maxPages <= 0 || maxPages > 100_000) throw new IllegalArgumentException("maxPages must be 1..100000");
    }
}
