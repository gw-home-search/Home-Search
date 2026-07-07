package com.home.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class MapEndpointMetricsConfiguration {

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
