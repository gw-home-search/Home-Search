package com.home.application.ingest.buildingprofile;

public record BuildingProfileRepairSummary(
        int targetCount, int requestCount, int completedCount, int failureCount, boolean completed) {}
