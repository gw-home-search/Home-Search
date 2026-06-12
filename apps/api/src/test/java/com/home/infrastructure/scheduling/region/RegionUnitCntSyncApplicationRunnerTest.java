package com.home.infrastructure.scheduling.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import com.home.application.region.RegionRelationSynchronizationResult;
import com.home.application.region.RegionUnitCntSynchronizationService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class RegionUnitCntSyncApplicationRunnerTest {

	@Test
	@DisplayName("region 세대수 one-shot runner는 RTMS와 coordinate readiness 이후 동기화를 실행한다")
	void runnerSynchronizesAfterRtmsAndCoordinateReadiness() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		RegionUnitCntSyncApplicationRunner runner = new RegionUnitCntSyncApplicationRunner(
			new RegionUnitCntSynchronizationService(() -> {
				calls.incrementAndGet();
				return new RegionRelationSynchronizationResult(false, true, false);
			})
		);

		runner.run(new DefaultApplicationArguments());

		assertThat(calls).hasValue(1);
		assertThat(runner.getOrder()).isEqualTo(ApplicationRunnerOrders.REGION_UNIT_CNT_SYNC);
		assertThat(runner.getOrder()).isGreaterThan(ApplicationRunnerOrders.COORDINATE_READINESS);
	}
}
