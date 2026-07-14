package com.home.infrastructure.persistence.ingest;

import com.home.infrastructure.persistence.ingest.normalization.JdbcTradePartitionMaintenanceRepository;
import com.home.infrastructure.persistence.ingest.normalization.TradePartitionMaintenanceRunner;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TradePartitionProperties.class)
class TradeNormalizationPersistenceConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "home.trade.partition.maintenance.enabled",
            havingValue = "true",
            matchIfMissing = false)
    ApplicationRunner tradePartitionMaintenanceRunner(
            JdbcTradePartitionMaintenanceRepository maintenanceRepository, TradePartitionProperties properties) {
        return new TradePartitionMaintenanceRunner(
                () -> maintenanceRepository, java.time.Clock.systemUTC(), properties.yearsAhead(), () -> true);
    }
}
