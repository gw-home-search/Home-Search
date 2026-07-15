package com.home.application.ingest.buildingmetadata;

public record BuildingMetadataBatchSummary(int targets, int requests, int resolved, int reviewRequired, int failed) {
    public static BuildingMetadataBatchSummary empty() {
        return new BuildingMetadataBatchSummary(0, 0, 0, 0, 0);
    }
}
