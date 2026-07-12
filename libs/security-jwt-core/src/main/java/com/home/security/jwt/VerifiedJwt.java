package com.home.security.jwt;

import java.time.Instant;
import java.util.Map;

public record VerifiedJwt(
    String keyId,
    String issuer,
    String audience,
    String subject,
    String tokenId,
    Instant issuedAt,
    Instant expiresAt,
    Map<String, Object> claims
) {
    public VerifiedJwt {
        claims = Map.copyOf(claims);
    }
}
