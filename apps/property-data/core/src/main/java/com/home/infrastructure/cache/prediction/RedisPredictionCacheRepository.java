package com.home.infrastructure.cache.prediction;

import com.home.application.prediction.PredictionCacheKey;
import com.home.application.prediction.PredictionCacheRepository;
import com.home.application.prediction.PricePredictionResult;
import com.home.domain.prediction.PredictionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RedisPredictionCacheRepository implements PredictionCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisPredictionCacheRepository.class);
    private static final int SCHEMA_VERSION = 1;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RedisPredictionCacheRepository(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public Optional<PricePredictionResult> find(PredictionCacheKey key) {
        try {
            String cachedValue = redisTemplate.opsForValue().get(key.cacheKey());
            if (cachedValue == null || cachedValue.isBlank()) {
                record("read", "miss");
                return Optional.empty();
            }
            record("read", "hit");
            return Optional.of(
                    objectMapper.readValue(cachedValue, CacheValue.class).toResult());
        } catch (RuntimeException ex) {
            record("read", "error");
            log.debug("Prediction cache read failed type={}", ex.getClass().getSimpleName());
            return Optional.of(cacheFailure("prediction cache read failure"));
        }
    }

    @Override
    public boolean acquireLock(PredictionCacheKey key, Duration ttl) {
        try {
            boolean acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key.lockKey(), "1", ttl));
            record("lock", acquired ? "acquired" : "contended");
            return acquired;
        } catch (RuntimeException ex) {
            record("lock", "error");
            log.debug("Prediction cache lock failed type={}", ex.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void save(PredictionCacheKey key, PricePredictionResult result, Duration ttl) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(key.cacheKey(), CacheValue.from(result).serialize(objectMapper), ttl);
            record("write", "success");
        } catch (RuntimeException ex) {
            record("write", "error");
            log.debug("Prediction cache write failed type={}", ex.getClass().getSimpleName());
        }
    }

    private void record(String operation, String result) {
        meterRegistry
                .counter("home.search.prediction.cache.operations", "operation", operation, "result", result)
                .increment();
    }

    private static PricePredictionResult cacheFailure(String message) {
        return new PricePredictionResult(
                PredictionStatus.FAILED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                message);
    }

    private record CacheValue(
            int schemaVersion,
            PredictionStatus status,
            String modelVersion,
            Long predictedDealAmount,
            BigDecimal predictedPricePerM2,
            BigDecimal predictedPricePerPyeong,
            Long intervalLow,
            Long intervalHigh,
            String intervalBasis,
            BigDecimal targetAreaM2,
            Integer targetFloor,
            Long basisTradeId,
            LocalDate basisDealDate,
            Instant generatedAt,
            String message) {

        private static CacheValue from(PricePredictionResult result) {
            return new CacheValue(
                    SCHEMA_VERSION,
                    result.status(),
                    result.modelVersion(),
                    result.predictedDealAmount(),
                    result.predictedPricePerM2(),
                    result.predictedPricePerPyeong(),
                    result.intervalLow(),
                    result.intervalHigh(),
                    result.intervalBasis(),
                    result.targetAreaM2(),
                    result.targetFloor(),
                    result.basisTradeId(),
                    result.basisDealDate(),
                    result.generatedAt(),
                    result.message());
        }

        private PricePredictionResult toResult() {
            return new PricePredictionResult(
                    status,
                    modelVersion,
                    predictedDealAmount,
                    predictedPricePerM2,
                    predictedPricePerPyeong,
                    intervalLow,
                    intervalHigh,
                    intervalBasis,
                    targetAreaM2,
                    targetFloor,
                    basisTradeId,
                    basisDealDate,
                    generatedAt,
                    message);
        }

        private String serialize(ObjectMapper objectMapper) throws JacksonException {
            return objectMapper.writeValueAsString(this);
        }
    }
}
