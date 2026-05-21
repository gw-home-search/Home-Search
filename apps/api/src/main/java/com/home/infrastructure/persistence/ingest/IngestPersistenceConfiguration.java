package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.ComplexMatcher;
import com.home.application.ingest.NormalizedTradeRepository;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.RawTradeIngestRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class IngestPersistenceConfiguration {

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	RawTradeIngestRepository rawTradeIngestRepository(JdbcClient jdbcClient) {
		return new JdbcRawTradeIngestRepository(jdbcClient);
	}

	@Bean
	@ConditionalOnBean({JdbcClient.class, PlatformTransactionManager.class})
	NormalizedTradeRepository normalizedTradeRepository(
		JdbcClient jdbcClient,
		PlatformTransactionManager transactionManager
	) {
		return new JdbcNormalizedTradeRepository(jdbcClient, new TransactionTemplate(transactionManager));
	}

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	ComplexMatcher complexMatcher(JdbcClient jdbcClient) {
		return new JdbcComplexMatcher(jdbcClient);
	}

	@Bean
	@ConditionalOnBean({RawTradeIngestRepository.class, NormalizedTradeRepository.class, ComplexMatcher.class})
	OpenApiTradeIngestService openApiTradeIngestService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher
	) {
		return new OpenApiTradeIngestService(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher
		);
	}
}
