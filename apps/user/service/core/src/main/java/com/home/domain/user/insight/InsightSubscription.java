package com.home.domain.user.insight;

import java.util.List;

public record InsightSubscription(
        long userId,
        boolean inAppEnabled,
        boolean emailEnabled,
        boolean dailyNewsEnabled,
        boolean weeklyTradeEnabled,
        List<String> regionCodes) {

    public InsightSubscription {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        regionCodes = List.copyOf(regionCodes);
    }

    public static InsightSubscription disabled(long userId) {
        return new InsightSubscription(userId, false, false, false, false, List.of());
    }
}
