package com.home.security.user;

public final class UserJwtVerificationException extends RuntimeException {
    public UserJwtVerificationException() {
        super(null, null, false, false);
    }
}
