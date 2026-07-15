package com.home.infrastructure.persistence.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.coordinate.lookup.ParcelCoordinateResolver;
import com.home.application.ingest.buildingmetadata.BuildingMetadataBatchService;
import com.home.application.ingest.buildingmetadata.BuildingMetadataEvidenceRepository;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceClient;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceParser;
import com.home.application.ingest.matching.ComplexIdentityResolver;
import com.home.application.ingest.matching.ComplexMasterBootstrapper;
import com.home.application.ingest.matching.ComplexMatcher;
import com.home.application.ingest.matching.TradeMatchEvidenceRepository;
import com.home.application.ingest.matching.TradeMatchRematchService;
import com.home.application.ingest.metadata.ComplexMetadataEnrichmentClient;
import com.home.application.ingest.metadata.ComplexMetadataEnrichmentRepository;
import com.home.application.ingest.metadata.ComplexMetadataEnrichmentService;
import com.home.application.ingest.metadata.ComplexMetadataLookup;
import com.home.application.ingest.metadata.ComplexMetadataResolution;
import com.home.application.ingest.metadata.OdcComplexMetadataResolver;
import com.home.application.ingest.metadata.OdcMetadataGapFillRepository;
import com.home.application.ingest.metadata.OdcMetadataGapFillService;
import com.home.application.ingest.metadata.OdcloudPnuPrefixAliasLookup;
import com.home.application.ingest.metadata.admin.MetadataAdminRepository;
import com.home.application.ingest.metadata.admin.MetadataAdminService;
import com.home.application.ingest.normalization.NormalizedTradeRepository;
import com.home.application.ingest.raw.RawReceiptService;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.application.ingest.raw.RawTradeItemParser;
import com.home.application.ingest.trade.OpenApiTradeIngestService;
import com.home.application.ingest.trade.TradeIngestFinalizer;
import com.home.application.ingest.trade.TradeIngestItemProcessor;
import com.home.application.ingest.trade.TradeIngestMetrics;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingMetadataEvidenceRepository;
import com.home.infrastructure.persistence.ingest.matching.JdbcComplexMasterBootstrapper;
import com.home.infrastructure.persistence.ingest.matching.JdbcComplexMatcher;
import com.home.infrastructure.persistence.ingest.matching.JdbcComplexMetadataEnrichmentRepository;
import com.home.infrastructure.persistence.ingest.matching.JdbcMetadataAdminRepository;
import com.home.infrastructure.persistence.ingest.matching.JdbcOdcMetadataGapFillRepository;
import com.home.infrastructure.persistence.ingest.matching.JdbcOdcloudPnuPrefixAliasLookup;
import com.home.infrastructure.persistence.ingest.matching.JdbcTradeMatchEvidenceRepository;
import com.home.infrastructure.persistence.ingest.matching.TradeMatchRematchRunner;
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
class TradeMatchPersistenceConfiguration {

