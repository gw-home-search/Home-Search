package com.home.domain.insight;

import java.util.Objects;

public record MarketInsightCoverage(
        RtmsCollectionMode collectionMode,
        RtmsCollectionScopeType scopeType,
        int plannedCount,
        int completedCount,
        int partialCount,
        int failedCount) {

    public MarketInsightCoverage {
        Objects.requireNonNull(collectionMode, "collectionMode is required");
        Objects.requireNonNull(scopeType, "scopeType is required");
        if (plannedCount < 0 || completedCount < 0 || partialCount < 0 || failedCount < 0) {
            throw new IllegalArgumentException("coverage counts must not be negative");
        }
        if (completedCount + partialCount + failedCount > plannedCount) {
            throw new IllegalArgumentException("terminal work unit count must not exceed plannedCount");
        }
    }
}
