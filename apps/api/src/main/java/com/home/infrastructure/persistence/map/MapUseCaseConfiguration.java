package com.home.infrastructure.persistence.map;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.map.ComplexMarkerRepository;
import com.home.application.map.MapQueryUseCase;
import com.home.application.map.MapUseCase;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class MapUseCaseConfiguration {

	@Bean
	@ConditionalOnMissingBean(MapUseCase.class)
	MapUseCase mapUseCase(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectProvider<StringRedisTemplate> redisTemplateProvider,
		ObjectProvider<ObjectMapper> objectMapperProvider,
		ObjectProvider<MeterRegistry> meterRegistryProvider,
		@Value("${home.map.marker-cache.enabled:false}") boolean markerCacheEnabled,
		@Value("${home.map.marker-cache.ttl:5m}") String markerCacheTtl
	) {
		JdbcClient jdbcClient = requiredJdbcClient(jdbcClientProvider);
		ComplexMarkerRepository complexMarkerRepository = complexMarkerRepository(
			jdbcClient,
			redisTemplateProvider,
			objectMapperProvider,
			meterRegistryProvider,
			markerCacheEnabled,
			markerCacheTtl(markerCacheTtl)
		);
		return new MapQueryUseCase(
			complexMarkerRepository,
			new JdbcRegionMarkerRepository(jdbcClient)
		);
	}

	private ComplexMarkerRepository complexMarkerRepository(
		JdbcClient jdbcClient,
		ObjectProvider<StringRedisTemplate> redisTemplateProvider,
		ObjectProvider<ObjectMapper> objectMapperProvider,
		ObjectProvider<MeterRegistry> meterRegistryProvider,
		boolean markerCacheEnabled,
		Duration markerCacheTtl
	) {
		ComplexMarkerRepository repository = new JdbcMapMarkerRepository(jdbcClient);
		if (!markerCacheEnabled) {
			return repository;
		}
		StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
		ObjectMapper objectMapper = objectMapperProvider.getIfAvailable();
		MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
		if (redisTemplate == null || objectMapper == null || meterRegistry == null) {
			return repository;
		}
		return new RedisCachingComplexMarkerRepository(repository, redisTemplate, objectMapper, markerCacheTtl, meterRegistry);
	}

	private JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for map read persistence");
		});
	}

	private Duration markerCacheTtl(String markerCacheTtl) {
		return DurationStyle.detectAndParse(markerCacheTtl);
	}
}
