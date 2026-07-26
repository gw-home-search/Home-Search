package com.home.domain.complex.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingProfileEffectiveValuePolicyTest {
    private final BuildingProfileEffectiveValuePolicy policy = new BuildingProfileEffectiveValuePolicy();

    @Test
    @DisplayName("shared PNU의 모든 현행 root가 허용차 안에서 같으면 fallback을 만든다")
    void createsPnuFallbackForConsensus() {
        BuildingProfileDecimalDecision decision = policy.pnuConsensus(
                List.of(new BigDecimal("84.480"), new BigDecimal("84.489")),
                BuildingProfileEffectiveValuePolicy.RATIO_TOLERANCE);

        assertThat(decision.value()).isEqualByComparingTo("84.480");
        assertThat(decision.scope()).isEqualTo(BuildingProfilePublicScope.PARCEL);
        assertThat(decision.quality()).isEqualTo(BuildingProfilePublicQuality.PNU_FALLBACK);
        assertThat(decision.conflictStatus()).isEqualTo(BuildingProfileConflictStatus.NONE);
    }

    @Test
    @DisplayName("shared PNU root가 충돌하면 scalar를 만들지 않는다")
    void rejectsConflictingPnuRoots() {
        BuildingProfileDecimalDecision decision = policy.pnuConsensus(
                List.of(new BigDecimal("84.48"), new BigDecimal("84.50")),
                BuildingProfileEffectiveValuePolicy.RATIO_TOLERANCE);

        assertThat(decision.value()).isNull();
        assertThat(decision.conflictStatus()).isEqualTo(BuildingProfileConflictStatus.SOURCE_CONFLICT);
    }

    @Test
    @DisplayName("SUM은 기대 contributor가 모두 유효할 때만 만든다")
    void requiresCompleteContributorsForSum() {
        assertThat(policy.completeSum(List.of(new BigDecimal("10")), 2).value()).isNull();
        assertThat(policy.completeSum(List.of(new BigDecimal("10"), new BigDecimal("20")), 2).value())
                .isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("MAX는 일부 contributor만 있어도 PARTIAL 후보로 제공한다")
    void exposesPartialMaximumWithoutVerification() {
        BuildingProfileDecimalDecision decision =
                policy.maximum(List.of(new BigDecimal("21.5")), 2);

        assertThat(decision.value()).isEqualByComparingTo("21.5");
        assertThat(decision.quality()).isEqualTo(BuildingProfilePublicQuality.PARTIAL);
        assertThat(decision.complete()).isFalse();
    }
}
