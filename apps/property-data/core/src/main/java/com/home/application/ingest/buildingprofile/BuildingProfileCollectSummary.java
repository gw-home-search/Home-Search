package com.home.application.ingest.buildingprofile;

public record BuildingProfileCollectSummary(
        int pnuCount, int requestCount, int collectedCount, int failureCount, boolean completed) {}
