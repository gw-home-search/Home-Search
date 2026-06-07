package com.home.infrastructure.external.naver;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleObservationIngestService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class NaverNewsExternalApiConfiguration {

	@Bean
	NaverNewsSearchProperties naverNewsSearchProperties(
		@Value("${naver.news.base-url:https://openapi.naver.com}") String baseUrl,
		@Value("${naver.news.path:}") String path,
		@Value("${naver.news.client-id:${NAVER_NEWS_CLIENT_ID:}}") String clientId,
		@Value("${naver.news.client-token:${NAVER_NEWS_CLIENT_TOKEN:}}") String clientToken,
		@Value("${naver.news.connect-timeout-millis:5000}") int connectTimeoutMillis,
		@Value("${naver.news.read-timeout-millis:5000}") int readTimeoutMillis
	) {
		return new NaverNewsSearchProperties(
			baseUrl,
			path,
			clientId,
			clientToken,
			connectTimeoutMillis,
			readTimeoutMillis
		);
	}

	@Bean
	NaverNewsOneShotIngestProperties naverNewsOneShotIngestProperties(
		@Value("${home.news.naver.enabled:false}") boolean enabled,
		@Value("${home.news.naver.query:}") String query,
		@Value("${home.news.naver.display:100}") int display,
		@Value("${home.news.naver.start:1}") int start,
		@Value("${home.news.naver.sort:date}") String sort,
		@Value("${home.news.naver.preflight-only:false}") boolean preflightOnly
	) {
		return new NaverNewsOneShotIngestProperties(enabled, query, display, start, sort, preflightOnly);
	}

	@Bean
	NaverNewsSearchResponseParser naverNewsSearchResponseParser(ObjectMapper objectMapper) {
		return new NaverNewsSearchResponseParser(objectMapper);
	}

	@Bean
	NaverNewsObservationMapper naverNewsObservationMapper(ObjectMapper objectMapper) {
		return new NaverNewsObservationMapper(Clock.systemUTC(), objectMapper);
	}

	@Bean
	RestClient naverNewsSearchRestClient(NaverNewsSearchProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeoutMillis());
		requestFactory.setReadTimeout(properties.readTimeoutMillis());
		return RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(properties.baseUrl())
			.build();
	}

	@Bean
	NaverNewsSearchClient naverNewsSearchClient(
		@Qualifier("naverNewsSearchRestClient")
		RestClient naverNewsSearchRestClient,
		NaverNewsSearchProperties properties,
		NaverNewsSearchResponseParser parser
	) {
		return new NaverNewsRestClient(naverNewsSearchRestClient, properties, parser);
	}

	@Bean
	@ConditionalOnProperty(name = "home.news.naver.enabled", havingValue = "true")
	NaverNewsOneShotIngestRunner naverNewsOneShotIngestRunner(
		NaverNewsSearchClient client,
		NaverNewsObservationMapper mapper,
		NewsArticleObservationIngestService ingestService
	) {
		return new NaverNewsOneShotIngestRunner(client, mapper, ingestService);
	}

	@Bean
	@ConditionalOnProperty(name = "home.news.naver.enabled", havingValue = "true")
	ApplicationRunner naverNewsOneShotIngestApplicationRunner(
		NaverNewsOneShotIngestRunner runner,
		NaverNewsOneShotIngestProperties properties,
		NaverNewsSearchProperties searchProperties
	) {
		return new NaverNewsOneShotIngestApplicationRunner(runner, properties, searchProperties);
	}
}
