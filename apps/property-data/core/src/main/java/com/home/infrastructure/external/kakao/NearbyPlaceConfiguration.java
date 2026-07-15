package com.home.infrastructure.external.kakao;

import com.home.application.place.NearbyPlaceCenter;
import com.home.application.place.NearbyPlaceCenterReader;
import com.home.application.place.NearbyPlaceExecutionOptions;
import com.home.application.place.NearbyPlaceProvider;
import com.home.application.place.NearbyPlaceProviderUnavailableException;
import com.home.application.propertydetail.ComplexCenterReader;
import com.home.infrastructure.cache.place.CachingNearbyPlaceProvider;
import com.home.infrastructure.cache.place.NearbyPlaceCache;
import com.home.infrastructure.cache.place.NearbyPlaceQuotaGuard;
import com.home.infrastructure.cache.place.NoopNearbyPlaceCache;
import com.home.infrastructure.cache.place.RedisDailyNearbyPlaceQuotaGuard;
import com.home.infrastructure.cache.place.RedisNearbyPlaceCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NearbyPlaceProperties.class)
public class NearbyPlaceConfiguration {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean(destroyMethod = "shutdown")
    ExecutorService nearbyPlaceExecutor(NearbyPlaceProperties properties) {
        NearbyPlaceProperties.Executor executor = properties.executor();
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                executor.threads(),
                executor.threads(),
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(executor.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "home-nearby-place-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    NearbyPlaceCenterReader nearbyPlaceCenterReader(ComplexCenterReader complexCenterReader) {
        return complexId -> readCenter(complexCenterReader, complexId);
    }

    @Bean
    NearbyPlaceExecutionOptions nearbyPlaceExecutionOptions(
            @Qualifier("nearbyPlaceExecutor") ExecutorService nearbyPlaceExecutor, NearbyPlaceProperties properties) {
        return new NearbyPlaceExecutionOptions(nearbyPlaceExecutor, Clock.system(SEOUL), properties.totalTimeout());
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.place.kakao", name = "enabled", havingValue = "false", matchIfMissing = true)
    NearbyPlaceProvider disabledNearbyPlaceProvider() {
        return (center, radius, category) -> {
            throw new NearbyPlaceProviderUnavailableException("주변 상권 기능이 비활성화되어 있습니다.");
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.place.kakao", name = "enabled", havingValue = "true")
    NearbyPlaceProvider kakaoNearbyPlaceProvider(
            NearbyPlaceProperties properties,
            NearbyPlaceExecutionOptions executionOptions,
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry) {
        NearbyPlaceProvider kakao = observed(
                new KakaoNearbyPlaceProvider(kakaoRestClient(properties), objectMapper, executionOptions.clock()),
                meterRegistry);
        NearbyPlaceCache cache = properties.cache().enabled()
                ? new RedisNearbyPlaceCache(
                        redisTemplate, objectMapper, properties.cache().ttl(), meterRegistry)
                : new NoopNearbyPlaceCache();
        NearbyPlaceQuotaGuard quotaGuard = new RedisDailyNearbyPlaceQuotaGuard(
                redisTemplate, properties.dailyRequestBudget(), executionOptions.clock(), meterRegistry);
        return new CachingNearbyPlaceProvider(kakao, cache, quotaGuard);
    }

    private NearbyPlaceProvider observed(NearbyPlaceProvider delegate, MeterRegistry meterRegistry) {
        return (center, radiusMeters, category) -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            String result = "success";
            try {
                return delegate.search(center, radiusMeters, category);
            } catch (RuntimeException exception) {
                result = "error";
                throw exception;
            } finally {
                meterRegistry
                        .counter("home.search.kakao.local.calls", "category", category.name(), "result", result)
                        .increment();
                sample.stop(meterRegistry.timer(
                        "home.search.kakao.local.duration", "category", category.name(), "result", result));
            }
        };
    }

    private RestClient kakaoRestClient(NearbyPlaceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl().toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.restApiKey())
                .build();
    }

    private Optional<NearbyPlaceCenter> readCenter(ComplexCenterReader complexCenterReader, Long complexId) {
        return complexCenterReader
                .findComplexCenter(complexId)
                .filter(center -> center.latitude() != null && center.longitude() != null)
                .map(center -> new NearbyPlaceCenter(complexId, center.latitude(), center.longitude()));
    }
}
