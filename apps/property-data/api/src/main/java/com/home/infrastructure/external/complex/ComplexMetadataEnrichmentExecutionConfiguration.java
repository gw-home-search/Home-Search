package com.home.infrastructure.external.complex;

import com.home.application.ingest.metadata.ComplexMetadataEnrichmentService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
class ComplexMetadataEnrichmentExecutionConfiguration {

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
}
