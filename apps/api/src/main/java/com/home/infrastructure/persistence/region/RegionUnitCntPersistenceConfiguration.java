package com.home.infrastructure.persistence.region;

import com.home.application.region.RegionRelationSynchronizationGateway;
import com.home.application.region.RegionUnitCntSynchronizationService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class RegionUnitCntPersistenceConfiguration {

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	RegionRelationSynchronizationGateway regionRelationSynchronizationGateway(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectProvider<PlatformTransactionManager> transactionManagerProvider
	) {
		return new JdbcRegionRelationSynchronizationRepository(
			requiredJdbcClient(jdbcClientProvider),
			new TransactionTemplate(requiredTransactionManager(transactionManagerProvider))
		);
	}

	@Bean
	@ConditionalOnBean(RegionRelationSynchronizationGateway.class)
	RegionUnitCntSynchronizationService regionUnitCntSynchronizationService(
		RegionRelationSynchronizationGateway gateway
	) {
		return new RegionUnitCntSynchronizationService(gateway);
	}

	private JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for region unit count persistence");
		});
	}

	private PlatformTransactionManager requiredTransactionManager(
		ObjectProvider<PlatformTransactionManager> transactionManagerProvider
	) {
		return transactionManagerProvider.getIfAvailable(() -> {
			throw new IllegalStateException("PlatformTransactionManager is required for region unit count persistence");
		});
	}
}
