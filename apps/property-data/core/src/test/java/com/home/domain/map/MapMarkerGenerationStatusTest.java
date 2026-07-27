package com.home.domain.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MapMarkerGenerationStatusTest {

    @Test
    @DisplayName("검증 완료와 직전 retired generation만 활성화 또는 rollback 후보가 된다")
    void activationCandidatesRemainExplicit() {
        assertThat(MapMarkerGenerationStatus.VALIDATED.canActivate()).isTrue();
        assertThat(MapMarkerGenerationStatus.RETIRED.canActivate()).isTrue();
        assertThat(MapMarkerGenerationStatus.BUILDING.canActivate()).isFalse();
        assertThat(MapMarkerGenerationStatus.ACTIVE.canActivate()).isFalse();
        assertThat(MapMarkerGenerationStatus.FAILED.canActivate()).isFalse();
    }
}
