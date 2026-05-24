package com.home.infrastructure.observability;

import com.home.application.ingest.TradeIngestMetrics;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

	@Bean
	TradeIngestMetrics tradeIngestMetrics(MeterRegistry meterRegistry) {
		return new MicrometerTradeIngestMetrics(meterRegistry);
	}

	@Bean
	MapEndpointMetricsInterceptor mapEndpointMetricsInterceptor(MeterRegistry meterRegistry) {
		return new MapEndpointMetricsInterceptor(meterRegistry);
	}

	@Bean
	WebMvcConfigurer mapEndpointMetricsWebMvcConfigurer(MapEndpointMetricsInterceptor interceptor) {
		return new WebMvcConfigurer() {
			@Override
			public void addInterceptors(InterceptorRegistry registry) {
				registry.addInterceptor(interceptor);
			}
		};
	}
}
