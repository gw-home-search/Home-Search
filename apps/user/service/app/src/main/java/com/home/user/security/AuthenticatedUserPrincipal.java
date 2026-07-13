package com.home.user.security;

public record AuthenticatedUserPrincipal(long userId) {
    public AuthenticatedUserPrincipal {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
    }
}
