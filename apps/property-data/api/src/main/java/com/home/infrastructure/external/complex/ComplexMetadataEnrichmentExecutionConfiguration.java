package com.home.infrastructure.external.complex;

import com.home.application.ingest.metadata.ComplexMetadataEnrichmentService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComplexMetadataProperties.class)
class ComplexMetadataEnrichmentExecutionConfiguration {

    @Bean
    @ConditionalOnProperty(name = "complex.metadata.enrich.enabled", havingValue = "true")
    ApplicationRunner complexMetadataEnrichmentRunner(
            ComplexMetadataEnrichmentService enrichmentService, ComplexMetadataProperties properties) {
        return new ComplexMetadataEnrichmentRunner(
                enrichmentService, properties.enrich().batchSize());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "complex.metadata.enrich.scheduler.enabled", havingValue = "true")
    static class ComplexMetadataEnrichmentSchedulingConfiguration {

        @Bean
        ComplexMetadataEnrichmentScheduler complexMetadataEnrichmentScheduler(
                ComplexMetadataEnrichmentService enrichmentService, ComplexMetadataProperties properties) {
            return new ComplexMetadataEnrichmentScheduler(
                    enrichmentService, properties.enrich().batchSize());
        }
    }
}
