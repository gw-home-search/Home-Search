package com.home.chatbff.ratelimit;

public final class ChatbotRateLimitUnavailableException extends RuntimeException {
    public ChatbotRateLimitUnavailableException() {
        super(null, null, false, false);
    }
}
