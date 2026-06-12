package com.home.infrastructure.scheduling.rtms;

import com.home.application.ingest.backfill.RtmsBackfillChunkRepository;
import com.home.application.ingest.backfill.RtmsBackfillJobRepository;
import com.home.application.ingest.trade.OpenApiTradeIngestService;
import com.home.application.ingest.run.RtmsIngestRunRepository;
import com.home.infrastructure.external.rtms.RtmsApartmentTradeClient;
import com.home.infrastructure.external.rtms.RtmsApartmentTradeProperties;
import com.home.infrastructure.external.rtms.RtmsCoordinateSourceAvailabilityProbe;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RtmsOneShotIngestConfigurationProperties.class)
class RtmsBatchOrchestrationConfiguration {

	@Bean
	RtmsOneShotIngestProperties rtmsOneShotIngestProperties(
		RtmsOneShotIngestConfigurationProperties properties
	) {
		return properties.toProperties();
	}

	@Bean
	RtmsOneShotTradeIngestRunner rtmsOneShotTradeIngestRunner(
		RtmsApartmentTradeClient client,
		ObjectProvider<OpenApiTradeIngestService> ingestServiceProvider
	) {
		return new RtmsOneShotTradeIngestRunner(
			client,
			() -> ingestServiceProvider.getIfAvailable(() -> {
				throw new IllegalStateException("OpenApiTradeIngestService is required for RTMS one-shot ingest");
			})
		);
	}

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
	RtmsNationwideBackfillRunner rtmsNationwideBackfillRunner(
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		ObjectProvider<RtmsBackfillJobRepository> backfillJobRepositoryProvider,
		ObjectProvider<RtmsBackfillChunkRepository> backfillChunkRepositoryProvider,
		RtmsOneShotIngestProperties properties
	) {
		return new RtmsNationwideBackfillRunner(
			RtmsBackfillRepositories.lazy(
				() -> backfillJobRepositoryProvider.getIfAvailable(() -> {
					throw new IllegalStateException(
						"RtmsBackfillJobRepository is required for RTMS nationwide backfill"
					);
				}),
				() -> backfillChunkRepositoryProvider.getIfAvailable(() -> {
					throw new IllegalStateException(
						"RtmsBackfillChunkRepository is required for RTMS nationwide backfill"
					);
				})
			),
			request -> RtmsBackfillChunkExecutionResult.from(
				monthlyRefreshRunner.refresh(request.lawdCd(), request.dealYmd())
			),
			java.time.Clock.systemUTC(),
			properties.nationwideBackfillOptions()
		);
	}

	@Bean
	RtmsCoordinateSourcePreflight rtmsCoordinateSourcePreflight(
		RtmsOneShotIngestProperties properties,
		RtmsCoordinateSourceAvailabilityProbe availabilityProbe
	) {
		return new RequiredRtmsCoordinateSourcePreflight(
			properties.allowCoordinatePendingOnly(),
			availabilityProbe
		);
	}

	@Bean
	ApplicationRunner rtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		ObjectProvider<RtmsNationwideBackfillRunner> nationwideBackfillRunnerProvider,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties,
		RtmsCoordinateSourcePreflight coordinateSourcePreflight
	) {
		return new RtmsOneShotIngestApplicationRunner(
			runner,
			monthlyRefreshRunner,
			nationwideBackfillRunnerProvider.getIfAvailable(),
			properties,
			tradeProperties,
			coordinateSourcePreflight
		);
	}
}
