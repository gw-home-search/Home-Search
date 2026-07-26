package com.home.domain.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketNewsFailureKindTest {

    @Test
    @DisplayName("뉴스 수집 영속 실패 사유는 안정된 한국어 운영 메타데이터를 제공한다")
    void exposesStableOperationalMetadata() {
        assertThat(MarketNewsFailureKind.values()).allSatisfy(kind -> {
            assertThat(kind.titleKo()).isNotBlank();
            assertThat(kind.descriptionKo()).isNotBlank();
        });
    }

    @Test
    @DisplayName("인증·provider 일일 한도·내부 호출 예산 실패만 남은 work unit을 중단한다")
    void identifiesFailuresThatStopRemainingWork() {
        assertThat(MarketNewsFailureKind.values())
                .filteredOn(MarketNewsFailureKind::stopsRemainingWork)
                .containsExactlyInAnyOrder(
                        MarketNewsFailureKind.AUTHENTICATION,
                        MarketNewsFailureKind.DAILY_QUOTA,
                        MarketNewsFailureKind.DAILY_CALL_BUDGET);
    }
}
