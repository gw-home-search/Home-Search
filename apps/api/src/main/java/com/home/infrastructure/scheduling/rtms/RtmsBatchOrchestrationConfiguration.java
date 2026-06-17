package com.home.infrastructure.scheduling.rtms;

import com.home.application.ingest.trade.OpenApiTradeIngestService;
import com.home.application.ingest.run.RtmsIngestRunRepository;
import com.home.infrastructure.external.rtms.RtmsApartmentTradeClient;
import com.home.infrastructure.external.rtms.RtmsCoordinateSourceAvailabilityProbe;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RtmsBatchOrchestrationConfiguration {

	@Bean
	RtmsMonthlyRefreshRunner rtmsMonthlyRefreshRunner(
		RtmsApartmentTradeClient client,
		ObjectProvider<OpenApiTradeIngestService> ingestServiceProvider,
		ObjectProvider<RtmsIngestRunRepository> ingestRunRepositoryProvider,
		@Value("${home.ingest.rtms.refresh-retry-attempts:3}") int refreshRetryAttempts,
		@Value("${home.ingest.rtms.refresh-retry-backoff-millis:250}") long refreshRetryBackoffMillis
	) {
		return new RtmsMonthlyRefreshRunner(
			client,
			() -> ingestServiceProvider.getIfAvailable(() -> {
				throw new IllegalStateException("OpenApiTradeIngestService is required for RTMS monthly refresh ingest");
			}),
			() -> ingestRunRepositoryProvider.getIfAvailable(
				RtmsIngestRunRepository::noop
			),
			java.time.Clock.systemUTC(),
			new RtmsMonthlyRefreshRetryPolicy(refreshRetryAttempts, refreshRetryBackoffMillis)
		);
	}

	@Bean
	RtmsCoordinateSourcePreflight rtmsCoordinateSourcePreflight(
		RtmsCoordinateSourceAvailabilityProbe availabilityProbe,
		@Value("${home.ingest.rtms.allow-coordinate-pending-only:false}") boolean allowCoordinatePendingOnly
	) {
		return new RequiredRtmsCoordinateSourcePreflight(
			allowCoordinatePendingOnly,
			availabilityProbe
		);
	}
}
