package com.home.domain.insight;

public record MarketInsightCoverageDecision(boolean publishable, MarketInsightRejectionReason reason) {

    static MarketInsightCoverageDecision accepted() {
        return new MarketInsightCoverageDecision(true, null);
    }

    static MarketInsightCoverageDecision rejected(MarketInsightRejectionReason reason) {
        return new MarketInsightCoverageDecision(false, reason);
    }
}
