package com.home.infrastructure.external.prediction;

import com.home.application.prediction.PredictionClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class PredictionExternalApiConfiguration {

	@Bean
	@ConditionalOnMissingBean(PredictionClient.class)
	PredictionClient predictionClient(
		@Value("${home.prediction.client.base-url:http://localhost:8001}") String baseUrl,
		@Value("${home.prediction.client.connect-timeout-millis:1000}") int connectTimeoutMillis,
		@Value("${home.prediction.client.read-timeout-millis:3000}") int readTimeoutMillis
	) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMillis);
		requestFactory.setReadTimeout(readTimeoutMillis);
		return new PythonPredictionClient(RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(baseUrl)
			.build());
	}
}
