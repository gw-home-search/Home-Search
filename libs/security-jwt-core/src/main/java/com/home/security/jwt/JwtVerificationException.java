package com.home.security.jwt;

public final class JwtVerificationException extends RuntimeException {
    public JwtVerificationException() {
        super("signed token verification failed");
    }
}
