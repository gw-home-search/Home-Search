package com.home.security.user;

import java.time.Instant;

public record VerifiedUser(long userId, String tokenId, Instant issuedAt, Instant expiresAt) {
    public VerifiedUser {
        if (userId <= 0 || tokenId == null || tokenId.isBlank() || issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("verified user fields are required");
        }
    }
}
