package com.home.application.insight.read;

public class InvalidInsightQueryException extends RuntimeException {
    public InvalidInsightQueryException(String message) {
        super(message);
    }
}
