package com.home.infrastructure.external.complex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceClient;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceParser;
import com.home.application.ingest.metadata.ComplexMetadataEnrichmentClient;
import com.home.application.ingest.metadata.OdcloudPnuPrefixAliasLookup;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class ComplexMetadataClientConfiguration {

	@Bean
	BuildingMetadataSourceParser buildingMetadataSourceParser(ObjectMapper objectMapper) {
		return new BuildingMetadataJsonParser(objectMapper);
	}

	@Bean
	BuildingMetadataSourceClient buildingMetadataSourceClient(
		@Value("${odcloud.data.base-url:https://api.odcloud.kr}") String odcloudBaseUrl,
		@Value("${odcloud.data.od-service-key:${ODC_SERVICE_KEY:}}") String odcloudServiceKey,
		@Value("${odcloud.data.apt-title-path:}") String odcloudAptPath,
		@Value("${apis.data.base-url:https://apis.data.go.kr}") String buildingBaseUrl,
		@Value("${apis.data.bld-service-key:${BLD_SERVICE_KEY:}}") String buildingServiceKey,
		@Value("${apis.data.bld-title-path:/1613000/BldRgstHubService/getBrRecapTitleInfo}") String recapPath,
		@Value("${apis.data.recap-title-path:/1613000/BldRgstHubService/getBrTitleInfo}") String titlePath,
		@Value("${complex.metadata.min-request-interval-millis:250}") long minRequestIntervalMillis,
		@Value("${complex.metadata.connect-timeout-millis:5000}") int connectTimeoutMillis,
		@Value("${complex.metadata.read-timeout-millis:5000}") int readTimeoutMillis
	) {
		return new PublicBuildingMetadataSourceClient(
			buildRestClient(odcloudBaseUrl, connectTimeoutMillis, readTimeoutMillis), odcloudBaseUrl, odcloudServiceKey,
			defaultOdcloudAptPath(odcloudAptPath),
			buildRestClient(buildingBaseUrl, connectTimeoutMillis, readTimeoutMillis), buildingBaseUrl, buildingServiceKey,
			recapPath, titlePath, minRequestIntervalMillis
		);
	}

	@Bean
	ComplexMetadataEnrichmentClient complexMetadataEnrichmentClient(
		@Value("${odcloud.data.base-url:https://api.odcloud.kr}") String odcloudBaseUrl,
		@Value("${odcloud.data.od-service-key:${ODC_SERVICE_KEY:}}") String odcloudServiceKey,
		@Value("${odcloud.data.apt-title-path:}") String odcloudAptPath,
		@Value("${apis.data.base-url:https://apis.data.go.kr}") String bldBaseUrl,
		@Value("${apis.data.bld-service-key:${BLD_SERVICE_KEY:}}") String bldServiceKey,
		@Value("${apis.data.bld-title-path:/1613000/BldRgstHubService/getBrRecapTitleInfo}") String bldRecapPath,
		@Value("${apis.data.recap-title-path:/1613000/BldRgstHubService/getBrTitleInfo}") String recapPath,
		@Value("${complex.metadata.building.enabled:false}") boolean buildingFallbackEnabled,
		@Value("${complex.metadata.connect-timeout-millis:5000}") int connectTimeoutMillis,
		@Value("${complex.metadata.read-timeout-millis:5000}") int readTimeoutMillis
		,
		ObjectProvider<OdcloudPnuPrefixAliasLookup> aliasLookupProvider
	) {
		return new PublicComplexMetadataResolver(
			buildRestClient(odcloudBaseUrl, connectTimeoutMillis, readTimeoutMillis),
			odcloudBaseUrl,
			odcloudServiceKey,
			defaultOdcloudAptPath(odcloudAptPath),
			buildRestClient(bldBaseUrl, connectTimeoutMillis, readTimeoutMillis),
			bldBaseUrl,
			bldServiceKey,
			bldRecapPath,
			recapPath,
			buildingFallbackEnabled,
			canonicalPnu -> aliasLookupProvider.getIfAvailable(OdcloudPnuPrefixAliasLookup::empty)
				.findApprovedByCanonicalPnu(canonicalPnu)
		);
	}

	private String defaultOdcloudAptPath(String configuredPath) {
		return configuredPath != null && !configuredPath.isBlank()
			? configuredPath
			: "/api/AptIdInfoSvc/" + "v" + "1/getAptInfo";
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
