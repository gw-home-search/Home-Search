package com.home.infrastructure.external.kakao;

import com.home.application.place.NearbyPlaceCenter;
import com.home.application.place.NearbyPlaceCenterReader;
import com.home.application.place.NearbyPlaceProvider;
import com.home.application.place.NearbyPlaceProviderUnavailableException;
import com.home.application.place.NearbyPlaceQueryService;
import com.home.application.place.NearbyPlaceUseCase;
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
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class NearbyPlaceConfiguration {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean(destroyMethod = "shutdown")
    ExecutorService nearbyPlaceExecutor(
            @Value("${home.place.kakao.executor.threads:3}") int threads,
            @Value("${home.place.kakao.executor.queue-capacity:24}") int queueCapacity) {
        if (threads < 1 || threads > 3) {
            throw new IllegalArgumentException("nearby place executor threads must be between 1 and 3");
        }
        if (queueCapacity < 1 || queueCapacity > 120) {
            throw new IllegalArgumentException("nearby place executor queue capacity must be between 1 and 120");
        }
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                threads,
                threads,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "home-nearby-place-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    @ConditionalOnMissingBean(NearbyPlaceUseCase.class)
    NearbyPlaceUseCase nearbyPlaceUseCase(
            ComplexCenterReader complexCenterReader,
            ObjectMapper objectMapper,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            MeterRegistry meterRegistry,
            @Qualifier("nearbyPlaceExecutor") Executor executor,
            @Value("${home.place.kakao.enabled:false}") boolean enabled,
            @Value("${home.place.kakao.rest-api-key:}") String restApiKey,
            @Value("${home.place.kakao.base-url:https://dapi.kakao.com}") String baseUrl,
            @Value("${home.place.kakao.cache.enabled:true}") boolean cacheEnabled,
            @Value("${home.place.kakao.cache.ttl:24h}") String cacheTtl,
            @Value("${home.place.kakao.daily-request-budget:10000}") int dailyRequestBudget,
            @Value("${home.place.kakao.connect-timeout:1s}") String connectTimeout,
            @Value("${home.place.kakao.read-timeout:2s}") String readTimeout,
            @Value("${home.place.kakao.total-timeout:5s}") String totalTimeout) {
        Clock clock = Clock.system(SEOUL);
        NearbyPlaceCenterReader centerReader = complexId -> readCenter(complexCenterReader, complexId);
        NearbyPlaceProvider provider = provider(
                enabled,
                restApiKey,
                baseUrl,
                objectMapper,
                clock,
                meterRegistry,
                redisTemplateProvider.getIfAvailable(),
                cacheEnabled,
                duration(cacheTtl),
                dailyRequestBudget,
                duration(connectTimeout),
                duration(readTimeout));
        NearbyPlaceUseCase service = new NearbyPlaceQueryService(
                centerReader, provider, executor, clock, duration(totalTimeout).toMillis());
        return (complexId, radiusMeters, categories, limitPerCategory) -> {
            String result = "success";
            try {
                return service.getNearbyPlaces(complexId, radiusMeters, categories, limitPerCategory);
            } catch (RuntimeException exception) {
                result = "error";
                throw exception;
            } finally {
                meterRegistry
                        .counter("home.search.nearby.place.requests", "result", result)
                        .increment();
            }
        };
    }

    private NearbyPlaceProvider provider(
            boolean enabled,
            String restApiKey,
            String baseUrl,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry,
            StringRedisTemplate redisTemplate,
            boolean cacheEnabled,
            Duration cacheTtl,
            int dailyRequestBudget,
            Duration connectTimeout,
            Duration readTimeout) {
        if (!enabled) {
            return (center, radius, category) -> {
                throw new NearbyPlaceProviderUnavailableException("주변 상권 기능이 비활성화되어 있습니다.");
            };
        }
        if (restApiKey == null || restApiKey.isBlank()) {
            return (center, radius, category) -> {
                throw new NearbyPlaceProviderUnavailableException("Kakao 장소 조회 설정이 준비되지 않았습니다.");
            };
        }
        if (redisTemplate == null) {
            return (center, radius, category) -> {
                throw new NearbyPlaceProviderUnavailableException("Kakao 호출 예산 저장소를 사용할 수 없습니다.");
            };
        }

        NearbyPlaceProvider kakao = observed(
                new KakaoNearbyPlaceProvider(
                        kakaoRestClient(baseUrl, restApiKey, connectTimeout, readTimeout), objectMapper, clock),
                meterRegistry);
        NearbyPlaceCache cache = cacheEnabled
                ? new RedisNearbyPlaceCache(redisTemplate, objectMapper, cacheTtl, meterRegistry)
                : new NoopNearbyPlaceCache();
        NearbyPlaceQuotaGuard quotaGuard =
                new RedisDailyNearbyPlaceQuotaGuard(redisTemplate, dailyRequestBudget, clock, meterRegistry);
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

    private RestClient kakaoRestClient(
            String baseUrl, String restApiKey, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                .build();
    }

    private Optional<NearbyPlaceCenter> readCenter(ComplexCenterReader complexCenterReader, Long complexId) {
        return complexCenterReader
                .findComplexCenter(complexId)
                .filter(center -> center.latitude() != null && center.longitude() != null)
                .map(center -> new NearbyPlaceCenter(complexId, center.latitude(), center.longitude()));
    }

    private Duration duration(String value) {
        return DurationStyle.detectAndParse(value);
    }
}
