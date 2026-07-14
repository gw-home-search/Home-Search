package com.home.application.favorite;

public final class InvalidComplexIdException extends RuntimeException {
    public InvalidComplexIdException() {
        super("complexId must be positive");
    }
}
