package com.home.domain.coordinate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordinateDisplayPolicyTest {

	@Test
	@DisplayName("building footprint 좌표는 confidence 80 이상일 때 trusted display coordinate로 본다")
	void classifiesTrustedBuildingFootprintConfidence() {
		assertThat(CoordinateDisplayPolicy.TRUSTED_BUILDING_FOOTPRINT_CONFIDENCE).isEqualTo(80);
		assertThat(CoordinateDisplayPolicy.isTrustedBuildingFootprintConfidence(79)).isFalse();
		assertThat(CoordinateDisplayPolicy.isTrustedBuildingFootprintConfidence(80)).isTrue();
		assertThat(CoordinateDisplayPolicy.isTrustedBuildingFootprintConfidence(90)).isTrue();
	}

	@Test
	@DisplayName("coordinate source 저장 값은 기존 DB constraint 값을 유지한다")
	void keepsStoredCoordinateSourceValues() {
		assertThat(CoordinateSource.BUILDING_FOOTPRINT.storedValue()).isEqualTo("BUILDING_FOOTPRINT");
		assertThat(CoordinateSource.PARCEL_FALLBACK.storedValue()).isEqualTo("PARCEL_FALLBACK");
		assertThat(CoordinateSource.BUILDING_FOOTPRINT.matches("BUILDING_FOOTPRINT")).isTrue();
		assertThat(CoordinateSource.BUILDING_FOOTPRINT.matches("PARCEL_FALLBACK")).isFalse();
	}
}
