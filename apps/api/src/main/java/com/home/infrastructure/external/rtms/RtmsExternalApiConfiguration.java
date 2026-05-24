package com.home.infrastructure.external.rtms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.OpenApiTradeIngestService;

import org.springframework.beans.factory.ObjectProvider;
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
		@Value("${home.ingest.rtms.preflight-only:false}") boolean preflightOnly
	) {
		return new RtmsOneShotIngestProperties(enabled, lawdCd, dealYmd, pageNo, preflightOnly);
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
	ApplicationRunner rtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties
	) {
		return new RtmsOneShotIngestApplicationRunner(runner, properties, tradeProperties);
	}
}
