package com.home.infrastructure.cache.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.prediction.PredictionCacheKey;
import com.home.application.prediction.PredictionStatus;
import com.home.application.prediction.PricePredictionResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RedisPredictionCacheRepositoryTest {

    private final ObjectMapper objectMapper =
            JsonMapper.builder().findAndAddModules().build();

    @Test
    @DisplayName("READY 예측 결과를 계획된 Redis key와 TTL로 저장한다")
    void storesReadyPredictionWithCacheKeyAndTtl() {
        RedisFixture redis = redis();
        RedisPredictionCacheRepository repository = new RedisPredictionCacheRepository(redis.template(), objectMapper);
        PredictionCacheKey key = new PredictionCacheKey(501L, 9001L, YearMonth.of(2026, 6));

        repository.save(key, readyResult(), Duration.ofHours(24));

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis.operations())
                .set(
                        org.mockito.ArgumentMatchers.eq(
                                "home-search:prediction:v1:F37:complex:501:basis:9001:ym:202606"),
                        valueCaptor.capture(),
                        org.mockito.ArgumentMatchers.eq(Duration.ofHours(24)));
        assertThat(valueCaptor.getValue())
                .contains("\"schemaVersion\":1")
                .contains("\"status\":\"READY\"")
                .contains("\"predictedDealAmount\":179163");
    }

    @Test
    @DisplayName("lock은 lock key에 SET NX TTL로 획득한다")
    void acquiresLockWithLockKeyAndTtl() {
        RedisFixture redis = redis();
        when(redis.operations().setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        RedisPredictionCacheRepository repository = new RedisPredictionCacheRepository(redis.template(), objectMapper);

        boolean acquired = repository.acquireLock(
                new PredictionCacheKey(501L, 9001L, YearMonth.of(2026, 6)), Duration.ofSeconds(60));

        assertThat(acquired).isTrue();
        verify(redis.operations())
                .setIfAbsent(
                        "home-search:prediction:v1:F37:lock:complex:501:basis:9001:ym:202606",
                        "1",
                        Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("Redis JSON 파싱 실패는 FAILED fallback status로 격리한다")
    void brokenJsonReturnsFailedFallbackStatus() {
        RedisFixture redis = redis();
        when(redis.operations().get(anyString())).thenReturn("{broken-json");
        RedisPredictionCacheRepository repository = new RedisPredictionCacheRepository(redis.template(), objectMapper);

        assertThat(repository.find(new PredictionCacheKey(501L, 9001L, YearMonth.of(2026, 6))))
                .hasValueSatisfying(result -> {
                    assertThat(result.status()).isEqualTo(PredictionStatus.FAILED);
                    assertThat(result.message()).contains("cache");
                });
    }

    @SuppressWarnings("unchecked")
    private static RedisFixture redis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(operations);
        return new RedisFixture(template, operations);
    }

    private static PricePredictionResult readyResult() {
        return new PricePredictionResult(
                PredictionStatus.READY,
                "deployment__F37_monthly_anchor_prev3_rolling_huber_010",
                179163L,
                new BigDecimal("2115.5"),
                new BigDecimal("6993.4"),
                139425L,
                218900L,
                "recent_holdout_p95",
                new BigDecimal("84.69"),
                6,
                9001L,
                LocalDate.of(2026, 1, 1),
                Instant.parse("2026-06-25T07:05:38Z"),
                null);
    }

    private record RedisFixture(StringRedisTemplate template, ValueOperations<String, String> operations) {}
}
