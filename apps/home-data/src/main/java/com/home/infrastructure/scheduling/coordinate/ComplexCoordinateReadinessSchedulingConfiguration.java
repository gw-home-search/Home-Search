package com.home.infrastructure.scheduling.coordinate;

import com.home.application.coordinate.readiness.ComplexCoordinateReadinessService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "home.coordinate.readiness.enabled", havingValue = "true")
public class ComplexCoordinateReadinessSchedulingConfiguration {

	@Bean
	@ConditionalOnProperty(
		name = "home.coordinate.readiness.scheduler.enabled",
		havingValue = "true",
		matchIfMissing = true
	)
	ComplexCoordinateReadinessScheduler complexCoordinateReadinessScheduler(
		ComplexCoordinateReadinessService complexCoordinateReadinessService,
		@Value("${home.coordinate.readiness.stage-limit:500}") int stageLimit,
		@Value("${home.coordinate.readiness.resolve-limit:500}") int resolveLimit,
		@Value("${home.coordinate.readiness.project-limit:1000}") int projectLimit
	) {
		return new ComplexCoordinateReadinessScheduler(
			complexCoordinateReadinessService,
			stageLimit,
			resolveLimit,
			projectLimit
		);
	}
}
