package com.home.application.ingest.buildingprofile;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public record BuildingProfileAnalysisCommand(
        UUID collectionId, UUID parseRunId, UUID analysisRunId, String rulesVersion, Path outputDirectory) {
    public BuildingProfileAnalysisCommand {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(parseRunId, "parseRunId");
        Objects.requireNonNull(analysisRunId, "analysisRunId");
        if (rulesVersion == null || !rulesVersion.matches("[A-Za-z0-9_.-]{1,80}")) {
            throw new IllegalArgumentException("rulesVersion must match [A-Za-z0-9_.-]{1,80}");
        }
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        if (!outputDirectory.isAbsolute()) throw new IllegalArgumentException("outputDirectory must be absolute");
        outputDirectory = outputDirectory.normalize();
    }
}
