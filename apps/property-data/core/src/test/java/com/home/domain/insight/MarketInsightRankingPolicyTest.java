package com.home.domain.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketInsightRankingPolicyTest {

    private final MarketInsightRankingPolicy policy = MarketInsightRankingPolicy.rollingSevenDay();

    @Test
    @DisplayName("현재 거래 후보를 최근 한 달로 제한한다")
    void limitsCurrentDealsToOneCalendarMonth() {
        assertThat(policy.earliestCurrentDealDate(LocalDate.parse("2026-07-22")))
                .isEqualTo(LocalDate.parse("2026-06-22"));
    }

    @Test
    @DisplayName("직전 거래를 현재 거래 전 6개월 이내로 제한한다")
    void limitsPreviousDealToSixCalendarMonthsBeforeCurrentDeal() {
        LocalDate current = LocalDate.parse("2026-07-18");

        assertThat(policy.isComparablePreviousDeal(current, LocalDate.parse("2026-01-18")))
                .isTrue();
        assertThat(policy.isComparablePreviousDeal(current, LocalDate.parse("2026-01-17")))
                .isFalse();
        assertThat(policy.isComparablePreviousDeal(current, current)).isFalse();
    }
}
