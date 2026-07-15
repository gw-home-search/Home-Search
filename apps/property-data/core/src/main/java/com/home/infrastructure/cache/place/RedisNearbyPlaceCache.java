package com.home.infrastructure.cache.place;

import com.home.application.place.NearbyPlaceProviderResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class RedisNearbyPlaceCache implements NearbyPlaceCache {

    private static final Logger log = LoggerFactory.getLogger(RedisNearbyPlaceCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final MeterRegistry meterRegistry;

    public RedisNearbyPlaceCache(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Duration ttl, MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.ttl = Objects.requireNonNull(ttl);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public Optional<NearbyPlaceProviderResult> find(NearbyPlaceCacheKey key) {
        try {
            String value = redisTemplate.opsForValue().get(key.redisKey());
            if (value == null || value.isBlank()) {
                record("miss");
                return Optional.empty();
            }
            NearbyPlaceProviderResult result = objectMapper.readValue(value, NearbyPlaceProviderResult.class);
            if (!valid(result, key)) {
                record("corrupt");
                redisTemplate.delete(key.redisKey());
                return Optional.empty();
            }
            record("hit");
            return Optional.of(result);
        } catch (JacksonException exception) {
            record("corrupt");
            log.debug(
                    "Discarding corrupt nearby-place cache entry type={}",
                    exception.getClass().getSimpleName());
            tryDelete(key);
            return Optional.empty();
        } catch (RuntimeException exception) {
            record("error");
            log.debug(
                    "Nearby-place cache read failed type={}",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void store(NearbyPlaceCacheKey key, NearbyPlaceProviderResult result) {
        try {
            redisTemplate.opsForValue().set(key.redisKey(), objectMapper.writeValueAsString(result), ttl);
            recordOperation("write", "success");
        } catch (RuntimeException exception) {
            recordOperation("write", "error");
            log.debug(
                    "Nearby-place cache write failed type={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void tryDelete(NearbyPlaceCacheKey key) {
        try {
            redisTemplate.delete(key.redisKey());
        } catch (RuntimeException exception) {
            recordOperation("delete", "error");
            log.debug(
                    "Nearby-place corrupt cache cleanup failed type={}",
                    exception.getClass().getSimpleName());
        }
    }

    private boolean valid(NearbyPlaceProviderResult result, NearbyPlaceCacheKey key) {
        if (result == null
                || result.category() != key.category()
                || result.matchedCount() < 0
                || result.places() == null
                || result.places().size() > 15
                || result.retrievedAt() == null) {
            return false;
        }
        return result.places().stream().allMatch(place -> {
            if (place == null
                    || place.placeId() == null
                    || !place.placeId().startsWith("kakao:")
                    || place.name() == null
                    || place.name().isBlank()
                    || !Double.isFinite(place.lat())
                    || place.lat() < -90
                    || place.lat() > 90
                    || !Double.isFinite(place.lng())
                    || place.lng() < -180
                    || place.lng() > 180
                    || place.distanceMeters() < 0) {
                return false;
            }
            if (place.placeUrl() == null) {
                return true;
            }
            try {
                URI uri = URI.create(place.placeUrl());
                return "https".equalsIgnoreCase(uri.getScheme())
                        && "place.map.kakao.com".equalsIgnoreCase(uri.getHost());
            } catch (IllegalArgumentException exception) {
                return false;
            }
        });
    }

    private void record(String result) {
        meterRegistry
                .counter("home.search.nearby.place.cache.requests", "result", result)
                .increment();
    }

    private void recordOperation(String operation, String result) {
        meterRegistry
                .counter("home.search.nearby.place.cache.operations", "operation", operation, "result", result)
                .increment();
    }
}
