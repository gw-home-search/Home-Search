package com.home.application.ingest.buildingprofile;

import java.nio.file.Path;
import java.util.List;

public record BuildingProfileAnalysisSummary(
        int assignmentCount,
        int complexMatchCount,
        int comparisonCount,
        int fieldCount,
        List<Path> reportFiles,
        boolean alreadyCompleted) {
    public BuildingProfileAnalysisSummary {
        reportFiles = List.copyOf(reportFiles);
    }
}
