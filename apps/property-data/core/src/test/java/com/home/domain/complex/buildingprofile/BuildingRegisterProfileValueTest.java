package com.home.domain.complex.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRegisterProfileValueTest {
    private final BuildingProfileValueClassifier classifier = new BuildingProfileValueClassifier();
    private final BuildingProfileRatioCalculator calculator = new BuildingProfileRatioCalculator();

    @Test
    @DisplayName("숫자 source 상태는 absent null blank zero positive invalid를 구분한다")
    void classifiesNumericSourceStatesWithoutDroppingZero() {
        assertThat(classifier
                        .classify(BuildingProfileField.PLAT_AREA, false, null)
                        .state())
                .isEqualTo(BuildingProfileValueState.ABSENT);
        assertThat(classifier
                        .classify(BuildingProfileField.PLAT_AREA, true, null)
                        .state())
                .isEqualTo(BuildingProfileValueState.NULL);
        assertThat(classifier
                        .classify(BuildingProfileField.PLAT_AREA, true, "  ")
                        .state())
                .isEqualTo(BuildingProfileValueState.BLANK);
        assertThat(classifier
                        .classify(BuildingProfileField.PLAT_AREA, true, "0")
                        .state())
                .isEqualTo(BuildingProfileValueState.ZERO);
        assertThat(classifier
                        .classify(BuildingProfileField.PLAT_AREA, true, "12.50")
                        .state())
                .isEqualTo(BuildingProfileValueState.POSITIVE);
        assertThat(classifier
                        .classify(BuildingProfileField.PLAT_AREA, true, "not-a-number")
                        .state())
                .isEqualTo(BuildingProfileValueState.INVALID);
    }

    @Test
    @DisplayName("용적률 재계산은 vlRatEstmTotArea만 사용하고 totArea를 사용하지 않는다")
    void recalculatesFloorAreaRatioFromEstimateAreaOnly() {
        List<BuildingProfileAreaContribution> titles = List.of(
                new BuildingProfileAreaContribution("A", new BigDecimal("100"), new BigDecimal("40")),
                new BuildingProfileAreaContribution("B", new BigDecimal("900"), new BigDecimal("60")));

        BuildingProfileCalculatedRatios ratios = calculator.calculate(new BigDecimal("200"), titles, true);

        assertThat(ratios.buildingCoverageRatio()).isEqualByComparingTo("500.0");
        assertThat(ratios.floorAreaRatio()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("기대 표제부가 불완전하면 SUM 후보를 만들지 않는다")
    void doesNotCreateSumsForIncompleteTitleSet() {
        BuildingProfileCalculatedRatios ratios = calculator.calculate(
                new BigDecimal("200"),
                List.of(new BuildingProfileAreaContribution("A", new BigDecimal("100"), null)),
                false);

        assertThat(ratios.complete()).isFalse();
        assertThat(ratios.buildingCoverageRatio()).isNull();
        assertThat(ratios.floorAreaRatio()).isNull();
    }
}
