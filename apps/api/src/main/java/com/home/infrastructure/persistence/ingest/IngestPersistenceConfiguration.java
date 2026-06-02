package com.home.infrastructure.persistence.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.ComplexMatcher;
import com.home.application.ingest.ComplexMetadataEnrichmentClient;
import com.home.application.ingest.ComplexMetadataEnrichmentRepository;
import com.home.application.ingest.ComplexMetadataEnrichmentService;
import com.home.application.ingest.ComplexMasterBootstrapper;
import com.home.application.ingest.NormalizedTradeRepository;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.RawIngestReconciliationRepository;
import com.home.application.ingest.RawIngestReconciliationService;
import com.home.application.ingest.RawTradeItemParser;
import com.home.application.ingest.RawTradeIngestRepository;
import com.home.application.ingest.RtmsIngestRunReportRepository;
import com.home.application.ingest.RtmsIngestRunRepository;
import com.home.application.ingest.TradeIngestMetrics;
import com.home.application.ingest.TradeMatchRematchService;
import com.home.application.ingest.TradeMatchEvidenceRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
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
	TradeMatchEvidenceRepository tradeMatchEvidenceRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectMapper objectMapper
	) {
		return new JdbcTradeMatchEvidenceRepository(requiredJdbcClient(jdbcClientProvider), objectMapper);
	}

	@Bean
	@Lazy
	RtmsIngestRunRepository rtmsIngestRunRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcRtmsIngestRunRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	RtmsIngestRunReportRepository rtmsIngestRunReportRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcRtmsIngestRunReportRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	ParcelCoordinateSnapshotRepository parcelCoordinateSnapshotRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcParcelCoordinateSnapshotRepository(requiredJdbcClient(jdbcClientProvider));
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
	ComplexMetadataEnrichmentRepository complexMetadataEnrichmentRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcComplexMetadataEnrichmentRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	ComplexMetadataEnrichmentService complexMetadataEnrichmentService(
		ComplexMetadataEnrichmentRepository complexMetadataEnrichmentRepository,
		ObjectProvider<ComplexMetadataEnrichmentClient> metadataEnrichmentClientProvider
	) {
		return new ComplexMetadataEnrichmentService(
			complexMetadataEnrichmentRepository,
			metadataEnrichmentClientProvider.getIfAvailable(ComplexMetadataEnrichmentClient::noop)
		);
	}

	@Bean
	@Lazy
	@ConditionalOnBean(JdbcClient.class)
	JdbcTradePartitionMaintenanceRepository tradePartitionMaintenanceRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcTradePartitionMaintenanceRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@ConditionalOnBean(JdbcTradePartitionMaintenanceRepository.class)
	@ConditionalOnProperty(
		name = "home.trade.partition.maintenance.enabled",
		havingValue = "true",
		matchIfMissing = true
	)
	ApplicationRunner tradePartitionMaintenanceRunner(
		JdbcTradePartitionMaintenanceRepository tradePartitionMaintenanceRepository,
		@Value("${home.trade.partition.maintenance.years-ahead:5}") int yearsAhead
	) {
		return new TradePartitionMaintenanceRunner(
			tradePartitionMaintenanceRepository,
			java.time.Clock.systemUTC(),
			yearsAhead
		);
	}

	@Bean
	@Lazy
	RawIngestReconciliationRepository rawIngestReconciliationRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcRawIngestReconciliationRepository(requiredJdbcClient(jdbcClientProvider));
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
	@ConditionalOnProperty(name = "home.ingest.raw-reconcile.enabled", havingValue = "true")
	ApplicationRunner rawIngestReconciliationRunner(
		RawIngestReconciliationService rawIngestReconciliationService,
		@Value("${home.ingest.raw-reconcile.batch-size:100}") int batchSize
	) {
		return new RawIngestReconciliationRunner(rawIngestReconciliationService, batchSize);
	}

	@Bean
	@Lazy
	RawTradeItemParser rawTradeItemParser(ObjectMapper objectMapper) {
		return new RtmsRawTradeItemParser(objectMapper);
	}

	@Bean
	@Lazy
	TradeMatchRematchService tradeMatchRematchService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		TradeMatchEvidenceRepository tradeMatchEvidenceRepository,
		RawTradeItemParser rawTradeItemParser
	) {
		return new TradeMatchRematchService(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			complexMasterBootstrapper,
			tradeMatchEvidenceRepository,
			rawTradeItemParser
		);
	}

	@Bean
	@ConditionalOnProperty(name = "home.ingest.match-rematch.enabled", havingValue = "true")
	ApplicationRunner tradeMatchRematchRunner(
		TradeMatchRematchService tradeMatchRematchService,
		@Value("${home.ingest.match-rematch.batch-size:100}") int batchSize
	) {
		return new TradeMatchRematchRunner(tradeMatchRematchService, batchSize);
	}

	@Bean
	@Lazy
	OpenApiTradeIngestService openApiTradeIngestService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		TradeMatchEvidenceRepository tradeMatchEvidenceRepository,
		TradeIngestMetrics tradeIngestMetrics
	) {
		return new OpenApiTradeIngestService(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			complexMasterBootstrapper,
			tradeIngestMetrics,
			tradeMatchEvidenceRepository
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
