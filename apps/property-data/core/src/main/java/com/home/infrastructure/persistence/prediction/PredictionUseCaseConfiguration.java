package com.home.infrastructure.persistence.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.prediction.PredictionCacheKey;
import com.home.application.prediction.PredictionCacheRepository;
import com.home.application.prediction.PredictionClient;
import com.home.application.prediction.PredictionFeatureRepository;
import com.home.application.prediction.PredictionProperties;
import com.home.application.prediction.PricePredictionResult;
import com.home.application.prediction.PricePredictionUseCase;
import com.home.infrastructure.cache.prediction.RedisPredictionCacheRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class PredictionUseCaseConfiguration {

    @Bean
    @ConditionalOnMissingBean(PredictionProperties.class)
    PredictionProperties predictionProperties(
            @Value("${home.prediction.enabled:true}") boolean enabled,
            @Value("${home.prediction.model-version:deployment__F37_monthly_anchor_prev3_rolling_huber_010}")
                    String modelVersion,
            @Value("${home.prediction.cache.ttl:24h}") String readyTtl,
            @Value("${home.prediction.cache.pending-ttl:60s}") String pendingTtl,
            @Value("${home.prediction.cache.failed-ttl:10m}") String failedTtl,
            @Value("${home.prediction.cache.unavailable-ttl:1h}") String unavailableTtl,
            @Value("${home.prediction.cache.lock-ttl:60s}") String lockTtl,
            @Value("${home.prediction.interval.pct:0.188077}") BigDecimal intervalPct,
            @Value("${home.prediction.interval.basis:recent_holdout_p95}") String intervalBasis,
            @Value("${home.prediction.zone:Asia/Seoul}") ZoneId zoneId) {
        return new PredictionProperties(
                enabled,
                modelVersion,
                duration(readyTtl),
                duration(pendingTtl),
                duration(failedTtl),
                duration(unavailableTtl),
                duration(lockTtl),
                intervalPct,
                intervalBasis,
                zoneId);
    }

    @Bean
    @ConditionalOnMissingBean(PredictionFeatureRepository.class)
    PredictionFeatureRepository predictionFeatureRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
        JdbcClient jdbcClient = jdbcClientProvider.getIfAvailable();
        return jdbcClient == null
                ? (complexId, anchorMonth) -> Optional.empty()
                : new JdbcPredictionFeatureRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean(PredictionCacheRepository.class)
    PredictionCacheRepository predictionCacheRepository(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable();
        return redisTemplate == null || objectMapper == null
                ? new NoopPredictionCacheRepository()
                : new RedisPredictionCacheRepository(redisTemplate, objectMapper);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService predictionExecutor(@Value("${home.prediction.executor.threads:2}") int threads) {
        int threadCount = Math.max(1, threads);
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable, "home-prediction-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean(PricePredictionUseCase.class)
    PricePredictionUseCase pricePredictionUseCase(
            PredictionFeatureRepository featureRepository,
            PredictionCacheRepository cacheRepository,
            PredictionClient client,
            @Qualifier("predictionExecutor") Executor executor,
            PredictionProperties properties) {
        return new PricePredictionUseCase(
                featureRepository, cacheRepository, client, executor, Clock.system(properties.zoneId()), properties);
    }

    private static Duration duration(String value) {
        return DurationStyle.detectAndParse(value);
    }

    private static final class NoopPredictionCacheRepository implements PredictionCacheRepository {

        @Override
        public Optional<PricePredictionResult> find(PredictionCacheKey key) {
            return Optional.empty();
        }

        @Override
        public boolean acquireLock(PredictionCacheKey key, Duration ttl) {
            return true;
        }

        @Override
        public void save(PredictionCacheKey key, PricePredictionResult result, Duration ttl) {
            // Cache is optional for non-local test slices.
        }
    }
}
