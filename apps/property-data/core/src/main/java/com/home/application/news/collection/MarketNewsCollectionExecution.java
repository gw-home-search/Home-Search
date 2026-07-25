package com.home.application.news.collection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MarketNewsCollectionExecution(
        UUID executionId,
        String requestId,
        String executionType,
        String policyVersion,
        Instant scheduledAt,
        Instant overlapCutoff,
        int callBudget,
        int consumedCallCount,
        int plannedWorkUnitCount,
        int completedWorkUnitCount,
        int failedWorkUnitCount,
        int truncatedWorkUnitCount,
        int skippedBudgetWorkUnitCount,
        List<MarketNewsWorkUnitSpec> workUnits) {}
