package com.home.infrastructure.batch;

import com.home.application.coordinate.lookup.CoordinateSourceFirstParcelCoordinateResolver;
import com.home.application.coordinate.lookup.ParcelCoordinateOverrideRepository;
import com.home.application.coordinate.lookup.ParcelCoordinateResolver;
import com.home.application.coordinate.lookup.ParcelCoordinateSourceRepository;
import com.home.infrastructure.external.complex.ComplexMetadataClientConfiguration;
import com.home.infrastructure.external.rtms.RtmsBatchOrchestrationConfiguration;
import com.home.infrastructure.external.rtms.RtmsExternalApiConfiguration;
import com.home.infrastructure.observability.TradeIngestMetricsConfiguration;
import com.home.infrastructure.ops.notification.OpsNotificationConfiguration;
import com.home.infrastructure.persistence.ingest.IngestPersistenceConfiguration;
import com.home.infrastructure.persistence.region.RegionUnitCntPersistenceConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

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
