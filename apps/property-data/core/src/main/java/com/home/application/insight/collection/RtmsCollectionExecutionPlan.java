package com.home.application.insight.collection;

import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record RtmsCollectionExecutionPlan(
        ExecutionCorrelationId executionId,
        RtmsCollectionMode collectionMode,
        RtmsCollectionScopeType scopeType,
        LocalDate runDate,
        List<RtmsCollectionWorkUnitPlan> workUnits) {

    public RtmsCollectionExecutionPlan {
        Objects.requireNonNull(executionId, "executionId is required");
        Objects.requireNonNull(collectionMode, "collectionMode is required");
        Objects.requireNonNull(scopeType, "scopeType is required");
        Objects.requireNonNull(runDate, "runDate is required");
        workUnits = List.copyOf(Objects.requireNonNull(workUnits, "workUnits are required"));
        if (workUnits.isEmpty()) {
            throw new IllegalArgumentException("workUnits must not be empty");
        }
        if (workUnits.stream().distinct().count() != workUnits.size()) {
            throw new IllegalArgumentException("workUnits must be unique");
        }
    }
}
