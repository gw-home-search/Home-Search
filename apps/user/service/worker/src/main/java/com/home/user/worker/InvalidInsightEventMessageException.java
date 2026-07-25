package com.home.user.worker;

public final class InvalidInsightEventMessageException extends IllegalArgumentException {
    public InvalidInsightEventMessageException(String message) {
        super(message);
    }

    public InvalidInsightEventMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
