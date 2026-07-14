package com.home.application.auth.port;

@FunctionalInterface
public interface AccessTokenIssuer {
    String issue(long userId);
}
