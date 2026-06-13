package com.home.infrastructure.external.complex;

import com.home.application.ingest.metadata.ComplexMetadataEnrichmentClient;
import com.home.application.ingest.metadata.ComplexMetadataEnrichmentService;
import com.home.application.ingest.metadata.OdcloudPnuPrefixAliasLookup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class ComplexMetadataExternalApiConfiguration {

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

	@Bean
	@ConditionalOnProperty(name = "complex.metadata.enrich.enabled", havingValue = "true")
	ApplicationRunner complexMetadataEnrichmentRunner(
		ComplexMetadataEnrichmentService enrichmentService,
		@Value("${complex.metadata.enrich.batch-size:100}") int batchSize
	) {
		return new ComplexMetadataEnrichmentRunner(enrichmentService, batchSize);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableScheduling
	@ConditionalOnProperty(name = "complex.metadata.enrich.scheduler.enabled", havingValue = "true")
	static class ComplexMetadataEnrichmentSchedulingConfiguration {

		@Bean
		ComplexMetadataEnrichmentScheduler complexMetadataEnrichmentScheduler(
			ComplexMetadataEnrichmentService enrichmentService,
			@Value("${complex.metadata.enrich.batch-size:100}") int batchSize
		) {
			return new ComplexMetadataEnrichmentScheduler(enrichmentService, batchSize);
		}
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
