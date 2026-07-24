package com.home.domain.insight;

import java.time.LocalDate;
import java.util.Objects;

public final class MarketInsightRankingPolicy {

    private static final MarketInsightRankingPolicy ROLLING_SEVEN_DAY = new MarketInsightRankingPolicy(1, 6);

    private final int currentDealLookbackMonths;
    private final int previousDealLookbackMonths;

    private MarketInsightRankingPolicy(int currentDealLookbackMonths, int previousDealLookbackMonths) {
        this.currentDealLookbackMonths = currentDealLookbackMonths;
        this.previousDealLookbackMonths = previousDealLookbackMonths;
    }

    public static MarketInsightRankingPolicy rollingSevenDay() {
        return ROLLING_SEVEN_DAY;
    }

    public int currentDealLookbackMonths() {
        return currentDealLookbackMonths;
    }

    public int previousDealLookbackMonths() {
        return previousDealLookbackMonths;
    }

    public LocalDate earliestCurrentDealDate(LocalDate periodEnd) {
        return Objects.requireNonNull(periodEnd, "periodEnd is required").minusMonths(currentDealLookbackMonths);
    }

    public boolean isComparablePreviousDeal(LocalDate currentDealDate, LocalDate previousDealDate) {
        Objects.requireNonNull(currentDealDate, "currentDealDate is required");
        Objects.requireNonNull(previousDealDate, "previousDealDate is required");
        return previousDealDate.isBefore(currentDealDate)
                && !previousDealDate.isBefore(currentDealDate.minusMonths(previousDealLookbackMonths));
    }
}
