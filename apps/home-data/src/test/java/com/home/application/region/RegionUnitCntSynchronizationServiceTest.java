package com.home.application.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegionUnitCntSynchronizationServiceTest {

	@Test
	@DisplayName("region 세대수 동기화 service는 미매칭 parcel을 PARTIAL 결과로 가시화한다")
	void reportsPartialWhenUnmatchedParcelExists() {
		RegionUnitCntSynchronizationService service = new RegionUnitCntSynchronizationService(
			() -> new RegionRelationSynchronizationResult(true, true, true)
		);

		RegionUnitCntSyncResult result = service.synchronize();

		assertThat(result.partial()).isTrue();
		assertThat(result.relationChanged()).isTrue();
		assertThat(result.unitCntChanged()).isTrue();
		assertThat(result.unmatchedParcelExists()).isTrue();
	}

	@Test
	@DisplayName("region 세대수 동기화 service는 gateway 실패를 숨기지 않는다")
	void propagatesGatewayFailure() {
		RegionUnitCntSynchronizationService service = new RegionUnitCntSynchronizationService(() -> {
			throw new IllegalStateException("complex and parcel region relation mismatch");
		});

		assertThatThrownBy(service::synchronize)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("relation mismatch");
	}
}
