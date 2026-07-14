package com.home.infrastructure.cache.place;

import com.home.application.place.NearbyPlaceProviderUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisDailyNearbyPlaceQuotaGuard implements NearbyPlaceQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisDailyNearbyPlaceQuotaGuard.class);
    private static final String KEY_PREFIX = "home-search:nearby-place:kakao:quota:format-1:";
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
		local budget = tonumber(ARGV[1])
		local existing = tonumber(redis.call('GET', KEYS[1]) or '0')
		if existing >= budget then return -1 end
		local current = redis.call('INCR', KEYS[1])
		if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
		return current
		""", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int dailyBudget;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final AtomicLong used = new AtomicLong();

    public RedisDailyNearbyPlaceQuotaGuard(
            StringRedisTemplate redisTemplate, int dailyBudget, Clock clock, MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        if (dailyBudget < 1) {
            throw new IllegalArgumentException("Kakao daily request budget must be positive");
        }
        this.dailyBudget = dailyBudget;
        this.clock = Objects.requireNonNull(clock);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        meterRegistry.gauge("home.search.kakao.local.quota.used", used);
        meterRegistry.gauge("home.search.kakao.local.quota.budget", new AtomicLong(dailyBudget));
    }

    @Override
    public void acquire() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        String key = KEY_PREFIX + LocalDate.from(now);
        long ttlSeconds = Math.max(
                1,
                Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()))
                        .plusMinutes(5)
                        .toSeconds());
        try {
            Long current = redisTemplate.execute(
                    INCREMENT_SCRIPT, List.of(key), Integer.toString(dailyBudget), Long.toString(ttlSeconds));
            if (current == null) {
                record("error");
                throw new NearbyPlaceProviderUnavailableException("Kakao 호출 예산을 확인할 수 없습니다.");
            }
            if (current < 0) {
                used.set(dailyBudget);
                record("exhausted");
                throw new NearbyPlaceProviderUnavailableException("Kakao 일일 호출 예산을 모두 사용했습니다.");
            }
            used.set(current);
            record("allowed");
        } catch (NearbyPlaceProviderUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            record("error");
            log.warn(
                    "Kakao quota guard failed closed type={}",
                    exception.getClass().getSimpleName());
            throw new NearbyPlaceProviderUnavailableException("Kakao 호출 예산을 확인할 수 없습니다.", exception);
        }
    }

    private void record(String result) {
        meterRegistry
                .counter("home.search.kakao.local.quota.requests", "result", result)
                .increment();
    }
}
