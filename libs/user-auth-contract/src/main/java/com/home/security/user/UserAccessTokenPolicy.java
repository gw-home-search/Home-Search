package com.home.security.user;

import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public record UserAccessTokenPolicy(
        String issuer, String audience, Duration maximumLifetime, Map<String, PublicKey> publicKeys) {
    public UserAccessTokenPolicy {
        issuer = required(issuer, "issuer");
        audience = required(audience, "audience");
        if (maximumLifetime == null || maximumLifetime.isZero() || maximumLifetime.isNegative()) {
            throw new IllegalArgumentException("maximumLifetime must be positive");
        }
        if (publicKeys == null || publicKeys.isEmpty()) {
            throw new IllegalArgumentException("at least one public key is required");
        }
        Map<String, PublicKey> validated = new LinkedHashMap<>();
        publicKeys.forEach((keyId, key) -> {
            String validatedKeyId = required(keyId, "keyId");
            if (!(key instanceof RSAKey rsa) || rsa.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("RSA public keys must be at least 2048 bit");
            }
            if (validated.putIfAbsent(validatedKeyId, key) != null) {
                throw new IllegalArgumentException("duplicate keyId");
            }
        });
        publicKeys = Map.copyOf(validated);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
