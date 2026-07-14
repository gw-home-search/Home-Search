package com.home.infrastructure.external.rtms;

import com.home.infrastructure.configuration.CoordinateSourceDbProperties;
import com.home.infrastructure.configuration.ExternalApiCredentialProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RtmsApartmentTradeProperties.class, ExternalApiCredentialProperties.class})
public class RtmsExternalApiConfiguration {

    @Bean
    RtmsApartmentTradeResponseParser rtmsApartmentTradeResponseParser(ObjectMapper objectMapper) {
        return new RtmsApartmentTradeResponseParser(objectMapper);
    }

    @Bean
    RestClient rtmsApartmentTradeRestClient(RtmsApartmentTradeProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeoutMillis());
        requestFactory.setReadTimeout(properties.readTimeoutMillis());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl())
                .build();
    }

    @Bean
    RtmsApartmentTradeClient rtmsApartmentTradeClient(
            @Qualifier("rtmsApartmentTradeRestClient") RestClient rtmsApartmentTradeRestClient,
            RtmsApartmentTradeProperties properties,
            ExternalApiCredentialProperties credentials,
            RtmsApartmentTradeResponseParser parser) {
        RtmsApartmentTradeProperties effectiveProperties = new RtmsApartmentTradeProperties(
                properties.baseUrl(),
                properties.path(),
                credentials.aptServiceKey(properties.aptServiceKey()),
                properties.numOfRows(),
                properties.connectTimeoutMillis(),
                properties.readTimeoutMillis(),
                properties.minRequestIntervalMillis());
        return new RateLimitedRtmsApartmentTradeClient(
                new RtmsPublicApartmentTradeClient(rtmsApartmentTradeRestClient, effectiveProperties, parser),
                properties.minRequestIntervalMillis());
    }

    @Bean
    RtmsCoordinateSourceAvailabilityProbe rtmsCoordinateSourceAvailabilityProbe(
            CoordinateSourceDbProperties properties) {
        return new JdbcRtmsCoordinateSourceAvailabilityProbe(
                properties.jdbcUrl(),
                properties.username(),
                properties.password(),
                properties.connectTimeoutSeconds(),
                properties.socketTimeoutSeconds(),
                properties.lockTimeoutMillis(),
                properties.statementTimeoutMillis());
    }
}
