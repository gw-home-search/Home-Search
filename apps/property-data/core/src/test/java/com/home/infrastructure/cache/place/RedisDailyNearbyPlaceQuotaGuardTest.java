package com.home.infrastructure.cache.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.home.application.place.NearbyPlaceProviderUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisDailyNearbyPlaceQuotaGuardTest {

    private static final Clock SEOUL_CLOCK =
            Clock.fixed(Instant.parse("2026-07-13T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    @DisplayName("첫 allocation과 남은 예산 allocation을 허용하고 사용량 metric을 갱신한다")
    void allowsFirstAndRemainingBudgetAllocations() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(anyRedisScript(), anyList(), anyString(), anyString()))
                .thenReturn(1L, 12L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var guard = new RedisDailyNearbyPlaceQuotaGuard(redisTemplate, 10_000, SEOUL_CLOCK, registry);

        assertThatCode(guard::acquire).doesNotThrowAnyException();
        assertThatCode(guard::acquire).doesNotThrowAnyException();
        assertThat(registry.get("home.search.kakao.local.quota.used").gauge().value())
                .isEqualTo(12);
    }

    @Test
    @DisplayName("Redis script 결과가 없으면 예산을 확인할 수 없어 fail-closed한다")
    void failsClosedWhenRedisReturnsNoAllocation() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(anyRedisScript(), anyList(), anyString(), anyString()))
                .thenReturn(null);
        var guard = new RedisDailyNearbyPlaceQuotaGuard(redisTemplate, 10_000, SEOUL_CLOCK, new SimpleMeterRegistry());

        assertThatThrownBy(guard::acquire)
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                .hasMessageContaining("예산");
    }

    @Test
    @DisplayName("일일 예산이 소진되면 외부 호출 권한을 발급하지 않는다")
    void rejectsExhaustedBudget() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(anyRedisScript(), anyList(), anyString(), anyString()))
                .thenReturn(-1L);
        var guard = new RedisDailyNearbyPlaceQuotaGuard(redisTemplate, 10_000, SEOUL_CLOCK, new SimpleMeterRegistry());

        assertThatThrownBy(guard::acquire)
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                .hasMessageContaining("예산");
    }

    @Test
    @DisplayName("Redis 장애로 예산을 확인할 수 없으면 fail-closed한다")
    void failsClosedWhenRedisIsUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(anyRedisScript(), anyList(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        var guard = new RedisDailyNearbyPlaceQuotaGuard(redisTemplate, 10_000, SEOUL_CLOCK, new SimpleMeterRegistry());

        assertThatThrownBy(guard::acquire)
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                .hasMessageNotContaining("redis unavailable");
    }

    private RedisScript<Long> anyRedisScript() {
        return any();
    }
}
