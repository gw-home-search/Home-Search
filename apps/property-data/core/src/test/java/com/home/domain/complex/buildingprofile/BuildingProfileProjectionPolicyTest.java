package com.home.domain.complex.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BuildingProfileProjectionPolicyTest {
    private final BuildingProfileProjectionPolicy policy = new BuildingProfileProjectionPolicy();

    @Test
    void fixesFiftyFiveNormalizedFieldsAtNationwideReadinessThreshold() {
        assertThat(policy.minimumReadiness()).isEqualByComparingTo("0.4492986425");
        assertThat(policy.fields()).hasSize(55);
        assertThat(policy.fields())
                .contains(
                        BuildingProfileField.HHLD_CNT,
                        BuildingProfileField.PLAT_AREA,
                        BuildingProfileField.MAIN_PURPS_CD,
                        BuildingProfileField.INDR_AUTO_UTCNT,
                        BuildingProfileField.USE_APR_DAY);
        assertThat(policy.fields())
                .doesNotContain(
                        BuildingProfileField.TOT_AREA,
                        BuildingProfileField.ARCH_AREA,
                        BuildingProfileField.VL_RAT_ESTM_TOT_AREA,
                        BuildingProfileField.BC_RAT,
                        BuildingProfileField.VL_RAT);
    }

    @Test
    void preservesRejectedDatesAsObservationOnly() {
        assertThat(policy.use(BuildingProfileField.STCNS_DAY, BuildingProfileQualityTier.REJECT_FOR_PROJECTION))
                .isEqualTo(BuildingProfileProjectionUse.OBSERVATION_ONLY);
        assertThat(policy.use(BuildingProfileField.USE_APR_DAY, BuildingProfileQualityTier.REJECT_FOR_PROJECTION))
                .isEqualTo(BuildingProfileProjectionUse.OBSERVATION_ONLY);
        assertThat(policy.use(BuildingProfileField.USE_APR_DAY, BuildingProfileQualityTier.PROMOTE_CANDIDATE))
                .isEqualTo(BuildingProfileProjectionUse.OBSERVATION_ONLY);
        assertThat(policy.use(BuildingProfileField.PLAT_AREA, BuildingProfileQualityTier.RETAIN_PROFILE))
                .isEqualTo(BuildingProfileProjectionUse.PROFILE_ONLY);
        assertThat(policy.use(BuildingProfileField.MAIN_PURPS_CD, BuildingProfileQualityTier.PROMOTE_CANDIDATE))
                .isEqualTo(BuildingProfileProjectionUse.OPERATIONAL);
        assertThat(policy.eligible(BigDecimal.valueOf(0.4492986425d))).isTrue();
        assertThat(policy.eligible(new BigDecimal("0.4492986424"))).isFalse();
    }
}
