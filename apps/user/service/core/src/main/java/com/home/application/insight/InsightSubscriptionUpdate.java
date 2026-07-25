package com.home.application.insight;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record InsightSubscriptionUpdate(
        boolean inAppEnabled,
        boolean emailEnabled,
        boolean dailyNewsEnabled,
        boolean weeklyTradeEnabled,
        List<String> regionCodes) {

    private static final Set<String> SUPPORTED_SIDO_CODES = Set.of(
            "11", "26", "27", "28", "29", "30", "31", "36", "41", "42", "43", "44", "45", "46", "47", "48", "50");

    public InsightSubscriptionUpdate {
        if (regionCodes == null) {
            throw new InvalidInsightSubscriptionException("regionCodes is required");
        }
        regionCodes = List.copyOf(regionCodes);
        if (regionCodes.size() > 5) {
            throw new InvalidInsightSubscriptionException("regionCodes may contain at most five codes");
        }
        if (new HashSet<>(regionCodes).size() != regionCodes.size()) {
            throw new InvalidInsightSubscriptionException("regionCodes must be distinct");
        }
        if (!SUPPORTED_SIDO_CODES.containsAll(regionCodes)) {
            throw new InvalidInsightSubscriptionException("regionCodes contains an unsupported SIDO code");
        }
    }
}
