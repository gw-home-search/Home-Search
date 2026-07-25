package com.home.application.news.collection;

public class NewsProviderCallException extends RuntimeException {

    private final NewsProviderFailureType type;
    private final Integer retryAfterSeconds;

    public NewsProviderCallException(
            NewsProviderFailureType type, String message, Integer retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public NewsProviderFailureType type() {
        return type;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean retryableOnce() {
        return type == NewsProviderFailureType.TRANSIENT;
    }
}
