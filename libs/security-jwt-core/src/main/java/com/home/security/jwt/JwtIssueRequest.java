package com.home.security.jwt;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

public record JwtIssueRequest(
        String issuer,
        String audience,
        String subject,
        String tokenId,
        String keyId,
        Duration lifetime,
        Map<String, Object> claims) {
    private static final Set<String> RESERVED = Set.of("iss", "aud", "sub", "jti", "iat", "exp", "nbf");

    public JwtIssueRequest {
        issuer = required(issuer, "issuer");
        audience = required(audience, "audience");
        subject = required(subject, "subject");
        tokenId = required(tokenId, "tokenId");
        keyId = required(keyId, "keyId");
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("lifetime must be positive");
        }
        claims = claims == null ? Map.of() : Map.copyOf(claims);
        if (claims.keySet().stream().anyMatch(RESERVED::contains)) {
            throw new IllegalArgumentException("custom claims must not replace registered claims");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
