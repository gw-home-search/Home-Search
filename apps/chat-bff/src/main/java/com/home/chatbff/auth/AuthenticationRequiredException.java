package com.home.chatbff.auth;

public final class AuthenticationRequiredException extends RuntimeException {
    public AuthenticationRequiredException() {
        super(null, null, false, false);
    }
}
