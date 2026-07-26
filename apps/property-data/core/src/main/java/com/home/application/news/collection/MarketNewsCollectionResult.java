package com.home.application.news.collection;

import com.home.domain.news.MarketNewsExecutionState;
import java.util.UUID;

public record MarketNewsCollectionResult(
        UUID executionId,
        MarketNewsExecutionState state,
        int callCount,
        int completedWorkUnits,
        int failedWorkUnits,
        int truncatedWorkUnits) {}
