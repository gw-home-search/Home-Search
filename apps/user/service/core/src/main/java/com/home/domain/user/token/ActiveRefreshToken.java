package com.home.domain.user.token;

import java.time.Instant;

public record ActiveRefreshToken(long userId, String tokenHash, Instant issuedAt, Instant expiresAt) {
    public ActiveRefreshToken {
        if (userId <= 0 || tokenHash == null || tokenHash.length() != 64 || issuedAt == null || expiresAt == null
                || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("active refresh token fields are invalid");
        }
    }

    public boolean isExpiredAt(Instant now) { return !expiresAt.isAfter(now); }
}
