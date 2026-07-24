package com.home.domain.insight;

public record MarketInsightQuality(
        int missingRegistrationDateCount,
        int invalidRegistrationDateCount,
        int missingCancellationDateCount,
        int invalidCancellationDateCount) {

    public static final MarketInsightQuality NONE = new MarketInsightQuality(0, 0, 0, 0);

    public MarketInsightQuality {
        if (missingRegistrationDateCount < 0
                || invalidRegistrationDateCount < 0
                || missingCancellationDateCount < 0
                || invalidCancellationDateCount < 0) {
            throw new IllegalArgumentException("quality counts must not be negative");
        }
    }

    public int excludedCount() {
        return missingCancellationDateCount + invalidCancellationDateCount;
    }

    public int registrationDateFallbackCount() {
        return missingRegistrationDateCount + invalidRegistrationDateCount;
    }
}
