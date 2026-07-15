package com.home.infrastructure.persistence.map;

import com.home.application.map.ComplexMarkerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MapMarkerCacheProperties.class)
class MapMarkerCacheConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "home.map.marker-cache.enabled", havingValue = "true")
    ComplexMarkerRepository cachingComplexMarkerRepository(
            JdbcMapMarkerRepository delegate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            MapMarkerCacheProperties properties) {
        return new RedisCachingComplexMarkerRepository(
                delegate, redisTemplate, objectMapper, properties.ttl(), meterRegistry);
    }
}
