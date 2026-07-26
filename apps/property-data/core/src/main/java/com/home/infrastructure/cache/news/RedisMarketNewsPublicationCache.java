package com.home.infrastructure.cache.news;

import com.home.application.news.collection.MarketNewsPublicationCache;
import com.home.application.news.collection.PublishedNewsSnapshot;
import com.home.domain.news.MarketNewsScopeType;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.ObjectMapper;

public final class RedisMarketNewsPublicationCache implements MarketNewsPublicationCache {

    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> WITHDRAW_SCRIPT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            if ARGV[1] == '' then
                redis.call('DEL', KEYS[2])
            else
                redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            end
            return 1
            """, Long.class);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisMarketNewsPublicationCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Duration ttl) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.ttl = Objects.requireNonNull(ttl);
    }

    @Override
    public void publish(PublishedNewsSnapshot snapshot) {
        String scopeKey =
                snapshot.scopeType().name() + ":" + (snapshot.regionCode() == null ? "_" : snapshot.regionCode());
        String value = payload(snapshot);
        Long published = redisTemplate.execute(
                PUBLISH_SCRIPT,
                List.of("market-news:current:" + scopeKey, "market-news:last-good:" + scopeKey),
                value,
                Long.toString(ttl.toMillis()));
        if (!Long.valueOf(1L).equals(published)) {
            throw new IllegalStateException("market news cache publication failed");
        }
    }

    @Override
    public void withdraw(MarketNewsScopeType scopeType, String regionCode, PublishedNewsSnapshot lastGood) {
        String scopeKey = scopeType.name() + ":" + (regionCode == null ? "_" : regionCode);
        Long withdrawn = redisTemplate.execute(
                WITHDRAW_SCRIPT,
                List.of("market-news:current:" + scopeKey, "market-news:last-good:" + scopeKey),
                lastGood == null ? "" : payload(lastGood),
                Long.toString(ttl.toMillis()));
        if (!Long.valueOf(1L).equals(withdrawn)) {
            throw new IllegalStateException("market news cache withdrawal failed");
        }
    }

    private String payload(PublishedNewsSnapshot snapshot) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("snapshotId", snapshot.snapshotId().toString());
            payload.put("generatedAt", snapshot.generatedAt().toString());
            payload.put("dataCutoff", snapshot.dataCutoff().toString());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("market news cache payload serialization failed", exception);
        }
    }
}
