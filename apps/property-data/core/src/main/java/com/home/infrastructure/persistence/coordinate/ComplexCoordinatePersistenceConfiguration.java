package com.home.infrastructure.persistence.coordinate;

import com.home.application.coordinate.readiness.ComplexCoordinateReadinessService;
import com.home.application.coordinate.readiness.CoordinateReadinessPolicy;
import com.home.domain.coordinate.CoordinateIdentityBlockingPolicy;
import com.home.infrastructure.configuration.CoordinateIdentityProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CoordinateReadinessProperties.class, CoordinateIdentityProperties.class})
class ComplexCoordinatePersistenceConfiguration {

    @Bean
    CoordinateIdentityBlockingPolicy coordinateIdentityBlockingPolicy(CoordinateIdentityProperties properties) {
        return new CoordinateIdentityBlockingPolicy(properties.blockOnUnavailable(), properties.blockOnFailed());
    }

    @Bean
    CoordinateReadinessPolicy coordinateReadinessPolicy(CoordinateReadinessProperties properties) {
        return new CoordinateReadinessPolicy(properties.retryLimit(), properties.retryAfterMillis());
    }

    @Bean
    @ConditionalOnProperty(name = "home.coordinate.readiness.enabled", havingValue = "true")
    ApplicationRunner complexCoordinateReadinessRunner(
            ComplexCoordinateReadinessService complexCoordinateReadinessService,
            CoordinateReadinessProperties properties) {
        return new ComplexCoordinateReadinessRunner(
                complexCoordinateReadinessService,
                properties.stageLimit(),
                properties.resolveLimit(),
                properties.projectLimit());
    }
}
