package com.home.infrastructure.event;

final class PropertyEventPublishException extends RuntimeException {

    PropertyEventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
