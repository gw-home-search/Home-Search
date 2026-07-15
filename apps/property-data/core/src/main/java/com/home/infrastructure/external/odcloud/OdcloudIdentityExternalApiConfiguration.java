package com.home.infrastructure.external.odcloud;

import com.home.application.coordinate.identity.ComplexCoordinateIdentityVerifier;
import com.home.application.ingest.matching.ComplexIdentityResolver;
import com.home.infrastructure.configuration.CoordinateIdentityProperties;
import com.home.infrastructure.configuration.ExternalApiCredentialProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    OdcloudProperties.class,
    ComplexIdentityProperties.class,
    CoordinateIdentityProperties.class,
    ExternalApiCredentialProperties.class
})
class OdcloudIdentityExternalApiConfiguration {

    @Bean
    @ConditionalOnProperty(name = "complex.identity.odcloud.enabled", havingValue = "true")
    ComplexIdentityResolver odcloudComplexIdentityResolver(
            OdcloudProperties odcloud,
            ComplexIdentityProperties identity,
            ExternalApiCredentialProperties credentials) {
        SimpleClientHttpRequestFactory requestFactory =
                requestFactory(identity.connectTimeoutMillis(), identity.readTimeoutMillis());
        return new OdcloudComplexIdentityResolver(
                RestClient.builder()
                        .requestFactory(requestFactory)
                        .baseUrl(odcloud.baseUrl().toString())
                        .build(),
                odcloud.baseUrl().toString(),
                credentials.odcServiceKey(odcloud.odServiceKey()),
                odcloud.effectiveAptTitlePath());
    }

    @Bean
    @ConditionalOnProperty(
            name = "complex.coordinate.identity.odcloud.enabled",
            havingValue = "true",
            matchIfMissing = true)
    ComplexCoordinateIdentityVerifier odcloudComplexCoordinateIdentityVerifier(
            OdcloudProperties odcloud,
            CoordinateIdentityProperties identity,
            ExternalApiCredentialProperties credentials) {
        String serviceKey = credentials.odcServiceKey(odcloud.odServiceKey());
        if (serviceKey.isBlank()) {
            return ComplexCoordinateIdentityVerifier.trusting();
        }
        SimpleClientHttpRequestFactory requestFactory =
                requestFactory(identity.connectTimeoutMillis(), identity.readTimeoutMillis());
        return new OdcloudComplexCoordinateIdentityVerifier(
                RestClient.builder()
                        .requestFactory(requestFactory)
                        .baseUrl(odcloud.baseUrl().toString())
                        .build(),
                odcloud.baseUrl().toString(),
                serviceKey,
                odcloud.effectiveAptTitlePath());
    }

    private SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMillis, int readTimeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMillis);
        requestFactory.setReadTimeout(readTimeoutMillis);
        return requestFactory;
    }
}
