package com.home.infrastructure.scheduling.coordinate;

import com.home.application.coordinate.readiness.ComplexCoordinateReadinessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(CoordinateReadinessProperties.class)
@ConditionalOnProperty(name = "home.coordinate.readiness.enabled", havingValue = "true")
public class ComplexCoordinateReadinessSchedulingConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "home.coordinate.readiness.scheduler.enabled",
            havingValue = "true",
            matchIfMissing = true)
    ComplexCoordinateReadinessScheduler complexCoordinateReadinessScheduler(
            ComplexCoordinateReadinessService complexCoordinateReadinessService,
            CoordinateReadinessProperties properties) {
        return new ComplexCoordinateReadinessScheduler(
                complexCoordinateReadinessService,
                properties.stageLimit(),
                properties.resolveLimit(),
                properties.projectLimit());
    }
}
