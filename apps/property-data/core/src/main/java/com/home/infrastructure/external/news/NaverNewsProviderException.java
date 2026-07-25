package com.home.infrastructure.external.news;

import com.home.application.news.collection.NewsProviderCallException;
import com.home.application.news.collection.NewsProviderFailureType;

public class NaverNewsProviderException extends NewsProviderCallException {

    private final NaverNewsFailureKind kind;
    private final Integer retryAfterSeconds;

    public NaverNewsProviderException(NaverNewsFailureKind kind, String message) {
        this(kind, message, null, null);
    }

    public NaverNewsProviderException(
            NaverNewsFailureKind kind, String message, Integer retryAfterSeconds, Throwable cause) {
        super(NewsProviderFailureType.valueOf(kind.name()), message, retryAfterSeconds, cause);
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public NaverNewsFailureKind kind() {
        return kind;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean retryableOnce() {
        return super.retryableOnce();
    }
}
