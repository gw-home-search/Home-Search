package com.home.infrastructure.external.complex;

import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceClient;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceParser;
import com.home.application.ingest.metadata.OdcloudPnuPrefixAliasLookup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ComplexMetadataClientConfiguration {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ComplexMetadataClientConfiguration.class);

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
            @Value("${apis.data.building-title-path:}") String canonicalTitlePath,
            @Value("${apis.data.building-recap-title-path:}") String canonicalRecapPath,
            @Value("${apis.data.bld-title-path:/1613000/BldRgstHubService/getBrRecapTitleInfo}")
                    String legacyBldTitlePath,
            @Value("${apis.data.recap-title-path:/1613000/BldRgstHubService/getBrTitleInfo}")
                    String legacyRecapTitlePath,
            @Value("${complex.metadata.min-request-interval-millis:250}") long minRequestIntervalMillis,
            @Value("${complex.metadata.connect-timeout-millis:5000}") int connectTimeoutMillis,
            @Value("${complex.metadata.read-timeout-millis:5000}") int readTimeoutMillis) {
        BuildingMetadataEndpointPaths paths = BuildingMetadataEndpointPaths.resolve(
                canonicalTitlePath, canonicalRecapPath, legacyBldTitlePath, legacyRecapTitlePath);
        warnLegacy(paths);
        return new PublicBuildingMetadataSourceClient(
                buildRestClient(odcloudBaseUrl, connectTimeoutMillis, readTimeoutMillis),
                odcloudBaseUrl,
                odcloudServiceKey,
                defaultOdcloudAptPath(odcloudAptPath),
                buildRestClient(buildingBaseUrl, connectTimeoutMillis, readTimeoutMillis),
                buildingBaseUrl,
                buildingServiceKey,
                paths.recap(),
                paths.title(),
                minRequestIntervalMillis);
    }

    @Bean
    PublicComplexMetadataResolver complexMetadataEnrichmentClient(
            @Value("${odcloud.data.base-url:https://api.odcloud.kr}") String odcloudBaseUrl,
            @Value("${odcloud.data.od-service-key:${ODC_SERVICE_KEY:}}") String odcloudServiceKey,
            @Value("${odcloud.data.apt-title-path:}") String odcloudAptPath,
            @Value("${apis.data.base-url:https://apis.data.go.kr}") String bldBaseUrl,
            @Value("${apis.data.bld-service-key:${BLD_SERVICE_KEY:}}") String bldServiceKey,
            @Value("${apis.data.building-title-path:}") String canonicalTitlePath,
            @Value("${apis.data.building-recap-title-path:}") String canonicalRecapPath,
            @Value("${apis.data.bld-title-path:/1613000/BldRgstHubService/getBrRecapTitleInfo}")
                    String legacyBldTitlePath,
            @Value("${apis.data.recap-title-path:/1613000/BldRgstHubService/getBrTitleInfo}")
                    String legacyRecapTitlePath,
            @Value("${complex.metadata.building.enabled:false}") boolean buildingFallbackEnabled,
            @Value("${complex.metadata.connect-timeout-millis:5000}") int connectTimeoutMillis,
            @Value("${complex.metadata.read-timeout-millis:5000}") int readTimeoutMillis,
            ObjectProvider<OdcloudPnuPrefixAliasLookup> aliasLookupProvider) {
        BuildingMetadataEndpointPaths paths = BuildingMetadataEndpointPaths.resolve(
                canonicalTitlePath, canonicalRecapPath, legacyBldTitlePath, legacyRecapTitlePath);
        warnLegacy(paths);
        return new PublicComplexMetadataResolver(
                buildRestClient(odcloudBaseUrl, connectTimeoutMillis, readTimeoutMillis),
                odcloudBaseUrl,
                odcloudServiceKey,
                defaultOdcloudAptPath(odcloudAptPath),
                buildRestClient(bldBaseUrl, connectTimeoutMillis, readTimeoutMillis),
                bldBaseUrl,
                bldServiceKey,
                paths.recap(),
                paths.title(),
                buildingFallbackEnabled,
                canonicalPnu -> aliasLookupProvider
                        .getIfAvailable(OdcloudPnuPrefixAliasLookup::empty)
                        .findApprovedByCanonicalPnu(canonicalPnu));
    }

    private void warnLegacy(BuildingMetadataEndpointPaths paths) {
        if (paths.usesLegacy()) {
            log.warn(
                    "legacy building metadata endpoint properties are in use; migrate to apis.data.building-title-path and apis.data.building-recap-title-path");
        }
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
