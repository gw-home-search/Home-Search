package com.home.infrastructure.external.rtms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.RtmsBackfillChunkRepository;
import com.home.application.ingest.RtmsBackfillJobRepository;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.RtmsIngestRunRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class RtmsExternalApiConfiguration {

	@Bean
	RtmsApartmentTradeProperties rtmsApartmentTradeProperties(
		@Value("${apis.data.base-url:https://apis.data.go.kr}") String baseUrl,
		@Value("${apis.data.apt-title-path:/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev}") String path,
		@Value("${apis.data.apt-service-key:${APT_SERVICE_KEY:}}") String serviceKey,
		@Value("${apis.data.apt-num-of-rows:1000}") int numOfRows,
		@Value("${apis.data.connect-timeout-millis:5000}") int connectTimeoutMillis,
		@Value("${apis.data.read-timeout-millis:5000}") int readTimeoutMillis
	) {
		return new RtmsApartmentTradeProperties(
			baseUrl,
			path,
			serviceKey,
			numOfRows,
			connectTimeoutMillis,
			readTimeoutMillis
		);
	}

	@Bean
	RtmsOneShotIngestProperties rtmsOneShotIngestProperties(
		@Value("${home.ingest.rtms.enabled:false}") boolean enabled,
		@Value("${home.ingest.rtms.lawd-cd:}") String lawdCd,
		@Value("${home.ingest.rtms.deal-ymd:}") String dealYmd,
		@Value("${home.ingest.rtms.page-no:1}") Integer pageNo,
		@Value("${home.ingest.rtms.preflight-only:false}") boolean preflightOnly,
		@Value("${home.ingest.rtms.mode:one-shot}") String mode,
		@Value("${home.ingest.rtms.lookback-months:0}") Integer lookbackMonths,
		@Value("${home.ingest.rtms.allow-coordinate-pending-only:false}") boolean allowCoordinatePendingOnly,
		@Value("${home.ingest.rtms.nationwide.lawd-cds:}") String nationwideLawdCds,
		@Value("${home.ingest.rtms.nationwide.deal-ymd-from:201201}") String nationwideDealYmdFrom,
		@Value("${home.ingest.rtms.nationwide.deal-ymd-to:202606}") String nationwideDealYmdTo,
		@Value("${home.ingest.rtms.nationwide.job-key:}") String nationwideJobKey,
		@Value("${home.ingest.rtms.nationwide.worker-id:rtms-backfill-worker}") String nationwideWorkerId,
		@Value("${home.ingest.rtms.nationwide.lease-minutes:30}") Integer nationwideLeaseMinutes,
		@Value("${home.ingest.rtms.nationwide.max-attempt-count:3}") Integer nationwideMaxAttemptCount,
		@Value("${home.ingest.rtms.nationwide.chunk-limit:2147483647}") Integer nationwideChunkLimit
	) {
		return new RtmsOneShotIngestProperties(
			enabled,
			lawdCd,
			dealYmd,
			pageNo,
			preflightOnly,
			mode,
			lookbackMonths,
			allowCoordinatePendingOnly,
			nationwideLawdCds,
			nationwideDealYmdFrom,
			nationwideDealYmdTo,
			nationwideJobKey,
			nationwideWorkerId,
			nationwideLeaseMinutes,
			nationwideMaxAttemptCount,
			nationwideChunkLimit
		);
	}

	@Bean
	RtmsApartmentTradeResponseParser rtmsApartmentTradeResponseParser(ObjectMapper objectMapper) {
		return new RtmsApartmentTradeResponseParser(objectMapper);
	}

	@Bean
	RestClient rtmsApartmentTradeRestClient(RtmsApartmentTradeProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeoutMillis());
		requestFactory.setReadTimeout(properties.readTimeoutMillis());
		return RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(properties.baseUrl())
			.build();
	}

	@Bean
	RtmsApartmentTradeClient rtmsApartmentTradeClient(
		@Qualifier("rtmsApartmentTradeRestClient")
		RestClient rtmsApartmentTradeRestClient,
		RtmsApartmentTradeProperties properties,
		RtmsApartmentTradeResponseParser parser
	) {
		return new RtmsPublicApartmentTradeClient(
			rtmsApartmentTradeRestClient,
			properties,
			parser
		);
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
			() -> ingestRunRepositoryProvider.getIfAvailable(RtmsIngestRunRepository::noop),
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
			() -> backfillJobRepositoryProvider.getIfAvailable(() -> {
				throw new IllegalStateException("RtmsBackfillJobRepository is required for RTMS nationwide backfill");
			}),
			() -> backfillChunkRepositoryProvider.getIfAvailable(() -> {
				throw new IllegalStateException("RtmsBackfillChunkRepository is required for RTMS nationwide backfill");
			}),
			request -> summaryToBackfillResult(monthlyRefreshRunner.refresh(request.lawdCd(), request.dealYmd())),
			java.time.Clock.systemUTC(),
			properties.nationwideBackfillOptions()
		);
	}

	@Bean
	RtmsCoordinateSourcePreflight rtmsCoordinateSourcePreflight(
		RtmsOneShotIngestProperties ingestProperties,
		@Value("${home.coordinate-source.db.jdbc-url:${COORDINATE_SOURCE_DB_JDBC_URL:}}") String jdbcUrl,
		@Value("${home.coordinate-source.db.username:${COORDINATE_SOURCE_DB_USERNAME:${DB_USERNAME:}}}") String username,
		@Value("${home.coordinate-source.db.password:${COORDINATE_SOURCE_DB_PASSWORD:${DB_PASSWORD:}}}") String password,
		@Value("${home.coordinate-source.db.connect-timeout-seconds:${COORDINATE_SOURCE_DB_CONNECT_TIMEOUT_SECONDS:5}}")
		int connectTimeoutSeconds,
		@Value("${home.coordinate-source.db.socket-timeout-seconds:${COORDINATE_SOURCE_DB_SOCKET_TIMEOUT_SECONDS:10}}")
		int socketTimeoutSeconds,
		@Value("${home.coordinate-source.db.lock-timeout-millis:${COORDINATE_SOURCE_DB_LOCK_TIMEOUT_MILLIS:1000}}")
		int lockTimeoutMillis,
		@Value("${home.coordinate-source.db.statement-timeout-millis:${COORDINATE_SOURCE_DB_STATEMENT_TIMEOUT_MILLIS:3000}}")
		int statementTimeoutMillis
	) {
		return new RequiredRtmsCoordinateSourcePreflight(
			jdbcUrl,
			ingestProperties.allowCoordinatePendingOnly(),
			new JdbcRtmsCoordinateSourceAvailabilityProbe(
				jdbcUrl,
				username,
				password,
				connectTimeoutSeconds,
				socketTimeoutSeconds,
				lockTimeoutMillis,
				statementTimeoutMillis
			)
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

	private RtmsBackfillChunkExecutionResult summaryToBackfillResult(RtmsMonthlyRefreshRunSummary summary) {
		return switch (summary.status()) {
			case COMPLETED -> RtmsBackfillChunkExecutionResult.completed(
				summary.lawdCd(),
				summary.dealYmd(),
				summary.runId(),
				summary.ingestResult()
			);
			case PARTIAL -> RtmsBackfillChunkExecutionResult.partial(
				summary.lawdCd(),
				summary.dealYmd(),
				summary.runId(),
				summary.failureReason(),
				summary.ingestResult()
			);
			case FAILED -> RtmsBackfillChunkExecutionResult.failed(
				summary.lawdCd(),
				summary.dealYmd(),
				summary.runId(),
				summary.failureReason(),
				summary.ingestResult()
			);
		};
	}
}
