package com.home.infrastructure.external.kakao;

import com.home.application.place.NearbyPlaceBoundsArea;
import com.home.application.place.NearbyPlaceCenter;
import com.home.application.place.NearbyPlaceCenterReader;
import com.home.application.place.NearbyPlaceExecutionOptions;
import com.home.application.place.NearbyPlaceProvider;
import com.home.application.place.NearbyPlaceProviderQuery;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NearbyPlaceProperties.class)
public class NearbyPlaceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NearbyPlaceConfiguration.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean
    ThreadPoolTaskExecutor nearbyPlaceExecutor(NearbyPlaceProperties properties) {
        NearbyPlaceProperties.Executor executor = properties.executor();
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(executor.threads());
        taskExecutor.setMaxPoolSize(executor.threads());
        taskExecutor.setQueueCapacity(executor.queueCapacity());
        taskExecutor.setThreadNamePrefix("home-nearby-place-");
        taskExecutor.setDaemon(true);
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationMillis(executor.shutdownAwait().toMillis());
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return taskExecutor;
    }

    @Bean
    NearbyPlaceCenterReader nearbyPlaceCenterReader(ComplexCenterReader complexCenterReader) {
        return complexId -> readCenter(complexCenterReader, complexId);
    }

    @Bean
    NearbyPlaceExecutionOptions nearbyPlaceExecutionOptions(
            @Qualifier("nearbyPlaceExecutor") Executor nearbyPlaceExecutor, NearbyPlaceProperties properties) {
        return new NearbyPlaceExecutionOptions(nearbyPlaceExecutor, Clock.system(SEOUL), properties.totalTimeout());
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.place.kakao", name = "enabled", havingValue = "false", matchIfMissing = true)
    NearbyPlaceProvider disabledNearbyPlaceProvider() {
        return query -> {
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
                        redisTemplate,
                        objectMapper,
                        properties.cache().ttl(),
                        properties.cache().viewportTtl(),
                        meterRegistry)
                : new NoopNearbyPlaceCache();
        NearbyPlaceQuotaGuard quotaGuard = new RedisDailyNearbyPlaceQuotaGuard(
                redisTemplate, properties.dailyRequestBudget(), executionOptions.clock(), meterRegistry);
        return new CachingNearbyPlaceProvider(kakao, cache, quotaGuard);
    }

    private NearbyPlaceProvider observed(NearbyPlaceProvider delegate, MeterRegistry meterRegistry) {
        return query -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            String result = "success";
            try {
                return delegate.search(query);
            } catch (RuntimeException exception) {
                result = "error";
                log.warn(
                        "Kakao nearby-place provider call failed category={} scope={} type={}",
                        query.category().name(),
                        scope(query),
                        exception.getClass().getSimpleName());
                throw exception;
            } finally {
                meterRegistry
                        .counter(
                                "home.search.kakao.local.calls",
                                "category",
                                query.category().name(),
                                "scope",
                                scope(query),
                                "result",
                                result)
                        .increment();
                sample.stop(meterRegistry.timer(
                        "home.search.kakao.local.duration",
                        "category",
                        query.category().name(),
                        "scope",
                        scope(query),
                        "result",
                        result));
            }
        };
    }

    private String scope(NearbyPlaceProviderQuery query) {
        return query.area() instanceof NearbyPlaceBoundsArea ? "viewport" : "complex";
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
