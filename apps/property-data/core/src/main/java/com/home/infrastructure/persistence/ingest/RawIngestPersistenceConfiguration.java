package com.home.infrastructure.persistence.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.application.ingest.raw.RawTradeItemParser;
import com.home.application.ingest.reconciliation.RawIngestReconciliationRepository;
import com.home.application.ingest.reconciliation.RawIngestReconciliationService;
import com.home.infrastructure.persistence.ingest.raw.JdbcRawIngestReconciliationRepository;
import com.home.infrastructure.persistence.ingest.raw.JdbcRawTradeIngestRepository;
import com.home.infrastructure.persistence.ingest.raw.RawIngestReconciliationRunner;
import com.home.infrastructure.persistence.ingest.raw.RtmsRawTradeItemParser;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class RawIngestPersistenceConfiguration {

	@Bean
	@Lazy
	RawTradeIngestRepository rawTradeIngestRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcRawTradeIngestRepository(IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	RawIngestReconciliationRepository rawIngestReconciliationRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcRawIngestReconciliationRepository(
			IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider)
		);
	}

	@Bean
	@Lazy
	RawIngestReconciliationService rawIngestReconciliationService(
		RawIngestReconciliationRepository rawIngestReconciliationRepository,
		RawTradeIngestRepository rawTradeIngestRepository
	) {
		return new RawIngestReconciliationService(rawIngestReconciliationRepository, rawTradeIngestRepository);
	}

	@Bean
	@ConditionalOnProperty(name = "home.ingest.raw-reconcile.enabled", havingValue = "true", matchIfMissing = true)
	ApplicationRunner rawIngestReconciliationRunner(
		ObjectProvider<RawIngestReconciliationService> reconciliationServiceProvider,
		ObjectProvider<JdbcClient> jdbcClientProvider,
		@Value("${home.ingest.raw-reconcile.batch-size:100}") int batchSize
	) {
		return new RawIngestReconciliationRunner(
			reconciliationServiceProvider::getObject,
			batchSize,
			() -> jdbcClientProvider.getIfAvailable() != null
		);
	}

	@Bean
	@Lazy
	RawTradeItemParser rawTradeItemParser(ObjectMapper objectMapper) {
		return new RtmsRawTradeItemParser(objectMapper);
	}
}
