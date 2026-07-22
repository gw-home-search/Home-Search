package com.home.application.ingest.buildingprofile;

public record BuildingProfileReplaySummary(int pageCount, long recordCount, int failureCount, boolean completed) {}
