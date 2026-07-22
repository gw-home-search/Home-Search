package com.home.application.ingest.buildingprofile;

import java.util.Map;

public record BuildingProfileReportStats(
        Map<String, Long> endpointStatusCounts,
        Map<String, Long> valueStateCounts,
        Map<String, Long> codeTransitionCounts,
        long profileStorageBytes) {
    public BuildingProfileReportStats {
        endpointStatusCounts = Map.copyOf(endpointStatusCounts);
        valueStateCounts = Map.copyOf(valueStateCounts);
        codeTransitionCounts = Map.copyOf(codeTransitionCounts);
    }
}
