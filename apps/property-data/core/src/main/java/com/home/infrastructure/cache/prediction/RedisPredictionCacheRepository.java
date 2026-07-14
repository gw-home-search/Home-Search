package com.home.infrastructure.cache.prediction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.prediction.PredictionCacheKey;
import com.home.application.prediction.PredictionCacheRepository;
import com.home.application.prediction.PredictionStatus;
import com.home.application.prediction.PricePredictionResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisPredictionCacheRepository implements PredictionCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisPredictionCacheRepository.class);
    private static final int SCHEMA_VERSION = 1;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisPredictionCacheRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public Optional<PricePredictionResult> find(PredictionCacheKey key) {
        try {
            String cachedValue = redisTemplate.opsForValue().get(key.cacheKey());
            if (cachedValue == null || cachedValue.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(
                    objectMapper.readValue(cachedValue, CacheValue.class).toResult());
        } catch (JsonProcessingException | RuntimeException ex) {
            log.debug("Failed to read prediction cache key={}", key.cacheKey(), ex);
            return Optional.of(cacheFailure("prediction cache read failure"));
        }
    }

    @Override
    public boolean acquireLock(PredictionCacheKey key, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key.lockKey(), "1", ttl));
        } catch (RuntimeException ex) {
            log.debug("Failed to acquire prediction lock key={}", key.lockKey(), ex);
            return false;
        }
    }

    @Override
    public void save(PredictionCacheKey key, PricePredictionResult result, Duration ttl) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(key.cacheKey(), CacheValue.from(result).serialize(objectMapper), ttl);
        } catch (JsonProcessingException | RuntimeException ex) {
            log.debug("Failed to write prediction cache key={}", key.cacheKey(), ex);
        }
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

        private String serialize(ObjectMapper objectMapper) throws JsonProcessingException {
            return objectMapper.writeValueAsString(this);
        }
    }
}
