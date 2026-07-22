package com.home.domain.insight;

import java.util.Objects;

public final class MarketInsightCoveragePolicy {

    private MarketInsightCoveragePolicy() {}

    public static MarketInsightCoverageDecision evaluate(MarketInsightCoverage coverage) {
        Objects.requireNonNull(coverage, "coverage is required");
        if (!coverage.collectionMode().qualifiesForDailyInsight()) {
            return MarketInsightCoverageDecision.rejected(MarketInsightRejectionReason.INELIGIBLE_COLLECTION_MODE);
        }
        if (!coverage.scopeType().qualifiesForNationwideInsight()) {
            return MarketInsightCoverageDecision.rejected(MarketInsightRejectionReason.INELIGIBLE_SCOPE);
        }
        if (coverage.partialCount() > 0 || coverage.failedCount() > 0) {
            return MarketInsightCoverageDecision.rejected(MarketInsightRejectionReason.NON_SUCCESSFUL_WORK_UNIT);
        }
        if (coverage.completedCount() != coverage.plannedCount()) {
            return MarketInsightCoverageDecision.rejected(MarketInsightRejectionReason.INCOMPLETE_WORKSET);
        }
        return MarketInsightCoverageDecision.accepted();
    }
}
