package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.reconciliation.RawIngestReconciliationService;
import com.home.infrastructure.persistence.ingest.raw.RawIngestReconciliationRunner;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RawIngestProperties.class)
class RawIngestPersistenceConfiguration {

    @Bean
    @ConditionalOnProperty(name = "home.ingest.raw-reconcile.enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner rawIngestReconciliationRunner(
            RawIngestReconciliationService reconciliationService, RawIngestProperties properties) {
        return new RawIngestReconciliationRunner(() -> reconciliationService, properties.batchSize(), () -> true);
    }
}
