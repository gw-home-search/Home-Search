package com.home.infrastructure.batch;

import com.home.application.coordinate.lookup.CoordinateSourceFirstParcelCoordinateResolver;
import com.home.application.coordinate.lookup.ParcelCoordinateOverrideRepository;
import com.home.application.coordinate.lookup.ParcelCoordinateResolver;
import com.home.application.coordinate.lookup.ParcelCoordinateSourceRepository;
import com.home.application.ingest.buildingmetadata.BuildingMetadataBatchService;
import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisService;
import com.home.application.ingest.buildingprofile.BuildingProfileCollectionService;
import com.home.application.ingest.buildingprofile.BuildingProfileReplayService;
import com.home.application.ingest.buildingprofile.LegalDongCodeImportService;
import com.home.application.ingest.buildingregister.BuildingRatioProjectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterCampaignService;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterRawPageFinalizer;
import com.home.application.ingest.buildingregister.BuildingRegisterRawReceiptService;
import com.home.application.ingest.metadata.OdcMetadataGapFillService;
import com.home.application.ingest.raw.RawReceiptService;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshUseCase;
import com.home.application.ingest.trade.OpenApiTradeIngestService;
import com.home.application.ingest.trade.TradeIngestFinalizer;
import com.home.application.ingest.trade.TradeIngestItemProcessor;
import com.home.application.insight.collection.RtmsCollectionExecutionService;
import com.home.application.insight.generation.MarketInsightDailyBuildService;
import com.home.application.region.RegionUnitCntSynchronizationService;
import com.home.infrastructure.external.complex.ComplexMetadataClientConfiguration;
import com.home.infrastructure.external.rtms.RtmsBatchOrchestrationConfiguration;
import com.home.infrastructure.external.rtms.RtmsExternalApiConfiguration;
import com.home.infrastructure.observability.TradeIngestMetricsConfiguration;
import com.home.infrastructure.ops.notification.OpsNotificationConfiguration;
import com.home.infrastructure.persistence.ingest.IngestPersistenceConfiguration;
import com.home.infrastructure.persistence.region.RegionUnitCntPersistenceConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

@ComponentScan(
        basePackages = "com.home.application",
        useDefaultFilters = false,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = {
                            BuildingMetadataBatchService.class,
                            BuildingProfileReplayService.class,
                            BuildingProfileCollectionService.class,
                            BuildingProfileAnalysisService.class,
                            LegalDongCodeImportService.class,
                            BuildingRegisterCampaignService.class,
                            BuildingRegisterCollectionService.class,
                            BuildingRegisterRawReceiptService.class,
                            BuildingRegisterRawPageFinalizer.class,
                            BuildingRatioProjectionService.class,
                            OdcMetadataGapFillService.class,
                            RawReceiptService.class,
                            RtmsMonthlyRefreshUseCase.class,
                            OpenApiTradeIngestService.class,
                            TradeIngestFinalizer.class,
                            TradeIngestItemProcessor.class,
                            RtmsCollectionExecutionService.class,
                            MarketInsightDailyBuildService.class,
                            RegionUnitCntSynchronizationService.class
                        }))
@ComponentScan(
        basePackages = {
            "com.home.infrastructure.persistence.ingest",
            "com.home.infrastructure.persistence.insight",
            "com.home.infrastructure.persistence.region"
        },
        useDefaultFilters = false,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = {Repository.class, Component.class}),
        excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Configuration.class))
@Import({
    RtmsExternalApiConfiguration.class,
    ComplexMetadataClientConfiguration.class,
    RtmsBatchOrchestrationConfiguration.class,
    TradeIngestMetricsConfiguration.class,
    OpsNotificationConfiguration.class,
    IngestPersistenceConfiguration.class,
    RegionUnitCntPersistenceConfiguration.class
})
public class PropertyDataBatchCoreConfiguration {

    @Bean
    @Primary
    ParcelCoordinateResolver batchParcelCoordinateResolver(
            ParcelCoordinateSourceRepository coordinateSourceRepository,
            ParcelCoordinateOverrideRepository overrideRepository) {
        return new CoordinateSourceFirstParcelCoordinateResolver(coordinateSourceRepository, overrideRepository);
    }
}
