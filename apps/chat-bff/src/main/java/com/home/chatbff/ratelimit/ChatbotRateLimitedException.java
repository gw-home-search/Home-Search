package com.home.chatbff.ratelimit;

public final class ChatbotRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public ChatbotRateLimitedException(long retryAfterSeconds) {
        super(null, null, false, false);
        if (retryAfterSeconds <= 0) throw new IllegalArgumentException("retryAfterSeconds must be positive");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
