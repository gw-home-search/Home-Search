package com.home.infrastructure.external.rtms;

import com.home.application.ingest.rtms.RtmsCoordinateSourcePreflight;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshExecution;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshRetryPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RtmsIngestProperties.class)
public class RtmsBatchOrchestrationConfiguration {

    @Bean
    RtmsMonthlyRefreshExecution rtmsMonthlyRefreshExecution(RtmsIngestProperties properties) {
        return new RtmsMonthlyRefreshExecution(
                java.time.Clock.systemUTC(),
                new RtmsMonthlyRefreshRetryPolicy(
                        properties.refreshRetryAttempts(), properties.refreshRetryBackoffMillis()));
    }

    @Bean
    RtmsCoordinateSourcePreflight rtmsCoordinateSourcePreflight(
            RtmsCoordinateSourceAvailabilityProbe availabilityProbe, RtmsIngestProperties properties) {
        return new RequiredRtmsCoordinateSourcePreflight(properties.allowCoordinatePendingOnly(), availabilityProbe);
    }
}
