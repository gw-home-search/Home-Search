package com.home.domain.complex.buildingmetadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingMetadataProjectionPolicyTest {
	private final BuildingMetadataProjectionPolicy policy = new BuildingMetadataProjectionPolicy();

	@Test
	@DisplayName("0과 음수는 NULL로 정규화하고 기존 값과 같은 후보는 누락값만 보강한다")
	void sanitizesInvalidValuesAndFillsOnlyMissingValues() {
		BuildingMetadataValues current = new BuildingMetadataValues(8, 740, null, null, null, null, null, null);
		BuildingMetadataValues candidate = new BuildingMetadataValues(8, 740, new BigDecimal("123.45"),
			BigDecimal.ZERO, new BigDecimal("-1"), null, null, null);

		var decision = policy.decide(current, candidate);

		assertThat(decision.apply()).isTrue();
		assertThat(decision.values().platArea()).isEqualByComparingTo("123.45");
		assertThat(decision.values().archArea()).isNull();
	}

	@Test
	@DisplayName("기존 non-null 값과 하나라도 다르면 어떤 projection도 변경하지 않고 CHANGE_PENDING이다")
	void holdsAllProjectionChangesOnAnyConflict() {
		BuildingMetadataValues current = new BuildingMetadataValues(8, 740, null, null, null, null, null, null);
		BuildingMetadataValues candidate = new BuildingMetadataValues(8, 741, new BigDecimal("123.45"),
			null, null, null, null, null);

		var decision = policy.decide(current, candidate);

		assertThat(decision.apply()).isFalse();
		assertThat(decision.values().platArea()).isNull();
	}
}
