package com.home.domain.user.token;

@FunctionalInterface
public interface OpaqueTokenGenerator {
    String generate();
}
