package com.home.infrastructure.external.vworld;

import com.home.application.coordinate.footprint.BuildingFootprintSource;
import com.home.application.coordinate.lookup.CoordinateSourceFirstParcelCoordinateResolver;
import com.home.application.coordinate.lookup.ParcelCoordinateOverrideRepository;
import com.home.application.coordinate.lookup.ParcelCoordinateResolver;
import com.home.application.coordinate.lookup.ParcelCoordinateSourceRepository;
import com.home.infrastructure.configuration.ExternalApiCredentialProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({VworldParcelCoordinateProperties.class, ExternalApiCredentialProperties.class})
class VworldExternalApiConfiguration {

    @Bean
    @Lazy
    ParcelCoordinateResolver vworldParcelCoordinateResolver(
            VworldParcelCoordinateProperties properties, ExternalApiCredentialProperties credentials) {
        properties = effectiveProperties(properties, credentials);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeoutMillis());
        requestFactory.setReadTimeout(properties.readTimeoutMillis());
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl())
                .build();
        return new VworldParcelCoordinateResolver(restClient, properties);
    }

    @Bean
    @Lazy
    BuildingFootprintSource vworldBuildingFootprintSource(
            VworldParcelCoordinateProperties properties, ExternalApiCredentialProperties credentials) {
        properties = effectiveProperties(properties, credentials);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeoutMillis());
        requestFactory.setReadTimeout(properties.readTimeoutMillis());
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl())
                .build();
        return new VworldBuildingFootprintSource(restClient, properties);
    }

    @Bean
    @Primary
    @Lazy
    ParcelCoordinateResolver parcelCoordinateResolver(
            ParcelCoordinateSourceRepository coordinateSourceRepository,
            ParcelCoordinateOverrideRepository overrideRepository) {
        return new CoordinateSourceFirstParcelCoordinateResolver(coordinateSourceRepository, overrideRepository);
    }

    private VworldParcelCoordinateProperties effectiveProperties(
            VworldParcelCoordinateProperties properties, ExternalApiCredentialProperties credentials) {
        return new VworldParcelCoordinateProperties(
                properties.baseUrl(),
                properties.vmWfsPath(),
                credentials.vwServiceKey(properties.vwServiceKey()),
                properties.vmDomain(),
                properties.numOfRows(),
                properties.connectTimeoutMillis(),
                properties.readTimeoutMillis());
    }
}
