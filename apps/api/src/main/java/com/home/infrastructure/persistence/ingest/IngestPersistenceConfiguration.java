package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.ComplexMatcher;
import com.home.application.ingest.ComplexMasterBootstrapper;
import com.home.application.ingest.NormalizedTradeRepository;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.RawTradeIngestRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class IngestPersistenceConfiguration {

	@Bean
	@Lazy
	RawTradeIngestRepository rawTradeIngestRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcRawTradeIngestRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	NormalizedTradeRepository normalizedTradeRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectProvider<PlatformTransactionManager> transactionManagerProvider
	) {
		return new JdbcNormalizedTradeRepository(
			requiredJdbcClient(jdbcClientProvider),
			new TransactionTemplate(requiredTransactionManager(transactionManagerProvider))
		);
	}

	@Bean
	@Lazy
	ComplexMatcher complexMatcher(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcComplexMatcher(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	ComplexMasterBootstrapper complexMasterBootstrapper(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ParcelCoordinateResolver parcelCoordinateResolver
	) {
		return new JdbcComplexMasterBootstrapper(
			requiredJdbcClient(jdbcClientProvider),
			parcelCoordinateResolver
		);
	}

	@Bean
	@Lazy
	OpenApiTradeIngestService openApiTradeIngestService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper
	) {
		return new OpenApiTradeIngestService(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			complexMasterBootstrapper
		);
	}

	private JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for RTMS ingest persistence");
		});
	}

	private PlatformTransactionManager requiredTransactionManager(
		ObjectProvider<PlatformTransactionManager> transactionManagerProvider
	) {
		return transactionManagerProvider.getIfAvailable(() -> {
			throw new IllegalStateException("PlatformTransactionManager is required for RTMS ingest persistence");
		});
	}
}
