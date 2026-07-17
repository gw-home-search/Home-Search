package com.home.chatbff.ratelimit;

import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
final class RedisChatbotRateLimiter implements ChatbotRateLimiter {
    private static final RedisScript<Long> ACQUIRE_SCRIPT = RedisScript.of(
            "local current = redis.call('INCR', KEYS[1]); "
                    + "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return current;",
            Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final ChatbotRateLimitProperties properties;

    RedisChatbotRateLimiter(ReactiveStringRedisTemplate redis, ChatbotRateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Mono<Void> acquire(long userId) {
        if (userId <= 0) return Mono.error(new ChatbotRateLimitUnavailableException());
        String key = properties.keyPrefix() + ":{" + userId + "}";
        return redis.execute(
                        ACQUIRE_SCRIPT,
                        List.of(key),
                        List.of(Long.toString(properties.window().toMillis())))
                .next()
                .switchIfEmpty(Mono.error(new ChatbotRateLimitUnavailableException()))
                .flatMap(count -> count <= properties.requests()
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new ChatbotRateLimitedException(properties.retryAfterSeconds())))
                .onErrorMap(
                        exception -> !(exception instanceof ChatbotRateLimitedException)
                                && !(exception instanceof ChatbotRateLimitUnavailableException),
                        exception -> new ChatbotRateLimitUnavailableException());
    }
}
