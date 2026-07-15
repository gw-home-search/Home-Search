package com.home.infrastructure.observability;

import com.home.application.ingest.trade.TradeIngestMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TradeIngestMetricsConfiguration {

    @Bean
    TradeIngestMetrics tradeIngestMetrics(MeterRegistry meterRegistry) {
        return new MicrometerTradeIngestMetrics(meterRegistry);
    }
}
