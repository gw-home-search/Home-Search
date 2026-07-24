package com.home.domain.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MarketInsightRankingPolicyTest {

    private final MarketInsightRankingPolicy policy = MarketInsightRankingPolicy.rollingSevenDay();

    @Test
    void limitsCurrentDealsToOneCalendarMonth() {
        assertThat(policy.earliestCurrentDealDate(LocalDate.parse("2026-07-22")))
                .isEqualTo(LocalDate.parse("2026-06-22"));
    }

    @Test
    void limitsPreviousDealToSixCalendarMonthsBeforeCurrentDeal() {
        LocalDate current = LocalDate.parse("2026-07-18");

        assertThat(policy.isComparablePreviousDeal(current, LocalDate.parse("2026-01-18")))
                .isTrue();
        assertThat(policy.isComparablePreviousDeal(current, LocalDate.parse("2026-01-17")))
                .isFalse();
        assertThat(policy.isComparablePreviousDeal(current, current)).isFalse();
    }
}
