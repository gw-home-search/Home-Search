package com.home.domain.user.token;

import java.time.Instant;

public record StoredRefreshToken(long userId, String tokenHash, Instant expiresAt) {
    public StoredRefreshToken {
        if (userId <= 0 || tokenHash == null || tokenHash.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("Stored refresh token fields are required");
        }
    }

    public boolean isExpiredAt(Instant instant) {
        return !expiresAt.isAfter(instant);
    }
}
