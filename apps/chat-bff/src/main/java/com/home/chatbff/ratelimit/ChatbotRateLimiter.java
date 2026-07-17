package com.home.chatbff.ratelimit;

import reactor.core.publisher.Mono;

public interface ChatbotRateLimiter {
    Mono<Void> acquire(long userId);
}
