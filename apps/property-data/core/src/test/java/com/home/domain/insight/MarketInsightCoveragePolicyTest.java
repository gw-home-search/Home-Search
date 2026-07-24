package com.home.domain.insight;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketInsightCoveragePolicyTest {

    @Test
    @DisplayName("252개 중 4개만 완료된 전국 DAILY 실행은 insight snapshot을 publish할 수 없다")
    void incompleteNationwideDailyExecutionCannotPublish() {
        MarketInsightCoverage coverage =
                new MarketInsightCoverage(RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 252, 4, 0, 0);

        assertThat(MarketInsightCoveragePolicy.evaluate(coverage).publishable()).isFalse();
        assertThat(MarketInsightCoveragePolicy.evaluate(coverage).reason())
                .isEqualTo(MarketInsightRejectionReason.INCOMPLETE_WORKSET);
    }

    @Test
    @DisplayName("신규 거래가 0건이어도 모든 work unit이 완료된 전국 DAILY 실행은 publish할 수 있다")
    void completeNationwideDailyExecutionCanPublishEmptySnapshot() {
        MarketInsightCoverage coverage =
                new MarketInsightCoverage(RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 252, 252, 0, 0);

        assertThat(MarketInsightCoveragePolicy.evaluate(coverage).publishable()).isTrue();
        assertThat(MarketInsightCoveragePolicy.evaluate(coverage).reason()).isNull();
    }

    @Test
    @DisplayName("BACKFILL 또는 TARGETED 실행은 work unit이 모두 완료돼도 전국 snapshot을 publish할 수 없다")
    void nonDailyOrTargetedExecutionCannotPublish() {
        assertThat(MarketInsightCoveragePolicy.evaluate(new MarketInsightCoverage(
                                RtmsCollectionMode.BACKFILL, RtmsCollectionScopeType.NATIONWIDE, 252, 252, 0, 0))
                        .reason())
                .isEqualTo(MarketInsightRejectionReason.INELIGIBLE_COLLECTION_MODE);
        assertThat(MarketInsightCoveragePolicy.evaluate(new MarketInsightCoverage(
                                RtmsCollectionMode.DAILY, RtmsCollectionScopeType.TARGETED, 4, 4, 0, 0))
                        .reason())
                .isEqualTo(MarketInsightRejectionReason.INELIGIBLE_SCOPE);
    }

    @Test
    @DisplayName("PARTIAL 또는 FAILED work unit이 하나라도 있으면 publish할 수 없다")
    void partialOrFailedWorkUnitCannotPublish() {
        assertThat(MarketInsightCoveragePolicy.evaluate(new MarketInsightCoverage(
                                RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 252, 251, 1, 0))
                        .reason())
                .isEqualTo(MarketInsightRejectionReason.NON_SUCCESSFUL_WORK_UNIT);
        assertThat(MarketInsightCoveragePolicy.evaluate(new MarketInsightCoverage(
                                RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 252, 251, 0, 1))
                        .reason())
                .isEqualTo(MarketInsightRejectionReason.NON_SUCCESSFUL_WORK_UNIT);
    }
}