    @Bean
    @Lazy
    BuildingMetadataEvidenceRepository buildingMetadataEvidenceRepository(
            ObjectProvider<JdbcClient> jdbcClientProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            ObjectMapper objectMapper) {
        return new JdbcBuildingMetadataEvidenceRepository(
                IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider),
                new TransactionTemplate(transactionManagerProvider.getObject()));
    }

    @Bean
    @Lazy
    BuildingMetadataBatchService buildingMetadataBatchService(
            BuildingMetadataEvidenceRepository repository,
            ObjectProvider<BuildingMetadataSourceClient> clientProvider,
            BuildingMetadataSourceParser parser) {
        return new BuildingMetadataBatchService(
                repository, clientProvider.getIfAvailable(BuildingMetadataSourceClient::noop), parser);
    }

    @Bean
    @Lazy
    OdcMetadataGapFillRepository odcMetadataGapFillRepository(
            ObjectProvider<JdbcClient> jdbcClientProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        return new JdbcOdcMetadataGapFillRepository(
                IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider),
                new TransactionTemplate(transactionManagerProvider.getObject()));
    }

    @Bean
    @Lazy
    OdcMetadataGapFillService odcMetadataGapFillService(
            OdcMetadataGapFillRepository repository, ObjectProvider<OdcComplexMetadataResolver> resolverProvider) {
        return new OdcMetadataGapFillService(
                repository, resolverProvider.getIfAvailable(() -> new OdcComplexMetadataResolver() {
                    @Override
                    public ComplexMetadataResolution resolveOdc(ComplexMetadataLookup lookup) {
                        throw new IllegalStateException("ODC metadata resolver is not configured");
                    }

                    @Override
                    public boolean isOdcConfigured() {
                        return false;
                    }
                }));
    }

    @Bean
    @Lazy
    ComplexMatcher complexMatcher(ObjectProvider<JdbcClient> jdbcClientProvider) {
        return new JdbcComplexMatcher(IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider));
    }

    @Bean
    @Lazy
    TradeMatchEvidenceRepository tradeMatchEvidenceRepository(
            ObjectProvider<JdbcClient> jdbcClientProvider, ObjectMapper objectMapper) {
        return new JdbcTradeMatchEvidenceRepository(
                IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider), objectMapper);
    }

    @Bean
    @Lazy
    ComplexMasterBootstrapper complexMasterBootstrapper(
            ObjectProvider<JdbcClient> jdbcClientProvider,
            ParcelCoordinateResolver parcelCoordinateResolver,
            ObjectProvider<ComplexIdentityResolver> identityResolverProvider) {
        return new JdbcComplexMasterBootstrapper(
                IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider),
                parcelCoordinateResolver,
                identityResolverProvider.getIfAvailable(ComplexIdentityResolver::noop));
    }

    @Bean
    @Lazy
    MetadataAdminRepository metadataAdminRepository(
            ObjectProvider<JdbcClient> jdbcClientProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        return new JdbcMetadataAdminRepository(
                IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider),
                new TransactionTemplate(transactionManagerProvider.getObject()));
    }

    @Bean
    @Lazy
    MetadataAdminService metadataAdminService(MetadataAdminRepository repository) {
        return new MetadataAdminService(repository);
    }

    @Bean
    @Lazy
    OdcloudPnuPrefixAliasLookup odcloudPnuPrefixAliasLookup(ObjectProvider<JdbcClient> jdbcClientProvider) {
        return new JdbcOdcloudPnuPrefixAliasLookup(IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider));
    }

    @Bean
    @Lazy
    ComplexMetadataEnrichmentRepository complexMetadataEnrichmentRepository(
            ObjectProvider<JdbcClient> jdbcClientProvider) {
        return new JdbcComplexMetadataEnrichmentRepository(
                IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider));
    }

    @Bean
    @Lazy
    ComplexMetadataEnrichmentService complexMetadataEnrichmentService(
            ComplexMetadataEnrichmentRepository complexMetadataEnrichmentRepository,
            ObjectProvider<ComplexMetadataEnrichmentClient> metadataEnrichmentClientProvider) {
        return new ComplexMetadataEnrichmentService(
                complexMetadataEnrichmentRepository,
                metadataEnrichmentClientProvider.getIfAvailable(ComplexMetadataEnrichmentClient::noop));
    }

    @Bean
    @Lazy
    TradeMatchRematchService tradeMatchRematchService(
            RawTradeIngestRepository rawTradeIngestRepository,
            NormalizedTradeRepository normalizedTradeRepository,
            ComplexMatcher complexMatcher,
            ComplexMasterBootstrapper complexMasterBootstrapper,
            TradeMatchEvidenceRepository tradeMatchEvidenceRepository,
            RawTradeItemParser rawTradeItemParser) {
        return new TradeMatchRematchService(
                rawTradeIngestRepository,
                normalizedTradeRepository,
                complexMatcher,
                complexMasterBootstrapper,
                tradeMatchEvidenceRepository,
                rawTradeItemParser);
    }

    @Bean
    @ConditionalOnProperty(name = "home.ingest.match-rematch.enabled", havingValue = "true")
    ApplicationRunner tradeMatchRematchRunner(
            TradeMatchRematchService tradeMatchRematchService,
            @Value("${home.ingest.match-rematch.batch-size:100}") int batchSize) {
        return new TradeMatchRematchRunner(tradeMatchRematchService, batchSize);
    }

    @Bean
    @Lazy
    TradeIngestItemProcessor tradeIngestItemProcessor(
            RawReceiptService rawReceiptService, TradeIngestFinalizer tradeIngestFinalizer) {
        return new TradeIngestItemProcessor(rawReceiptService, tradeIngestFinalizer);
    }

    @Bean
    @Lazy
    TradeIngestFinalizer tradeIngestFinalizer(
            RawTradeIngestRepository rawTradeIngestRepository,
            NormalizedTradeRepository normalizedTradeRepository,
            ComplexMatcher complexMatcher,
            ComplexMasterBootstrapper complexMasterBootstrapper,
            TradeMatchEvidenceRepository tradeMatchEvidenceRepository) {
        return new TradeIngestFinalizer(
                rawTradeIngestRepository,
                normalizedTradeRepository,
                complexMatcher,
                complexMasterBootstrapper,
                tradeMatchEvidenceRepository);
    }

    @Bean
    @Lazy
    OpenApiTradeIngestService openApiTradeIngestService(
            TradeIngestItemProcessor tradeIngestItemProcessor, TradeIngestMetrics tradeIngestMetrics) {
        return new OpenApiTradeIngestService(tradeIngestItemProcessor, tradeIngestMetrics);
    }
}
