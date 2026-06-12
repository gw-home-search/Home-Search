package com.home.infrastructure.scheduling.region;

import java.util.Objects;

import com.home.application.region.RegionUnitCntSyncResult;
import com.home.application.region.RegionUnitCntSynchronizationService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class RegionUnitCntSyncApplicationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(RegionUnitCntSyncApplicationRunner.class);

	private final RegionUnitCntSynchronizationService synchronizationService;

	RegionUnitCntSyncApplicationRunner(RegionUnitCntSynchronizationService synchronizationService) {
		this.synchronizationService = Objects.requireNonNull(synchronizationService);
	}

	@Override
	public void run(ApplicationArguments args) {
		RegionUnitCntSyncResult result = synchronizationService.synchronize();
		log.info(
			"region unit count synchronization completed partial={} relationChanged={} unitCntChanged={} unmatchedParcelExists={}",
			result.partial(),
			result.relationChanged(),
			result.unitCntChanged(),
			result.unmatchedParcelExists()
		);
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.REGION_UNIT_CNT_SYNC;
	}
}
