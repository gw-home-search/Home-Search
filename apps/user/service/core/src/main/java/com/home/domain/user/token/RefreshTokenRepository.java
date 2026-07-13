package com.home.domain.user.token;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository {
    Optional<StoredRefreshToken> findActiveByHash(String tokenHash);
    Optional<StoredRefreshToken> findActiveByUserId(long userId);
    void replaceActive(StoredRefreshToken token);
    boolean rotateActive(String expectedHash, StoredRefreshToken replacement, Instant rotatedAt);
    void revokeByUserId(long userId, Instant revokedAt);
}
