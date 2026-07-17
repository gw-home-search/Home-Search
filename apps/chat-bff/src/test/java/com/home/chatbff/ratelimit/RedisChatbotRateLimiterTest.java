package com.home.chatbff.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class RedisChatbotRateLimiterTest {
    private ReactiveStringRedisTemplate redis;
    private RedisChatbotRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = org.mockito.Mockito.mock(ReactiveStringRedisTemplate.class);
        limiter = new RedisChatbotRateLimiter(
                redis, new ChatbotRateLimitProperties(2, Duration.ofMinutes(1), "home-search:chatbot:test"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void permitsARequestInsideTheConfiguredLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(2L));

        StepVerifier.create(limiter.acquire(42L)).verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsARequestAboveTheConfiguredLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(3L));

        StepVerifier.create(limiter.acquire(42L))
                .expectErrorMatches(error ->
                        error instanceof ChatbotRateLimitedException limited && limited.retryAfterSeconds() == 60L)
                .verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsClosedWhenRedisFails() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("redis detail")));

        StepVerifier.create(limiter.acquire(42L))
                .expectError(ChatbotRateLimitUnavailableException.class)
                .verify();
    }

    @Test
    void rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new ChatbotRateLimitProperties(0, Duration.ofMinutes(1), "valid:key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatbotRateLimitProperties(1, Duration.ZERO, "valid:key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatbotRateLimitProperties(1, Duration.ofMinutes(1), "invalid key"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
