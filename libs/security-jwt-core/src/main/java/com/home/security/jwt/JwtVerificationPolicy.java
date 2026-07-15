package com.home.security.jwt;

import java.security.PublicKey;
import java.time.Duration;
import java.util.function.Function;

public record JwtVerificationPolicy(
        String issuer, String audience, Duration maximumLifetime, Function<String, PublicKey> keyResolver) {
    public JwtVerificationPolicy {
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("issuer is required");
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("audience is required");
        if (maximumLifetime == null || maximumLifetime.isZero() || maximumLifetime.isNegative()) {
            throw new IllegalArgumentException("maximumLifetime must be positive");
        }
        if (keyResolver == null) throw new IllegalArgumentException("keyResolver is required");
    }
}
