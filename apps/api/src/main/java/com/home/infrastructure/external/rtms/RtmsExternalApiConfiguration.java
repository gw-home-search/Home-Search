package com.home.infrastructure.external.rtms;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
	RtmsCoordinateSourcePreflight rtmsCoordinateSourcePreflight(
		@Value("${home.ingest.rtms.allow-coordinate-pending-only:false}") boolean allowCoordinatePendingOnly,
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
			allowCoordinatePendingOnly,
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
}
