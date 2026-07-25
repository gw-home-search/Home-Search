package com.home.infrastructure.cache.news;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.news.collection.PublishedNewsSnapshot;
import com.home.domain.news.MarketNewsScopeType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;

class RedisMarketNewsPublicationCacheTest {

    @Test
    @DisplayName("current와 last-good pointer를 한 Redis script로 같은 TTL에 발행한다")
    void publishesCurrentAndLastGoodAtomically() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        var snapshot = new PublishedNewsSnapshot(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174700"),
                MarketNewsScopeType.SIDO,
                "11",
                Instant.parse("2026-07-24T09:30:00Z"),
                Instant.parse("2026-07-24T09:29:00Z"));

        new RedisMarketNewsPublicationCache(redis, new ObjectMapper(), Duration.ofDays(31)).publish(snapshot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<String>> keys = ArgumentCaptor.forClass(java.util.List.class);
        verify(redis)
                .execute(
                        any(RedisScript.class),
                        keys.capture(),
                        org.mockito.ArgumentMatchers.contains(
                                snapshot.snapshotId().toString()),
                        eq(Long.toString(Duration.ofDays(31).toMillis())));
        org.assertj.core.api.Assertions.assertThat(keys.getValue())
                .containsExactly("market-news:current:SIDO:11", "market-news:last-good:SIDO:11");
    }

    @Test
    @DisplayName("Redis script가 원자 발행을 확인하지 못하면 성공으로 숨기지 않는다")
    void rejectsUnconfirmedPublication() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(null);
        var snapshot = new PublishedNewsSnapshot(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174701"),
                MarketNewsScopeType.NATIONWIDE,
                null,
                Instant.parse("2026-07-24T09:30:00Z"),
                Instant.parse("2026-07-24T09:29:00Z"));

        assertThatThrownBy(() -> new RedisMarketNewsPublicationCache(redis, new ObjectMapper(), Duration.ofDays(31))
                        .publish(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publication failed");
    }

    @Test
    @DisplayName("품질 회수는 current를 제거하고 직전 PostgreSQL last-good pointer를 원자 복구한다")
    void withdrawalRestoresLastGoodPointer() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        PublishedNewsSnapshot lastGood = new PublishedNewsSnapshot(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174702"),
                MarketNewsScopeType.SIDO,
                "11",
                Instant.parse("2026-07-24T08:30:00Z"),
                Instant.parse("2026-07-24T08:29:00Z"));

        new RedisMarketNewsPublicationCache(redis, new ObjectMapper(), Duration.ofDays(31))
                .withdraw(MarketNewsScopeType.SIDO, "11", lastGood);

        verify(redis)
                .execute(
                        any(RedisScript.class),
                        eq(List.of("market-news:current:SIDO:11", "market-news:last-good:SIDO:11")),
                        org.mockito.ArgumentMatchers.contains(
                                lastGood.snapshotId().toString()),
                        eq(Long.toString(Duration.ofDays(31).toMillis())));
    }
}
