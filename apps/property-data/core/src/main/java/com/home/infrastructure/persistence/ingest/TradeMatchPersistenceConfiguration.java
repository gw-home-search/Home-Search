package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.matching.TradeMatchRematchService;
import com.home.infrastructure.persistence.ingest.matching.TradeMatchRematchRunner;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TradeMatchProperties.class)
class TradeMatchPersistenceConfiguration {

    @Bean
    @ConditionalOnProperty(name = "home.ingest.match-rematch.enabled", havingValue = "true")
    ApplicationRunner tradeMatchRematchRunner(
            TradeMatchRematchService tradeMatchRematchService, TradeMatchProperties properties) {
        return new TradeMatchRematchRunner(tradeMatchRematchService, properties.batchSize());
    }
}
