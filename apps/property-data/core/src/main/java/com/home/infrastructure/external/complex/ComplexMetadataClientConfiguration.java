package com.home.infrastructure.external.complex;

import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceClient;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceParser;
import com.home.application.ingest.buildingprofile.BuildingProfilePageParser;
import com.home.application.ingest.buildingregister.BuildingRegisterPageClient;
import com.home.application.ingest.buildingregister.BuildingRegisterPageParser;
import com.home.application.ingest.metadata.OdcloudPnuPrefixAliasLookup;
import com.home.infrastructure.configuration.ExternalApiCredentialProperties;
import com.home.infrastructure.external.odcloud.OdcloudProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    OdcloudProperties.class,
    BuildingApiProperties.class,
    ComplexMetadataProperties.class,
    ExternalApiCredentialProperties.class
})
public class ComplexMetadataClientConfiguration {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ComplexMetadataClientConfiguration.class);

    @Bean
    BuildingMetadataSourceParser buildingMetadataSourceParser(ObjectMapper objectMapper) {
        return new BuildingMetadataJsonParser(objectMapper);
    }

    @Bean
    BuildingRegisterPageParser buildingRegisterPageParser(ObjectMapper objectMapper) {
        return new BuildingRegisterJsonParser(objectMapper);
    }

    @Bean
    BuildingProfilePageParser buildingProfilePageParser(ObjectMapper objectMapper) {
        return new BuildingRegisterProfileJsonParser(objectMapper);
    }

    @Bean
    BuildingRegisterPageClient buildingRegisterPageClient(
            BuildingApiProperties building,
            ComplexMetadataProperties metadata,
            ExternalApiCredentialProperties credentials) {
        BuildingMetadataEndpointPaths paths = paths(building);
        return new PublicBuildingRegisterPageClient(
                buildRestClient(building.baseUrl().toString(), metadata),
                building.baseUrl().toString(),
                credentials.bldServiceKey(building.bldServiceKey()),
                paths.recap(),
                paths.title(),
                building.buildingBasicOverviewPath(),
                metadata.minRequestIntervalMillis());
    }

    @Bean
    BuildingMetadataSourceClient buildingMetadataSourceClient(
            OdcloudProperties odcloud,
            BuildingApiProperties building,
            ComplexMetadataProperties metadata,
            ExternalApiCredentialProperties credentials) {
        BuildingMetadataEndpointPaths paths = paths(building);
        return new PublicBuildingMetadataSourceClient(
                buildRestClient(odcloud.baseUrl().toString(), metadata),
                odcloud.baseUrl().toString(),
                credentials.odcServiceKey(odcloud.odServiceKey()),
                odcloud.effectiveAptTitlePath(),
                buildRestClient(building.baseUrl().toString(), metadata),
                building.baseUrl().toString(),
                credentials.bldServiceKey(building.bldServiceKey()),
                paths.recap(),
                paths.title(),
                metadata.minRequestIntervalMillis());
    }

    @Bean
    PublicComplexMetadataResolver complexMetadataEnrichmentClient(
            OdcloudProperties odcloud,
            BuildingApiProperties building,
            ComplexMetadataProperties metadata,
            ExternalApiCredentialProperties credentials,
            OdcloudPnuPrefixAliasLookup aliasLookup) {
        BuildingMetadataEndpointPaths paths = paths(building);
        return new PublicComplexMetadataResolver(
                buildRestClient(odcloud.baseUrl().toString(), metadata),
                odcloud.baseUrl().toString(),
                credentials.odcServiceKey(odcloud.odServiceKey()),
                odcloud.effectiveAptTitlePath(),
                buildRestClient(building.baseUrl().toString(), metadata),
                building.baseUrl().toString(),
                credentials.bldServiceKey(building.bldServiceKey()),
                paths.recap(),
                paths.title(),
                metadata.building().enabled(),
                canonicalPnu -> aliasLookup.findApprovedByCanonicalPnu(canonicalPnu));
    }

    private BuildingMetadataEndpointPaths paths(BuildingApiProperties properties) {
        BuildingMetadataEndpointPaths paths = BuildingMetadataEndpointPaths.resolve(
                properties.buildingTitlePath(),
                properties.buildingRecapTitlePath(),
                properties.bldTitlePath(),
                properties.recapTitlePath());
        if (paths.usesLegacy()) {
            log.warn(
                    "legacy building metadata endpoint properties are in use; migrate to apis.data.building-title-path and apis.data.building-recap-title-path");
        }
        return paths;
    }

    private RestClient buildRestClient(String baseUrl, ComplexMetadataProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeoutMillis());
        requestFactory.setReadTimeout(properties.readTimeoutMillis());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }
}
