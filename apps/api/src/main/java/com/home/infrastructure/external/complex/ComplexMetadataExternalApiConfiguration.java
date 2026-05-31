package com.home.infrastructure.external.complex;

import com.home.application.ingest.ComplexMetadataResolver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class ComplexMetadataExternalApiConfiguration {

	@Bean
	ComplexMetadataResolver complexMetadataResolver(
		@Value("${odcloud.data.base-url:https://api.odcloud.kr}") String odcloudBaseUrl,
		@Value("${odcloud.data.od-service-key:${ODC_SERVICE_KEY:}}") String odcloudServiceKey,
		@Value("${odcloud.data.apt-title-path:/api/AptIdInfoSvc/v1/getAptInfo}") String odcloudAptPath,
		@Value("${apis.data.base-url:https://apis.data.go.kr}") String bldBaseUrl,
		@Value("${apis.data.bld-service-key:${BLD_SERVICE_KEY:}}") String bldServiceKey,
		@Value("${apis.data.bld-title-path:/1613000/BldRgstHubService/getBrRecapTitleInfo}") String bldRecapPath,
		@Value("${apis.data.recap-title-path:/1613000/BldRgstHubService/getBrTitleInfo}") String recapPath,
		@Value("${complex.metadata.connect-timeout-millis:5000}") int connectTimeoutMillis,
		@Value("${complex.metadata.read-timeout-millis:5000}") int readTimeoutMillis
	) {
		return new PublicComplexMetadataResolver(
			buildRestClient(odcloudBaseUrl, connectTimeoutMillis, readTimeoutMillis),
			odcloudServiceKey,
			odcloudAptPath,
			buildRestClient(bldBaseUrl, connectTimeoutMillis, readTimeoutMillis),
			bldServiceKey,
			bldRecapPath,
			recapPath
		);
	}

	private RestClient buildRestClient(String baseUrl, int connectTimeoutMillis, int readTimeoutMillis) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMillis);
		requestFactory.setReadTimeout(readTimeoutMillis);
		return RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(baseUrl)
			.build();
	}
}
