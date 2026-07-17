package com.home.chatbff.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("home.chat-bff.rate-limit")
public record ChatbotRateLimitProperties(long requests, Duration window, String keyPrefix) {
    public ChatbotRateLimitProperties {
        if (requests < 1 || requests > 1_000) {
            throw new IllegalArgumentException("rate-limit requests must be between 1 and 1000");
        }
        if (window == null
                || window.compareTo(Duration.ofSeconds(1)) < 0
                || window.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("rate-limit window must be between 1 second and 1 hour");
        }
        if (keyPrefix == null || !keyPrefix.matches("[a-z0-9:-]{1,64}")) {
            throw new IllegalArgumentException("rate-limit key prefix is invalid");
        }
    }

    public long retryAfterSeconds() {
        return Math.max(1, window.toSeconds());
    }
}
