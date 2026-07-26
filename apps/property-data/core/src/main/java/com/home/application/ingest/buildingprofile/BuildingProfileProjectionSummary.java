package com.home.application.ingest.buildingprofile;

public record BuildingProfileProjectionSummary(
        int eligibleFieldCount,
        int complexCount,
        int projectableComplexCount,
        int buildingCount,
        String complexSnapshotSha256,
        boolean alreadyCompleted) {}
