package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.normalization.NormalizedTradeRepository;
import com.home.infrastructure.persistence.ingest.normalization.JdbcNormalizedTradeRepository;
import com.home.infrastructure.persistence.ingest.normalization.JdbcTradePartitionMaintenanceRepository;
import com.home.infrastructure.persistence.ingest.normalization.TradePartitionMaintenanceRunner;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class TradeNormalizationPersistenceConfiguration {

	@Bean
	@Lazy
	NormalizedTradeRepository normalizedTradeRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectProvider<PlatformTransactionManager> transactionManagerProvider
	) {
		return new JdbcNormalizedTradeRepository(
			IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider),
			new TransactionTemplate(IngestPersistenceJdbcSupport.requiredTransactionManager(transactionManagerProvider))
		);
	}

	@Bean
	@Lazy
	JdbcTradePartitionMaintenanceRepository tradePartitionMaintenanceRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcTradePartitionMaintenanceRepository(
			IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider)
		);
	}

	@Bean
	@ConditionalOnProperty(
		name = "home.trade.partition.maintenance.enabled",
		havingValue = "true",
		matchIfMissing = true
	)
	ApplicationRunner tradePartitionMaintenanceRunner(
		ObjectProvider<JdbcTradePartitionMaintenanceRepository> maintenanceRepositoryProvider,
		ObjectProvider<JdbcClient> jdbcClientProvider,
		@Value("${home.trade.partition.maintenance.years-ahead:5}") int yearsAhead
	) {
		return new TradePartitionMaintenanceRunner(
			maintenanceRepositoryProvider::getObject,
			java.time.Clock.systemUTC(),
			yearsAhead,
			() -> jdbcClientProvider.getIfAvailable() != null
		);
	}
}
