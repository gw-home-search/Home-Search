package com.home.infrastructure.scheduling.region;

import com.home.application.region.RegionUnitCntSynchronizationService;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RegionUnitCntSyncConfiguration {

	@Bean
	@ConditionalOnBean(RegionUnitCntSynchronizationService.class)
	@ConditionalOnProperty(name = "home.region.sync.one-shot.enabled", havingValue = "true")
	ApplicationRunner regionUnitCntSyncApplicationRunner(
		RegionUnitCntSynchronizationService synchronizationService
	) {
		return new RegionUnitCntSyncApplicationRunner(synchronizationService);
	}
}
