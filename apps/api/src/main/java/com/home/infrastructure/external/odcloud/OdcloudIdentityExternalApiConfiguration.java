package com.home.infrastructure.external.odcloud;

import com.home.application.ingest.ComplexIdentityResolver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class OdcloudIdentityExternalApiConfiguration {

	@Bean
	@ConditionalOnProperty(name = "complex.identity.odcloud.enabled", havingValue = "true")
	ComplexIdentityResolver odcloudComplexIdentityResolver(
		@Value("${odcloud.data.base-url:https://api.odcloud.kr}") String odcloudBaseUrl,
		@Value("${odcloud.data.od-service-key:${ODC_SERVICE_KEY:}}") String odcloudServiceKey,
		@Value("${odcloud.data.apt-title-path:}") String odcloudAptPath,
		@Value("${complex.identity.connect-timeout-millis:5000}") int connectTimeoutMillis,
		@Value("${complex.identity.read-timeout-millis:5000}") int readTimeoutMillis
	) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMillis);
		requestFactory.setReadTimeout(readTimeoutMillis);
		return new OdcloudComplexIdentityResolver(
			RestClient.builder()
				.requestFactory(requestFactory)
				.baseUrl(odcloudBaseUrl)
				.build(),
			odcloudBaseUrl,
			odcloudServiceKey,
			defaultOdcloudAptPath(odcloudAptPath)
		);
	}

	private String defaultOdcloudAptPath(String configuredPath) {
		return configuredPath != null && !configuredPath.isBlank()
			? configuredPath
			: "/api/AptIdInfoSvc/" + "v" + "1/getAptInfo";
	}
}
