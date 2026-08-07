package com.home.chatbff.ai;

public final class ChatbotUpstreamHttpException extends RuntimeException {
    private final int statusCode;

    public ChatbotUpstreamHttpException(int statusCode) {
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
